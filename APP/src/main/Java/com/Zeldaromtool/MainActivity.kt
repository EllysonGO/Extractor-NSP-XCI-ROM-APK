package com.zeldaromtool

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.FileInputStream
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private lateinit var prodStatus: TextView
    private lateinit var titleStatus: TextView
    private lateinit var romStatus: TextView
    private lateinit var romButton: Button

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
        if (uri != null) {
            romStatus.text = "${displayName(uri)}\nFormato: ${detectFormat(uri)}\n\n(Listagem e extração: próxima etapa)"
        }
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

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        })
        refresh()
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
