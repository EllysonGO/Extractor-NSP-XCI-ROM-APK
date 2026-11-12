package com.zeldaromtool

import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object Updater {

    private const val API_URL =
        "https://api.github.com/repos/EllysonGO/Extractor-NSP-XCI-ROM-APK/releases?per_page=10"

    data class Release(
        val name: String,
        val tag: String,
        val version: String,
        val downloadUrl: String,
        val notes: String
    )

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ZeldaRomTool-Android")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
    }

    fun checkLatestRelease(): Release {
        val connection = openConnection(API_URL)

        try {
            val responseCode = connection.responseCode

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException(
                    "O GitHub respondeu com o código HTTP $responseCode."
                )
            }

            val response = connection.inputStream.bufferedReader().use {
                it.readText()
            }

            val releases = JSONArray(response)

            if (releases.length() == 0) {
                throw IllegalStateException(
                    "Nenhuma publicação foi encontrada no GitHub."
                )
            }

            // O GitHub entrega as publicações da mais recente para a mais antiga.
            // Usamos a primeira publicação que não seja um rascunho.
            var releaseJson = releases.getJSONObject(0)

            for (i in 0 until releases.length()) {
                val candidate = releases.getJSONObject(i)

                if (!candidate.optBoolean("draft", false)) {
                    releaseJson = candidate
                    break
                }
            }

            val name = releaseJson.optString("name", "ZeldaRomTool")
            val tag = releaseJson.optString("tag_name", "")
            val notes = releaseJson.optString("body", "")

            val assets = releaseJson.optJSONArray("assets")
                ?: throw IllegalStateException(
                    "A publicação não possui arquivos anexados."
                )

            var apkUrl: String? = null

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val assetName = asset.optString("name", "")

                if (assetName.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    if (!apkUrl.isNullOrBlank()) break
                }
            }

            if (apkUrl.isNullOrBlank()) {
                throw IllegalStateException(
                    "Não encontrei um arquivo APK na publicação mais recente. " +
                        "Anexe o APK na seção Assets do GitHub."
                )
            }

            val versionText = "$name $notes $tag"
            val versionRegex = Regex(
                """(?i)v?(\d+\.\d+\.\d+(?:-[0-9a-z.-]+)?)"""
            )

            val version = versionRegex.find(versionText)
                ?.groupValues
                ?.get(1)
                ?: throw IllegalStateException(
                    "Não consegui identificar a versão da publicação. " +
                        "Inclua uma versão no título, na descrição ou na tag."
                )

            return Release(
                name = name,
                tag = tag,
                version = version,
                downloadUrl = apkUrl,
                notes = notes
            )
        } finally {
            connection.disconnect()
        }
    }

    fun isNewerVersion(remote: String, installed: String): Boolean {
        return compareVersions(remote, installed) > 0
    }

    private fun compareVersions(first: String, second: String): Int {
        val regex = Regex(
            """^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$"""
        )

        val a = regex.matchEntire(first.trim())
        val b = regex.matchEntire(second.trim())

        if (a == null || b == null) {
            return first.compareTo(second, ignoreCase = true)
        }

        for (i in 1..3) {
            val numberA = a.groupValues[i].toLongOrNull() ?: 0L
            val numberB = b.groupValues[i].toLongOrNull() ?: 0L

            if (numberA != numberB) {
                return numberA.compareTo(numberB)
            }
        }

        val preA = a.groupValues[4]
        val preB = b.groupValues[4]

        // Uma versão estável é mais recente que sua versão beta.
        if (preA.isEmpty() && preB.isNotEmpty()) return 1
        if (preA.isNotEmpty() && preB.isEmpty()) return -1

        return preA.compareTo(preB, ignoreCase = true)
    }

    fun downloadApk(
        release: Release,
        destinationDirectory: File
    ): File {
        if (!destinationDirectory.exists() &&
            !destinationDirectory.mkdirs()
        ) {
            throw IllegalStateException(
                "Não consegui criar a pasta de download."
            )
        }

        val safeVersion = release.version.replace(
            Regex("[^A-Za-z0-9._-]"),
            "_"
        )

        val apkFile = File(
            destinationDirectory,
            "ZeldaRomTool-update-$safeVersion.apk"
        )

        val connection = openConnection(release.downloadUrl)

        try {
            val responseCode = connection.responseCode

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException(
                    "Falha ao baixar o APK. Código HTTP: $responseCode."
                )
            }

            connection.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)

                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break

                        output.write(buffer, 0, count)
                    }

                    output.flush()
                }
            }

            if (!apkFile.exists() || apkFile.length() < 4) {
                apkFile.delete()
                throw IllegalStateException(
                    "O arquivo APK baixado está vazio ou incompleto."
                )
            }

            // APK é um arquivo ZIP e começa com a assinatura PK.
            val signature = apkFile.inputStream().use {
                byteArrayOf(it.read().toByte(), it.read().toByte())
            }

            if (signature[0] != 0x50.toByte() ||
                signature[1] != 0x4B.toByte()
            ) {
                apkFile.delete()
                throw IllegalStateException(
                    "O arquivo recebido não parece ser um APK válido."
                )
            }

            return apkFile
        } catch (e: Exception) {
            apkFile.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }
}
