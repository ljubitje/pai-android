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
                // 1. Try bundled bootstrap from APK assets (instant, no download)
                val assetZip = extractAssetBootstrap()
                if (assetZip != null) {
                    Log.i(TAG, "Using bundled bootstrap from assets (${assetZip.length()} bytes)")
                    post { onProgress("Extracting bundled bootstrap...") }
                    extractBootstrap(assetZip)
                    createFirstRunUpdate()
                    post { onComplete(true) }
                    return@Thread
                }

                // 2. Try local file on external storage
                val localFile = File(context.getExternalFilesDir(null), "bootstrap-aarch64.zip")
                if (localFile.exists() && localFile.length() > 1_000_000) {
                    Log.i(TAG, "Found local bootstrap: ${localFile.absolutePath} (${localFile.length()} bytes)")
                    post { onProgress("Found bootstrap archive. Extracting...") }
                    extractBootstrap(localFile)
                    createFirstRunUpdate()
                    post { onComplete(true) }
                    return@Thread
                }

                // 3. Download from network
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

    /**
     * Extracts bootstrap-aarch64.zip from APK assets to a temp file.
     * Returns the temp file if found, null if no bundled bootstrap exists.
     */
    private fun extractAssetBootstrap(): File? {
        return try {
            val input = context.assets.open("bootstrap-aarch64.zip")
            val tempFile = File(context.cacheDir, "bootstrap-aarch64.zip")
            input.use { src ->
                FileOutputStream(tempFile).use { dst ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    var len: Int
                    while (src.read(buffer).also { len = it } != -1) {
                        dst.write(buffer, 0, len)
                        total += len
                        if (total % (5 * 1024 * 1024) == 0L) {
                            val mb = total / (1024 * 1024)
                            post { onProgress("Preparing bootstrap... ${mb}MB") }
                        }
                    }
                }
            }
            Log.i(TAG, "Extracted asset bootstrap to ${tempFile.absolutePath} (${tempFile.length()} bytes)")
            tempFile
        } catch (e: java.io.FileNotFoundException) {
            Log.i(TAG, "No bundled bootstrap in assets")
            null
        }
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
        createFirstRunUpdate()
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

        // Path patching was moved to build time (scripts/prebuild-bootstrap.py).
        // We no longer scan 349 files here at runtime — that saves 5-15 seconds
        // on a cold filesystem. patchTermuxPaths() remains available as a fallback
        // in case someone ships an unpatched bootstrap (detected below).
        if (!isPrePatched(stagingDir)) {
            post { onProgress("Patching paths (fallback)...") }
            patchTermuxPaths(stagingDir)
        } else {
            Log.i(TAG, "Bootstrap is pre-patched at build time — skipping runtime scan")
        }

        post { onProgress("Setting permissions...") }
        setExecutablePermissions(stagingDir)

        val prefixDir = File(prefix)
        if (prefixDir.exists()) prefixDir.deleteRecursively()
        if (!stagingDir.renameTo(prefixDir)) {
            throw RuntimeException("Failed to rename staging to prefix")
        }

        post { onProgress("Configuring package manager...") }
        createAptConfig(prefixDir)

        post { onProgress("Staging bundled packages...") }
        stageBundledDebs(prefixDir)
        stageBundledPai(prefixDir)
        stageBundledTsx(prefixDir)

        Log.i(TAG, "Bootstrap complete: $entryCount files")
        post { onProgress("Bootstrap complete! $entryCount files installed.") }
    }

    /**
     * Copies bundled .deb files from APK assets to $PREFIX/var/cache/apt/archives/offline/.
     * The first-run script (see [createFirstRunUpdate]) installs them via `dpkg -i` before
     * any network call, so first-boot doesn't need to download nodejs-lts, git, proot, etc.
     *
     * No-op if the assets/pkgs/ directory is absent — bundling is optional.
     */
    private fun stageBundledDebs(prefixDir: File) {
        val assets = context.assets
        val entries = try {
            assets.list("pkgs") ?: emptyArray()
        } catch (_: Exception) {
            emptyArray()
        }
        val debs = entries.filter { it.endsWith(".deb") }
        if (debs.isEmpty()) {
            Log.i(TAG, "No bundled .debs in assets/pkgs/ — skipping offline stage")
            return
        }
        val offlineDir = File(prefixDir, "var/cache/apt/archives/offline")
        offlineDir.mkdirs()
        var copied = 0
        var totalBytes = 0L
        for (name in debs) {
            val outFile = File(offlineDir, name)
            assets.open("pkgs/$name").use { src ->
                FileOutputStream(outFile).use { dst -> src.copyTo(dst) }
            }
            totalBytes += outFile.length()
            copied++
        }
        // Copy manifest for debuggability — not used at install time.
        if (entries.contains("manifest.txt")) {
            assets.open("pkgs/manifest.txt").use { src ->
                FileOutputStream(File(offlineDir, "manifest.txt")).use { dst -> src.copyTo(dst) }
            }
        }
        Log.i(TAG, "Staged $copied bundled .debs (${totalBytes / 1024} KB) to $offlineDir")
    }

    /**
     * Copies bundled PAI release tarball from APK assets to $PREFIX/var/cache/pai/.
     * pai-setup.sh extracts this instead of git-cloning when present, saving ~23 MB
     * of download on first install. PAI doesn't change very often, so the bundled
     * snapshot stays fresh for most users.
     *
     * No-op if assets/pai-release.tar is absent — bundling is optional; pai-setup
     * falls back to `git clone` as before.
     */
    private fun stageBundledPai(prefixDir: File) {
        val assets = context.assets
        val tarName = "pai-release.tar"
        val versionName = "pai-release.version"
        val hasTar = try {
            assets.open(tarName).close(); true
        } catch (_: Exception) { false }
        if (!hasTar) {
            Log.i(TAG, "No bundled PAI release in assets — skipping PAI stage")
            return
        }
        val paiDir = File(prefixDir, "var/cache/pai")
        paiDir.mkdirs()
        assets.open(tarName).use { src ->
            FileOutputStream(File(paiDir, tarName)).use { dst -> src.copyTo(dst) }
        }
        try {
            assets.open(versionName).use { src ->
                FileOutputStream(File(paiDir, versionName)).use { dst -> src.copyTo(dst) }
            }
        } catch (_: Exception) { /* version file is optional */ }
        val sz = File(paiDir, tarName).length()
        Log.i(TAG, "Staged PAI release tarball (${sz / 1024} KB) to $paiDir")
    }

    /**
     * Extracts the bundled tsx + esbuild (aarch64) tarball directly into $PREFIX.
     * Populates:
     *   $PREFIX/bin/tsx
     *   $PREFIX/lib/node_modules/tsx/
     *   $PREFIX/lib/node_modules/get-tsconfig/
     *   $PREFIX/lib/node_modules/esbuild/
     *   $PREFIX/lib/node_modules/@esbuild/android-arm64/
     *
     * pai-setup.sh's `command -v tsx` check then passes and skips the network
     * `npm install -g tsx`. Saves ~10-30s on first install.
     *
     * No-op if assets/tsx-bundle.tar is absent.
     */
    private fun stageBundledTsx(prefixDir: File) {
        val assets = context.assets
        val tarName = "tsx-bundle.tar"
        val hasTar = try {
            assets.open(tarName).close(); true
        } catch (_: Exception) { false }
        if (!hasTar) {
            Log.i(TAG, "No bundled tsx in assets — skipping tsx stage")
            return
        }
        // Stage the tarball to disk, then invoke the real tar (from bootstrap)
        // to extract. We do NOT use java.util.zip / tar-in-java because the
        // tar preserves POSIX permissions we need (executable bits on the
        // esbuild native binary and the tsx shim script).
        val stageTar = File(prefixDir, "tmp/tsx-bundle.tar")
        stageTar.parentFile?.mkdirs()
        assets.open(tarName).use { src ->
            FileOutputStream(stageTar).use { dst -> src.copyTo(dst) }
        }
        val tarBin = File(prefixDir, "bin/tar")
        if (!tarBin.exists()) {
            Log.w(TAG, "tar not found in bootstrap — cannot extract tsx bundle")
            stageTar.delete()
            return
        }
        // tar is a dynamically-linked Termux binary; without LD_LIBRARY_PATH
        // it can't find libandroid-glob.so and fails with "CANNOT LINK
        // EXECUTABLE". The bootstrap ships all its libs under $PREFIX/lib.
        val pb = ProcessBuilder(tarBin.absolutePath, "-xf", stageTar.absolutePath,
                                "-C", prefixDir.absolutePath)
            .redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = File(prefixDir, "lib").absolutePath
        pb.environment()["PATH"] = File(prefixDir, "bin").absolutePath
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        stageTar.delete()
        if (code != 0) {
            Log.w(TAG, "tsx bundle extraction exited $code: ${out.take(500)}")
            return
        }
        // Ensure executables are flagged (tar should do this, belt-and-braces)
        File(prefixDir, "bin/tsx").setExecutable(true, false)
        File(prefixDir, "bin/tsx.mjs").setExecutable(true, false)
        Log.i(TAG, "Staged tsx bundle (extracted to $prefixDir)")
    }

    /**
     * Detects whether the bootstrap was pre-patched at build time by checking
     * a canonical file (/bin/apt is always a text script in Termux bootstrap)
     * for our package name vs the default com.termux. Cheap sample — reads one
     * file instead of scanning 349.
     */
    private fun isPrePatched(stagingDir: File): Boolean {
        val probe = File(stagingDir, "etc/apt/sources.list.d")
            .takeIf { it.exists() }
            ?.listFiles()?.firstOrNull()
            ?: File(stagingDir, "etc/termux/termux-bootstrap/termux-bootstrap-second-stage.sh")
            .takeIf { it.exists() }
            ?: File(stagingDir, "etc/profile")
        return try {
            if (!probe.exists()) return false
            val text = probe.readText(Charsets.UTF_8)
            val hasOurPkg = text.contains("/data/data/${context.packageName}/")
            val hasTermuxPkg = text.contains("/data/data/com.termux/")
            // Either it mentions our path (definitely patched) OR it contains
            // no path at all (trivially "patched"). Flag only when unambiguous.
            hasOurPkg && !hasTermuxPkg
        } catch (_: Exception) {
            false
        }
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
            DPkg::Options:: "--instdir=$p/tmp/dpkg-instdir";
            DPkg::Options:: "--admindir=$p/tmp/dpkg-instdir/var/lib/dpkg";
            DPkg::Options:: "--force-script-chrootless";
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

        // Create preferences.d directory to suppress warning
        File(prefixDir, "etc/apt/preferences.d").mkdirs()

        // Termux .deb packages contain paths like ./data/data/com.termux/files/usr/...
        // Create a symlink-based instdir so dpkg extracts to our prefix:
        //   instdir/data/data/com.termux → /data/data/<our.pkg>
        val instBase = File(prefixDir, "tmp/dpkg-instdir")
        val instDir = File(instBase, "data/data")
        instDir.mkdirs()
        val symlinkTarget = "/data/data/$appPkg"
        val symlinkFile = File(instDir, "com.termux")
        if (!symlinkFile.exists()) createSymlink(symlinkTarget, symlinkFile.absolutePath)

        // dpkg requires admindir inside instdir — create symlink inside instdir
        val instVarLib = File(instBase, "var/lib")
        instVarLib.mkdirs()
        val adminSymlink = File(instVarLib, "dpkg")
        if (!adminSymlink.exists()) createSymlink("$p/var/lib/dpkg", adminSymlink.absolutePath)

        // Import Termux GPG key and configure signed-by for package verification.
        // Termux apt-key uses compiled-in tmp path we can't write to, so we import
        // the key manually and use signed-by in sources.list instead of trusted=yes.
        val keyringDir = File(prefixDir, "etc/apt/keyrings")
        keyringDir.mkdirs()
        // Shared wrapper prelude: GPG key bootstrap + lazy `apt update`.
        //
        // Lazy-update design: first-run script drops a sentinel file at
        // `var/lib/apt/.needs-update`. When the user first runs `apt` or
        // `apt-get` (other than `update` itself), we transparently trigger
        // `apt update` and delete the sentinel. This keeps the critical
        // post-install path off the network while still giving the user
        // fresh indices the moment they ask apt to do anything.
        val wrapperPrelude = """
                #!/data/data/$appPkg/files/usr/bin/sh
                # Import Termux repo key if not present
                kr="$p/etc/apt/keyrings/termux.gpg"
                if [ ! -f "${'$'}kr" ] && command -v gpg >/dev/null 2>&1; then
                    gpg --batch --keyserver hkp://keyserver.ubuntu.com --recv-keys 5C316B38 2>/dev/null
                    gpg --batch --export 5C316B38 > "${'$'}kr" 2>/dev/null
                fi
                # Use signed-by if key exists, otherwise fall back to trusted=yes
                if [ -f "${'$'}kr" ]; then
                    sed -i '/^deb / { /\[signed-by=/! s|^deb |deb [signed-by='"${'$'}kr"'] | }' "$p/etc/apt/sources.list" 2>/dev/null
                else
                    sed -i '/^deb / { /\[trusted=yes\]/! s/^deb /deb [trusted=yes] / }' "$p/etc/apt/sources.list" 2>/dev/null
                fi
                # Lazy `apt update`: trigger once, on the user's first apt invocation
                # that isn't itself `update`. Skip when explicitly disabled.
                if [ -f "$p/var/lib/apt/.needs-update" ] && [ "${'$'}{PAI_SKIP_APT_UPDATE:-0}" != "1" ]; then
                    case "${'$'}1" in
                        update|--version|-v|"") : ;;
                        *)
                            echo "[apt] First-use refresh of package indices..." >&2
                            rm -f "$p/var/lib/apt/.needs-update"
                            WRAPPER_BIN_SELF="${'$'}0"
                            # Call the real binary directly to avoid wrapper recursion
                            "${'$'}{WRAPPER_BIN_SELF}.bin" update 2>&1 | tail -5 >&2 || true
                            ;;
                    esac
                fi
        """.trimIndent()

        val aptBin = File(prefixDir, "bin/apt")
        val aptReal = File(prefixDir, "bin/apt.bin")
        if (aptBin.exists() && !aptReal.exists()) {
            aptBin.renameTo(aptReal)
            aptBin.writeText(wrapperPrelude + "\n" + "exec \"$p/bin/apt.bin\" \"\$@\"\n")
            aptBin.setExecutable(true, false)
        }

        // Also wrap apt-get (pkg and some scripts use it)
        val aptGetBin = File(prefixDir, "bin/apt-get")
        val aptGetReal = File(prefixDir, "bin/apt-get.bin")
        if (aptGetBin.exists() && !aptGetReal.exists()) {
            aptGetBin.renameTo(aptGetReal)
            aptGetBin.writeText(wrapperPrelude + "\n" + "exec \"$p/bin/apt-get.bin\" \"\$@\"\n")
            aptGetBin.setExecutable(true, false)
        }

        // dpkg reads $HOME/.dpkg.cfg — create internal home with config
        // so dpkg doesn't try to read from external storage (FUSE permission issues)
        val internalHome = "/data/data/$appPkg/files/home"
        File(internalHome).mkdirs()
        File("$internalHome/.dpkg.cfg").writeText("# dpkg user config\n")

        // Wrap dpkg to:
        // 1. Use internal home for .dpkg.cfg
        // 2. Patch shebangs in .deb postinst scripts (they reference Termux paths)
        val dpkgBin = File(prefixDir, "bin/dpkg")
        val dpkgReal = File(prefixDir, "bin/dpkg.bin")
        if (dpkgBin.exists() && !dpkgReal.exists()) {
            dpkgBin.renameTo(dpkgReal)
        }
        // Always rewrite wrapper (patches .deb control scripts before dpkg processes them)
        if (dpkgReal.exists()) {
            val infoDir = "$p/var/lib/dpkg/info"
            dpkgBin.writeText("""
                #!/data/data/$appPkg/files/usr/bin/sh
                p=$p
                appPkg=$appPkg
                infoDir=$infoDir
                iHome=$internalHome

                # dpkg-deb -b creates a temp file for the control member. Without
                # TMPDIR set, it falls back to a compiled-in Termux path that
                # doesn't exist under our package name, and silently produces a
                # 0-byte .deb — which then fails at install with "unexpected end
                # of file". This caused nodejs-lts + its deps (c-ares, libicu,
                # libsqlite) to go uninstalled from our offline bundle. Forcing
                # TMPDIR to our prefix's tmp fixes it.
                export TMPDIR="${'$'}p/tmp"
                mkdir -p "${'$'}TMPDIR"

                # Each wrapper invocation gets its OWN temp subdirectory. Without
                # this, concurrent invocations (e.g. setup's dpkg -i running
                # alongside PAI-install's apt install) stomped each other's
                # patched .deb files during the end-of-wrapper cleanup — causing
                # "cannot access archive: No such file or directory" errors for
                # the outer invocation's packages. Scoping cleanup to our own
                # invocation dir makes the wrapper safe for parallel use.
                invocTmp=${'$'}(mktemp -d "${'$'}p/tmp/dpkg-invoc.XXXXXX")

                # Patch existing dpkg maintainer scripts
                for f in ${'$'}infoDir/*.postinst ${'$'}infoDir/*.preinst ${'$'}infoDir/*.prerm ${'$'}infoDir/*.postrm; do
                    [ -f "${'$'}f" ] && sed -i 's|/data/data/com.termux|/data/data/'"${'$'}appPkg"'|g' "${'$'}f" 2>/dev/null
                done

                # Rewrite .deb arguments to patch com.termux shebangs in control scripts
                new_args=""
                for arg in "${'$'}@"; do
                    if [ -f "${'$'}arg" ] && echo "${'$'}arg" | grep -qE '\.deb${'$'}'; then
                        pdir=${'$'}(mktemp -d "${'$'}invocTmp/deb-patch.XXXXXX")
                        "${'$'}p/bin/dpkg-deb" --raw-extract "${'$'}arg" "${'$'}pdir/pkg" 2>/dev/null
                        if [ -d "${'$'}pdir/pkg/DEBIAN" ]; then
                            # Patch shebangs in control scripts
                            for f in "${'$'}pdir/pkg/DEBIAN/"*; do
                                [ -f "${'$'}f" ] && sed -i 's|/data/data/com.termux|/data/data/'"${'$'}appPkg"'|g' "${'$'}f" 2>/dev/null
                                [ -f "${'$'}f" ] && chmod 0755 "${'$'}f" 2>/dev/null
                            done
                            # Patch shebangs/paths in data files (skip binaries with -I)
                            grep -rlI '/data/data/com.termux' "${'$'}pdir/pkg" 2>/dev/null | while read -r f; do
                                sed -i 's|/data/data/com.termux|/data/data/'"${'$'}appPkg"'|g' "${'$'}f" 2>/dev/null
                            done
                            "${'$'}p/bin/dpkg-deb" -b "${'$'}pdir/pkg" "${'$'}pdir/patched.deb" 2>/dev/null
                            # Use -s (non-empty) not -f; a zero-byte patched.deb
                            # would otherwise be chosen and fail at install.
                            if [ -s "${'$'}pdir/patched.deb" ]; then
                                new_args="${'$'}new_args ${'$'}pdir/patched.deb"
                            else
                                new_args="${'$'}new_args ${'$'}arg"
                            fi
                        else
                            new_args="${'$'}new_args ${'$'}arg"
                            rm -rf "${'$'}pdir"
                        fi
                    else
                        new_args="${'$'}new_args ${'$'}arg"
                    fi
                done

                # Add --admindir + --instdir if not already specified. Termux-compiled
                # dpkg.bin has `/data/data/com.termux/files/...` paths baked in; when
                # invoked directly (not via apt) with no overrides it tries to extract
                # `./data/data/com.termux/...` paths relative to `/` and gets
                # "Permission denied" on the read-only root. The instdir below is a
                # symlink farm (tmp/dpkg-instdir/data/data/com.termux -> our app data
                # dir) set up in createAptConfig(), so extraction redirects correctly.
                case "${'$'}new_args" in
                    *--admindir*) ;;
                    *) new_args="--admindir=${'$'}p/var/lib/dpkg --instdir=${'$'}p/tmp/dpkg-instdir --force-script-chrootless ${'$'}new_args" ;;
                esac

                HOME=${'$'}iHome "${'$'}p/bin/dpkg.bin" ${'$'}new_args
                ret=${'$'}?

                # Patch newly installed scripts
                for f in ${'$'}infoDir/*.postinst ${'$'}infoDir/*.preinst ${'$'}infoDir/*.prerm ${'$'}infoDir/*.postrm; do
                    [ -f "${'$'}f" ] && sed -i 's|/data/data/com.termux|/data/data/'"${'$'}appPkg"'|g' "${'$'}f" 2>/dev/null
                done

                # Clean up only THIS invocation's temp dir (not the global pool,
                # which could belong to a concurrent wrapper instance).
                rm -rf "${'$'}invocTmp" 2>/dev/null

                exit ${'$'}ret
            """.trimIndent() + "\n")
            dpkgBin.setExecutable(true, false)
        }

        // Suppress Termux second-stage bootstrap warning.
        // Our createAptConfig() already handles all package manager configuration,
        // so the second-stage script is redundant. Replace it with a no-op and
        // ensure the lock file exists so any caller exits silently.
        val secondStageDir = File(prefixDir, "etc/termux/termux-bootstrap/second-stage")
        if (secondStageDir.exists()) {
            val script = File(secondStageDir, "termux-bootstrap-second-stage.sh")
            script.writeText("#!/data/data/$appPkg/files/usr/bin/sh\n# Handled by PAI bootstrap\nexit 0\n")
            script.setExecutable(true, false)
            val lock = File(secondStageDir, "termux-bootstrap-second-stage.sh.lock")
            if (!lock.exists()) createSymlink("termux-bootstrap-second-stage.sh", lock.absolutePath)
        }

        Log.i(TAG, "Created apt.conf redirecting to $p")
    }

    /**
     * Creates a first-run script in profile.d/ that installs APK-bundled .debs
     * offline. Sourced by $PREFIX/etc/profile on first login, then deletes itself.
     *
     * Design goals: keep this path minimal. No `apt update`, no `apt upgrade`,
     * no `apt install`. The bundled .debs cover all PAI prerequisites; anything
     * the user wants to install later via `pkg`/`apt` triggers a lazy
     * `apt update` via a wrapper (see [createAptConfig]). This shaves 15-25s
     * off the first-run critical path.
     */
    private fun createFirstRunUpdate() {
        val appPkg = context.packageName
        val p = "/data/data/$appPkg/files/usr"
        val profileDir = File("$p/etc/profile.d")
        profileDir.mkdirs()
        val offlineDir = "$p/var/cache/apt/archives/offline"
        val script = File(profileDir, "pai-first-run.sh")
        script.writeText("""
            # Self-delete first to prevent concurrent runs
            rm -f "$p/etc/profile.d/pai-first-run.sh"

            # Install APK-bundled .debs offline (no network needed).
            # Bundled: nodejs-lts, git, proot, libicu, libsqlite, libexpat,
            # libtalloc, c-ares. Everything PAI prerequisites need.
            if ls "$offlineDir"/*.deb >/dev/null 2>&1; then
                echo -e "\033[1;36m[PAI] Installing bundled packages (offline)...\033[0m"
                # --force-confold: keep any existing config files untouched
                dpkg -i --force-confold "$offlineDir"/*.deb 2>&1 || true
                # Free APK-sized cache now that packages are installed
                rm -rf "$offlineDir"
            fi

            # Mark that apt has never been updated on this install; the apt
            # wrapper (see createAptConfig) auto-runs `apt update` on first use.
            touch "$p/var/lib/apt/.needs-update"

            echo -e "\033[1;32m[PAI] Ready. Run 'pai-setup' to install PAI.\033[0m"
        """.trimIndent() + "\n")
        Log.i(TAG, "Created first-run update script in profile.d/")
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

    private fun createSymlink(target: String, linkPath: String) {
        try {
            android.system.Os.symlink(target, linkPath)
        } catch (e: Exception) {
            Runtime.getRuntime().exec(arrayOf("ln", "-sf", target, linkPath)).waitFor()
        }
    }

    private fun post(action: () -> Unit) {
        mainHandler.post(action)
    }
}
