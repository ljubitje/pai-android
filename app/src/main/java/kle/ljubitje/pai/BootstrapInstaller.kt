package kle.ljubitje.pai

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
 * Uses ConnectivityManager for proper network binding (works with VPNs).
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
    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

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

    // ── Network-aware connection opening ──

    /**
     * Opens a connection bound to a specific network.
     * On Android with VPNs, unbound sockets may get ECONNREFUSED.
     */
    private fun openConnection(url: URL, network: Network? = null): HttpURLConnection {
        val net = network ?: connectivityManager.activeNetwork
        if (net != null) {
            Log.d(TAG, "Opening connection via network: $net")
            return net.openConnection(url) as HttpURLConnection
        }
        Log.d(TAG, "No active network, using default connection")
        return url.openConnection() as HttpURLConnection
    }

    /**
     * Gets all available networks ordered by preference: active first,
     * then underlying networks (WiFi behind VPN), then all others.
     */
    private fun getNetworksToTry(): List<Network?> {
        val networks = mutableListOf<Network?>()
        val active = connectivityManager.activeNetwork
        if (active != null) networks.add(active)

        // Also try all registered networks (WiFi, cellular, etc.)
        connectivityManager.allNetworks.forEach { net ->
            if (net != active) {
                val caps = connectivityManager.getNetworkCapabilities(net)
                val hasInternet = caps?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                ) == true
                if (hasInternet) {
                    networks.add(net)
                    Log.d(TAG, "Found alternative network: $net (caps: $caps)")
                }
            }
        }

        // null = unbound default as last resort
        networks.add(null)
        return networks
    }

    // ── Download with retries ──

    private fun downloadAndExtract(destFile: File) {
        destFile.delete()

        val networks = getNetworksToTry()
        Log.i(TAG, "Networks to try: ${networks.map { it?.toString() ?: "default" }}")

        var lastException: Exception? = null

        for (network in networks) {
            val networkName = network?.toString() ?: "default"

            // Bind process to this network
            if (network != null) {
                connectivityManager.bindProcessToNetwork(network)
            } else {
                connectivityManager.bindProcessToNetwork(null)
            }
            Log.i(TAG, "Trying network: $networkName")
            post { onProgress("Trying network $networkName...") }

            // Try normal download on this network
            try {
                downloadWithRedirects(destFile, useFallbackDns = false, network = network)
                finishDownload(destFile)
                return
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Network $networkName failed: ${e.javaClass.simpleName}: ${e.message}")
                destFile.delete()
            }

            // Try DoH fallback on this network
            try {
                post { onProgress("Trying DoH on network $networkName...") }
                downloadWithRedirects(destFile, useFallbackDns = true, network = network)
                finishDownload(destFile)
                return
            } catch (e: Exception) {
                Log.w(TAG, "DoH on network $networkName failed: ${e.message}")
                destFile.delete()
            }
        }

        throw lastException ?: RuntimeException("All networks exhausted")
    }

    private fun finishDownload(destFile: File) {
        Log.i(TAG, "Download complete: ${destFile.length()} bytes")
        post { onProgress("Download complete. Extracting...") }
        extractBootstrap(destFile)
        post { onComplete(true) }
    }

    private fun downloadWithRedirects(destFile: File, useFallbackDns: Boolean, network: Network? = null) {
        var url = URL(BOOTSTRAP_URL)
        var redirects = 0

        while (true) {
            val connection = if (useFallbackDns) {
                openConnectionWithDoH(url, network)
            } else {
                openConnection(url, network)
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

    /**
     * Resolves hostname via DNS-over-HTTPS, then opens HTTPS connection
     * to the resolved IP with proper SNI and hostname verification.
     */
    private fun openConnectionWithDoH(url: URL, network: Network? = null): HttpURLConnection {
        val originalHost = url.host
        val resolved = resolveViaDoH(originalHost, network)
            ?: throw RuntimeException("DoH resolution failed for $originalHost")

        Log.i(TAG, "DoH resolved $originalHost -> ${resolved.hostAddress}")

        val resolvedUrl = URL(url.protocol, resolved.hostAddress, url.port, url.file)
        val connection = openConnection(resolvedUrl, network)
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

    // ── DNS-over-HTTPS (by IP, no DNS needed) ──

    private fun resolveViaDoH(hostname: String, network: Network? = null): InetAddress? {
        val endpoints = listOf(
            "$CLOUDFLARE_DOH?name=$hostname&type=A",
            "$GOOGLE_DOH?name=$hostname&type=A"
        )

        for (endpoint in endpoints) {
            try {
                val result = doHQuery(endpoint, hostname, network)
                if (result != null) return result
            } catch (e: Exception) {
                Log.w(TAG, "DoH endpoint failed: ${e.message}")
            }
        }
        return null
    }

    private fun doHQuery(endpoint: String, hostname: String, network: Network? = null): InetAddress? {
        val dohUrl = URL(endpoint)
        val conn = openConnection(dohUrl, network) as HttpsURLConnection
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

        post { onProgress("Setting permissions...") }
        setExecutablePermissions(stagingDir)

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
