package kle.ljubitje.pai

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
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
        terminalView.setTextSize(24)

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
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(commandReceiver)
    }

    private fun setupFilesystem() {
        listOf("$PREFIX/bin", "$PREFIX/lib", "$PREFIX/etc", "$PREFIX/tmp").forEach {
            File(it).mkdirs()
        }
        deployShellConfigs()
        migrateDpkgWrapper()
        deployNodeFix()
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

    /** Deploy shell config files to HOME, updating stale versions. */
    private fun deployShellConfigs() {
        mapOf("bashrc" to ".bashrc", "profile" to ".profile").forEach { (asset, filename) ->
            val dest = File(HOME, filename)
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

        deployPaiSetup()
    }

    /** Deploy Node.js preload fix for com.termux hardcoded paths. */
    private fun deployNodeFix() {
        try {
            val dest = File("$PREFIX/lib", "node-termux-fix.js")
            if (!dest.exists()) {
                val content = assets.open("node-termux-fix.js").bufferedReader().use { it.readText() }
                dest.writeText(content)
                Log.i("MainActivity", "Deployed node-termux-fix.js")
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to deploy node-termux-fix.js: ${e.message}")
        }
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
        val env = arrayOf(
            "HOME=$HOME",
            "PAI_DIR=$HOME/.claude",
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
            "NODE_OPTIONS=--require=$PREFIX/lib/node-termux-fix.js"
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
