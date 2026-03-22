package kle.ljubitje.pai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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

    private lateinit var terminalView: TerminalView
    private var session: TerminalSession? = null
    private var pendingPermission = false
    private var bootstrapping = false
    private var sessionRestartCount = 0
    private var lastSessionStart = 0L

    private val PREFIX: String by lazy {
        applicationContext.filesDir.absolutePath + "/usr"
    }

    private val HOME: String by lazy {
        Environment.getExternalStorageDirectory().absolutePath
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
            1f // weight=1 takes remaining space
        ))
        layout.addView(extraKeysView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        setContentView(layout)

        // Request all-files access if not granted — defer terminal start until onResume
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            pendingPermission = true
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } else {
            initTerminal()
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingPermission) {
            pendingPermission = false
            initTerminal()
        }
    }

    private fun initTerminal() {
        if (BootstrapInstaller.isBootstrapped(PREFIX)) {
            startTerminalSession()
        } else {
            // Start a basic shell first so the user sees progress output
            startTerminalSession()
            runBootstrap()
        }
    }

    private fun runBootstrap() {
        if (bootstrapping) return
        bootstrapping = true

        BootstrapInstaller(
            context = applicationContext,
            prefix = PREFIX,
            onProgress = { message ->
                writeToTerminal("\r\n\u001b[1;36m[PAI] $message\u001b[0m\r\n")
            },
            onComplete = { success ->
                bootstrapping = false
                if (success) {
                    writeToTerminal("\r\n\u001b[1;32m[PAI] Bootstrap complete! Restarting shell with bash...\u001b[0m\r\n")
                    terminalView.postDelayed({
                        restartWithBash()
                    }, 1500)
                } else {
                    writeToTerminal("\r\n\u001b[1;31m[PAI] Bootstrap failed. Check your internet connection and restart the app.\u001b[0m\r\n")
                }
            }
        ).install()
    }

    private fun restartWithBash() {
        session?.finishIfRunning()
        startTerminalSession()
    }

    /** Write text directly to the terminal display (not through the shell). */
    private fun writeToTerminal(text: String) {
        val emulator = session?.emulator ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
        emulator.append(bytes, bytes.size)
        terminalView.onScreenUpdated()
    }

    private fun setupFilesystem() {
        listOf("$PREFIX/bin", "$PREFIX/lib", "$PREFIX/etc", "$PREFIX/tmp").forEach {
            File(it).mkdirs()
        }
        deployBashrc()
    }

    /** Deploy .bashrc to HOME if not already present. */
    private fun deployBashrc() {
        val bashrc = File(HOME, ".bashrc")
        if (bashrc.exists()) return
        try {
            assets.open("bashrc").bufferedReader().use { reader ->
                bashrc.writeText(reader.readText())
            }
        } catch (_: Exception) {
            // Asset not found or HOME not writable — non-fatal
        }
    }

    private fun startTerminalSession() {
        lastSessionStart = System.currentTimeMillis()
        val shell = findShell()
        val env = arrayOf(
            "HOME=$HOME",
            "PREFIX=$PREFIX",
            "TERM=xterm-256color",
            "LANG=en_US.UTF-8",
            "PATH=$PREFIX/bin:/system/bin:/system/xbin",
            "TERMUX_VERSION=PAI",
            "COLORTERM=truecolor",
            "TMPDIR=$PREFIX/tmp",
            "LD_LIBRARY_PATH=$PREFIX/lib",
            "TERMUX_APP_PACKAGE_MANAGER=apt",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
            "APT_CONFIG=$PREFIX/etc/apt/apt.conf"
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
        val now = System.currentTimeMillis()
        if (now - lastSessionStart < 2000) {
            sessionRestartCount++
        } else {
            sessionRestartCount = 0
        }

        if (sessionRestartCount > 3) {
            writeToTerminal("\r\n\u001b[1;31m[PAI] Shell keeps crashing. Check logcat for errors.\u001b[0m\r\n")
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

    // ── Logging (shared by both interfaces) ──

    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}
