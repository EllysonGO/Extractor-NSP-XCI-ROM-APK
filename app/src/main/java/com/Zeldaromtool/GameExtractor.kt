package com.zeldaromtool

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private fun hexToBytes(s: String): ByteArray {
    if (s.length % 2 != 0) throw IllegalStateException("Chave com tamanho inválido")
    return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

private fun bytesToHex(b: ByteArray): String =
    b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

internal fun readBytes(ch: FileChannel, pos: Long, len: Int): ByteArray {
    val bb = ByteBuffer.allocate(len)
    var p = pos
    while (bb.hasRemaining()) {
        val n = ch.read(bb, p)
        if (n < 0) throw IllegalStateException("Arquivo menor do que o esperado")
        p += n
    }
    return bb.array()
}

private fun ecb(mode: Int, key: ByteArray, data: ByteArray): ByteArray {
    val c = Cipher.getInstance("AES/ECB/NoPadding")
    c.init(mode, SecretKeySpec(key, "AES"))
    return c.doFinal(data)
}

/** AES-XTS do cabeçalho do NCA (a Nintendo usa o número do setor em big-endian). */
private fun xtsDecrypt(key1: ByteArray, key2: ByteArray, data: ByteArray, firstSector: Long): ByteArray {
    val sectorSize = 0x200
    val tweakCipher = Cipher.getInstance("AES/ECB/NoPadding")
    tweakCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key2, "AES"))
    val dataCipher = Cipher.getInstance("AES/ECB/NoPadding")
    dataCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key1, "AES"))

    val out = ByteArray(data.size)
    val blk = ByteArray(16)
    var sector = firstSector
    var off = 0
    while (off < data.size) {
        val t = ByteArray(16)
        var s = sector
        for (i in 15 downTo 8) {
            t[i] = (s and 0xFF).toByte()
            s = s shr 8
        }
        val tw = tweakCipher.doFinal(t)
        for (b in 0 until sectorSize / 16) {
            val p = off + b * 16
            for (i in 0 until 16) blk[i] = (data[p + i].toInt() xor tw[i].toInt()).toByte()
            val d = dataCipher.doFinal(blk)
            for (i in 0 until 16) out[p + i] = (d[i].toInt() xor tw[i].toInt()).toByte()
            // tweak = tweak * alpha (GF(2^128), little-endian)
            var carry = 0
            for (i in 0 until 16) {
                val v = tw[i].toInt() and 0xFF
                tw[i] = (((v shl 1) and 0xFF) or carry).toByte()
                carry = v ushr 7
            }
            if (carry != 0) tw[0] = (tw[0].toInt() xor 0x87).toByte()
        }
        off += sectorSize
        sector++
    }
    return out
}

class Keys(val prod: Map<String, String>, val title: Map<String, String>) {
    fun need(name: String): ByteArray {
        val v = prod[name] ?: throw IllegalStateException("Chave ausente no prod.keys: $name")
        return hexToBytes(v)
    }
}

class Section(
    val start: Long,        // início da seção dentro do NCA
    val encType: Int,       // 1 = sem criptografia, 3 = AES-CTR, 4 = AES-CTR-EX (patch)
    val fsType: Int,        // 0 = RomFS, 1 = PartitionFS (ExeFS)
    val hashType: Int,
    val ctrUpper: ByteArray,
    val dataOff: Long,      // onde os dados começam dentro da seção (-1 = tipo não suportado)
    val dataSize: Long,
    val relOff: Long = 0,   // tabela de relocação BKTR (só tipo 4)
    val relSize: Long = 0,
    val subOff: Long = 0,   // tabela de subseções BKTR (só tipo 4)
    val subSize: Long = 0
) {
    /** Preenchido pela unificação: leitor que mistura o RomFS do jogo base com o patch. */
    var bktr: Bktr? = null
}

class Nca(private val ch: FileChannel, private val base: Long, keys: Keys, tickets: Map<String, ByteArray>) {

