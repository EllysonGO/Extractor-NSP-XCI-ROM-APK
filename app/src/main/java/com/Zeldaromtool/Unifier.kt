package com.zeldaromtool

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.channels.FileChannel

/** Junta o jogo base (NSP/XCI) com a atualização (NCA, ou NSP/XCI com o NCA dentro). */
object Unifier {

    private fun findProgram(
        ch: FileChannel,
        entries: List<RomExtractor.Entry>,
        keys: Keys,
        tickets: Map<String, ByteArray>
    ): Nca? {
        for (e in entries) {
            if (!e.name.endsWith(".nca", true)) continue
            if (Nca.peekType(ch, e.offset, keys) == 0) return Nca(ch, e.offset, keys, tickets)
        }
        return null
    }

    /** Se o arquivo é NSP/XCI, lista o conteúdo; senão trata como um NCA solto. */
    private fun listUpdate(ctx: Context, uri: Uri, ch: FileChannel): List<RomExtractor.Entry> {
        val size = ch.size()
        if (size < 0x400) throw IllegalStateException("Arquivo de atualização pequeno demais.")
        val m0 = String(readBytes(ch, 0, 4), Charsets.US_ASCII)
        val m1 = String(readBytes(ch, 0x100, 4), Charsets.US_ASCII)
        return if (m0 == "PFS0" || m1 == "HEAD") {
            RomExtractor.list(ctx, uri)
        } else {
            listOf(RomExtractor.Entry("update.nca", 0L, size))
        }
    }

    fun unify(
        ctx: Context,
        baseUri: Uri,
        baseEntries: List<RomExtractor.Entry>,
        updateUri: Uri,
        outTreeUri: Uri,
        outFolderName: String,
        isCancelled: () -> Boolean,
        onProgress: (done: Long, total: Long, file: String) -> Unit
    ) {
        if (baseEntries.any { it.name.endsWith(".ncz", true) }) {
            throw IllegalStateException("NSZ/XCZ (compactado) não é suportado. Use um NSP ou XCI normal.")
        }
        val keys = GameExtractor.loadKeys(ctx)

        val basePfd = ctx.contentResolver.openFileDescriptor(baseUri, "r")
            ?: throw IllegalStateException("Não consegui abrir o jogo base")
        basePfd.use { bp ->
            val updPfd = ctx.contentResolver.openFileDescriptor(updateUri, "r")
                ?: throw IllegalStateException("Não consegui abrir a atualização")
            updPfd.use { up ->
                val bch = FileInputStream(bp.fileDescriptor).channel
                val uch = FileInputStream(up.fileDescriptor).channel

                val updEntries = listUpdate(ctx, updateUri, uch)
                if (updEntries.any { it.name.endsWith(".ncz", true) }) {
                    throw IllegalStateException("Atualização compactada (NCZ) não é suportada.")
                }

                val tickets = HashMap<String, ByteArray>()
                tickets.putAll(GameExtractor.collectTickets(bch, baseEntries))
                tickets.putAll(GameExtractor.collectTickets(uch, updEntries))

                // 1. NCA Program do jogo base e NCA Program da atualização
                val baseNca = findProgram(bch, baseEntries, keys, tickets)
                    ?: throw IllegalStateException("Não encontrei o NCA do jogo (Program) no jogo base.")
                val updNca = findProgram(uch, updEntries, keys, tickets)
                    ?: throw IllegalStateException("Não encontrei o NCA de atualização (Program) neste arquivo.")

                // 2. A atualização precisa ser deste jogo (Title ID da atualização = base + 0x800)
                val diff = baseNca.titleId xor updNca.titleId
                if (diff != 0L && diff != 0x800L) {
                    throw IllegalStateException("Esta atualização não pertence a este jogo base (Title ID diferente).")
                }

                // 3. Liga o RomFS de patch ao RomFS do jogo base
                val baseRomfs = baseNca.sections.firstOrNull { it.fsType == 0 && it.dataOff >= 0 }
                for (sec in updNca.sections) {
                    if (sec.fsType == 0 && sec.encType == 4) {
                        if (baseRomfs == null) throw IllegalStateException("O jogo base não tem RomFS.")
                        updNca.attachBase(sec, baseNca, baseRomfs)
                    }
                }

                // 4. ExeFS e RomFS: o que a atualização tem vale; o resto vem do jogo base
                val items = ArrayList<GameExtractor.Item>()
                items.addAll(GameExtractor.listItems(updNca, true, true))
                if (items.none { it.path.startsWith("exefs/") }) {
                    items.addAll(GameExtractor.listItems(baseNca, true, false).filter { it.path.startsWith("exefs/") })
                }
                if (items.none { it.path.startsWith("romfs/") }) {
                    items.addAll(GameExtractor.listItems(baseNca, false, true))
                }
                if (items.isEmpty()) throw IllegalStateException("Nenhum arquivo de ExeFS/RomFS encontrado.")

                GameExtractor.writeItems(ctx, outTreeUri, outFolderName, items, isCancelled, onProgress)
            }
        }
    }
}
