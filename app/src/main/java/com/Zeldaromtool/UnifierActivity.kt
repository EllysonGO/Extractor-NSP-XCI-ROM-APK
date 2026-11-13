package com.zeldaromtool

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** Tela "Unificar jogo base + atualização". */
class UnifierActivity : AppCompatActivity() {

    private lateinit var ui: Ui

    private lateinit var baseCard: View
    private lateinit var baseStatus: TextView
    private lateinit var updateCard: View
    private lateinit var updateStatus: TextView
    private lateinit var destinationStatus: TextView
    private lateinit var unifyButton: Button
    private lateinit var cancelButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    private var baseUri: Uri? = null
    private var baseEntries: List<RomExtractor.Entry>? = null
    private var baseName = ""
    private var updateUri: Uri? = null
    private var destinationTreeUri: Uri? = null
    private var waitingForDestination = false
    private var working = false
    @Volatile private var cancelFlag = false

    private val pickBase = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadBase(uri)
    }

    private val pickUpdate = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            updateUri = uri
            updateStatus.text = "${displayName(uri)}\nAtualização selecionada"
            updateButton()
        }
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
                    startUnify()
                }
            } catch (e: Exception) {
                waitingForDestination = false
                toast("Não foi possível autorizar essa pasta: ${e.message}")
            }
        } else {
            waitingForDestination = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        destinationTreeUri = getSharedPreferences("zeldaromtool", MODE_PRIVATE)
            .getString("destination_tree_uri", null)?.let { Uri.parse(it) }
        buildUi()
    }

    override fun onDestroy() {
        cancelFlag = true
        super.onDestroy()
    }

    private fun buildUi() {
        ui = Ui(this, AppSettings.palette(this))
        val pal = ui.pal
        ui.applyBars(this)
        window.setBackgroundDrawable(ColorDrawable(pal.bg))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(24))
        }

        root.addView(TextView(this).apply {
            text = "Unificar jogo base + atualização"
            textSize = 20f
            setTextColor(pal.fg)
            gravity = Gravity.CENTER
        })
        root.addView(ui.body("Escolha o jogo base e o NCA de atualização. O app junta tudo e salva o ExeFS e o RomFS já atualizados."))

        root.addView(ui.sectionTitle("1. Jogo base"))
        val (bc, bs) = ui.card("🎮", "Jogo base (XCI / NSP)") { pickBase.launch(arrayOf("*/*")) }
        baseCard = bc
        baseStatus = bs
        root.addView(bc)

        root.addView(ui.sectionTitle("2. Atualização"))
        val (uc, us) = ui.card("⬆️", "Atualização (NCA)") { pickUpdate.launch(arrayOf("*/*")) }
        updateCard = uc
        updateStatus = us
        root.addView(uc)

        root.addView(ui.sectionTitle("3. Destino"))
        root.addView(ui.roundButton("Escolher pasta de destino", false) {
            pickDestination.launch(destinationTreeUri)
        })
        destinationStatus = ui.body()
        root.addView(destinationStatus)

        unifyButton = ui.roundButton("Unificar e extrair", true) { startUnify() }
        root.addView(unifyButton)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(pal.accent)
            progressBackgroundTintList = ColorStateList.valueOf(pal.cardStroke)
        }
        root.addView(progressBar)
        progressText = ui.body()
        root.addView(progressText)

        cancelButton = ui.roundButton("Cancelar", false) {
            cancelFlag = true
            progressText.text = "Cancelando…"
        }
        cancelButton.visibility = View.GONE
        root.addView(cancelButton)

        root.addView(ui.roundButton("Voltar", false) { finish() })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(pal.bg)
            addView(root)
        })

        baseStatus.text = "Toque para escolher o XCI ou NSP"
        updateStatus.text = "Toque para escolher o NCA de atualização"
        updateDestinationStatus()
        ui.setOn(baseCard, KeysManager.hasProd(this))
        ui.setOn(updateCard, KeysManager.hasProd(this))
        if (!KeysManager.hasProd(this)) baseStatus.text = "Importe o prod.keys na tela inicial primeiro."
        updateButton()
    }

    private fun updateButton() {
        ui.setOn(unifyButton, baseEntries != null && updateUri != null && !working)
    }

    private fun loadBase(uri: Uri) {
        baseUri = null
        baseEntries = null
        updateButton()
        baseStatus.text = "Lendo o jogo base…"
        Thread {
            try {
                val list = RomExtractor.list(this, uri)
                val name = displayName(uri)
                runOnUiThread {
                    baseUri = uri
                    baseEntries = list
                    baseName = name
                    baseStatus.text = "$name\n${list.size} arquivos • ${fmt(list.sumOf { it.size })}"
                    updateButton()
                }
            } catch (e: Exception) {
                CrashLogger.logError(this, "Ler o jogo base", e)
                runOnUiThread { baseStatus.text = "Erro ao ler o jogo base: ${e.message}" }
            }
        }.start()
    }

    private fun startUnify() {
        val base = baseUri ?: return
        val entries = baseEntries ?: return
        val update = updateUri ?: return
        if (working) return
        val treeUri = destinationTreeUri
        if (treeUri == null) {
            waitingForDestination = true
            pickDestination.launch(null)
            return
        }

        val folder = (baseName.substringBeforeLast('.') + " (atualizado)")
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .ifBlank { "jogo_atualizado" }

        working = true
        cancelFlag = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateButton()
        ui.setOn(baseCard, false)
        ui.setOn(updateCard, false)
        cancelButton.visibility = View.VISIBLE
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        progressText.text = "Preparando…"

        Thread {
            try {
                Unifier.unify(
                    ctx = this,
                    baseUri = base,
                    baseEntries = entries,
                    updateUri = update,
                    outTreeUri = treeUri,
                    outFolderName = folder,
                    isCancelled = { cancelFlag },
                    onProgress = { done, total, file ->
                        runOnUiThread { showProgress(done, total, file) }
                    }
                )
                runOnUiThread {
                    endWork("✔ Unificação concluída!\nExeFS e RomFS atualizados salvos em:\n$folder")
                }
            } catch (e: RomExtractor.Cancelled) {
                runOnUiThread { endWork("Unificação cancelada. O arquivo parcial foi removido.") }
            } catch (e: Exception) {
                CrashLogger.logError(this, "Unificação", e)
                runOnUiThread { endWork("Erro na unificação: ${e.message}") }
            }
        }.start()
    }

    private fun showProgress(done: Long, total: Long, file: String) {
        val frac = if (total > 0) done.toDouble() / total else 0.0
        progressBar.progress = (frac * 1000).toInt()
        progressText.text = String.format("%.1f%%\n%s\n%s / %s", frac * 100, file, fmt(done), fmt(total))
    }

    private fun endWork(msg: String) {
        working = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cancelButton.visibility = View.GONE
        progressBar.visibility = View.GONE
        progressText.text = msg
        ui.setOn(baseCard, KeysManager.hasProd(this))
        ui.setOn(updateCard, KeysManager.hasProd(this))
        updateButton()
        toast(msg.lines().first())
    }

    private fun updateDestinationStatus() {
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

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: uri.lastPathSegment ?: "arquivo"

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
