
package com.zeldaromtool

import android.content.Context
import android.net.Uri
import java.io.File

/** Importa, valida e guarda prod.keys e title.keys no armazenamento privado do app. */
object KeysManager {

    data class Result(val ok: Boolean, val count: Int, val message: String)

    private const val MAX_BYTES = 2 * 1024 * 1024

    private fun dir(ctx: Context) = File(ctx.filesDir, "keys").apply { mkdirs() }
    fun prodFile(ctx: Context) = File(dir(ctx), "prod.keys")
    fun titleFile(ctx: Context) = File(dir(ctx), "title.keys")

    private val hexRegex = Regex("^[0-9a-fA-F]+$")

    /** Lê linhas "nome = valor" (ignora vazias e comentários). */
    fun parse(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            val k = line.substring(0, idx).trim().lowercase()
            val v = line.substring(idx + 1).trim().lowercase()
            map[k] = v
        }
        return map
    }

    private fun readText(ctx: Context, uri: Uri): String? {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size > MAX_BYTES) return null
        return String(bytes, Charsets.UTF_8)
    }

    fun importProd(ctx: Context, uri: Uri): Result {
        val text = readText(ctx, uri)
            ?: return Result(false, 0, "Não consegui ler o arquivo (ou ele é grande demais).")
        val keys = parse(text)
        val header = keys["header_key"]
        if (header == null || header.length != 64 || !hexRegex.matches(header)) {
            return Result(false, 0, "Esse arquivo não parece um prod.keys válido (header_key ausente).")
        }
        if (keys.keys.none { it.startsWith("master_key_") }) {
            return Result(false, 0, "prod.keys sem nenhuma master_key.")
        }
        prodFile(ctx).writeText(text)
        return Result(true, keys.size, "prod.keys importado: ${keys.size} chaves.")
    }

    fun importTitle(ctx: Context, uri: Uri): Result {
        val text = readText(ctx, uri)
            ?: return Result(false, 0, "Não consegui ler o arquivo (ou ele é grande demais).")
        val keys = parse(text)
        val valid = keys.filter { (k, v) ->
            k.length == 32 && hexRegex.matches(k) && v.length == 32 && hexRegex.matches(v)
        }
        if (valid.isEmpty()) {
            return Result(false, 0, "Esse arquivo não parece um title.keys válido.")
        }
        titleFile(ctx).writeText(text)
        return Result(true, valid.size, "title.keys importado: ${valid.size} title keys.")
    }

    fun prodCount(ctx: Context): Int =
        if (prodFile(ctx).exists()) parse(prodFile(ctx).readText()).size else 0

    fun titleCount(ctx: Context): Int =
        if (titleFile(ctx).exists()) parse(titleFile(ctx).readText()).count { (k, v) -> k.length == 32 && v.length == 32 } else 0

    fun hasProd(ctx: Context) = prodCount(ctx) > 0

    fun clear(ctx: Context) {
        prodFile(ctx).delete()
        titleFile(ctx).delete()
    }
}
