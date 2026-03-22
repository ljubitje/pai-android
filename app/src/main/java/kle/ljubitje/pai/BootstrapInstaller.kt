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
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SNIHostName

/**
 * Downloads and extracts the Termux bootstrap archive into $PREFIX.
 * Falls back to DNS-over-HTTPS if system DNS fails.
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
        private const val CLOUDFLARE_DOH = "https://1.1.1.1/dns-query"
        private const val GOOGLE_DOH = "https://8.8.8.8/resolve"

        fun isBootstrapped(prefix: String): Boolean =
            File("$prefix/bin/bash").exists() || File("$prefix/bin/sh").exists()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun install() {
        Thread {
            try {
                val localFile = File(context.getExternalFilesDir(null), "bootstrap-aarch64.zip")
                if (localFile.exists() && localFile.length() > 1_000_000) {
                    Log.i(TAG, "Found local bootstrap: ${localFile.absolutePath} (${localFile.length()} bytes)")
                    post { onProgress("Found bootstrap archive. Extracting...") }
                    extractBootstrap(localFile)
                    post { onComplete(true) }
                    return@Thread
                }

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

    // ── Download ──

    private fun downloadAndExtract(destFile: File) {
        destFile.delete()

        // Try normal download first
        try {
            downloadWithRedirects(destFile, useFallbackDns = false)
            finishDownload(destFile)
            return
        } catch (e: Exception) {
            Log.w(TAG, "Normal download failed: ${e.javaClass.simpleName}: ${e.message}")
            destFile.delete()
        }

        // Fall back to DNS-over-HTTPS
        post { onProgress("Retrying with DNS-over-HTTPS...") }
        downloadWithRedirects(destFile, useFallbackDns = true)
        finishDownload(destFile)
    }

    private fun finishDownload(destFile: File) {
        Log.i(TAG, "Download complete: ${destFile.length()} bytes")
        post { onProgress("Download complete. Extracting...") }
        extractBootstrap(destFile)
        post { onComplete(true) }
    }

    private fun downloadWithRedirects(destFile: File, useFallbackDns: Boolean) {
        var url = URL(BOOTSTRAP_URL)
        var redirects = 0

        while (true) {
            val connection = if (useFallbackDns) {
                openConnectionWithDoH(url)
            } else {
                url.openConnection() as HttpURLConnection
            }

            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "PAI/1.0")
            connection.connect()

            val code = connection.responseCode
            val mode = if (useFallbackDns) "DoH" else "system"
            Log.i(TAG, "HTTP $code from ${url.host}${url.path.take(60)} ($mode)")

            if (code in 301..303 || code == 307 || code == 308) {
                val location = connection.getHeaderField("Location")
                    ?: throw RuntimeException("Redirect with no Location header")
                connection.disconnect()
                url = URL(location)
                redirects++
                if (redirects > 8) throw RuntimeException("Too many redirects")
                continue
            }

            if (code != 200) {
                connection.disconnect()
                throw RuntimeException("HTTP $code from ${url.host}")
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
            break
        }
    }

    // ── DNS-over-HTTPS fallback ──

    /**
     * Resolves hostname via DNS-over-HTTPS, then opens HTTPS connection
     * to the resolved IP with proper SNI and hostname verification.
     */
    private fun openConnectionWithDoH(url: URL): HttpURLConnection {
        val originalHost = url.host
        val resolved = resolveViaDoH(originalHost)
            ?: throw RuntimeException("DoH resolution failed for $originalHost")

        Log.i(TAG, "DoH resolved $originalHost -> ${resolved.hostAddress}")

        val resolvedUrl = URL(url.protocol, resolved.hostAddress, url.port, url.file)
        val connection = resolvedUrl.openConnection() as HttpURLConnection
        connection.setRequestProperty("Host", originalHost)

        if (connection is HttpsURLConnection) {
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
                HttpsURLConnection.getDefaultHostnameVerifier().verify(originalHost, session)
            }

            val baseSf = SSLSocketFactory.getDefault() as SSLSocketFactory
            connection.sslSocketFactory = object : SSLSocketFactory() {
                override fun getDefaultCipherSuites() = baseSf.defaultCipherSuites
                override fun getSupportedCipherSuites() = baseSf.supportedCipherSuites

                override fun createSocket(s: java.net.Socket, host: String, port: Int, autoClose: Boolean): java.net.Socket {
                    val socket = baseSf.createSocket(s, originalHost, port, autoClose)
                    if (socket is SSLSocket) {
                        socket.sslParameters = socket.sslParameters.apply {
                            serverNames = listOf(SNIHostName(originalHost))
                        }
                    }
                    return socket
                }

                override fun createSocket(host: String, port: Int) = baseSf.createSocket(host, port)
                override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) =
                    baseSf.createSocket(host, port, localHost, localPort)
                override fun createSocket(host: InetAddress, port: Int) = baseSf.createSocket(host, port)
                override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int) =
                    baseSf.createSocket(address, port, localAddress, localPort)
            }
        }

        return connection
    }

    private fun resolveViaDoH(hostname: String): InetAddress? {
        val endpoints = listOf(
            "$CLOUDFLARE_DOH?name=$hostname&type=A",
            "$GOOGLE_DOH?name=$hostname&type=A"
        )

        for (endpoint in endpoints) {
            try {
                val result = doHQuery(endpoint, hostname)
                if (result != null) return result
            } catch (e: Exception) {
                Log.w(TAG, "DoH endpoint failed: ${e.message}")
            }
        }
        return null
    }

    private fun doHQuery(endpoint: String, hostname: String): InetAddress? {
        val dohUrl = URL(endpoint)
        val conn = dohUrl.openConnection() as HttpsURLConnection
        conn.setRequestProperty("Accept", "application/dns-json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.connect()

        if (conn.responseCode != 200) {
            Log.w(TAG, "DoH returned HTTP ${conn.responseCode} from ${dohUrl.host}")
            conn.disconnect()
            return null
        }

        val json = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        Log.d(TAG, "DoH response: ${json.take(200)}")

        // Parse JSON: {"Answer":[{"data":"1.2.3.4","type":1,...},...]}
        val answerIdx = json.indexOf("\"Answer\"")
        if (answerIdx < 0) {
            Log.w(TAG, "DoH response has no Answer section")
            return null
        }

        var searchFrom = answerIdx
        while (true) {
            val dataIdx = json.indexOf("\"data\"", searchFrom)
            if (dataIdx < 0) break

            val typeIdx = json.lastIndexOf("\"type\"", dataIdx)
            if (typeIdx > searchFrom) {
                val typeValStart = json.indexOf(':', typeIdx) + 1
                val typeVal = json.substring(typeValStart, minOf(typeValStart + 5, json.length)).trim()
                if (typeVal.startsWith("1") && (typeVal.length == 1 || !typeVal[1].isDigit())) {
                    val valStart = json.indexOf('"', json.indexOf(':', dataIdx) + 1) + 1
                    val valEnd = json.indexOf('"', valStart)
                    if (valStart > 0 && valEnd > valStart) {
                        val ip = json.substring(valStart, valEnd)
                        Log.i(TAG, "DoH resolved $hostname -> $ip (via ${dohUrl.host})")
                        return InetAddress.getByName(ip)
                    }
                }
            }
            searchFrom = dataIdx + 6
        }

        Log.w(TAG, "DoH: no A record found in response")
        return null
    }

    // ── Extraction ──

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
                if (symlinkLines.isNotEmpty()) {
                    Log.i(TAG, "SYMLINKS.txt first line: ${symlinkLines[0].take(100)}")
                }
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

        post { onProgress("Patching paths...") }
        patchTermuxPaths(stagingDir)

        post { onProgress("Setting permissions...") }
        setExecutablePermissions(stagingDir)

        val prefixDir = File(prefix)
        if (prefixDir.exists()) prefixDir.deleteRecursively()
        if (!stagingDir.renameTo(prefixDir)) {
            throw RuntimeException("Failed to rename staging to prefix")
        }

        post { onProgress("Configuring package manager...") }
        createAptConfig(prefixDir)

        Log.i(TAG, "Bootstrap complete: $entryCount files")
        post { onProgress("Bootstrap complete! $entryCount files installed.") }
    }

    /**
     * Replaces hardcoded Termux paths in shell scripts and config files
     * with our app's actual paths, so pkg/apt/profile/etc. work correctly.
     */
    private fun patchTermuxPaths(stagingDir: File) {
        // Use /data/data/ style paths (shorter, works with shebang length limits)
        val appPkg = context.packageName
        val appDataDir = "/data/data/$appPkg/files"
        val termuxFiles = "/data/data/com.termux/files"
        val termuxCache = "/data/data/com.termux/cache"
        val appCacheDir = "/data/data/$appPkg/cache"

        // Termux uses /data/data/com.termux/files/home as $HOME but we use external storage
        val termuxHome = "/data/data/com.termux/files/home"
        val appHome = "$appDataDir/home"  // patched first, then fixed below

        // Only patch text files in etc/ and bin/ — skip binaries and libraries
        val patchDirs = listOf("etc", "bin").map { File(stagingDir, it) }
        var patchCount = 0

        for (dir in patchDirs) {
            if (!dir.exists()) continue
            dir.walkTopDown()
                .filter { it.isFile && !it.name.endsWith(".so") && it.length() < 512_000 }
                .forEach { file ->
                    try {
                        // Skip ELF binaries — reading them as UTF-8 corrupts binary data
                        val header = ByteArray(4)
                        file.inputStream().use { it.read(header) }
                        if (header[0] == 0x7F.toByte() && header[1] == 0x45.toByte() &&
                            header[2] == 0x4C.toByte() && header[3] == 0x46.toByte()) return@forEach

                        val content = file.readText(Charsets.UTF_8)
                        if (content.contains(termuxFiles) || content.contains(termuxCache)) {
                            var patched = content
                                .replace(termuxCache, appCacheDir)
                                .replace(termuxFiles, appDataDir)
                            // Replace hardcoded home dir with $HOME variable reference
                            patched = patched.replace(appHome, "\$HOME")
                            file.writeText(patched, Charsets.UTF_8)
                            patchCount++
                        }
                    } catch (_: Exception) {
                        // Skip files that aren't valid UTF-8
                    }
                }
        }

        Log.i(TAG, "Patched $patchCount files (com.termux -> ${context.packageName})")
    }

    /**
     * Creates apt.conf that redirects all apt paths from the compiled-in
     * Termux prefix to our app's actual prefix. Needed because Termux-compiled
     * apt/dpkg binaries have /data/data/com.termux/files/usr baked in.
     */
    private fun createAptConfig(prefixDir: File) {
        val appPkg = context.packageName
        val p = "/data/data/$appPkg/files/usr"
        val cacheDir = "/data/data/$appPkg/cache/apt"

        val aptConf = File(prefixDir, "etc/apt/apt.conf")
        aptConf.writeText("""
            Dir "$p";
            Dir::State "$p/var/lib/apt";
            Dir::State::status "$p/var/lib/dpkg/status";
            Dir::Cache "$cacheDir";
            Dir::Etc "$p/etc/apt";
            Dir::Etc::TrustedParts "$p/etc/apt/trusted.gpg.d";
            Dir::Bin::DPkg "$p/bin/dpkg";
            Dir::Bin::methods "$p/lib/apt/methods";
            Acquire::https::CaInfo "$p/etc/tls/cert.pem";
            Dir::Bin::apt-key "$p/bin/apt-key";
            Dir::Log "$p/var/log/apt";
            DPkg::Path "$p/bin:/system/bin:/system/xbin";
            DPkg::Options:: "--admindir=$p/var/lib/dpkg";
            DPkg::Options:: "--instdir=$p/tmp/dpkg-instdir";
            DPkg::Options:: "--log=$p/var/log/dpkg.log";
        """.trimIndent() + "\n")

        // Ensure required directories exist
        listOf(
            "$p/var/lib/apt/lists/partial",
            "$p/var/lib/dpkg/info",
            "$p/var/lib/dpkg/updates",
            "$p/var/log",
            "$p/var/log/apt",
            "$p/etc/apt/apt.conf.d",
            "$p/tmp",
            cacheDir,
            "$cacheDir/archives/partial"
        ).forEach { File(it).mkdirs() }

        // Create dpkg status file if missing
        val statusFile = File("$p/var/lib/dpkg/status")
        if (!statusFile.exists()) statusFile.createNewFile()

        // Mark repos as trusted — apt-key temp file creation uses compiled-in
        // Termux tmp path we can't override, so skip GPG verification.
        // HTTPS provides transport security.
        val sourcesList = File(prefixDir, "etc/apt/sources.list")
        if (sourcesList.exists()) {
            val content = sourcesList.readText()
            if (!content.contains("[trusted=yes]")) {
                sourcesList.writeText(content.replace(
                    "deb https://",
                    "deb [trusted=yes] https://"
                ))
            }
        }

        // Create preferences.d directory to suppress warning
        File(prefixDir, "etc/apt/preferences.d").mkdirs()

        // Termux .deb packages contain paths like ./data/data/com.termux/files/usr/...
        // Create a symlink-based instdir so dpkg extracts to our prefix:
        //   instdir/data/data/com.termux → /data/data/<our.pkg>
        val instDir = File(prefixDir, "tmp/dpkg-instdir/data/data")
        instDir.mkdirs()
        val symlinkTarget = "/data/data/$appPkg"
        val symlinkFile = File(instDir, "com.termux")
        if (!symlinkFile.exists()) {
            try {
                android.system.Os.symlink(symlinkTarget, symlinkFile.absolutePath)
            } catch (e: Exception) {
                Runtime.getRuntime().exec(
                    arrayOf("ln", "-sf", symlinkTarget, symlinkFile.absolutePath)
                ).waitFor()
            }
        }

        Log.i(TAG, "Created apt.conf redirecting to $p")
    }

    private fun createSymlinks(stagingDir: File, lines: List<String>) {
        var symlinkCount = 0
        var failCount = 0

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // Termux SYMLINKS.txt format: target←linkpath (← = U+2190)
            val sepIndex = trimmed.indexOf('←')
            if (sepIndex < 0) {
                Log.w(TAG, "Skipping symlink line (no ← separator): ${trimmed.take(80)}")
                failCount++
                continue
            }

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
                    failCount++
                }
            }
        }

        Log.i(TAG, "Created $symlinkCount symlinks ($failCount failed)")
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
