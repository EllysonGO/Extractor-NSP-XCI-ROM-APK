package com.zeldaromtool

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    // Cores (AMOLED: fundo preto puro). Na tela de Definições isso vira uma opção.
    private val amoled = true
    private val bg get() = if (amoled) 0xFF000000.toInt() else 0xFF10241D.toInt()
    private val fg = 0xFFF2E8C9.toInt()
    private val gold = 0xFFE8C766.toInt()
    private val muted = 0xFF9A9A8A.toInt()
    private val cardBg = 0xFF0C0C0C.toInt()
    private val cardStroke = 0xFF262626.toInt()
    private val badgeBg = 0xFF1A1A1A.toInt()

    private lateinit var prodStatus: TextView
    private lateinit var titleStatus: TextView
    private lateinit var romStatus: TextView
    private lateinit var romCard: View
    private lateinit var extractButton: Button
    private lateinit var cancelButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    private var romUri: Uri? = null
    private var romEntries: List<RomExtractor.Entry>? = null
    private var romName = ""
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

    // ---------- helpers de visual ----------

    private fun shape(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun ripple(d: GradientDrawable) =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), d, null)

    private fun setOn(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.35f
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t; textSize = 20f; setTextColor(gold)
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun body(t: String = "") = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(fg)
        setPadding(0, dp(4), 0, dp(4))
    }

    /** Cartão arredondado com ícone redondo, nome e uma linha de status. */
    private fun card(icon: String, label: String, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val badge = TextView(this).apply {
            text = icon; textSize = 22f; gravity = Gravity.CENTER
            background = shape(badgeBg, 28)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        }
        val name = TextView(this).apply {
            text = label; textSize = 16f; setTextColor(fg)
            setTypeface(typeface, Typeface.BOLD)
        }
        val status = TextView(this).apply { textSize = 13f; setTextColor(muted) }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(name)
            addView(status)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = ripple(shape(cardBg, 24, cardStroke, 1))
            addView(badge)
            addView(texts)
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        return row to status
    }

    private fun roundButton(t: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = t
        setAllCaps(false)
        textSize = 15f
        stateListAnimator = null
        setTextColor(if (filled) 0xFF000000.toInt() else gold)
        background = ripple(if (filled) shape(gold, 28) else shape(0, 28, gold, 1))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { setMargins(0, dp(6), 0, dp(6)) }
    }

    // ---------- tela ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "Zelda ROM Tool"; textSize = 26f; setTextColor(fg); gravity = Gravity.START
        })
        root.addView(body("Importe suas chaves primeiro. Elas ficam só neste celular."))

        root.addView(sectionTitle("1. Chaves"))
        val (prodCard, ps) = card("🔑", "prod.keys") { pickProd.launch(arrayOf("*/*")) }
        prodStatus = ps
        root.addView(prodCard)
        val (titleCard, ts) = card("🗝️", "title.keys") { pickTitle.launch(arrayOf("*/*")) }
        titleStatus = ts
        root.addView(titleCard)
        root.addView(roundButton("Apagar chaves", false) {
            KeysManager.clear(this); refresh(); toast("Chaves apagadas.")
        })

        root.addView(sectionTitle("2. Jogo base"))
        val (rc, rs) = card("🎮", "Jogo (XCI / NSP)") { pickRom.launch(arrayOf("*/*")) }
        romCard = rc
        romStatus = rs
        root.addView(rc)

        root.addView(sectionTitle("3. Extração"))
        root.addView(body("Extrai o ExeFS e o RomFS já decifrados para a pasta de dados do app (Android/data/com.zeldaromtool/files)."))
        extractButton = roundButton("Extrair ExeFS e RomFS", true) { startExtraction() }
        setOn(extractButton, false)
        root.addView(extractButton)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(gold)
            progressBackgroundTintList = ColorStateList.valueOf(cardStroke)
        }
        root.addView(progressBar)
        progressText = body()
        root.addView(progressText)

        cancelButton = roundButton("Cancelar extração", false) {
            cancelFlag = true
            progressText.text = "Cancelando…"
        }
        cancelButton.visibility = View.GONE
        root.addView(cancelButton)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        })
        refresh()
    }

    override fun onDestroy() {
        cancelFlag = true
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
                runOnUiThread { romStatus.text = "Erro ao ler a ROM: ${e.message}" }
            }
        }.start()
    }

    private fun startExtraction() {
        val rom = romUri ?: return
        val list = romEntries ?: return
        if (extracting) return
        val base = getExternalFilesDir(null)
        if (base == null) {
            toast("Armazenamento externo indisponível.")
            return
        }

        val folder = romName.substringBeforeLast('.')
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .ifBlank { "jogo_extraido" }
        val outDir = File(base, folder)

        // Impede que imagens e vídeos extraídos apareçam na Galeria.
        outDir.mkdirs()
        File(outDir, ".nomedia").createNewFile()

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
                    outDir = outDir,
                    isCancelled = { cancelFlag },
                    onProgress = { done, total, file ->
                        runOnUiThread { showProgress(done, total, file) }
                    }
                )
                runOnUiThread {
                    finishExtraction("✔ Extração concluída!\nExeFS e RomFS salvos em:\n${outDir.absolutePath}")
                }
            } catch (e: RomExtractor.Cancelled) {
                runOnUiThread { finishExtraction("Extração cancelada. O arquivo parcial foi removido.") }
            } catch (e: Exception) {
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
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