    val contentType: Int
    val titleId: Long
    val sections = ArrayList<Section>()
    private val ctrKey: ByteArray

    init {
        val hdr = readHeader(ch, base, keys)
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        contentType = hdr[0x205].toInt() and 0xFF
        titleId = bb.getLong(0x210)

        val gen = maxOf(hdr[0x206].toInt() and 0xFF, hdr[0x220].toInt() and 0xFF)
        val mk = if (gen > 0) gen - 1 else 0
        val idx = "%02x".format(mk)

        val rights = hdr.copyOfRange(0x230, 0x240)
        if (rights.any { it.toInt() != 0 }) {
            // Criptografia com title key (vem do .tik do NSP ou do title.keys)
            val rid = bytesToHex(rights)
            val enc = tickets[rid] ?: keys.title[rid]?.let { hexToBytes(it) }
                ?: throw IllegalStateException(
                    "Sem title key para este jogo (rights ID $rid). Falta o .tik no NSP ou o title.keys."
                )
            ctrKey = ecb(Cipher.DECRYPT_MODE, keys.need("titlekek_$idx"), enc)
        } else {
            val kind = when (hdr[0x207].toInt() and 0xFF) {
                0 -> "application"
                1 -> "ocean"
                2 -> "system"
                else -> throw IllegalStateException("Tipo de key area desconhecido")
            }
            val area = ecb(
                Cipher.DECRYPT_MODE,
                keys.need("key_area_key_${kind}_$idx"),
                hdr.copyOfRange(0x300, 0x340)
            )
            ctrKey = area.copyOfRange(0x20, 0x30)
        }

        for (i in 0 until 4) {
            val start = (bb.getInt(0x240 + i * 0x10).toLong() and 0xFFFFFFFFL) * 0x200
            val end = (bb.getInt(0x244 + i * 0x10).toLong() and 0xFFFFFFFFL) * 0x200
            if (end <= start) continue
            val f = 0x400 + i * 0x200
            val fsType = hdr[f + 2].toInt() and 0xFF
            val hashType = hdr[f + 3].toInt() and 0xFF
            val encType = hdr[f + 4].toInt() and 0xFF
            val upper = ByteArray(8) { hdr[f + 0x140 + 7 - it] }
            var dOff = -1L
            var dSize = 0L
            if (fsType == 1 && hashType == 2) {
                dOff = bb.getLong(f + 0x40)
                dSize = bb.getLong(f + 0x48)
            } else if (fsType == 0 && hashType == 3) {
                dOff = bb.getLong(f + 0x90)
                dSize = bb.getLong(f + 0x98)
            }
            var rOff = 0L
            var rSize = 0L
            var sOff = 0L
            var sSize = 0L
            if (encType == 4) {
                rOff = bb.getLong(f + 0x100)
                rSize = bb.getLong(f + 0x108)
                sOff = bb.getLong(f + 0x120)
                sSize = bb.getLong(f + 0x128)
            }
            sections.add(Section(start, encType, fsType, hashType, upper, dOff, dSize, rOff, rSize, sOff, sSize))
        }
    }

    companion object {
        fun readHeader(ch: FileChannel, base: Long, keys: Keys): ByteArray {
            val hk = keys.need("header_key")
            val raw = readBytes(ch, base, 0xC00)
            val hdr = xtsDecrypt(hk.copyOfRange(0, 16), hk.copyOfRange(16, 32), raw, 0)
            val magic = String(hdr, 0x200, 4, Charsets.US_ASCII)
            if (magic != "NCA3") {
                throw IllegalStateException(
                    if (magic == "NCA2" || magic == "NCA0") "NCA antigo ($magic) não suportado"
                    else "Não consegui decifrar o cabeçalho do NCA. O header_key do prod.keys está correto?"
                )
            }
            return hdr
        }

        fun peekType(ch: FileChannel, base: Long, keys: Keys): Int =
            readHeader(ch, base, keys)[0x205].toInt() and 0xFF
    }

