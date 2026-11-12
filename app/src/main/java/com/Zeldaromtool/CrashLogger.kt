package com.zeldaromtool

import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.DocumentsContract
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Log próprio do app. Só funciona enquanto o app está aberto e só grava se a chave
 * "Salvar log de erros" estiver ativa nas Configurações.
 */
object CrashLogger {

    private var installed = false
    private var previous: Thread.UncaughtExceptionHandler? = null

    /** Registra o capturador de fechamentos inesperados. */
    @Synchronized
    fun install(ctx: Context) {
        if (installed) return
        val app = ctx.applicationContext
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, "CRASH", "Thread \"${thread.name}\"", error)
            } catch (ignored: Throwable) {
            }
            val prev = previous
            if (prev != null) {
                prev.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(1)
            }
        }
        installed = true
    }

    @Synchronized
    fun uninstall() {
        if (!installed) return
        Thread.setDefaultUncaughtExceptionHandler(previous)
        previous = null
        installed = false
    }

    /** Para erros que o app captura sozinho (ex.: falha ao ler ou extrair a ROM). */
    fun logError(ctx: Context, where: String, error: Throwable) {
        try {
            write(ctx.applicationContext, "ERRO", where, error)
        } catch (ignored: Throwable) {
        }
    }

    /** Botão de teste das Configurações. Devolve onde gravou, ou null se não gravou. */
    fun writeTest(ctx: Context): String? =
        try {
            write(
                ctx.applicationContext, "TESTE", "Botão de teste nas Configurações",
                RuntimeException("Log de teste (não é um erro real)")
            )
        } catch (e: Throwable) {
            null
        }

    private fun write(ctx: Context, kind: String, where: String, error: Throwable): String? {
        if (!AppSettings.logEnabled(ctx)) return null
        val text = report(ctx, kind, where, error)
        val base = kind.lowercase(Locale.US) + "_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        if (writeToChosenFolder(ctx, base, text)) return "pasta escolhida (Zelda ROM Tool/Logs)"

        return try {
            File(AppFolders.privateLogsDir(ctx), "$base.txt").writeText(text)
            "Android/data/${ctx.packageName}/files/logs"
        } catch (e: Exception) {
            null
        }
    }

    private fun writeToChosenFolder(ctx: Context, baseName: String, text: String): Boolean {
        return try {
            val dir = AppSettings.logsDirUri(ctx) ?: return false
            // sem ".txt" no nome: o sistema acrescenta a extensão pelo tipo text/plain
            val doc = DocumentsContract.createDocument(ctx.contentResolver, dir, "text/plain", baseName)
                ?: return false
            ctx.contentResolver.openOutputStream(doc)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: return false
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun report(ctx: Context, kind: String, where: String, error: Throwable): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val rt = Runtime.getRuntime()
        val mb = 1024L * 1024L
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        val keys = try {
            KeysManager.hasProd(ctx)
        } catch (e: Exception) {
            false
        }
        return buildString {
            appendLine("Zelda ROM Tool - relatório ($kind)")
            appendLine("Data: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("Onde: $where")
            appendLine("App: versão $version")
            appendLine("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine(
                "Memória do app: ${(rt.totalMemory() - rt.freeMemory()) / mb} MB em uso, máximo ${rt.maxMemory() / mb} MB"
            )
            appendLine("prod.keys carregado: $keys")
            appendLine()
            append(sw.toString())
        }
    }
}
