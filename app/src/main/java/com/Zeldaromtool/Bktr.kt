package com.zeldaromtool

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Leitor da seção RomFS de um NCA de atualização (BKTR / AES-CTR-EX).
 *
 * O RomFS "virtual" é montado assim: a tabela de relocação diz, para cada trecho, se os bytes
 * vêm do jogo base (já decifrado) ou do próprio patch. Os trechos do patch são decifrados com
 * AES-CTR usando o contador da tabela de subseções.
 *
 * Otimizações: trechos vizinhos que continuam um do outro são mesclados (menos pedaços para
 * ler), a busca usa vetores simples e os bytes são copiados direto para o destino. Os trechos
 * pequenos do jogo base passam por um cache de leitura (um bloco por vez).
 */
class Bktr(
    private val patch: Nca,
    private val sec: Section,
    private val baseNca: Nca,
    private val baseSec: Section
) {
    private class Reloc(val virt: Long, val phys: Long, val isPatch: Boolean)
    private class Sub(val offset: Long, val ctrVal: Int)

    // Tabela de relocação (já mesclada)
    private var relVirt = LongArray(0)
    private var relPhys = LongArray(0)
    private var relPatch = BooleanArray(0)
    private var relCount = 0

    // Tabela de subseções (já mesclada)
    private var subOff = LongArray(0)
    private var subCtr = IntArray(0)
    private var subCount = 0

    // Cache de leitura do jogo base
    private var cacheStart = -1L
    private var cacheData: ByteArray? = null

    init {
        val rawRel = ArrayList<Reloc>()
        val rawSub = ArrayList<Sub>()
        loadBuckets(sec.relOff, sec.relSize, 0x14) { b, o ->
            rawRel.add(Reloc(b.getLong(o), b.getLong(o + 8), b.getInt(o + 16) != 0))
        }
        loadBuckets(sec.subOff, sec.subSize, 0x10) { b, o ->
            rawSub.add(Sub(b.getLong(o), b.getInt(o + 12)))
        }
        if (rawRel.isEmpty()) throw IllegalStateException("Tabela de relocação da atualização está vazia.")
        rawRel.sortBy { it.virt }
        rawSub.sortBy { it.offset }

        // Mescla trechos que continuam um do outro (mesma origem e posições contíguas).
        relVirt = LongArray(rawRel.size)
        relPhys = LongArray(rawRel.size)
        relPatch = BooleanArray(rawRel.size)
        var n = 0
        for (r in rawRel) {
            if (n > 0 && relPatch[n - 1] == r.isPatch &&
                relPhys[n - 1] + (r.virt - relVirt[n - 1]) == r.phys
            ) continue
            relVirt[n] = r.virt
            relPhys[n] = r.phys
            relPatch[n] = r.isPatch
            n++
        }
        relCount = n

        // Subseções seguidas com o mesmo contador geram a mesma sequência de cifra: mescla.
        subOff = LongArray(rawSub.size)
        subCtr = IntArray(rawSub.size)
        var m = 0
        for (s in rawSub) {
            if (m > 0 && subCtr[m - 1] == s.ctrVal) continue
            subOff[m] = s.offset
            subCtr[m] = s.ctrVal
            m++
        }
        subCount = m
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

    /** Índice do último item com valor <= [key], ou -1. */
    private fun floorIndex(arr: LongArray, count: Int, key: Long): Int {
        var lo = 0
        var hi = count - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid] <= key) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** Lê dados que estão no próprio patch (offset físico [p] dentro da seção) direto em [out]. */
    private fun readPatch(p: Long, len: Int, out: ByteArray, outOff: Int) {
        var done = 0
        while (done < len) {
            val cur = p + done
            val idx = floorIndex(subOff, subCount, cur)
            val ctrVal: Int? = if (idx >= 0) subCtr[idx] else null
            val end = when {
                idx >= 0 && idx + 1 < subCount -> subOff[idx + 1]
                idx < 0 && subCount > 0 -> subOff[0]
                else -> Long.MAX_VALUE
            }
            val n = minOf((len - done).toLong(), end - cur).toInt()
            if (n <= 0) throw IllegalStateException("Tabela de subseções inconsistente.")
            val chunk = patch.readCtr(sec, cur, n, ctrVal)
            System.arraycopy(chunk, 0, out, outOff + done, n)
            done += n
        }
    }

    /**
     * Lê dados do jogo base (offset [phys] dentro da seção) direto em [out].
     * Trechos pequenos leem um bloco maior de uma vez e os próximos trechos vizinhos
     * saem do cache, evitando muitas leituras minúsculas no arquivo.
     */
    private fun readBase(phys: Long, n: Int, out: ByteArray, outOff: Int) {
        val cd = cacheData
        if (cd != null && phys >= cacheStart && phys + n <= cacheStart + cd.size) {
            System.arraycopy(cd, (phys - cacheStart).toInt(), out, outOff, n)
            return
        }
        if (n >= BASE_BLOCK) {
            System.arraycopy(baseNca.read(baseSec, phys, n), 0, out, outOff, n)
            return
        }
        val limit = baseSec.dataOff + baseSec.dataSize
        val want = minOf(BASE_BLOCK.toLong(), limit - phys).toInt()
        if (want < n) {
            // Perto do fim da seção (ou tamanho desconhecido): lê só o necessário.
            System.arraycopy(baseNca.read(baseSec, phys, n), 0, out, outOff, n)
            return
        }
        val block = baseNca.read(baseSec, phys, want)
        cacheStart = phys
        cacheData = block
        System.arraycopy(block, 0, out, outOff, n)
    }

    /** Lê [len] bytes do RomFS já atualizado, a partir de [pos] (relativo ao início da seção). */
    fun read(pos: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        var done = 0
        while (done < len) {
            val cur = pos + done
            val idx = floorIndex(relVirt, relCount, cur)
            if (idx < 0) throw IllegalStateException("Posição fora da tabela de relocação.")
            val segEnd = if (idx + 1 < relCount) relVirt[idx + 1] else Long.MAX_VALUE
            val n = minOf((len - done).toLong(), segEnd - cur).toInt()
            if (n <= 0) throw IllegalStateException("Tabela de relocação inconsistente.")
            val phys = relPhys[idx] + (cur - relVirt[idx])
            if (relPatch[idx]) readPatch(phys, n, out, done) else readBase(phys, n, out, done)
            done += n
        }
        return out
    }

    private companion object {
        /** Tamanho do bloco de leitura do jogo base para trechos pequenos. */
        const val BASE_BLOCK = 512 * 1024
    }
}