    /** Contador AES-CTR. [ctrVal] (só BKTR) substitui os 4 bytes de geração do contador. */
    private fun ctrCipher(upper: ByteArray, ctrVal: Int?, aligned: Long): Cipher {
        val counter = ByteArray(16)
        System.arraycopy(upper, 0, counter, 0, 8)
        if (ctrVal != null) {
            counter[4] = (ctrVal ushr 24).toByte()
            counter[5] = (ctrVal ushr 16).toByte()
            counter[6] = (ctrVal ushr 8).toByte()
            counter[7] = ctrVal.toByte()
        }
        var o = aligned shr 4
        for (i in 15 downTo 8) {
            counter[i] = (o and 0xFF).toByte()
            o = o shr 8
        }
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(ctrKey, "AES"), IvParameterSpec(counter))
        return c
    }

    private fun cipherAt(sec: Section, aligned: Long): Cipher? {
        if (sec.encType == 1) return null
        if (sec.encType == 4) {
            throw IllegalStateException(
                "Esta seção é de atualização (AES-CTR-EX) e precisa do jogo base. Use \"Unificar jogo base + atualização\"."
            )
        }
        if (sec.encType != 3 && sec.encType != 0) {
            throw IllegalStateException("Criptografia de seção não suportada (tipo ${sec.encType}).")
        }
        return ctrCipher(sec.ctrUpper, null, aligned)
    }

    /** Lê [len] bytes da seção com AES-CTR puro (e [ctrVal] opcional). Usado pelo leitor BKTR. */
    fun readCtr(sec: Section, pos: Long, len: Int, ctrVal: Int?): ByteArray {
        val abs = sec.start + pos
        val aligned = abs and 15L.inv()
        val skip = (abs - aligned).toInt()
        val raw = readBytes(ch, base + aligned, skip + len)
        val dec = ctrCipher(sec.ctrUpper, ctrVal, aligned).update(raw) ?: ByteArray(0)
        if (dec.size != raw.size) throw IllegalStateException("Falha ao decifrar")
        return dec.copyOfRange(skip, skip + len)
    }

    /** Liga a seção BKTR [sec] deste NCA (atualização) ao RomFS [baseSec] do jogo base [baseNca]. */
    fun attachBase(sec: Section, baseNca: Nca, baseSec: Section) {
        sec.bktr = Bktr(this, sec, baseNca, baseSec)
    }

    /** Lê [len] bytes decifrados, a partir de [pos] (relativo ao início da seção). */
    fun read(sec: Section, pos: Long, len: Int): ByteArray {
        sec.bktr?.let { return it.read(pos, len) }
        val abs = sec.start + pos
        val aligned = abs and 15L.inv()
        val skip = (abs - aligned).toInt()
        val raw = readBytes(ch, base + aligned, skip + len)
        val c = cipherAt(sec, aligned)
        val dec = if (c != null) (c.update(raw) ?: ByteArray(0)) else raw
        if (dec.size != raw.size) throw IllegalStateException("Falha ao decifrar")
        return dec.copyOfRange(skip, skip + len)
    }

    /**
     * Decifra e grava [len] bytes a partir de [pos] direto no [out].
     * Leitura/decifragem e gravação rodam em paralelo (pipeline), com blocos do tamanho
     * escolhido pelo modo turbo (DeviceTuning.current).
     */
    fun copyRange(
        sec: Section, pos: Long, len: Long, out: OutputStream,
        isCancelled: () -> Boolean, onBytes: (Int) -> Unit
    ) {
        val tuning = DeviceTuning.current
        val bk = sec.bktr

        val fill: (ByteArray) -> Int
        if (bk != null) {
            // Caminho da atualização (BKTR)
            var p = pos
            var left = len
            val maxChunk = minOf(tuning.bufferSize, 4 shl 20)
            fill = { buf ->
                if (left <= 0L) {
                    -1
                } else {
                    val n = minOf(maxChunk.toLong(), left).toInt()
                    System.arraycopy(bk.read(p, n), 0, buf, 0, n)
                    p += n
                    left -= n
                    n
                }
            }
        } else {
            // Caminho normal (jogo base)
            val abs = sec.start + pos
            var cur = abs and 15L.inv()
            var toSkip = (abs - cur).toInt()
            val c = cipherAt(sec, cur)
            var remaining = len
            val scratch = if (c != null) ByteArray(tuning.bufferSize) else null
            fill = { buf ->
                if (remaining <= 0L) {
                    -1
                } else {
                    val want = minOf(buf.size.toLong(), remaining + toSkip).toInt()
                    val target = scratch ?: buf
                    val bb = ByteBuffer.wrap(target, 0, want)
                    var rp = base + cur
                    while (bb.hasRemaining()) {
                        val r = ch.read(bb, rp)
                        if (r < 0) throw IllegalStateException("Arquivo menor do que o esperado")
                        rp += r
                    }
                    if (c != null) {
                        val dn = c.update(target, 0, want, buf, 0)
                        if (dn != want) throw IllegalStateException("Falha ao decifrar")
                    }
                    val n = want - toSkip
                    if (toSkip > 0) System.arraycopy(buf, toSkip, buf, 0, n)
                    remaining -= n
                    cur += want
                    toSkip = 0
                    n
                }
            }
        }

        if (!copyPipelined(fill, out, tuning, isCancelled, onBytes)) throw RomExtractor.Cancelled()
    }

    /** Lista os arquivos de um ExeFS (PFS0). Offsets relativos à seção. */
    fun listPfs0(sec: Section): List<RomExtractor.Entry> {
        val h = ByteBuffer.wrap(read(sec, sec.dataOff, 16)).order(ByteOrder.LITTLE_ENDIAN)
        if (h.getInt(0) != 0x30534650) {
            throw IllegalStateException("Falha ao decifrar o ExeFS. Confira se o prod.keys tem as chaves da versão de firmware deste jogo.")
        }
        val count = h.getInt(4)
        val strSize = h.getInt(8)
        if (count < 0 || count > 10_000 || strSize < 0 || strSize > (1 shl 20)) {
            throw IllegalStateException("ExeFS corrompido")
        }
        val tableLen = count * 0x18 + strSize
        val t = read(sec, sec.dataOff + 16, tableLen)
        val tb = ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN)
        val dataStart = sec.dataOff + 16 + tableLen
        val out = ArrayList<RomExtractor.Entry>()
        for (i in 0 until count) {
            val off = tb.getLong(i * 0x18)
            val size = tb.getLong(i * 0x18 + 8)
            val nameOff = count * 0x18 + tb.getInt(i * 0x18 + 16)
            var end = nameOff
            while (end < t.size && t[end] != 0.toByte()) end++
            out.add(RomExtractor.Entry(String(t, nameOff, end - nameOff, Charsets.UTF_8), dataStart + off, size))
        }
        return out
    }

    /** Lista todos os arquivos do RomFS (com caminho). Offsets relativos à seção. */
    fun listRomFs(sec: Section): List<RomExtractor.Entry> {
        val h = ByteBuffer.wrap(read(sec, sec.dataOff, 0x50)).order(ByteOrder.LITTLE_ENDIAN)
        if (h.getLong(0) != 0x50L) {
            throw IllegalStateException("Falha ao decifrar o RomFS. Confira se o prod.keys tem as chaves da versão de firmware deste jogo.")
        }
        val dirOff = h.getLong(0x18)
        val dirSize = h.getLong(0x20)
        val fileOff = h.getLong(0x38)
        val fileSize = h.getLong(0x40)
        val dataOff = h.getLong(0x48)
        val max = 64L * 1024 * 1024
        if (dirSize < 0 || dirSize > max || fileSize < 0 || fileSize > max) {
            throw IllegalStateException("RomFS corrompido")
        }
        val dir = read(sec, sec.dataOff + dirOff, dirSize.toInt())
        val files = read(sec, sec.dataOff + fileOff, fileSize.toInt())
        val d = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
        val f = ByteBuffer.wrap(files).order(ByteOrder.LITTLE_ENDIAN)

        val result = ArrayList<RomExtractor.Entry>()
        var guard = 0

        fun walk(dirOffset: Int, prefix: String) {
            val nameLen = d.getInt(dirOffset + 20)
            val myPath = if (dirOffset == 0) "" else
                prefix + String(dir, dirOffset + 24, nameLen, Charsets.UTF_8) + "/"
            var fo = d.getInt(dirOffset + 12)
            while (fo != -1) {
                if (++guard > 2_000_000) throw IllegalStateException("RomFS corrompido")
                val nl = f.getInt(fo + 28)
                val name = String(files, fo + 32, nl, Charsets.UTF_8)
                result.add(
                    RomExtractor.Entry(myPath + name, sec.dataOff + dataOff + f.getLong(fo + 8), f.getLong(fo + 16))
                )
                fo = f.getInt(fo + 4)
            }
            var sub = d.getInt(dirOffset + 8)
            while (sub != -1) {
                if (++guard > 2_000_000) throw IllegalStateException("RomFS corrompido")
                walk(sub, myPath)
                sub = d.getInt(sub + 4)
            }
        }
        walk(0, "")
        return result
    }
}

