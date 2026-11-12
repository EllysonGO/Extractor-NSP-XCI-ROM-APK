package com.zeldaromtool

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Lê NSP (PFS0) e XCI (HFS0) e copia os arquivos internos para uma pasta escolhida pelo usuário. */
object RomExtractor {

    data class Entry(val name: String, val offset: Long, val size: Long)

    class Cancelled : Exception()

    private const val BUF = 1 shl 20

    private fun readAt(ch: FileChannel, pos: Long, len: Int): ByteBuffer {
        val bb = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        var p = pos
        while (bb.hasRemaining()) {
            val n = ch.read(bb, p)
            if (n < 0) throw IllegalStateException("Arquivo menor do que o esperado")
            p += n
        }
        bb.flip()
        return bb
    }

    /** PFS0 (NSP) ou HFS0 (partições do XCI). Devolve offsets absolutos. */
    private fun parseFs(ch: FileChannel, base: Long, hfs: Boolean): List<Entry> {
        val tag = if (hfs) "HFS0" else "PFS0"
        val head = readAt(ch, base, 16)
        val magic = ByteArray(4)
        head.get(magic)
        if (String(magic, Charsets.US_ASCII) != tag) throw IllegalStateException("Cabeçalho $tag inválido")
        val count = head.int
        val strSize = head.int
        if (count < 0 || count > 100_000 || strSize < 0 || strSize > 16 * 1024 * 1024) {
            throw IllegalStateException("Cabeçalho $tag corrompido")
        }
        val entSize = if (hfs) 0x40 else 0x18
        val table = readAt(ch, base + 16, count * entSize + strSize)
        val strings = ByteArray(strSize)
        table.position(count * entSize)
        table.get(strings)
        val dataStart = base + 16 + count * entSize + strSize

        val out = ArrayList<Entry>()
        for (i in 0 until count) {
            table.position(i * entSize)
            val off = table.long
            val size = table.long
            val nameOff = table.int
            if (off < 0 || size < 0 || nameOff < 0 || nameOff > strings.size) {
                throw IllegalStateException("Entrada $i corrompida")
            }
            var end = nameOff
            while (end < strings.size && strings[end] != 0.toByte()) end++
            out.add(Entry(String(strings, nameOff, end - nameOff, Charsets.UTF_8), dataStart + off, size))
        }
        return out
    }

    private fun listIn(ch: FileChannel): List<Entry> {
        val m0 = ByteArray(4).also { readAt(ch, 0, 4).get(it) }
        if (String(m0, Charsets.US_ASCII) == "PFS0") return parseFs(ch, 0, false)

        val m1 = ByteArray(4).also { readAt(ch, 0x100, 4).get(it) }
        if (String(m1, Charsets.US_ASCII) != "HEAD") {
            throw IllegalStateException("Não parece NSP nem XCI")
        }
        val rootOffset = readAt(ch, 0x130, 8).long
        val result = ArrayList<Entry>()
        for (part in parseFs(ch, rootOffset, true)) {
            // "update" e "logo" são ignoradas; o jogo está em "secure" (e às vezes "normal")
            if (part.name == "secure" || part.name == "normal") {
                result.addAll(parseFs(ch, part.offset, true))
            }
        }
        if (result.isEmpty()) throw IllegalStateException("Nenhum arquivo do jogo encontrado no XCI")
        return result
    }

    fun list(ctx: Context, uri: Uri): List<Entry> {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Não consegui abrir o arquivo")
        return pfd.use { listIn(FileInputStream(it.fileDescriptor).channel) }
    }

    private fun safe(name: String): String =
        name.replace('/', '_').replace('\\', '_').ifBlank { "arquivo" }

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /**
     * Copia os arquivos de dentro da ROM para uma subpasta [folderName] criada dentro de [treeUri].
     * Chame fora da thread principal. Lança [Cancelled] se [isCancelled] virar true.
     */
    fun extract(
        ctx: Context,
        romUri: Uri,
        entries: List<Entry>,
        treeUri: Uri,
        folderName: String,
        isCancelled: () -> Boolean,
        onProgress: (done: Long, total: Long, file: String, index: Int, count: Int) -> Unit
    ) {
        val cr = ctx.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val dirUri = DocumentsContract.createDocument(
            cr, parent, DocumentsContract.Document.MIME_TYPE_DIR, safe(folderName)
        ) ?: throw IllegalStateException("Não consegui criar a pasta no destino")

        val total = entries.sumOf { it.size }
        var done = 0L
        var last = 0L

        val pfd = cr.openFileDescriptor(romUri, "r")
            ?: throw IllegalStateException("Não consegui abrir a ROM")
        pfd.use {
            val ch = FileInputStream(it.fileDescriptor).channel
            val buf = ByteBuffer.allocate(BUF)

            for ((i, e) in entries.withIndex()) {
                if (isCancelled()) throw Cancelled()
                val doc = DocumentsContract.createDocument(cr, dirUri, mimeFor(e.name), safe(e.name))
                    ?: throw IllegalStateException("Não consegui criar ${e.name}")
                try {
                    val os = cr.openOutputStream(doc, "w")
                        ?: throw IllegalStateException("Não consegui gravar ${e.name}")
                    os.use { out ->
                        var pos = e.offset
                        var left = e.size
                        while (left > 0) {
                            if (isCancelled()) throw Cancelled()
                            buf.clear()
                            if (left < BUF) buf.limit(left.toInt())
                            val n = ch.read(buf, pos)
                            if (n <= 0) throw IllegalStateException("Fim inesperado em ${e.name}")
                            out.write(buf.array(), 0, n)
                            pos += n
                            left -= n
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - last >= 250) {
                                last = now
                                onProgress(done, total, e.name, i + 1, entries.size)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    // remove o arquivo parcial
                    try { DocumentsContract.deleteDocument(cr, doc) } catch (ignored: Exception) {}
                    throw t
                }
                onProgress(done, total, e.name, i + 1, entries.size)
            }
        }
    }
}
