package kle.ljubitje.pai

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.util.Log
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

class MainActivity : ComponentActivity(), TerminalViewClient, TerminalSessionClient {

    companion object {
        const val EXTRA_RUN_COMMAND = "run_command"
    }

    private lateinit var terminalView: TerminalView
    private var session: TerminalSession? = null
    private var sessionRestartCount = 0
    private var lastSessionStart = 0L
    private var pendingCommand: String? = null
    private var urlFileObserver: FileObserver? = null

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val cmd = intent?.getStringExtra("cmd") ?: return
            session?.write(cmd + "\n")
        }
    }

    private val PREFIX: String by lazy {
        applicationContext.filesDir.absolutePath + "/usr"
    }

    private val HOME: String by lazy {
        Environment.getExternalStorageDirectory().absolutePath
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val filter = IntentFilter("kle.ljubitje.pai.RUN_COMMAND")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }

        // Check for command to run after terminal starts
        pendingCommand = intent?.getStringExtra(EXTRA_RUN_COMMAND)

        setupFilesystem()

        terminalView = TerminalView(this, null)
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        terminalView.setTextSize(prefs.getInt(SettingsActivity.KEY_FONT_SIZE, SettingsActivity.DEFAULT_FONT_SIZE))

        val extraKeysView = ExtraKeysView(this, terminalView)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        layout.addView(terminalView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        layout.addView(extraKeysView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        setContentView(layout)

        startTerminalSession()
        startUrlFileObserver()
    }

    override fun onResume() {
        super.onResume()
        // Re-apply font size in case it was changed in settings
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        terminalView.setTextSize(prefs.getInt(SettingsActivity.KEY_FONT_SIZE, SettingsActivity.DEFAULT_FONT_SIZE))
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(commandReceiver)
        urlFileObserver?.stopWatching()
    }

    private fun setupFilesystem() {
        listOf("$PREFIX/bin", "$PREFIX/lib", "$PREFIX/etc", "$PREFIX/tmp").forEach {
            File(it).mkdirs()
        }
        deployShellConfigs()
        migrateDpkgWrapper()
        deployNodeFix()
        deployResolvConf()
        deployUrlOpener()
    }

    /** Upgrade dpkg wrapper to deb-patching version if stale. */
    private fun migrateDpkgWrapper() {
        try {
            val dpkgBin = File("$PREFIX/bin/dpkg")
            val dpkgReal = File("$PREFIX/bin/dpkg.bin")
            if (!dpkgBin.exists() || !dpkgReal.exists()) return
            val current = dpkgBin.readText()
            if (current.contains("dpkg-deb") && current.contains("--admindir") && current.contains("grep -rlI")) return

            val appPkg = packageName
            val p = "/data/data/$appPkg/files/usr"
            val internalHome = "/data/data/$appPkg/files/home"
            val infoDir = "$p/var/lib/dpkg/info"
            dpkgBin.writeText("""
                #!/data/data/$appPkg/files/usr/bin/sh
                p=$p
                appPkg=$appPkg
                infoDir=$infoDir
                iHome=$internalHome

                # Patch existing dpkg maintainer scripts
                for f in ${'$'}infoDir/*.postinst ${'$'}infoDir/*.preinst ${'$'}infoDir/*.prerm ${'$'}infoDir/*.postrm; do
                    [ -f "${'$'}f" ] && sed -i 's|/data/data/com.termux|/data/data/'"${'$'}appPkg"'|g' "${'$'}f" 2>/dev/null
                done

                # Rewrite .deb arguments to patch com.termux shebangs in control scripts
                new_args=""
                for arg in "${'$'}@"; do
                    if [ -f "${'$'}arg" ] && echo "${'$'}arg" | grep -qE '\.deb${'$'}'; then
                        pdir=${'$'}(mktemp -d "${'$'}p/tmp/deb-patch.XXXXXX")
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
                            if [ -f "${'$'}pdir/patched.deb" ]; then
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

                # Add --admindir if not already specified (dpkg.bin has hardcoded com.termux paths)
                case "${'$'}new_args" in
                    *--admindir*) ;;
                    *) new_args="--admindir=${'$'}p/var/lib/dpkg --force-script-chrootless ${'$'}new_args" ;;
                esac

                HOME=${'$'}iHome "${'$'}p/bin/dpkg.bin" ${'$'}new_args
                ret=${'$'}?

                # Patch newly installed scripts
                for f in ${'$'}infoDir/*.postinst ${'$'}infoDir/*.preinst ${'$'}infoDir/*.prerm ${'$'}infoDir/*.postrm; do
                    [ -f "${'$'}f" ] && sed -i 's|/data/data/com.termux|/data/data/'"${'$'}appPkg"'|g' "${'$'}f" 2>/dev/null
                done

                # Clean up patched .deb temp dirs
                rm -rf "${'$'}p/tmp/deb-patch."* 2>/dev/null

                exit ${'$'}ret
            """.trimIndent() + "\n")
            dpkgBin.setExecutable(true, false)
            Log.i("MainActivity", "Migrated dpkg wrapper to deb-patching version")
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to migrate dpkg wrapper: ${e.message}")
        }
    }

    /** Deploy shell config files to app internal data, updating stale versions. */
    private fun deployShellConfigs() {
        val dataHome = applicationContext.filesDir.absolutePath
        mapOf("bashrc" to ".bashrc", "profile" to ".profile").forEach { (asset, filename) ->
            val dest = File(dataHome, filename)
            try {
                val bundled = assets.open(asset).bufferedReader().use { it.readText() }
                if (dest.exists()) {
                    val bundledVersion = Regex("""\(v(\d+)\)""").find(bundled.lineSequence().first())?.groupValues?.get(1) ?: return@forEach
                    val existingFirst = dest.readText().lineSequence().first()
                    val existingVersion = Regex("""\(v(\d+)\)""").find(existingFirst)?.groupValues?.get(1) ?: "0"
                    if (existingVersion.toInt() >= bundledVersion.toInt()) return@forEach
                }
                dest.writeText(bundled)
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to deploy $filename: ${e.message}")
            }
        }
        // Deploy thin wrapper dotfiles to $HOME (sdcard) so bash can find them.
        // Termux bash has /data/data/com.termux/... compiled in as /etc/profile path,
        // which doesn't exist under our package name, so bash never sources /etc/profile.
        // These wrappers redirect to the full configs in internal storage ($PAI_DATA_HOME).
        deployHomeDotfiles()
        // Deploy profile.d script so login shell sources configs from internal storage
        deployProfileDScript()
        deployPaiSetup()
    }

    /** Deploy thin wrapper dotfiles to $HOME so bash login/non-login shells find them. */
    private fun deployHomeDotfiles() {
        val wrapper = """
            # PAI — source configs from internal storage (v1)
            [ -n "${'$'}PAI_DATA_HOME" ] && [ -f "${'$'}PAI_DATA_HOME/.bashrc" ] && . "${'$'}PAI_DATA_HOME/.bashrc"
        """.trimIndent() + "\n"
        // .bash_profile for login shells (bash checks this before .profile)
        try {
            File(HOME, ".bash_profile").writeText(wrapper)
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to deploy .bash_profile: ${e.message}")
        }
        // .bashrc for interactive non-login shells (e.g. running 'bash' inside terminal)
        try {
            File(HOME, ".bashrc").writeText(wrapper)
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to deploy .bashrc: ${e.message}")
        }
        // Clean up old full-size .profile from sdcard (superseded by .bash_profile wrapper)
        try {
            val oldProfile = File(HOME, ".profile")
            if (oldProfile.exists()) oldProfile.delete()
        } catch (_: Exception) {}
    }

    /** Ensure /etc/profile sources .bashrc/.profile from app internal storage. */
    private fun deployProfileDScript() {
        try {
            val profileDir = File("$PREFIX/etc/profile.d")
            profileDir.mkdirs()
            val dest = File(profileDir, "pai-user.sh")
            val scriptContent = """
                # Source PAI user configs from app internal storage
                if [ -n "${'$'}PAI_DATA_HOME" ]; then
                    [ -f "${'$'}PAI_DATA_HOME/.profile" ] && . "${'$'}PAI_DATA_HOME/.profile"
                    [ -f "${'$'}PAI_DATA_HOME/.bashrc" ] && . "${'$'}PAI_DATA_HOME/.bashrc"
                fi
            """.trimIndent() + "\n"
            if (!dest.exists()) dest.writeText(scriptContent)

            // Ensure /etc/profile sources profile.d scripts (Termux's may not)
            val profile = File("$PREFIX/etc/profile")
            val marker = "# PAI: source profile.d"
            if (profile.exists() && !profile.readText().contains(marker)) {
                profile.appendText("\n$marker\nfor _f in ${'$'}PREFIX/etc/profile.d/*.sh; do [ -r \"${'$'}_f\" ] && . \"${'$'}_f\"; done\nunset _f\n")
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to deploy profile.d: ${e.message}")
        }
    }

    /** Deploy Node.js preload fix for com.termux hardcoded paths. */
    private fun deployNodeFix() {
        try {
            val dest = File("$PREFIX/lib", "node-termux-fix.js")
            val content = assets.open("node-termux-fix.js").bufferedReader().use { it.readText() }
            // Always redeploy to pick up updates (e.g. DNS fix)
            dest.writeText(content)
            Log.i("MainActivity", "Deployed node-termux-fix.js")
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to deploy node-termux-fix.js: ${e.message}")
        }
    }

    /** Create resolv.conf so c-ares (used by Node.js) can find DNS servers. */
    private fun deployResolvConf() {
        try {
            val dest = File("$PREFIX/etc", "resolv.conf")
            if (dest.exists()) return
            dest.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n")
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to deploy resolv.conf: ${e.message}")
        }
    }

    /** Deploy xdg-open shim so CLI tools (e.g. Claude Code OAuth) can open URLs in a browser. */
    private fun deployUrlOpener() {
        try {
            val content = assets.open("xdg-open.sh").bufferedReader().use { it.readText() }
            // Deploy xdg-open — delete first in case it's a symlink (e.g. to termux-open)
            for (name in listOf("xdg-open", "open", "termux-open", "termux-open-url")) {
                val dest = File("$PREFIX/bin", name)
                dest.delete()
                dest.writeText(content)
                dest.setExecutable(true, false)
            }
            Log.i("MainActivity", "Deployed URL opener shims")
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to deploy xdg-open: ${e.message}")
        }
    }

    /** Watch $PREFIX/tmp/.open-url for URL open requests from shell scripts (e.g. xdg-open shim). */
    private fun startUrlFileObserver() {
        val urlFile = File("$PREFIX/tmp/.open-url")
        val tmpDir = File("$PREFIX/tmp")
        tmpDir.mkdirs()

        urlFileObserver = object : FileObserver(tmpDir, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path != ".open-url") return
                try {
                    val url = urlFile.readText().trim()
                    if (url.isNotEmpty()) {
                        urlFile.delete()
                        runOnUiThread {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(intent)
                                Log.i("MainActivity", "Opened URL: $url")
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Failed to open URL: $url — ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error reading URL file: ${e.message}")
                }
            }
        }
        urlFileObserver?.startWatching()
    }

    /** Deploy pai-setup script to $PREFIX/bin/. */
    private fun deployPaiSetup() {
        try {
            val setupDest = File("$PREFIX/bin", "pai-setup")
            if (!setupDest.exists()) {
                val content = assets.open("pai-setup.sh").bufferedReader().use { it.readText() }
                setupDest.writeText(content)
                setupDest.setExecutable(true, false)
                Log.i("MainActivity", "pai-setup deployed: ${setupDest.length()} bytes")
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to deploy pai-setup: ${e.message}")
        }
    }

    private fun startTerminalSession() {
        lastSessionStart = System.currentTimeMillis()
        val shell = findShell()
        val dataHome = applicationContext.filesDir.absolutePath
        val env = arrayOf(
            "HOME=$HOME",
            "PAI_DIR=$HOME/.claude",
            "PAI_DATA_HOME=$dataHome",
            "PREFIX=$PREFIX",
            "SHELL=$shell",
            "TERM=xterm-256color",
            "LANG=en_US.UTF-8",
            "BUN_INSTALL=$PREFIX",
            "PATH=$PREFIX/bin:/system/bin:/system/xbin",
            "TERMUX_VERSION=PAI",
            "COLORTERM=truecolor",
            "TMPDIR=$PREFIX/tmp",
            "LD_LIBRARY_PATH=$PREFIX/lib",
            "TERMUX_APP_PACKAGE_MANAGER=apt",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
            "APT_CONFIG=$PREFIX/etc/apt/apt.conf",
            "CURL_CA_BUNDLE=$PREFIX/etc/tls/cert.pem",
            "SSL_CERT_FILE=$PREFIX/etc/tls/cert.pem",
            "GIT_EXEC_PATH=$PREFIX/libexec/git-core",
            "GIT_TEMPLATE_DIR=$PREFIX/share/git-core/templates",
            "GIT_SSL_CAINFO=$PREFIX/etc/tls/cert.pem",
            "GIT_SSH_COMMAND=ssh -i $dataHome/.ssh/id_ed25519 -o UserKnownHostsFile=$dataHome/.ssh/known_hosts -o StrictHostKeyChecking=accept-new",
            "NODE_OPTIONS=--require=$PREFIX/lib/node-termux-fix.js",
            // Redirect dot-folders from sdcard root to app data dir
            "NPM_CONFIG_CACHE=$dataHome/.npm",
            "NPM_CONFIG_USERCONFIG=$dataHome/.npmrc",
            "XDG_CACHE_HOME=$dataHome/.cache",
            "XDG_CONFIG_HOME=$dataHome/.config",
            "XDG_DATA_HOME=$dataHome/.local/share",
            "XDG_STATE_HOME=$dataHome/.local/state",
            "HISTFILE=$dataHome/.bash_history",
            "NODE_REPL_HISTORY=$dataHome/.node_repl_history",
            "LESSHISTFILE=$dataHome/.lesshst",
            "GIT_CONFIG_GLOBAL=$dataHome/.gitconfig",
            "CURL_HOME=$dataHome",
            "WGETHSTS=$dataHome/.wget-hsts",
            "PYTHON_HISTORY=$dataHome/.python_history",
            "SQLITE_HISTORY=$dataHome/.sqlite_history",
        )

        session = TerminalSession(
            shell,
            HOME,
            arrayOf("-"),
            env,
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
            this
        )

        terminalView.attachSession(session)
        terminalView.setTerminalViewClient(this)

        // Run pending command after shell starts
        if (pendingCommand != null) {
            val cmd = pendingCommand
            pendingCommand = null
            terminalView.postDelayed({
                session?.write("$cmd\n")
            }, 1000)
        }
    }

    private fun findShell(): String {
        val candidates = listOf(
            "$PREFIX/bin/bash",
            "$PREFIX/bin/sh",
            "/system/bin/sh"
        )
        return candidates.first { File(it).exists() }
    }

    // ── TerminalViewClient ──

    override fun onScale(scale: Float): Float = scale

    override fun onSingleTapUp(e: MotionEvent?) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(terminalView, 0)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() {}

    // ── TerminalSessionClient ──

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (session == null || finishedSession !== session) return

        val now = System.currentTimeMillis()
        if (now - lastSessionStart < 2000) {
            sessionRestartCount++
        } else {
            sessionRestartCount = 0
        }

        if (sessionRestartCount > 3) {
            return
        }

        startTerminalSession()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PAI", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this).toString()
            session?.emulator?.paste(text)
        }
    }

    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    // ── Logging ──

    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}