object GameExtractor {

    internal class Item(val path: String, val sec: Section, val nca: Nca, val pos: Long, val size: Long)
    private data class ChildDocument(val id: String, val mimeType: String)

    /** Encontra um arquivo ou diretório filho dentro da pasta SAF. */
    private fun findChild(
        ctx: Context,
        treeUri: Uri,
        parentId: String,
        name: String
    ): ChildDocument? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        ctx.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol) == name) {
                    return ChildDocument(cursor.getString(idCol), cursor.getString(mimeCol))
                }
            }
        }
        return null
    }

    /** Cria ou reutiliza uma pasta dentro do destino escolhido pelo usuário. */
    private fun ensureDirectory(
        ctx: Context,
        treeUri: Uri,
        parentId: String,
        name: String
    ): Uri {
        val existing = findChild(ctx, treeUri, parentId, name)
        if (existing != null && existing.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.id)
        }
        if (existing != null) {
            val oldUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.id)
            DocumentsContract.deleteDocument(ctx.contentResolver, oldUri)
        }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        return DocumentsContract.createDocument(
            ctx.contentResolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name
        ) ?: throw IllegalStateException("Não consegui criar a pasta: $name")
    }

    /** Cria ou substitui um arquivo no destino SAF. */
    private fun createOutputFile(
        ctx: Context,
        treeUri: Uri,
        parentId: String,
        name: String
    ): Uri {
        val existing = findChild(ctx, treeUri, parentId, name)
        if (existing != null) {
            val oldUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.id)
            DocumentsContract.deleteDocument(ctx.contentResolver, oldUri)
        }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        return DocumentsContract.createDocument(
            ctx.contentResolver,
            parentUri,
            "application/octet-stream",
            name
        ) ?: throw IllegalStateException("Não consegui criar o arquivo: $name")
    }

    /** Título (title key) cifrado de um ticket comum: devolve rightsId (hex) -> chave cifrada. */
    internal fun parseTicket(b: ByteArray): Pair<String, ByteArray>? {
        if (b.size < 4) return null
        val sigType = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
        val skip = when (sigType) {
            0x10000, 0x10003 -> 0x240
            0x10001, 0x10004 -> 0x140
            0x10002, 0x10005 -> 0x80
            else -> return null
        }
        if (b.size < skip + 0x170) return null
        if (b[skip + 0x141].toInt() != 0) return null // ticket personalizado (RSA): não dá para usar
        return bytesToHex(b.copyOfRange(skip + 0x160, skip + 0x170)) to b.copyOfRange(skip + 0x40, skip + 0x50)
    }

    private fun safePath(path: String): String {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == ".." || it == "." }) {
            throw IllegalStateException("Caminho inválido dentro da ROM: $path")
        }
        return parts.joinToString("/")
    }

    /** Carrega prod.keys e title.keys importados pelo usuário. */
    internal fun loadKeys(ctx: Context): Keys {
        val prodFile = KeysManager.prodFile(ctx)
        if (!prodFile.exists()) throw IllegalStateException("Importe o prod.keys primeiro.")
        val titleFile = KeysManager.titleFile(ctx)
        return Keys(
            KeysManager.parse(prodFile.readText()),
            if (titleFile.exists()) KeysManager.parse(titleFile.readText()) else emptyMap()
        )
    }

    /** Title keys comuns dos arquivos .tik de um NSP. */
    internal fun collectTickets(ch: FileChannel, entries: List<RomExtractor.Entry>): HashMap<String, ByteArray> {
        val tickets = HashMap<String, ByteArray>()
        for (e in entries) {
            if (e.name.endsWith(".tik", true) && e.size in 0x200L..0x1000L) {
                parseTicket(readBytes(ch, e.offset, e.size.toInt()))?.let { tickets[it.first] = it.second }
            }
        }
        return tickets
    }

    /** Lista os arquivos de ExeFS e/ou RomFS de um NCA Program. */
    internal fun listItems(nca: Nca, wantExefs: Boolean, wantRomfs: Boolean): List<Item> {
        val items = ArrayList<Item>()
        var pfsCount = 0
        for (sec in nca.sections) {
            if (sec.dataOff < 0) {
                throw IllegalStateException(
                    "Tipo de seção não suportado (fs=${sec.fsType}, hash=${sec.hashType}, cripto=${sec.encType})."
                )
            }
            if (sec.fsType == 1) {
                val dir = if (pfsCount++ == 0) "exefs" else "logo"
                if (wantExefs) {
                    for (e in nca.listPfs0(sec)) items.add(Item("$dir/${e.name}", sec, nca, e.offset, e.size))
                }
            } else if (wantRomfs) {
                for (e in nca.listRomFs(sec)) items.add(Item("romfs/${e.name}", sec, nca, e.offset, e.size))
            }
        }
        return items
    }

    /** Se o app tem "Acesso a todos os arquivos" e a pasta é do armazenamento interno, devolve o caminho real. */
    private fun directRoot(treeUri: Uri): File? {
        if (Build.VERSION.SDK_INT < 30 || !Environment.isExternalStorageManager()) return null
        return try {
            val id = DocumentsContract.getTreeDocumentId(treeUri)
            if (!id.startsWith("primary:")) null
            else File(Environment.getExternalStorageDirectory(), id.removePrefix("primary:"))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Pede ao Android para reler a pasta de saída. Com o .nomedia já lá, a Galeria
     * esconde o que tinha indexado antes. Só funciona para o armazenamento interno.
     */
    private fun rescanOutput(ctx: Context, treeUri: Uri, folderName: String) {
        try {
            val id = DocumentsContract.getTreeDocumentId(treeUri)
            if (!id.startsWith("primary:")) return
            val base = File(Environment.getExternalStorageDirectory(), id.removePrefix("primary:"))
            val folder = File(base, folderName)
            MediaScannerConnection.scanFile(
                ctx,
                arrayOf(folder.absolutePath, File(folder, ".nomedia").absolutePath),
                null,
                null
            )
        } catch (_: Exception) {
        }
    }

    /** Decifra e grava os [items] na pasta [outFolderName], dentro do destino escolhido. */
    internal fun writeItems(
        ctx: Context,
        outTreeUri: Uri,
        outFolderName: String,
        items: List<Item>,
        isCancelled: () -> Boolean,
        onProgress: (done: Long, total: Long, file: String) -> Unit
    ) {
        val total = items.sumOf { it.size }

        // Caminho direto (rápido) quando possível; senão, SAF com cache de pastas.
        val direct = directRoot(outTreeUri)?.let { File(it, outFolderName) }?.takeIf { it.isDirectory || it.mkdirs() }
        val sink: Sink = if (direct != null) DirectSink(direct) else SafSink(ctx, outTreeUri, outFolderName)

        // Mantém os arquivos extraídos fora da Galeria: .nomedia na raiz e em cada pasta
        // de primeiro nível (exefs, romfs, logo), ANTES de gravar qualquer arquivo.
        sink.markNoMedia("")
        items.map { safePath(it.path) }
            .filter { it.contains('/') }
            .map { it.substringBefore('/') }
            .distinct()
            .forEach { sink.markNoMedia(it) }
        rescanOutput(ctx, outTreeUri, outFolderName)

        // Escolhe o perfil de desempenho (RAM + velocidade real da pasta de destino)
        val speed = DeviceTuning.measureSpeedMBs(
            openOut = { sink.open(".speedtest") },
            openIn = { sink.openRead(".speedtest") },
            cleanup = { sink.discard(".speedtest") },
            testMb = 128
        )
        DeviceTuning.current = DeviceTuning.choose(ctx, speed)
        onProgress(
            0, total,
            if (DeviceTuning.current.turbo) "Modo turbo ativado" else "Modo padrão"
        )

        var done = 0L
        var last = 0L
        for (item in items) {
            if (isCancelled()) throw RomExtractor.Cancelled()
            val path = safePath(item.path)
            try {
                sink.open(path).use { os ->
                    item.nca.copyRange(item.sec, item.pos, item.size, os, isCancelled) { n ->
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - last >= 500) {
                            last = now
                            onProgress(done, total, item.path)
                        }
                    }
                }
            } catch (t: Throwable) {
                sink.discard(path)
                throw t
            }
            onProgress(done, total, item.path)
        }

        // Releitura final: garante que a Galeria esconda tudo que acabou de ser gravado.
        rescanOutput(ctx, outTreeUri, outFolderName)
    }

    /**
     * Decifra o NCA "Program" do jogo e grava ExeFS e RomFS na pasta escolhida pelo usuário.
     * Chame fora da thread principal.
     */
    fun extract(
        ctx: Context,
        romUri: Uri,
        entries: List<RomExtractor.Entry>,
        outTreeUri: Uri,
        outFolderName: String,
        isCancelled: () -> Boolean,
        onProgress: (done: Long, total: Long, file: String) -> Unit
    ) {
        if (entries.any { it.name.endsWith(".ncz", true) }) {
            throw IllegalStateException("NSZ/XCZ (compactado) não é suportado. Use um NSP ou XCI normal.")
        }
        val keys = loadKeys(ctx)

        val pfd = ctx.contentResolver.openFileDescriptor(romUri, "r")
            ?: throw IllegalStateException("Não consegui abrir a ROM")
        pfd.use { p ->
            val ch = FileInputStream(p.fileDescriptor).channel
            val tickets = collectTickets(ch, entries)

            var program: Nca? = null
            for (e in entries) {
                if (!e.name.endsWith(".nca", true)) continue
                if (Nca.peekType(ch, e.offset, keys) == 0) {
                    program = Nca(ch, e.offset, keys, tickets)
                    break
                }
            }
            val nca = program ?: throw IllegalStateException("Não encontrei o NCA do jogo (Program) nesta ROM.")

            val items = listItems(nca, true, true)
            if (items.isEmpty()) throw IllegalStateException("Nenhum arquivo de ExeFS/RomFS encontrado.")
            writeItems(ctx, outTreeUri, outFolderName, items, isCancelled, onProgress)
        }
    }
}

