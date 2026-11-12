package com.zeldaromtool

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

enum class ThemeMode { AMOLED, DARK, LIGHT }

class Palette(
    val bg: Int,
    val fg: Int,
    val accent: Int,
    val muted: Int,
    val cardBg: Int,
    val cardStroke: Int,
    val badgeBg: Int,
    val onAccent: Int,
    val lightBars: Boolean
)

/** Preferências do app (tema, log, pasta escolhida). */
object AppSettings {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun themeMode(ctx: Context): ThemeMode =
        try {
            ThemeMode.valueOf(prefs(ctx).getString("theme", null) ?: ThemeMode.AMOLED.name)
        } catch (e: Exception) {
            ThemeMode.AMOLED
        }

    fun setThemeMode(ctx: Context, mode: ThemeMode) {
        prefs(ctx).edit().putString("theme", mode.name).apply()
    }

    fun palette(ctx: Context): Palette = when (themeMode(ctx)) {
        ThemeMode.AMOLED -> Palette(
            bg = 0xFF000000.toInt(), fg = 0xFFF2E8C9.toInt(), accent = 0xFFE8C766.toInt(),
            muted = 0xFF9A9A8A.toInt(), cardBg = 0xFF0C0C0C.toInt(), cardStroke = 0xFF262626.toInt(),
            badgeBg = 0xFF1A1A1A.toInt(), onAccent = 0xFF000000.toInt(), lightBars = false
        )
        ThemeMode.DARK -> Palette(
            bg = 0xFF10241D.toInt(), fg = 0xFFF2E8C9.toInt(), accent = 0xFFE8C766.toInt(),
            muted = 0xFFA9B5AA.toInt(), cardBg = 0xFF173229.toInt(), cardStroke = 0xFF2B4A3E.toInt(),
            badgeBg = 0xFF1F3E33.toInt(), onAccent = 0xFF000000.toInt(), lightBars = false
        )
        ThemeMode.LIGHT -> Palette(
            bg = 0xFFF6F1E3.toInt(), fg = 0xFF1B1B1B.toInt(), accent = 0xFF8A6A00.toInt(),
            muted = 0xFF6B6B5E.toInt(), cardBg = 0xFFFFFFFF.toInt(), cardStroke = 0xFFD9D2BC.toInt(),
            badgeBg = 0xFFEFE8D2.toInt(), onAccent = 0xFFFFFFFF.toInt(), lightBars = true
        )
    }

    fun logEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("log_enabled", true)

    fun setLogEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("log_enabled", on).apply()
    }

    /** Pasta escolhida pelo usuário (permissão persistente do seletor de pastas). */
    fun treeUri(ctx: Context): Uri? = prefs(ctx).getString("tree_uri", null)?.let { Uri.parse(it) }

    /** Pasta "Zelda ROM Tool/Logs" criada dentro da pasta escolhida. */
    fun logsDirUri(ctx: Context): Uri? = prefs(ctx).getString("logs_dir_uri", null)?.let { Uri.parse(it) }

    fun setFolder(ctx: Context, tree: Uri, logsDir: Uri) {
        prefs(ctx).edit()
            .putString("tree_uri", tree.toString())
            .putString("logs_dir_uri", logsDir.toString())
            .apply()
    }

    fun clearFolder(ctx: Context) {
        prefs(ctx).edit().remove("tree_uri").remove("logs_dir_uri").apply()
    }
}

/** Criação das pastas do app dentro da pasta que o usuário autorizou. */
object AppFolders {

    const val ROOT_NAME = "Zelda ROM Tool"
    const val LOGS_NAME = "Logs"

    /** Cria (se preciso) "Zelda ROM Tool/Logs" dentro da pasta escolhida. */
    fun ensureLogsDir(ctx: Context, tree: Uri): DocumentFile? {
        val picked = DocumentFile.fromTreeUri(ctx, tree) ?: return null
        val root = child(picked, ROOT_NAME) ?: return null
        return child(root, LOGS_NAME)
    }

    private fun child(parent: DocumentFile, name: String): DocumentFile? {
        val existing = parent.findFile(name)
        if (existing != null && existing.isDirectory) return existing
        return parent.createDirectory(name)
    }

    fun folderName(ctx: Context, tree: Uri): String =
        DocumentFile.fromTreeUri(ctx, tree)?.name ?: "pasta escolhida"

    /** Pasta de apoio, sem precisar de permissão (Android/data/com.zeldaromtool/files/logs). */
    fun privateLogsDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "logs").apply { mkdirs() }
}
