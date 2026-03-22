package kle.ljubitje.pai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads and extracts the Termux bootstrap archive into $PREFIX.
 * Uses HttpURLConnection for reliable downloading with redirect support.
 * Extraction runs on a background thread.
 */
class BootstrapInstaller(
    private val context: Context,
    private val prefix: String,
    private val onProgress: (String) -> Unit,
    private val onComplete: (success: Boolean) -> Unit
) {

    companion object {
        private const val TAG = "BootstrapInstaller"
        private const val BOOTSTRAP_URL =
            "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip"

        fun isBootstrapped(prefix: String): Boolean =
            File("$prefix/bin/bash").exists() || File("$prefix/bin/sh").exists()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun install() {
        Thread {
            try {
                // Check for pre-placed bootstrap file first (e.g. from ADB push or prior download)
                val localFile = File(context.getExternalFilesDir(null), "bootstrap-aarch64.zip")
                if (localFile.exists() && localFile.length() > 1_000_000) {
                    Log.i(TAG, "Found local bootstrap: ${localFile.absolutePath} (${localFile.length()} bytes)")
                    post { onProgress("Found bootstrap archive. Extracting...") }
                    extractBootstrap(localFile)
                    post { onComplete(true) }
                    return@Thread
                }

                // Download via HttpURLConnection
                post { onProgress("Downloading Termux bootstrap...") }
                Log.i(TAG, "Starting download: $BOOTSTRAP_URL")
                downloadAndExtract(localFile)
            } catch (e: Exception) {
                Log.e(TAG, "Bootstrap failed: ${e.javaClass.name}: ${e.message}")
                Log.e(TAG, Log.getStackTraceString(e))
                post { onProgress("ERROR: ${e.javaClass.simpleName}: ${e.message}") }
                post { onComplete(false) }
            }
        }.start()
    }

    private fun resolveWithFallback(hostname: String): InetAddress {
        return try {
            InetAddress.getByName(hostname)
        } catch (e: Exception) {
            Log.w(TAG, "System DNS failed for $hostname, trying Mullvad DNS (194.242.2.2)")
            // Use Mullvad unfiltered DNS as fallback via UDP query
            val resolver = miniDnsResolve(hostname, "194.242.2.2")
            if (resolver != null) resolver
            else throw RuntimeException("DNS resolution failed for $hostname (system + Mullvad fallback)")
        }
    }

    private fun miniDnsResolve(hostname: String, dnsServer: String): InetAddress? {
        try {
            val socket = java.net.DatagramSocket()
            socket.soTimeout = 5000

            // Build a minimal DNS A record query
            val txId = (Math.random() * 65535).toInt()
            val query = buildDnsQuery(txId, hostname)
            val serverAddr = InetAddress.getByName(dnsServer)
            socket.send(java.net.DatagramPacket(query, query.size, serverAddr, 53))

            val response = ByteArray(512)
            val respPacket = java.net.DatagramPacket(response, response.size)
            socket.receive(respPacket)
            socket.close()

            return parseDnsResponse(response, respPacket.length)
        } catch (e: Exception) {
            Log.e(TAG, "Mullvad DNS query failed: ${e.message}")
            return null
        }
    }

    private fun buildDnsQuery(txId: Int, hostname: String): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        buf.write(txId shr 8); buf.write(txId and 0xFF) // Transaction ID
        buf.write(0x01); buf.write(0x00) // Flags: standard query, recursion desired
        buf.write(0x00); buf.write(0x01) // Questions: 1
        buf.write(0x00); buf.write(0x00) // Answers: 0
        buf.write(0x00); buf.write(0x00) // Authority: 0
        buf.write(0x00); buf.write(0x00) // Additional: 0
        for (label in hostname.split(".")) {
            buf.write(label.length)
            buf.write(label.toByteArray(Charsets.US_ASCII))
        }
        buf.write(0x00) // End of name
        buf.write(0x00); buf.write(0x01) // Type A
        buf.write(0x00); buf.write(0x01) // Class IN
        return buf.toByteArray()
    }

    private fun parseDnsResponse(data: ByteArray, length: Int): InetAddress? {
        if (length < 12) return null
        val answerCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        if (answerCount == 0) return null

        // Skip header (12 bytes) and question section
        var pos = 12
        while (pos < length && data[pos].toInt() != 0) {
            val labelLen = data[pos].toInt() and 0xFF
            if (labelLen >= 0xC0) { pos += 2; break } // Pointer
            pos += labelLen + 1
        }
        if (pos < length && data[pos].toInt() == 0) pos++ // null terminator
        pos += 4 // skip QTYPE + QCLASS

        // Parse answer records, look for first A record
        for (i in 0 until answerCount) {
            if (pos >= length) break
            // Skip name (may be pointer)
            if ((data[pos].toInt() and 0xC0) == 0xC0) pos += 2
            else { while (pos < length && data[pos].toInt() != 0) pos++; pos++ }
            if (pos + 10 > length) break
            val type = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            val rdLength = ((data[pos + 8].toInt() and 0xFF) shl 8) or (data[pos + 9].toInt() and 0xFF)
            pos += 10
            if (type == 1 && rdLength == 4 && pos + 4 <= length) {
                return InetAddress.getByAddress(data.copyOfRange(pos, pos + 4))
            }
            pos += rdLength
        }
        return null
    }

    private fun downloadAndExtract(destFile: File) {
        destFile.delete()

        var url = URL(BOOTSTRAP_URL)
        var connection: HttpURLConnection
        var redirects = 0

        // Follow redirects manually (HttpURLConnection doesn't follow cross-protocol redirects)
        while (true) {
            // Resolve hostname with Mullvad DNS fallback
            val resolved = resolveWithFallback(url.host)
            val resolvedUrl = URL(url.protocol, resolved.hostAddress, url.port, url.file)
            connection = resolvedUrl.openConnection() as HttpURLConnection
            connection.setRequestProperty("Host", url.host)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.connect()

            val code = connection.responseCode
            Log.i(TAG, "HTTP $code from ${url.host}${url.path.take(60)}")

            if (code in 301..303 || code == 307 || code == 308) {
                val location = connection.getHeaderField("Location")
                    ?: throw RuntimeException("Redirect with no Location header")
                connection.disconnect()
                url = URL(location)
                redirects++
                if (redirects > 5) throw RuntimeException("Too many redirects")
                continue
            }

            if (code != 200) {
                connection.disconnect()
                throw RuntimeException("HTTP $code from ${url.host}")
            }
            break
        }

        val total = connection.contentLength.toLong()
        Log.i(TAG, "Downloading $total bytes")

        BufferedInputStream(connection.inputStream).use { input ->
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(8192)
                var downloaded = 0L
                var lastReport = 0L
                var len: Int

                while (input.read(buffer).also { len = it } != -1) {
                    output.write(buffer, 0, len)
                    downloaded += len

                    if (downloaded - lastReport > 500_000) {
                        lastReport = downloaded
                        val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
                        val mb = downloaded / (1024 * 1024)
                        Log.i(TAG, "Downloaded ${mb}MB ($pct%)")
                        post { onProgress("Downloading... ${mb}MB ($pct%)") }
                    }
                }
            }
        }
        connection.disconnect()

        Log.i(TAG, "Download complete: ${destFile.length()} bytes")
        post { onProgress("Download complete. Extracting...") }
        extractBootstrap(destFile)
        post { onComplete(true) }
    }

    private fun extractBootstrap(zipFile: File) {
        val stagingDir = File("${prefix}_staging")
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        val zipInput = ZipInputStream(FileInputStream(zipFile))
        val symlinkLines = mutableListOf<String>()
        var entryCount = 0

        var entry = zipInput.nextEntry
        while (entry != null) {
            val name = entry.name
            if (name == "SYMLINKS.txt") {
                val content = zipInput.readBytes().toString(Charsets.UTF_8)
                symlinkLines.addAll(content.lines().filter { it.isNotBlank() })
                Log.i(TAG, "SYMLINKS.txt: ${symlinkLines.size} lines")
            } else if (!entry.isDirectory) {
                val outFile = File(stagingDir, name)
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { fos ->
                    zipInput.copyTo(fos)
                }
                entryCount++
                if (entryCount % 200 == 0) {
                    val count = entryCount
                    post { onProgress("Extracting... ($count files)") }
                }
            }
            zipInput.closeEntry()
            entry = zipInput.nextEntry
        }
        zipInput.close()
        zipFile.delete()
        Log.i(TAG, "Extracted $entryCount files")

        post { onProgress("Extracted $entryCount files. Creating symlinks...") }
        createSymlinks(stagingDir, symlinkLines)

        post { onProgress("Setting permissions...") }
        setExecutablePermissions(stagingDir)

        // Atomic swap: rename staging to final prefix
        val prefixDir = File(prefix)
        if (prefixDir.exists()) prefixDir.deleteRecursively()
        if (!stagingDir.renameTo(prefixDir)) {
            throw RuntimeException("Failed to rename staging to prefix")
        }

        Log.i(TAG, "Bootstrap complete: $entryCount files")
        post { onProgress("Bootstrap complete! $entryCount files installed.") }
    }

    private fun createSymlinks(stagingDir: File, lines: List<String>) {
        var symlinkCount = 0

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // Format: target←linkpath
            val sepIndex = trimmed.indexOf('←')
            if (sepIndex < 0) continue

            val target = trimmed.substring(0, sepIndex)
            val linkPath = trimmed.substring(sepIndex + 1)
            if (target.isEmpty() || linkPath.isEmpty()) continue

            val linkFile = File(stagingDir, linkPath)
            linkFile.parentFile?.mkdirs()
            try {
                linkFile.delete()
                android.system.Os.symlink(target, linkFile.absolutePath)
                symlinkCount++
            } catch (e: Exception) {
                try {
                    Runtime.getRuntime().exec(
                        arrayOf("ln", "-sf", target, linkFile.absolutePath)
                    ).waitFor()
                    symlinkCount++
                } catch (_: Exception) {
                    Log.w(TAG, "Failed to create symlink: $linkPath -> $target")
                }
            }
        }

        Log.i(TAG, "Created $symlinkCount symlinks")
        post { onProgress("Created $symlinkCount symlinks.") }
    }

    private fun setExecutablePermissions(dir: File) {
        val execDirs = listOf("bin", "libexec", "lib/apt/apt-helper", "lib/apt/methods")
        for (execDir in execDirs) {
            val d = File(dir, execDir)
            if (d.exists()) {
                if (d.isDirectory) {
                    d.listFiles()?.forEach { it.setExecutable(true, false) }
                } else {
                    d.setExecutable(true, false)
                }
            }
        }
        dir.walkTopDown().filter { it.name.endsWith(".so") || it.name.contains(".so.") }.forEach {
            it.setExecutable(true, false)
        }
    }

    private fun post(action: () -> Unit) {
        mainHandler.post(action)
    }
}
