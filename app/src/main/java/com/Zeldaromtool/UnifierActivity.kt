package com.zeldaromtool

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Tela "Unificar jogo base + atualização". O trabalho roda no UnifyService (segundo plano). */
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

    private val pickBase = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadBase(uri)
    }

    private val pickUpdate = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            keepAccess(uri)
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

    // Permissão de notificações (Android 13+): o progresso aparece na barra de notificações.
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        launchService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        destinationTreeUri = getSharedPreferences("zeldaromtool", MODE_PRIVATE)
            .getString("destination_tree_uri", null)?.let { Uri.parse(it) }
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        UnifyState.listener = { s -> runOnUiThread { render(s) } }
        render(UnifyState.snapshot)
    }

    override fun onStop() {
        UnifyState.listener = null
        super.onStop()
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
        root.addView(ui.body("Escolha o jogo base e o NCA de atualização. O app junta tudo e salva o ExeFS e o RomFS já atualizados. Você pode sair do app: a unificação continua em segundo plano."))

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
            UnifyState.cancel = true
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

    /** Mantém o acesso ao arquivo escolhido mesmo com a tela fechada. */
    private fun keepAccess(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }

    private fun loadBase(uri: Uri) {
        keepAccess(uri)
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
        if (baseUri == null || baseEntries == null || updateUri == null) return
        if (working) return
        if (destinationTreeUri == null) {
            waitingForDestination = true
            pickDestination.launch(null)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchService()
    }

    private fun launchService() {
        val base = baseUri ?: return
        val update = updateUri ?: return
        val tree = destinationTreeUri ?: return
        if (working) return

        val folder = (baseName.substringBeforeLast('.') + " (atualizado)")
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .ifBlank { "jogo_atualizado" }

        render(UnifyState.Snapshot(true, 0, 0, "Preparando…", null))
        try {
            UnifyService.start(this, UnifyService.MODE_UNIFY, base, update, tree, folder)
        } catch (e: Exception) {
            CrashLogger.logError(this, "Iniciar o serviço", e)
            render(UnifyState.Snapshot(false, 0, 0, "", "Não consegui iniciar em segundo plano: ${e.message}"))
            return
        }
        askBatteryOptimizationOnce()
    }

    /** Pede uma vez para o Android não encerrar o app durante extrações longas. */
    private fun askBatteryOptimizationOnce() {
        val prefs = getSharedPreferences("zeldaromtool", MODE_PRIVATE)
        if (prefs.getBoolean("battery_asked", false)) return
        prefs.edit().putBoolean("battery_asked", true).apply()
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
        }
    }

    /** Atualiza a tela conforme o estado do serviço. */
    private fun render(s: UnifyState.Snapshot) {
        if (s.running) {
            working = true
            ui.setOn(baseCard, false)
            ui.setOn(updateCard, false)
            cancelButton.visibility = View.VISIBLE
            progressBar.visibility = View.VISIBLE
            updateButton()
            showProgress(s.done, s.total, s.file)
        } else if (s.message != null) {
            UnifyState.clearMessage()
            endWork(s.message)
        }
    }

    private fun showProgress(done: Long, total: Long, file: String) {
        if (total <= 0) {
            progressBar.progress = 0
            progressText.text = file
            return
        }
        val frac = done.toDouble() / total
        progressBar.progress = (frac * 1000).toInt()
        progressText.text = String.format("%.1f%%\n%s\n%s / %s", frac * 100, file, fmt(done), fmt(total))
    }

    private fun endWork(msg: String) {
        working = false
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
