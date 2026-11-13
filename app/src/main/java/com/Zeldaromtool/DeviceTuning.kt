package com.zeldaromtool

import android.app.ActivityManager
import android.content.Context
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Perfil escolhido para o aparelho. */
data class Tuning(
    val bufferSize: Int,   // tamanho de cada bloco de leitura/escrita
    val queueDepth: Int,   // quantos blocos ficam "em voo" entre leitor e gravador
    val turbo: Boolean
)

object DeviceTuning {

    private const val MB = 1024 * 1024

    /** Perfil em uso. Definido no início de cada extração (writeItems). */
    @Volatile
    var current: Tuning = Tuning(bufferSize = 4 * MB, queueDepth = 3, turbo = false)

    // Celular de 16 GB reporta ~15 GB em totalMem, por isso o corte é 14 e não 16.
    private const val TURBO_RAM_GB = 14.0
    private const val TURBO_SPEED_MBS = 200.0

    fun totalRamGb(ctx: Context): Double {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / 1024.0 / 1024.0 / 1024.0
    }

    /**
     * Mede a velocidade real (MB/s) escrevendo e lendo de volta um arquivo de teste
     * na pasta de saída escolhida. Retorna o menor dos dois valores.
     * Funciona com File ou com SAF/DocumentFile: você só informa como abrir e apagar.
     */
    fun measureSpeedMBs(
        openOut: () -> OutputStream,
        openIn: () -> InputStream,
        cleanup: () -> Unit,
        testMb: Int = 256
    ): Double {
        return try {
            val block = ByteArray(4 * MB) { (it * 31).toByte() }

            val t0 = System.nanoTime()
            openOut().use { out ->
                repeat(testMb / 4) { out.write(block) }
                out.flush()
            }
            val writeMBs = testMb / ((System.nanoTime() - t0) / 1e9)

            val buf = ByteArray(4 * MB)
            val t1 = System.nanoTime()
            openIn().use { inp -> while (inp.read(buf) != -1) { /* só lê */ } }
            val readMBs = testMb / ((System.nanoTime() - t1) / 1e9)

            minOf(writeMBs, readMBs)
        } catch (e: Exception) {
            0.0
        } finally {
            runCatching { cleanup() }
        }
    }

    fun choose(ctx: Context, speedMBs: Double): Tuning {
        val ram = totalRamGb(ctx)
        return when {
            ram >= TURBO_RAM_GB && speedMBs >= TURBO_SPEED_MBS ->
                Tuning(bufferSize = 8 * MB, queueDepth = 4, turbo = true)
            ram >= 7.0 ->
                Tuning(bufferSize = 4 * MB, queueDepth = 3, turbo = false)
            else ->
                Tuning(bufferSize = 1 * MB, queueDepth = 2, turbo = false)
        }
    }
}

/**
 * Cópia em pipeline: uma thread produz blocos (lê + decifra) enquanto a thread atual grava.
 * [fill] preenche o buffer recebido (a partir do índice 0) e devolve quantos bytes colocou,
 * ou -1 quando acabou.
 * Retorna true se terminou, false se foi cancelada.
 */
fun copyPipelined(
    fill: (ByteArray) -> Int,
    output: OutputStream,
    tuning: Tuning,
    isCancelled: () -> Boolean,
    onBytes: (Int) -> Unit
): Boolean {
    class Chunk(val data: ByteArray, var len: Int)

    val total = tuning.queueDepth + 1
    val free = ArrayBlockingQueue<Chunk>(total)
    val full = ArrayBlockingQueue<Chunk>(total + 1)
    repeat(total) { free.put(Chunk(ByteArray(tuning.bufferSize), 0)) }
    val eof = Chunk(ByteArray(0), -1)

    val failed = AtomicBoolean(false)
    val readError = AtomicReference<Throwable?>(null)

    val reader = Thread {
        try {
            while (!isCancelled() && !failed.get()) {
                val c = free.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val n = fill(c.data)
                if (n < 0) break
                c.len = n
                full.put(c)
            }
        } catch (t: Throwable) {
            readError.set(t)
        } finally {
            full.put(eof)
        }
    }
    reader.start()

    var writeError: Throwable? = null
    while (true) {
        val c = full.take()
        if (c === eof) break
        if (!failed.get()) {
            try {
                output.write(c.data, 0, c.len)
                onBytes(c.len)
            } catch (t: Throwable) {
                writeError = t
                failed.set(true)   // continua drenando a fila só para o leitor terminar
            }
        }
        free.put(c)
    }
    reader.join()

    writeError?.let { throw it }
    readError.get()?.let { throw it }
    return !isCancelled()
}

/** Versão para InputStream comum. */
fun copyPipelined(
    input: InputStream,
    output: OutputStream,
    tuning: Tuning,
    isCancelled: () -> Boolean,
    onBytes: (Int) -> Unit
): Boolean = copyPipelined({ buf -> input.read(buf) }, output, tuning, isCancelled, onBytes)

/*
 * COMO USAR (exemplo):
 *
 * val speed = DeviceTuning.measureSpeedMBs(
 *     openOut = { contentResolver.openOutputStream(testUri)!! },
 *     openIn  = { contentResolver.openInputStream(testUri)!! },
 *     cleanup = { DocumentFile.fromSingleUri(this, testUri)?.delete() }
 * )
 * val tuning = DeviceTuning.choose(this, speed)
 *
 * val ok = copyPipelined(input, output, tuning, { cancelado }, { bytes -> progresso += bytes })
 *
 * No AndroidManifest, dentro de <application>, adicione: android:largeHeap="true"
 * (o modo turbo usa uns 40-50 MB de buffers).
 */
