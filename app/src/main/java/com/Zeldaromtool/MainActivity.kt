package com.zeldaromtool

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
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private lateinit var prodStatus: TextView
    private lateinit var titleStatus: TextView
    private lateinit var romStatus: TextView
    private lateinit var romButton: Button
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bg = 0xFF10241D.toInt()
        val fg = 0xFFF2E8C9.toInt()
        val gold = 0xFFE8C766.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(24))
        }

        fun title(t: String) = TextView(this).apply {
            text = t; textSize = 20f; setTextColor(gold)
            setPadding(0, dp(20), 0, dp(8))
        }

        fun body(t: String = "") = TextView(this).apply {
            text = t; textSize = 14f; setTextColor(fg)
            setPadding(0, dp(4), 0, dp(4))
        }

        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { onClick() }
        }

        root.addView(TextView(this).apply {
            text = "Zelda ROM Tool"; textSize = 26f; setTextColor(fg); gravity = Gravity.START
        })
        root.addView(body("Importe suas chaves primeiro. Elas ficam só neste celular."))

        root.addView(title("1. Chaves"))
        root.addView(button("Selecionar prod.keys") { pickProd.launch(arrayOf("*/*")) })
        prodStatus = body(); root.addView(prodStatus)
        root.addView(button("Selecionar title.keys") { pickTitle.launch(arrayOf("*/*")) })
        titleStatus = body(); root.addView(titleStatus)
        root.addView(button("Apagar chaves") {
            KeysManager.clear(this); refresh(); toast("Chaves apagadas.")
        })

        root.addView(title("2. Jogo (XCI / NSP)"))
        romButton = button("Selecionar XCI / NSP") { pickRom.launch(arrayOf("*/*")) }
        root.addView(romButton)
        romStatus = body(); root.addView(romStatus)

        root.addView(title("3. Extração"))
        root.addView(body("Extrai o ExeFS e o RomFS já decifrados para a pasta de dados do app (Android/data/com.zeldaromtool/files)."))
        extractButton = button("Extrair ExeFS e RomFS") { startExtraction() }
        extractButton.isEnabled = false
        root.addView(extractButton)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        root.addView(progressBar)
        progressText = body(); root.addView(progressText)

        cancelButton = button("Cancelar extração") {
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
        prodStatus.text = if (p > 0) "✔ prod.keys carregado ($p chaves)" else "✘ prod.keys não carregado (obrigatório)"
        titleStatus.text = if (t > 0) "✔ title.keys carregado ($t title keys)" else "• title.keys não carregado (opcional)"
        val ready = KeysManager.hasProd(this)
        romButton.isEnabled = ready
        if (!ready) romStatus.text = "Importe o prod.keys para liberar esta etapa."
        else if (romStatus.text.startsWith("Importe")) romStatus.text = ""
    }

    /** Lê a estrutura da ROM (rápido, só os cabeçalhos) e libera o botão de extrair. */
    private fun loadRom(uri: Uri) {
        romUri = null
        romEntries = null
        extractButton.isEnabled = false
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
                    romStatus.text = "$name\nFormato: $format\nArquivos: ${list.size}\nTamanho: ${fmt(total)}"
                    extractButton.isEnabled = !extracting
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

        extracting = true
        cancelFlag = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        extractButton.isEnabled = false
        romButton.isEnabled = false
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
        romButton.isEnabled = KeysManager.hasProd(this)
        extractButton.isEnabled = romEntries != null
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
                String(a.array(), Charsets.US_ASCII) == "PFS0" -> "NSP (PFS0)"
                String(b.array(), Charsets.US_ASCII) == "HEAD" -> "XCI"
                else -> "desconhecido (não parece NSP nem XCI)"
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