/** Destino de gravação: cria as pastas necessárias e devolve o fluxo do arquivo. */
private interface Sink {
    fun open(path: String): OutputStream
    fun openRead(path: String): InputStream
    fun discard(path: String)

    /** Cria o arquivo .nomedia na pasta [dir] ("" = raiz da pasta de saída). */
    fun markNoMedia(dir: String)
}

/** Gravação direta por caminho real (rápida). */
private class DirectSink(private val root: File) : Sink {
    override fun open(path: String): OutputStream {
        val f = File(root, path)
        f.parentFile?.mkdirs()
        return FileOutputStream(f)
    }

    override fun openRead(path: String): InputStream = FileInputStream(File(root, path))

    override fun discard(path: String) {
        File(root, path).delete()
    }

    override fun markNoMedia(dir: String) {
        val d = if (dir.isEmpty()) root else File(root, dir)
        d.mkdirs()
        val f = File(d, ".nomedia")
        if (!f.exists()) f.createNewFile()
    }
}

/** Gravação via SAF, listando cada pasta só uma vez (antes era uma consulta por arquivo). */
private class SafSink(ctx: Context, private val tree: Uri, rootName: String) : Sink {
    private val cr = ctx.contentResolver
    private val dirIds = HashMap<String, String>()
    private val kids = HashMap<String, HashMap<String, Pair<String, String>>>()
    private val files = HashMap<String, Uri>()
    private val dirMime = DocumentsContract.Document.MIME_TYPE_DIR

