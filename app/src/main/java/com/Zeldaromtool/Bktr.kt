package com.zeldaromtool

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Leitor da seção RomFS de um NCA de atualização (BKTR / AES-CTR-EX).
 *
 * O RomFS "virtual" é montado assim: a tabela de relocação diz, para cada trecho, se os bytes
 * vêm do jogo base (já decifrado) ou do próprio patch. Os trechos do patch são decifrados com
 * AES-CTR usando o contador da tabela de subseções.
 */
class Bktr(
    private val patch: Nca,
    private val sec: Section,
    private val baseNca: Nca,
    private val baseSec: Section
) {
    private class Reloc(val virt: Long, val phys: Long, val isPatch: Boolean)
    private class Sub(val offset: Long, val ctrVal: Int)

    private val relocs = ArrayList<Reloc>()
    private val subs = ArrayList<Sub>()

    init {
        loadBuckets(sec.relOff, sec.relSize, 0x14) { b, o ->
            relocs.add(Reloc(b.getLong(o), b.getLong(o + 8), b.getInt(o + 16) != 0))
        }
        loadBuckets(sec.subOff, sec.subSize, 0x10) { b, o ->
            subs.add(Sub(b.getLong(o), b.getInt(o + 12)))
        }
        relocs.sortBy { it.virt }
        subs.sortBy { it.offset }
        if (relocs.isEmpty()) throw IllegalStateException("Tabela de relocação da atualização está vazia.")
    }

    /** Cada tabela tem um cabeçalho de 0x4000 bytes e depois "buckets" de 0x4000 bytes. */
    private fun loadBuckets(
        tableOff: Long,
        tableSize: Long,
        entrySize: Int,
        onEntry: (ByteBuffer, Int) -> Unit
    ) {
        if (tableOff <= 0 || tableSize < 0x4000) {
            throw IllegalStateException("Esta atualização não tem as tabelas BKTR esperadas.")
        }
        val head = ByteBuffer.wrap(patch.readCtr(sec, tableOff, 0x10, null)).order(ByteOrder.LITTLE_ENDIAN)
        val buckets = head.getInt(4)
        if (buckets < 0 || buckets > 4096) throw IllegalStateException("Tabela BKTR corrompida.")
        for (i in 0 until buckets) {
            val b = ByteBuffer.wrap(
                patch.readCtr(sec, tableOff + 0x4000L * (1 + i), 0x4000, null)
            ).order(ByteOrder.LITTLE_ENDIAN)
            val count = b.getInt(4)
            if (count < 0 || 0x10 + count * entrySize > 0x4000) {
                throw IllegalStateException("Tabela BKTR corrompida.")
            }
            for (k in 0 until count) onEntry(b, 0x10 + k * entrySize)
        }
    }

    private fun findReloc(cur: Long): Int {
        var lo = 0
        var hi = relocs.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (relocs[mid].virt <= cur) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    private fun findSub(cur: Long): Int {
        var lo = 0
        var hi = subs.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (subs[mid].offset <= cur) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** Lê dados que estão no próprio patch (offset físico [p] dentro da seção). */
    private fun readPatch(p: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        var done = 0
        while (done < len) {
            val cur = p + done
            val idx = findSub(cur)
            val ctrVal: Int? = if (idx >= 0) subs[idx].ctrVal else null
            val end = if (idx >= 0 && idx + 1 < subs.size) subs[idx + 1].offset else Long.MAX_VALUE
            val n = minOf((len - done).toLong(), end - cur).toInt()
            if (n <= 0) throw IllegalStateException("Tabela de subseções inconsistente.")
            val chunk = patch.readCtr(sec, cur, n, ctrVal)
            System.arraycopy(chunk, 0, out, done, n)
            done += n
        }
        return out
    }

    /** Lê [len] bytes do RomFS já atualizado, a partir de [pos] (relativo ao início da seção). */
    fun read(pos: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        var done = 0
        while (done < len) {
            val cur = pos + done
            val idx = findReloc(cur)
            if (idx < 0) throw IllegalStateException("Posição fora da tabela de relocação.")
            val r = relocs[idx]
            val segEnd = if (idx + 1 < relocs.size) relocs[idx + 1].virt else Long.MAX_VALUE
            val n = minOf((len - done).toLong(), segEnd - cur).toInt()
            if (n <= 0) throw IllegalStateException("Tabela de relocação inconsistente.")
            val phys = r.phys + (cur - r.virt)
            val chunk = if (r.isPatch) readPatch(phys, n) else baseNca.read(baseSec, phys, n)
            System.arraycopy(chunk, 0, out, done, n)
            done += n
        }
        return out
    }
}
