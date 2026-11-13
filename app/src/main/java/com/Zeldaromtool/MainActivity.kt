package com.zeldaromtool

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.FileInputStream
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private lateinit var ui: Ui
    private lateinit var appliedTheme: ThemeMode

    private lateinit var prodStatus: TextView
    private lateinit var titleStatus: TextView
    private lateinit var romStatus: TextView
    private lateinit var romCard: View
    private lateinit var extractButton: Button
    private lateinit var destinationStatus: TextView
    private lateinit var cancelButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    private var romUri: Uri? = null
    private var romEntries: List<RomExtractor.Entry>? = null
    private var romName = ""
    private var destinationTreeUri: Uri? = null
    private var waitingForDestination = false
    private var extracting = false
    @Volatile private var cancelFlag = false

    private val pickProd = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            toast(KeysManager.importProd(this, uri).message)
            refresh()
        }
    }

    private val pickTitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            toast(KeysManager.importTitle(this, uri).message)
            refresh()
        }
    }

    private val pickRom = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadRom(uri)
    }

    private val pickDestination = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
                destinationTreeUri = uri
                getSharedPreferences("zeldaromtool", MODE_PRIVATE).edit()
                    .putString("destination_tree_uri", uri.toString()).apply()
                updateDestinationStatus()
                toast("Pasta de destino selecionada.")
                if (waitingForDestination) {
                    waitingForDestination = false
                    startExtraction()
                }
            } catch (e: Exception) {
                waitingForDestination = false
                toast("Não foi possível autorizar essa pasta: ${e.message}")
            }
        } else {
            waitingForDestination = false
        }
    }

    private fun setOn(v: View, on: Boolean) = ui.setOn(v, on)

    // ---------- tela ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        CrashLogger.install(this)
        val savedTree = getSharedPreferences("zeldaromtool", MODE_PRIVATE)
            .getString("destination_tree_uri", null)
        destinationTreeUri = savedTree?.let { Uri.parse(it) }
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        // Voltou das Configurações com outro tema: refaz a tela sem perder o estado.
        if (AppSettings.themeMode(this) != appliedTheme) buildUi()
    }

    /** Monta (ou remonta) a tela inteira com as cores do tema atual. */
    private fun buildUi() {
        val oldRom = if (::romStatus.isInitialized) romStatus.text.toString() else ""
        val oldProgressText = if (::progressText.isInitialized) progressText.text.toString() else ""
        val oldProgress = if (::progressBar.isInitialized) progressBar.progress else 0

        appliedTheme = AppSettings.themeMode(this)
        ui = Ui(this, AppSettings.palette(this))
        val pal = ui.pal
        ui.applyBars(this)
        window.setBackgroundDrawable(ColorDrawable(pal.bg))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(24))
        }

        // Barra superior: engrenagem à esquerda, três barras à direita.
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.iconButton(R.drawable.ic_settings, "Configurações") {
                this@MainActivity.startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            })
            addView(TextView(this@MainActivity).apply {
                text = "Zelda ROM Tool"; textSize = 20f; setTextColor(pal.fg); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(ui.iconButton(R.drawable.ic_menu, "Menu") { anchor -> showMenu(anchor) })
        }
        root.addView(topBar)
        root.addView(ui.body("Importe suas chaves primeiro. Elas ficam só neste celular."))

        root.addView(ui.sectionTitle("1. Chaves"))
        val (prodCard, ps) = ui.card("🔑", "prod.keys") { pickProd.launch(arrayOf("*/*")) }
        prodStatus = ps
        root.addView(prodCard)
        val (titleCard, ts) = ui.card("🗝️", "title.keys") { pickTitle.launch(arrayOf("*/*")) }
        titleStatus = ts
        root.addView(titleCard)
        root.addView(ui.roundButton("Apagar chaves", false) {
            KeysManager.clear(this); refresh(); toast("Chaves apagadas.")
        })

        root.addView(ui.sectionTitle("2. Jogo base"))
        val (rc, rs) = ui.card("🎮", "Jogo (XCI / NSP)") { pickRom.launch(arrayOf("*/*")) }
        romCard = rc
        romStatus = rs
        root.addView(rc)

        root.addView(ui.sectionTitle("3. Extração"))
        root.addView(ui.body("Escolha onde deseja salvar os arquivos extraídos. O aplicativo lembrará a última pasta selecionada."))
        val destinationButton = ui.roundButton("Escolher pasta de destino", false) {
            pickDestination.launch(destinationTreeUri)
        }
        root.addView(destinationButton)
        destinationStatus = ui.body()
        root.addView(destinationStatus)
        extractButton = ui.roundButton("Extrair ExeFS e RomFS", true) { startExtraction() }
        setOn(extractButton, false)
        root.addView(extractButton)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(pal.accent)
            progressBackgroundTintList = ColorStateList.valueOf(pal.cardStroke)
        }
        root.addView(progressBar)
        progressText = ui.body()
        root.addView(progressText)

        cancelButton = ui.roundButton("Cancelar extração", false) {
            cancelFlag = true
            progressText.text = "Cancelando…"
        }
        cancelButton.visibility = View.GONE
        root.addView(cancelButton)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(pal.bg)
            addView(root)
        })

        // Restaura o que já estava na tela antes de refazer o visual.
        refresh()
        updateDestinationStatus()
        if (romEntries != null && oldRom.isNotBlank()) romStatus.text = oldRom
        progressText.text = oldProgressText
        if (extracting) {
            progressBar.progress = oldProgress
            progressBar.visibility = View.VISIBLE
            cancelButton.visibility = View.VISIBLE
            setOn(extractButton, false)
            setOn(romCard, false)
        } else {
            setOn(extractButton, romEntries != null)
        }
    }

    // ---------- menu das três barras ----------

    /** Menu no tema do app: segue AMOLED / escuro / claro, com cantos arredondados. */
    private fun showMenu(anchor: View) {
        val pal = ui.pal

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
            background = GradientDrawable().apply {
                setColor(pal.bg)
                cornerRadius = ui.dp(16).toFloat()
                setStroke(ui.dp(1), pal.cardStroke)
            }
        }

        val popup = PopupWindow(
            box,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            // Fundo transparente da janela: sem isso o canto arredondado aparece quadrado.
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = ui.dp(8).toFloat()
        }

        fun addItem(text: String, onClick: () -> Unit) {
            val rippleColor = (pal.accent and 0x00FFFFFF) or 0x33000000
            val mask = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = ui.dp(12).toFloat()
            }
            box.addView(TextView(this).apply {
                this.text = text
                textSize = 16f
                setTextColor(pal.fg)
                minWidth = ui.dp(240)
                setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
                background = RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask)
                isClickable = true
                setOnClickListener {
                    popup.dismiss()
                    onClick()
                }
            })
        }

        addItem("🔀  Unificar jogo base + atualização") {
            // Troque pela linha abaixo quando a UnifierActivity existir:
            // startActivity(Intent(this, UnifierActivity::class.java))
            toast("O unificador chega na próxima etapa.")
        }

        popup.showAsDropDown(anchor, 0, ui.dp(4), Gravity.END)
    }

    override fun onDestroy() {
        cancelFlag = true
        if (isFinishing) CrashLogger.uninstall()
        super.onDestroy()
    }

    private fun refresh() {
        val p = KeysManager.prodCount(this)
        val t = KeysManager.titleCount(this)
        prodStatus.text = if (p > 0) "✔ carregado ($p chaves)" else "✘ não carregado (obrigatório)"
        titleStatus.text = if (t > 0) "✔ carregado ($t title keys)" else "• não carregado (opcional)"
        val ready = KeysManager.hasProd(this)
        setOn(romCard, ready)
        if (!ready) romStatus.text = "Importe o prod.keys para liberar esta etapa."
        else if (romStatus.text.isEmpty() || romStatus.text.startsWith("Importe")) {
            romStatus.text = "Toque para escolher o XCI ou NSP"
        }
    }

    /** Lê a estrutura da ROM (rápido, só os cabeçalhos) e libera o botão de extrair. */
    private fun loadRom(uri: Uri) {
        romUri = null
        romEntries = null
        setOn(extractButton, false)
        progressText.text = ""
        romStatus.text = "Lendo a ROM…"
        Thread {
            try {
                val list = RomExtractor.list(this, uri)
                val name = displayName(uri)
                val format = detectFormat(uri)
                val total = list.sumOf { it.size }
                runOnUiThread {
                    romUri = uri
                    romEntries = list
                    romName = name
                    romStatus.text = "$name\n$format • ${list.size} arquivos • ${fmt(total)}"
                    setOn(extractButton, !extracting)
                }
            } catch (e: Exception) {
                CrashLogger.logError(this, "Ler a ROM", e)
                runOnUiThread { romStatus.text = "Erro ao ler a ROM: ${e.message}" }
            }
        }.start()
    }

    private fun startExtraction() {
        val rom = romUri ?: return
        val list = romEntries ?: return
        if (extracting) return
        val treeUri = destinationTreeUri
        if (treeUri == null) {
            waitingForDestination = true
            pickDestination.launch(null)
            return
        }

        val folder = romName.substringBeforeLast('.')
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .ifBlank { "jogo_extraido" }

        extracting = true
        cancelFlag = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setOn(extractButton, false)
        setOn(romCard, false)
        cancelButton.visibility = View.VISIBLE
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        progressText.text = "Preparando…"

        Thread {
            try {
                GameExtractor.extract(
                    ctx = this,
                    romUri = rom,
                    entries = list,
                    outTreeUri = treeUri,
                    outFolderName = folder,
                    isCancelled = { cancelFlag },
                    onProgress = { done, total, file ->
                        runOnUiThread { showProgress(done, total, file) }
                    }
                )
                runOnUiThread {
                    finishExtraction("✔ Extração concluída!\nExeFS e RomFS salvos na pasta escolhida:\n$folder")
                }
            } catch (e: RomExtractor.Cancelled) {
                runOnUiThread { finishExtraction("Extração cancelada. O arquivo parcial foi removido.") }
            } catch (e: Exception) {
                CrashLogger.logError(this, "Extração", e)
                runOnUiThread { finishExtraction("Erro na extração: ${e.message}") }
            }
        }.start()
    }

    private fun showProgress(done: Long, total: Long, file: String) {
        val frac = if (total > 0) done.toDouble() / total else 0.0
        progressBar.progress = (frac * 1000).toInt()
        progressText.text = String.format("%.1f%%\n%s\n%s / %s", frac * 100, file, fmt(done), fmt(total))
    }

    private fun finishExtraction(msg: String) {
        extracting = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cancelButton.visibility = View.GONE
        progressBar.visibility = View.GONE
        progressText.text = msg
        setOn(romCard, KeysManager.hasProd(this))
        setOn(extractButton, romEntries != null)
        toast(msg.lines().first())
    }

    private fun updateDestinationStatus() {
        if (!::destinationStatus.isInitialized) return
        destinationStatus.text = if (destinationTreeUri == null) {
            "Nenhuma pasta selecionada."
        } else {
            "Destino selecionado: pasta autorizada pelo Android."
        }
    }

    private fun fmt(b: Long): String = when {
        b >= (1L shl 30) -> String.format("%.2f GB", b / (1L shl 30).toDouble())
        b >= (1L shl 20) -> String.format("%.1f MB", b / (1L shl 20).toDouble())
        else -> "$b B"
    }

    private fun detectFormat(uri: Uri): String = try {
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val ch = FileInputStream(pfd.fileDescriptor).channel
            val a = ByteBuffer.allocate(4)
            ch.read(a, 0)
            val b = ByteBuffer.allocate(4)
            ch.read(b, 0x100)
            when {
                String(a.array(), Charsets.US_ASCII) == "PFS0" -> "NSP"
                String(b.array(), Charsets.US_ASCII) == "HEAD" -> "XCI"
                else -> "formato desconhecido"
            }
        } ?: "não consegui abrir"
    } catch (e: Exception) {
        "erro ao ler: ${e.message}"
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: uri.lastPathSegment ?: "arquivo"

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