    init {
        dirIds[""] = ensureDir(DocumentsContract.getTreeDocumentId(tree), rootName)
    }

    private fun children(parentId: String): HashMap<String, Pair<String, String>> =
        kids.getOrPut(parentId) {
            val map = HashMap<String, Pair<String, String>>()
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val proj = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            cr.query(uri, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) map[c.getString(1)] = Pair(c.getString(0), c.getString(2))
            }
            map
        }

    private fun ensureDir(parentId: String, name: String): String {
        val map = children(parentId)
        val ex = map[name]
        if (ex != null && ex.second == dirMime) return ex.first
        if (ex != null) {
            DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, ex.first))
            map.remove(name)
        }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val created = DocumentsContract.createDocument(cr, parentUri, dirMime, name)
            ?: throw IllegalStateException("Não consegui criar a pasta: $name")
        val id = DocumentsContract.getDocumentId(created)
        map[name] = Pair(id, dirMime)
        kids[id] = HashMap() // pasta nova: está vazia, não precisa listar
        return id
    }

    override fun open(path: String): OutputStream {
        val parts = path.split('/')
        var parentPath = ""
        var parentId = dirIds[""] ?: throw IllegalStateException("Pasta de destino não criada")
        for (d in parts.dropLast(1)) {
            val p = if (parentPath.isEmpty()) d else "$parentPath/$d"
            val known = dirIds[p]
            parentId = if (known != null) known else ensureDir(parentId, d).also { dirIds[p] = it }
            parentPath = p
        }
        val name = parts.last()
        val map = children(parentId)
        val old = map[name]
        if (old != null) {
            DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, old.first))
            map.remove(name)
        }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val uri = DocumentsContract.createDocument(cr, parentUri, "application/octet-stream", name)
            ?: throw IllegalStateException("Não consegui criar o arquivo: $name")
        map[name] = Pair(DocumentsContract.getDocumentId(uri), "application/octet-stream")
        files[path] = uri
        return cr.openOutputStream(uri, "w")
            ?: throw IllegalStateException("Não consegui abrir o arquivo para gravação: $path")
    }

    override fun openRead(path: String): InputStream {
        val uri = files[path] ?: throw IllegalStateException("Arquivo de teste não encontrado")
        return cr.openInputStream(uri)
            ?: throw IllegalStateException("Não consegui ler o arquivo de teste")
    }

    override fun discard(path: String) {
        val uri = files[path] ?: return
        try {
            DocumentsContract.deleteDocument(cr, uri)
        } catch (_: Exception) {
        }
    }

    private fun displayNameOf(uri: Uri): String? = try {
        cr.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    /** Cria o .nomedia e confere se o Android não trocou o nome (ex.: ".nomedia.bin"). */
    override fun markNoMedia(dir: String) {
        val path = if (dir.isEmpty()) ".nomedia" else "$dir/.nomedia"
        open(path).close()
        val uri = files[path] ?: return
        val real = displayNameOf(uri)
        if (real != null && real != ".nomedia") {
            try {
                DocumentsContract.renameDocument(cr, uri, ".nomedia")
            } catch (_: Exception) {
            }
        }
    }
}
