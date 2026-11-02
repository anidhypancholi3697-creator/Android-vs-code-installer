package com.codebox.installer

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class SetupManager(private val context: Context, private val runner: ProotRunner) {
    companion object {
        private const val ROOTFS_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz"
        private const val MARKER_DOWNLOAD = ".marker_download"
        private const val MARKER_EXTRACT = ".marker_extract"
        private const val MARKER_RESOLV = ".marker_resolv"
        private const val MARKER_APT = ".marker_apt"
        private const val MARKER_AIDER = ".marker_aider"
        private const val MARKER_CLEAN = ".marker_clean"
    }

    private val tarFile = File(context.filesDir, "ubuntu-base.tar.gz")
    private val markerDownload = File(context.filesDir, MARKER_DOWNLOAD)
    private val markerExtract = File(context.filesDir, MARKER_EXTRACT)
    private val markerResolv = File(context.filesDir, MARKER_RESOLV)
    private val markerApt = File(context.filesDir, MARKER_APT)
    private val markerAider = File(context.filesDir, MARKER_AIDER)
    private val markerClean = File(context.filesDir, MARKER_CLEAN)

    private fun runWithRetry(name: String, command: String, onLine: (String) -> Unit) {
        var attempts = 0
        val maxRetries = 3
        var exitCode = -1
        while (attempts < maxRetries) {
            attempts++
            onLine("Running $name (attempt $attempts/$maxRetries)...")
            exitCode = runner.run(command, onLine)
            if (exitCode == 0) {
                return
            }
            onLine("$name attempt $attempts failed with exit code $exitCode")
        }
        throw IOException("$name failed after $maxRetries attempts (exit code: $exitCode)")
    }

    fun isInstalled(): Boolean {
        val binLocal = File(runner.rootfsDir, "usr/local/bin/aider")
        val binUsr = File(runner.rootfsDir, "usr/bin/aider")
        return markerAider.exists() && (binLocal.exists() || binUsr.exists())
    }

    fun install(onLine: (String) -> Unit) {
        // Step 1: Download ubuntu-base tar.gz
        if (!markerDownload.exists()) {
            onLine("Downloading rootfs...")
            var connection: HttpURLConnection? = null
            try {
                var url = URL(ROOTFS_URL)
                var redirects = 0
                while (redirects < 5) {
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 30000
                        readTimeout = 30000
                    }
                    val code = conn.responseCode
                    if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                        code == HttpURLConnection.HTTP_MOVED_TEMP ||
                        code == HttpURLConnection.HTTP_SEE_OTHER ||
                        code == 307 || code == 308
                    ) {
                        val newUrl = conn.getHeaderField("Location")
                        conn.disconnect()
                        url = URL(newUrl)
                        redirects++
                    } else if (code == HttpURLConnection.HTTP_OK) {
                        connection = conn
                        break
                    } else {
                        conn.disconnect()
                        throw IOException("Download failed with HTTP $code")
                    }
                }
                if (connection == null) {
                    throw IOException("Too many redirects downloading rootfs")
                }

                val totalBytes = connection.contentLengthLong
                val buffer = ByteArray(8192)
                var downloadedBytes = 0L
                var lastPercent = -1

                connection.inputStream.use { input ->
                    tarFile.outputStream().use { output ->
                        var bytesRead = input.read(buffer)
                        while (bytesRead != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                                if (percent >= lastPercent + 5 || (percent == 100 && lastPercent != 100)) {
                                    lastPercent = (percent / 5) * 5
                                    onLine("Download progress: $lastPercent%")
                                }
                            }
                            bytesRead = input.read(buffer)
                        }
                    }
                }
                markerDownload.createNewFile()
                onLine("Rootfs download complete")
            } finally {
                connection?.disconnect()
            }
        } else {
            onLine("Rootfs download already completed, skipping")
        }

        // Step 2: Extract with /system/bin/tar into rootfs
        if (!markerExtract.exists()) {
            onLine("Extracting rootfs...")
            runner.rootfsDir.mkdirs()
            val pb = ProcessBuilder(
                "/system/bin/tar",
                "-xzf",
                tarFile.absolutePath,
                "-C",
                runner.rootfsDir.absolutePath,
                "--exclude=./dev/*"
            )
            pb.redirectErrorStream(true)
            val process = pb.start()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach(onLine)
            }
            val exitCode = process.waitFor()
            if (!File(runner.rootfsDir, "bin/bash").exists()) {
                throw IOException("Extraction failed (exit code $exitCode): bin/bash not found in rootfs")
            }
            markerExtract.createNewFile()
            onLine("Rootfs extraction complete")
        } else {
            onLine("Rootfs extraction already completed, skipping")
        }

        // Step 3: Write resolv.conf
        if (!markerResolv.exists()) {
            onLine("Configuring DNS in resolv.conf...")
            val etcDir = File(runner.rootfsDir, "etc")
            etcDir.mkdirs()
            File(etcDir, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            markerResolv.createNewFile()
            onLine("resolv.conf written")
        } else {
            onLine("resolv.conf already written, skipping")
        }

        // Step 4: apt-get update and install packages
        if (!markerApt.exists()) {
            val aptCmd = "apt-get update -o APT::Sandbox::User=root -o Acquire::Retries=3 && " +
                "apt-get install -y -o APT::Sandbox::User=root -o Acquire::Retries=3 " +
                "ca-certificates wget curl git python3 python3-pip nano"
            runWithRetry("apt-get packages", aptCmd, onLine)
            markerApt.createNewFile()
            onLine("Apt packages installed")
        } else {
            onLine("Apt packages already installed, skipping")
        }

        // Step 5: pip3 install aider-chat
        if (!markerAider.exists()) {
            val pipCmd = "pip3 install --no-cache-dir --ignore-installed aider-chat"
            runWithRetry("pip3 install aider-chat", pipCmd, onLine)
            markerAider.createNewFile()
            onLine("Aider installed")
        } else {
            onLine("Aider already installed, skipping")
        }

        // Step 6: apt-get clean and cleanup docs/lists
        if (!markerClean.exists()) {
            runWithRetry("apt-get clean", "apt-get clean", onLine)
            onLine("Cleaning up documentation and apt lists...")
            File(runner.rootfsDir, "usr/share/doc").deleteRecursively()
            File(runner.rootfsDir, "usr/share/man").deleteRecursively()
            File(runner.rootfsDir, "var/lib/apt/lists").deleteRecursively()
            markerClean.createNewFile()
            onLine("Clean up complete")
        } else {
            onLine("Clean up already completed, skipping")
        }
    }
}
