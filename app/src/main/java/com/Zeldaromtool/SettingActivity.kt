package com.zeldaromtool

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {

    private lateinit var ui: Ui

    private val updaterExecutor = Executors.newSingleThreadExecutor()

    private var latestRelease: Updater.Release? = null
    private var pendingApk: File? = null

    private lateinit var updateInfoText: TextView
    private lateinit var checkUpdateButton: android.widget.Button
    private lateinit var installUpdateButton: android.widget.Button

    private val pickFolder =
        registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) onFolderPicked(uri)
        }

    private val installPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            val apk = pendingApk

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                Settings.canRequestPackageInstalls() &&
                apk != null
            ) {
                installApk(apk)
            } else {
                toast(
                    "Permissão não concedida. " +
                        "Autorize a instalação para continuar."
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        buildUi()
    }

    override fun onDestroy() {
        updaterExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        ui = Ui(this, AppSettings.palette(this))
        val pal = ui.pal

        ui.applyBars(this)
        window.setBackgroundDrawable(ColorDrawable(pal.bg))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                ui.dp(20),
                ui.dp(12),
                ui.dp(20),
                ui.dp(24)
            )
        }

        // ---------- Topo ----------
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(
                TextView(this@SettingsActivity).apply {
                    text = "←"
                    textSize = 24f
                    setTextColor(pal.fg)
                    gravity = Gravity.CENTER
                    contentDescription = "Voltar"
                    isClickable = true
                    setOnClickListener {
                        this@SettingsActivity.finish()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ui.dp(48),
                        ui.dp(48)
                    )
                }
            )

            addView(
                TextView(this@SettingsActivity).apply {
                    text = "Configurações"
                    textSize = 22f
                    setTextColor(pal.fg)
                }
            )
        }

        root.addView(top)

        // ---------- Atualização ----------
        root.addView(ui.sectionTitle("Atualização"))

        root.addView(
            ui.body(
                "Verifique se existe uma versão mais recente do " +
                    "ZeldaRomTool publicada no GitHub. " +
                    "Se houver uma atualização, você poderá baixar " +
                    "o APK e iniciar a instalação."
            )
        )

        val installedVersion = getInstalledVersion()

        root.addView(
            ui.body("Versão instalada: $installedVersion")
        )

        updateInfoText = ui.body()
        root.addView(updateInfoText)

        checkUpdateButton = ui.roundButton(
            "Verificar atualização",
            true
        ) {
            checkForUpdates()
        }

        root.addView(checkUpdateButton)

        installUpdateButton = ui.roundButton(
            "Baixar e instalar atualização",
            false
        ) {
            downloadAndInstall()
        }

        installUpdateButton.visibility = View.GONE
        root.addView(installUpdateButton)

        refreshUpdaterSection()

        // ---------- Aparência ----------
        root.addView(ui.sectionTitle("Aparência"))

        val current = AppSettings.themeMode(this)

        val options = listOf(
            Triple(
                ThemeMode.AMOLED,
                "🌑",
                "AMOLED (preto puro)"
            ),
            Triple(
                ThemeMode.DARK,
                "🌙",
                "Escuro padrão"
            ),
            Triple(
                ThemeMode.LIGHT,
                "☀️",
                "Claro"
            )
        )

        for ((mode, icon, label) in options) {
            val (row, status) = ui.card(icon, label) {
                AppSettings.setThemeMode(this, mode)
                buildUi()
            }

            if (mode == current) {
                status.text = "✔ em uso"
                status.setTextColor(pal.accent)
            } else {
                status.text = "Toque para usar"
            }

            root.addView(row)
        }

        // ---------- Arquivos e pastas ----------
        root.addView(ui.sectionTitle("Arquivos e pastas"))

        root.addView(
            ui.body(
                "Escolha uma pasta do celular (por exemplo, dentro de Documentos). " +
                    "O app cria nela a pasta “${AppFolders.ROOT_NAME}/${AppFolders.LOGS_NAME}” " +
                    "e só acessa a pasta que você escolher."
            )
        )

        val tree = AppSettings.treeUri(this)

        val (folderRow, folderStatus) = ui.card(
            "📁",
            "Pasta do app"
        ) {
            pickFolder.launch(null)
        }

        if (tree != null) {
            folderStatus.text =
                "✔ ${AppFolders.folderName(this, tree)}/" +
                    "${AppFolders.ROOT_NAME}/${AppFolders.LOGS_NAME}"

            folderStatus.setTextColor(pal.accent)
        } else {
            folderStatus.text = "✘ nenhuma pasta escolhida"
        }

        root.addView(folderRow)

        if (tree != null) {
            root.addView(
                ui.roundButton(
                    "Remover acesso à pasta",
                    false
                ) {
                    revokeFolder()
                }
            )
        }

        // ---------- Log de erros ----------
        root.addView(ui.sectionTitle("Log de erros"))

        root.addView(
            ui.body(
                "Registra o erro quando o app fecha sozinho ou a leitura/extração falha. " +
                    "Só funciona com o app aberto. Deslize para a direita para ativar."
            )
        )

        val logStatus = ui.body()

        fun refreshLogStatus() {
            logStatus.setTextColor(pal.muted)

            logStatus.text = when {
                !AppSettings.logEnabled(this) ->
                    "Desativado: nenhum log é gravado."

                AppSettings.logsDirUri(this) != null ->
                    "Os logs são salvos em ${AppFolders.ROOT_NAME}/${AppFolders.LOGS_NAME}, na pasta escolhida."

                else ->
                    "Sem pasta escolhida: os logs vão para Android/data/$packageName/files/logs. " +
                        "Escolha uma pasta acima para achar os logs com mais facilidade."
            }
        }

        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        )

        val sw = SwitchCompat(this).apply {
            text = "Salvar log de erros"
            textSize = 16f
            setTextColor(pal.fg)

            isChecked = AppSettings.logEnabled(
                this@SettingsActivity
            )

            thumbTintList = ColorStateList(
                states,
                intArrayOf(pal.accent, pal.muted)
            )

            trackTintList = ColorStateList(
                states,
                intArrayOf(
                    (pal.accent and 0x00FFFFFF) or 0x66000000,
                    pal.cardStroke
                )
            )

            setOnCheckedChangeListener { _, on ->
                AppSettings.setLogEnabled(
                    this@SettingsActivity,
                    on
                )

                refreshLogStatus()
            }
        }

        val logRow = LinearLayout(this).apply {
            setPadding(
                ui.dp(18),
                ui.dp(10),
                ui.dp(18),
                ui.dp(10)
            )

            background = ui.shape(
                pal.cardBg,
                24,
                pal.cardStroke,
                1
            )

            addView(
                sw,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(
                    0,
                    ui.dp(6),
                    0,
                    ui.dp(6)
                )
            }
        }

        root.addView(logRow)
        root.addView(logStatus)

        refreshLogStatus()

        root.addView(
            ui.roundButton(
                "Gravar log de teste",
                false
            ) {
                val where = CrashLogger.writeTest(this)

                toast(
                    if (where != null) {
                        "Log de teste gravado em: $where"
                    } else {
                        "Não gravou. Ative o log de erros primeiro."
                    }
                )
            }
        )

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(pal.bg)
                addView(root)
            }
        )
    }

    // =========================================================
    // ATUALIZADOR
    // =========================================================

    private fun getInstalledVersion(): String {
        return try {
            val info = packageManager.getPackageInfo(
                packageName,
                0
            )

            info.versionName ?: "desconhecida"
        } catch (e: Exception) {
            "desconhecida"
        }
    }

    private fun refreshUpdaterSection() {
        val release = latestRelease

        if (release == null) {
            updateInfoText.text =
                "Toque em “Verificar atualização” para consultar o GitHub."

            installUpdateButton.visibility = View.GONE
            return
        }

        val installed = getInstalledVersion()

        val newer = Updater.isNewerVersion(
            release.version,
            installed
        )

        if (newer) {
            updateInfoText.text =
                "Nova versão disponível: ${release.version}\n" +
                    "Versão instalada: $installed\n" +
                    "Uma atualização está disponível."

            installUpdateButton.visibility = View.VISIBLE
        } else {
            updateInfoText.text =
                "Versão disponível: ${release.version}\n" +
                    "Versão instalada: $installed\n" +
                    "✔ O ZeldaRomTool está atualizado."

            installUpdateButton.visibility = View.GONE
        }
    }

    private fun checkForUpdates() {
        checkUpdateButton.isEnabled = false
        checkUpdateButton.alpha = 0.5f

        updateInfoText.text =
            "Consultando a publicação mais recente no GitHub..."

        updaterExecutor.execute {
            try {
                val release = Updater.checkLatestRelease()

                runOnUiThread {
                    latestRelease = release

                    refreshUpdaterSection()

                    checkUpdateButton.isEnabled = true
                    checkUpdateButton.alpha = 1f

                    toast("Verificação concluída.")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateInfoText.text =
                        "Não foi possível verificar atualizações.\n" +
                            (e.message ?: "Erro desconhecido.")

                    checkUpdateButton.isEnabled = true
                    checkUpdateButton.alpha = 1f

                    installUpdateButton.visibility = View.GONE

                    toast("Falha ao consultar o GitHub.")
                }
            }
        }
    }

    private fun downloadAndInstall() {
        val release = latestRelease

        if (release == null) {
            toast("Verifique as atualizações primeiro.")
            return
        }

        val installed = getInstalledVersion()

        if (!Updater.isNewerVersion(release.version, installed)) {
            refreshUpdaterSection()
            toast("O aplicativo já está atualizado.")
            return
        }

        installUpdateButton.isEnabled = false
        installUpdateButton.alpha = 0.5f

        updateInfoText.text =
            "Baixando ZeldaRomTool ${release.version}...\n" +
                "Aguarde a conclusão do download."

        updaterExecutor.execute {
            try {
                val directory = File(cacheDir, "updates")

                val apk = Updater.downloadApk(
                    release,
                    directory
                )

                runOnUiThread {
                    installUpdateButton.isEnabled = true
                    installUpdateButton.alpha = 1f

                    updateInfoText.text =
                        "Download concluído.\n" +
                            "Preparando a instalação..."

                    requestInstallPermissionOrInstall(apk)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    installUpdateButton.isEnabled = true
                    installUpdateButton.alpha = 1f

                    updateInfoText.text =
                        "Falha ao baixar a atualização.\n" +
                            (e.message ?: "Erro desconhecido.")

                    toast("Não foi possível baixar o APK.")
                }
            }
        }
    }

    private fun requestInstallPermissionOrInstall(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!Settings.canRequestPackageInstalls()) {
                pendingApk = apk

                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )

                installPermissionLauncher.launch(intent)

                toast(
                    "Autorize a instalação do ZeldaRomTool " +
                        "na próxima tela do Android."
                )

                return
            }
        }

        installApk(apk)
    }

    private fun installApk(apk: File) {
        try {
            if (!apk.exists()) {
                toast("O arquivo de atualização não foi encontrado.")
                return
            }

            val apkUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apk
            )

            val intent = Intent(
                Intent.ACTION_VIEW
            ).apply {
                setDataAndType(
                    apkUri,
                    "application/vnd.android.package-archive"
                )

                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            startActivity(intent)

            updateInfoText.text =
                "O instalador do Android foi aberto. " +
                    "Confirme a instalação para concluir a atualização."

        } catch (e: Exception) {
            CrashLogger.logError(
                this,
                "Instalar atualização",
                e
            )

            toast(
                "Não consegui abrir o instalador: ${e.message}"
            )
        }
    }

    // =========================================================
    // PASTAS
    // =========================================================

    private fun onFolderPicked(uri: Uri) {
        try {
            val flags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION

            contentResolver.takePersistableUriPermission(
                uri,
                flags
            )

            val logs = AppFolders.ensureLogsDir(
                this,
                uri
            )

            if (logs == null) {
                toast(
                    "Não consegui criar as pastas aí. " +
                        "Escolha outra pasta (por exemplo, dentro de Documentos)."
                )
                return
            }

            val old = AppSettings.treeUri(this)

            if (old != null && old != uri) {
                try {
                    contentResolver.releasePersistableUriPermission(
                        old,
                        flags
                    )
                } catch (ignored: Exception) {
                }
            }

            AppSettings.setFolder(
                this,
                uri,
                logs.uri
            )

            toast(
                "Pastas criadas: ${AppFolders.ROOT_NAME}/${AppFolders.LOGS_NAME}"
            )

            buildUi()

        } catch (e: Exception) {
            CrashLogger.logError(
                this,
                "Escolher pasta",
                e
            )

            toast("Erro ao acessar a pasta: ${e.message}")
        }
    }

    private fun revokeFolder() {
        val tree = AppSettings.treeUri(this)

        if (tree != null) {
            try {
                contentResolver.releasePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (ignored: Exception) {
            }
        }

        AppSettings.clearFolder(this)

        toast("Acesso removido.")

        buildUi()
    }

    private fun toast(msg: String) {
        Toast.makeText(
            this,
            msg,
            Toast.LENGTH_LONG
        ).show()
    }
}
