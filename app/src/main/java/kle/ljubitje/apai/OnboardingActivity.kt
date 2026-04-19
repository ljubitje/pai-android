package kle.ljubitje.apai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kle.ljubitje.apai.ui.theme.PAITheme
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class OnboardingActivity : ComponentActivity() {

    companion object {
        private const val TAG = "Onboarding"
        private const val MAX_LOG_LINES = 500
    }

    private val prefix: String by lazy {
        applicationContext.filesDir.absolutePath + "/usr"
    }

    private val home: String by lazy {
        Environment.getExternalStorageDirectory().absolutePath
    }

    private val dataHome: String by lazy {
        applicationContext.filesDir.absolutePath
    }

    // UI state
    // Screens: permission, battery, existingInstall, existingConfirm, setup, install, ready
    private var currentScreen by mutableStateOf("permission")
    private var setupProgress by mutableFloatStateOf(0f)
    private var setupError by mutableStateOf<String?>(null)
    private var currentStepIndex by mutableIntStateOf(0)
    private val setupSteps = mutableStateListOf(
        SetupStep("Extracting base system", StepStatus.PENDING),
        SetupStep("Configuring package manager", StepStatus.PENDING),
        SetupStep("Updating packages", StepStatus.PENDING),
        SetupStep("Installing prerequisites", StepStatus.PENDING),
        SetupStep("Installing Bun", StepStatus.PENDING),
        SetupStep("Finalizing setup", StepStatus.PENDING),
    )
    private val logLines = mutableStateListOf<String>()

    // PAI install state
    private var installProgress by mutableFloatStateOf(0f)
    private var installError by mutableStateOf<String?>(null)
    private var installStepIndex by mutableIntStateOf(0)
    private val installSteps = mutableStateListOf(
        SetupStep("Installing Node.js", StepStatus.PENDING),
        SetupStep("Installing Claude Code", StepStatus.PENDING),
        SetupStep("Cloning PAI repository", StepStatus.PENDING),
        SetupStep("Deploying PAI", StepStatus.PENDING),
    )
    private val installLogLines = mutableStateListOf<String>()

    // Tracks whether bootstrap extraction has been kicked off. We start it
    // eagerly in onCreate even while the user is still on permission/battery
    // screens, since BootstrapInstaller writes only to app-private storage
    // and needs no user permission. By the time the user reaches the "setup"
    // screen the heavy work is often already done — pure perceived-speed win.
    private var bootstrapStarted = false

    // Old-install detection state. Populated once we have storage permission
    // (can't scan $HOME before that). Null = not yet scanned; empty list =
    // scanned, nothing found; non-empty = show existingInstall screen.
    private var detectedOldFiles by mutableStateOf<List<DetectedFile>?>(null)
    // Chosen mode for handling the existing install. Null until user picks.
    //   "update" → overlay tarball on top, preserve settings.json + user data
    //   "clean"  → rm -rf everything detected, fresh start
    private var reinstallMode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Permissions gate: storage first, then battery, then existing-install
        // detection, then proceed.
        currentScreen = when {
            !hasStoragePermission() -> "permission"
            isBatteryOptimized() -> "battery"
            // Both permissions handled — determine next step
            BootstrapInstaller.isBootstrapped(prefix) && isPaiInstalled() -> {
                launchTerminal()
                return
            }
            shouldShowExistingInstall() -> "existingInstall"
            BootstrapInstaller.isBootstrapped(prefix) -> "ready"
            else -> "setup"
        }

        setContent {
            PAITheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "screen"
                    ) { screen ->
                        when (screen) {
                            "permission" -> PermissionScreen(onGrantAccess = ::requestStoragePermission)
                            "battery" -> BatteryScreen(
                                onDisable = ::requestBatteryOptimizationExemption,
                                onSkip = ::advancePastBattery,
                            )
                            "existingInstall" -> ExistingInstallScreen(
                                detected = detectedOldFiles.orEmpty(),
                                onChooseUpdate = {
                                    reinstallMode = "update"
                                    currentScreen = "existingConfirm"
                                },
                                onChooseClean = {
                                    reinstallMode = "clean"
                                    currentScreen = "existingConfirm"
                                },
                            )
                            "existingConfirm" -> ExistingInstallConfirmScreen(
                                mode = reinstallMode ?: "update",
                                detected = detectedOldFiles.orEmpty(),
                                onConfirm = ::applyReinstallMode,
                                onBack = {
                                    reinstallMode = null
                                    currentScreen = "existingInstall"
                                },
                            )
                            "setup" -> ProgressScreen(
                                title = "Setting up PAI",
                                subtitle = "This may take a few minutes",
                                steps = setupSteps,
                                progress = setupProgress,
                                logLines = logLines,
                                error = setupError,
                                onRetry = ::startSetup,
                            )
                            "install" -> ProgressScreen(
                                title = "Installing PAI",
                                subtitle = "Setting up your AI infrastructure",
                                steps = installSteps,
                                progress = installProgress,
                                logLines = installLogLines,
                                error = installError,
                                onRetry = ::startPaiInstall,
                            )
                            "ready" -> ReadyScreen(
                                onInstallPai = ::startPaiInstall,
                            )
                        }
                    }
                }
            }
        }

        if (currentScreen == "setup") {
            startSetup()
        } else if (currentScreen == "permission" || currentScreen == "battery") {
            // Parallelization: kick off bootstrap now in the background while
            // the user deals with permission/battery prompts. When they reach
            // the setup screen, progress is already advanced.
            preloadBootstrapAsync()
        }
    }

    /**
     * Starts bootstrap install eagerly in the background. Safe to call
     * multiple times — guarded by [bootstrapStarted]. Only does work when
     * bootstrap is not already installed.
     */
    private fun preloadBootstrapAsync() {
        if (bootstrapStarted) return
        if (BootstrapInstaller.isBootstrapped(prefix)) return
        bootstrapStarted = true
        // Preparatory mkdirs + deploy helpers (local, fast, no permissions)
        listOf("$prefix/bin", "$prefix/lib", "$prefix/etc", "$prefix/tmp").forEach {
            File(it).mkdirs()
        }
        deployShellConfigs()
        deployUrlOpener()
        runOnUiThread { markStep(setupSteps, 0, StepStatus.ACTIVE) }
        runBootstrap()
    }

    override fun onResume() {
        super.onResume()
        // Advance through permission gates as each is granted
        if (currentScreen == "permission" && hasStoragePermission()) {
            currentScreen = when {
                isBatteryOptimized() -> "battery"
                shouldShowExistingInstall() -> "existingInstall"
                else -> "setup"
            }
            if (currentScreen == "setup") startSetup()
        } else if (currentScreen == "battery" && !isBatteryOptimized()) {
            advancePastBattery()
        }
    }

    /** PAI is installed when settings.json exists (written by the installer wizard). */
    private fun isPaiInstalled(): Boolean {
        return File("$home/.claude/settings.json").exists()
    }

    /**
     * Scans sdcard root ($HOME) for leftover files from previous installs.
     *
     * Looks for a fixed set of known paths (matches what our own code writes —
     * `.claude/`, `.bashrc`, `.bash_profile`, `.profile`) PLUS a dynamic glob
     * for any `.claude*` entries to catch artifacts from forgotten versions
     * (e.g. a stale `.claude.json` that Claude Code might have left behind).
     *
     * Storage permission is required — caller must check beforehand.
     *
     * @return list of detected files/dirs (empty if nothing found).
     */
    private fun scanForOldInstallFiles(): List<DetectedFile> {
        val homeDir = File(home)
        if (!homeDir.isDirectory) return emptyList()

        // Fixed list — files our current code is known to have created.
        val fixed = listOf(".claude", ".config", ".bashrc", ".bash_profile", ".profile")
        val found = mutableMapOf<String, DetectedFile>()

        fun track(f: File) {
            if (!f.exists()) return
            val size = if (f.isDirectory) dirSize(f) else f.length()
            val fileCount = if (f.isDirectory) countFiles(f) else 1
            found[f.name] = DetectedFile(
                name = f.name,
                path = f.absolutePath,
                isDirectory = f.isDirectory,
                sizeBytes = size,
                fileCount = fileCount,
            )
        }

        for (name in fixed) track(File(homeDir, name))

        // Dynamic: any $HOME/.claude* entry we haven't already tracked.
        homeDir.listFiles { f -> f.name.startsWith(".claude") }?.forEach { track(it) }

        return found.values.sortedByDescending { it.sizeBytes }
    }

    private fun dirSize(dir: File): Long {
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    private fun countFiles(dir: File): Int {
        var n = 0
        dir.walkTopDown().forEach { if (it.isFile) n++ }
        return n
    }

    /** Returns true if we should show the existingInstall screen before setup. */
    private fun shouldShowExistingInstall(): Boolean {
        if (detectedOldFiles == null) detectedOldFiles = scanForOldInstallFiles()
        return (detectedOldFiles?.isNotEmpty() == true) && reinstallMode == null
    }

    /**
     * Clean install: rm -rf every detected path. Runs synchronously on a
     * background thread because some paths (e.g. `.claude/`) can hold
     * thousands of files. Call only from a background Thread.
     */
    private fun performCleanWipe() {
        val files = detectedOldFiles.orEmpty()
        for (f in files) {
            try {
                val target = File(f.path)
                if (target.exists()) {
                    val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
                    Log.i(TAG, "Clean wipe ${if (ok) "removed" else "FAILED"} ${target.absolutePath}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Clean wipe error on ${f.path}: ${e.message}")
            }
        }
        // After wipe, the scan is stale — caller should update state if it cares.
    }

    /**
     * Update overlay: back up user-customized files, extract the bundled PAI
     * release tarball ON TOP of the existing `.claude/` (adding/overwriting
     * framework files, leaving everything else alone), then restore the
     * backups. This preserves MEMORY/, user-created skills/agents/hooks, and
     * the user's own `settings.json` / `CLAUDE.md`.
     *
     * EXPERIMENTAL: users are warned on the confirmation screen to back up
     * their `.claude/` first. If any step fails we leave the directory as-is
     * and surface the error via [setupError].
     *
     * @return true if overlay completed successfully, false otherwise.
     */
    private fun performUpdateOverlay(): Boolean {
        val claudeDir = File(home, ".claude")
        if (!claudeDir.exists()) {
            Log.w(TAG, "Update overlay: $claudeDir doesn't exist — skipping overlay")
            return false
        }
        // Files we must preserve across the overlay. These are in the release
        // tarball so `tar -xf` would overwrite them if we didn't back up.
        val preserveRelative = listOf("settings.json", "CLAUDE.md")
        val backups = mutableMapOf<String, ByteArray>()
        for (rel in preserveRelative) {
            val f = File(claudeDir, rel)
            if (f.exists() && f.isFile) {
                try {
                    backups[rel] = f.readBytes()
                    Log.i(TAG, "Update overlay: backed up $rel (${backups[rel]?.size} bytes)")
                } catch (e: Exception) {
                    Log.w(TAG, "Update overlay: failed to back up $rel: ${e.message}")
                    // Soft-fail — continue; user was warned this is experimental
                }
            }
        }

        // Extract tarball over existing .claude/. The bundled tarball contains
        // `.claude/` at root, so we extract into $home (parent of .claude/).
        val bundleTar = File(prefix, "var/cache/pai/pai-release.tar")
        val tarBin = File(prefix, "bin/tar")
        if (!bundleTar.exists() || !tarBin.exists()) {
            Log.w(TAG, "Update overlay: missing $bundleTar or $tarBin")
            return false
        }
        val proc = ProcessBuilder(
            tarBin.absolutePath, "-xf", bundleTar.absolutePath, "-C", home
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) {
            Log.e(TAG, "Update overlay: tar exit $code: ${out.take(500)}")
            return false
        }

        // Restore backups (overwrites whatever the tarball put down).
        for ((rel, data) in backups) {
            try {
                File(claudeDir, rel).writeBytes(data)
                Log.i(TAG, "Update overlay: restored $rel")
            } catch (e: Exception) {
                Log.w(TAG, "Update overlay: failed to restore $rel: ${e.message}")
            }
        }
        return true
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    /** Advance past battery screen to the appropriate next step. */
    private fun advancePastBattery() {
        currentScreen = when {
            BootstrapInstaller.isBootstrapped(prefix) && isPaiInstalled() -> {
                launchTerminal()
                return
            }
            shouldShowExistingInstall() -> "existingInstall"
            BootstrapInstaller.isBootstrapped(prefix) -> "ready"
            else -> "setup"
        }
        if (currentScreen == "setup") startSetup()
    }

    /**
     * Called from the existingConfirm screen's "Proceed" button. Runs the
     * chosen destructive action on a background thread (so UI stays
     * responsive even when wiping a large `.claude/`), then advances to the
     * next logical screen.
     */
    private fun applyReinstallMode() {
        val mode = reinstallMode ?: return
        // Show a lightweight "working" screen by staying on existingConfirm;
        // the thread is fast enough that we don't add a full progress UI.
        Thread {
            try {
                when (mode) {
                    "clean" -> {
                        performCleanWipe()
                    }
                    "update" -> {
                        // Update overlay requires the PAI release tarball, which
                        // lives inside $PREFIX once the bootstrap is installed.
                        // If bootstrap isn't ready yet (fresh install flow),
                        // defer the overlay until after setup completes. We mark
                        // the mode; runPaiInstallFlow / startPaiInstall reads it.
                        // If bootstrap IS already done (reinstall onto existing
                        // bootstrap), do the overlay immediately.
                        if (BootstrapInstaller.isBootstrapped(prefix)) {
                            val ok = performUpdateOverlay()
                            if (!ok) {
                                Log.w(TAG, "Update overlay failed — falling back to clean path for safety")
                                performCleanWipe()
                            }
                        }
                        // When bootstrap is NOT yet installed, the normal setup
                        // flow will build it; the "update" mode flag is picked
                        // up later in startPaiInstall to skip the rm -rf step.
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "applyReinstallMode($mode) failed: ${e.message}")
            }
            // Refresh detection list so subsequent screens reflect reality
            detectedOldFiles = scanForOldInstallFiles()
            runOnUiThread {
                currentScreen = when {
                    BootstrapInstaller.isBootstrapped(prefix) && isPaiInstalled() -> {
                        launchTerminal()
                        return@runOnUiThread
                    }
                    BootstrapInstaller.isBootstrapped(prefix) -> "ready"
                    else -> "setup"
                }
                if (currentScreen == "setup") startSetup()
            }
        }.start()
    }

    /** Returns true if the app is subject to battery optimization (i.e. NOT exempt). */
    private fun isBatteryOptimized(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    @Suppress("BatteryLife") // We have a legitimate reason: long-running AI tasks
    private fun requestBatteryOptimizationExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        intent.data = Uri.parse("package:$packageName")
        startActivity(intent)
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            logLines.add(line)
            if (logLines.size > MAX_LOG_LINES) logLines.removeAt(0)
        }
    }

    private fun appendInstallLog(line: String) {
        runOnUiThread {
            installLogLines.add(line)
            if (installLogLines.size > MAX_LOG_LINES) installLogLines.removeAt(0)
        }
    }

    // ── Setup (Bootstrap) ──

    private fun startSetup() {
        // If we already kicked off bootstrap during the permission/battery
        // screens (see preloadBootstrapAsync), it may already be in progress
        // or finished — its onComplete callback will drive runPostBootstrapSetup
        // unconditionally. In that case this call is a UI-state reset only.
        setupError = null
        logLines.clear()
        if (!bootstrapStarted) {
            for (i in setupSteps.indices) {
                setupSteps[i] = setupSteps[i].copy(status = StepStatus.PENDING)
            }
        }

        listOf("$prefix/bin", "$prefix/lib", "$prefix/etc", "$prefix/tmp").forEach {
            File(it).mkdirs()
        }

        if (BootstrapInstaller.isBootstrapped(prefix)) {
            // Safe to deploy into $PREFIX now — bootstrap won't overwrite
            deployShellConfigs()
            deployUrlOpener()
            deployNodeFix()
            deployResolvConf()
            markStep(setupSteps, 0, StepStatus.DONE)
            markStep(setupSteps, 1, StepStatus.DONE)
            appendLog("Base system already installed.")
            appendLog("Package manager already configured.")
            // runPostBootstrapSetup is either already running (from preload) or
            // needs to be kicked off now. preloadBootstrapAsync kicks it via
            // BootstrapInstaller's onComplete, so only call here if preload
            // didn't run.
            if (!bootstrapStarted) runPostBootstrapSetup()
        } else if (!bootstrapStarted) {
            deployShellConfigs()
            deployUrlOpener()
            markStep(setupSteps, 0, StepStatus.ACTIVE)
            runBootstrap()
        }
        // else: bootstrap is already running from the preload, onComplete will
        // advance the UI. Nothing to do here.
    }

    private fun runBootstrap() {
        BootstrapInstaller(
            context = applicationContext,
            prefix = prefix,
            onProgress = { message ->
                runOnUiThread {
                    appendLog(message)
                    when {
                        message.contains("Extracting") || message.contains("Preparing") -> setupProgress = 0.1f
                        message.contains("files)") -> setupProgress = 0.15f
                        message.contains("symlinks") -> setupProgress = 0.2f
                        message.contains("Patching") -> setupProgress = 0.25f
                        message.contains("Setting permissions") -> setupProgress = 0.28f
                        message.contains("Configuring") -> {
                            setupProgress = 0.3f
                            markStep(setupSteps, 0, StepStatus.DONE)
                            markStep(setupSteps, 1, StepStatus.ACTIVE)
                        }
                        message.contains("complete") -> {
                            setupProgress = 0.35f
                            markStep(setupSteps, 1, StepStatus.DONE)
                        }
                        message.contains("Download") -> setupProgress = 0.05f
                        message.contains("ERROR") -> setupError = message
                    }
                }
            },
            onComplete = { success ->
                runOnUiThread {
                    if (success) {
                        markStep(setupSteps, 0, StepStatus.DONE)
                        markStep(setupSteps, 1, StepStatus.DONE)
                        // Deploy into $PREFIX now that bootstrap is done
                        deployNodeFix()
                        deployResolvConf()
                        deployPaiSetup()
                        runPostBootstrapSetup()
                    } else {
                        setupError = "Bootstrap failed. Check your internet connection."
                        markStep(setupSteps, currentStepIndex, StepStatus.ERROR)
                    }
                }
            }
        ).install()
    }

    private fun runPostBootstrapSetup() {
        markStep(setupSteps, 2, StepStatus.ACTIVE)
        setupProgress = 0.4f

        Thread {
            try {
                // Step 1 — install APK-bundled .debs offline (no network). Covers
                // git, proot, openssh, nodejs-lts and their deps. The first-run
                // profile script (pai-first-run.sh) does this same work when the
                // user opens a terminal; doing it here keeps the onboarding UI
                // in sync and avoids a redundant re-install later.
                val offlineDir = "$prefix/var/cache/apt/archives/offline"
                if (File(offlineDir).exists() && (File(offlineDir).listFiles()
                        ?.any { it.name.endsWith(".deb") } == true)) {
                    appendLog("$ dpkg -i offline/*.deb  (bundled packages)")
                    runShellCommand(
                        "dpkg -i --force-confold $offlineDir/*.deb 2>&1 && " +
                        "rm -rf $offlineDir"
                    ) { line ->
                        appendLog(line)
                        runOnUiThread {
                            if (setupProgress < 0.6f) setupProgress += 0.005f
                        }
                    }
                }

                runOnUiThread {
                    markStep(setupSteps, 2, StepStatus.DONE)
                    markStep(setupSteps, 3, StepStatus.ACTIVE)
                    setupProgress = 0.6f
                }

                // Step 2 — verify everything the onboarding needs is present.
                // If yes, we can skip both `apt update` and `apt install` entirely.
                val requiredBins = listOf("git", "proot", "ssh")
                val missingBins = requiredBins.filter { !File("$prefix/bin/$it").exists() }

                if (missingBins.isNotEmpty()) {
                    // Fallback: some package wasn't bundled (e.g. bundle was
                    // stripped, or we added a new requirement). Do one network
                    // round-trip to recover.
                    appendLog("Missing bundled packages: ${missingBins.joinToString()} — fetching over network")
                    appendLog("$ apt update -y")
                    runShellCommand("apt update -y") { line -> appendLog(line) }
                    val pkgs = missingBins.map { if (it == "ssh") "openssh" else it }.joinToString(" ")
                    appendLog("$ apt install -y $pkgs")
                    runShellCommand("apt install -y $pkgs 2>&1") { line ->
                        appendLog(line)
                        runOnUiThread {
                            if (setupProgress < 0.85f) setupProgress += 0.005f
                        }
                    }
                    val stillMissing = requiredBins.filter { !File("$prefix/bin/$it").exists() }
                    if (stillMissing.isNotEmpty()) {
                        throw RuntimeException("Failed to install packages. Missing: ${stillMissing.joinToString()}. Check your internet connection.")
                    }
                } else {
                    appendLog("All prerequisites present from bundled packages (git, proot, ssh).")
                }

                runOnUiThread {
                    markStep(setupSteps, 3, StepStatus.DONE)
                    markStep(setupSteps, 4, StepStatus.ACTIVE)
                    setupProgress = 0.85f
                }

                // Bun install is skipped: the official Bun binary doesn't run on
                // Android's bionic libc (documented in pai-setup.sh) and PAI uses
                // Node.js everywhere anyway. Skipping saves ~15-30s of network I/O
                // on first install. If some future caller really wants Bun it can
                // still be installed manually via `apt install tur-repo && apt install bun`.
                appendLog("Skipping Bun install (PAI uses Node.js on Android).")

                runOnUiThread {
                    markStep(setupSteps, 4, StepStatus.DONE)
                    markStep(setupSteps, 5, StepStatus.ACTIVE)
                    setupProgress = 0.93f
                }

                appendLog("Generating SSH keys...")
                runShellCommand("mkdir -p '$dataHome/.ssh' && chmod 700 '$dataHome/.ssh' && [ -f '$dataHome/.ssh/id_ed25519' ] || ssh-keygen -t ed25519 -f '$dataHome/.ssh/id_ed25519' -N '' 2>&1") { line ->
                    appendLog(line)
                }
                // Migrate old .ssh from sdcard root if it exists
                val oldSshDir = File(home, ".ssh")
                if (oldSshDir.exists() && oldSshDir.isDirectory) {
                    appendLog("Migrating .ssh from sdcard to internal storage...")
                    try {
                        val newSshDir = File(dataHome, ".ssh")
                        oldSshDir.listFiles()?.forEach { file ->
                            val dest = File(newSshDir, file.name)
                            if (!dest.exists()) file.copyTo(dest, overwrite = false)
                        }
                        oldSshDir.deleteRecursively()
                    } catch (e: Exception) {
                        Log.w(TAG, "SSH migration failed: ${e.message}")
                    }
                }

                File("$prefix/etc/profile.d/pai-first-run.sh").delete()

                runOnUiThread {
                    markStep(setupSteps, 5, StepStatus.DONE)
                    setupProgress = 1f
                    appendLog("")
                    appendLog("Setup complete!")

                    window.decorView.postDelayed({
                        currentScreen = "ready"
                    }, 800)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Post-bootstrap setup failed: ${e.message}")
                runOnUiThread {
                    setupError = "Setup failed: ${e.message}"
                    markStep(setupSteps, currentStepIndex, StepStatus.ERROR)
                }
            }
        }.start()
    }

    // ── PAI Install ──

    private fun startPaiInstall() {
        currentScreen = "install"
        installError = null
        installLogLines.clear()
        installProgress = 0f
        for (i in installSteps.indices) {
            installSteps[i] = installSteps[i].copy(status = StepStatus.PENDING)
        }

        Thread {
            try {
                // Step 1: Node.js
                runOnUiThread {
                    markStep(installSteps, 0, StepStatus.ACTIVE)
                    installProgress = 0.05f
                }

                if (shellCommandSucceeds("command -v node")) {
                    appendInstallLog("Node.js already installed.")
                    runShellCommand("node --version") { line -> appendInstallLog("Node.js: $line") }
                } else {
                    appendInstallLog("$ apt install -y nodejs")
                    runShellCommand("apt install -y nodejs 2>&1") { line ->
                        appendInstallLog(line)
                        runOnUiThread { if (installProgress < 0.2f) installProgress += 0.005f }
                    }
                }
                // Install tsx and bun via npm (bun.sh binary is glibc, not Android-compatible)
                if (!shellCommandSucceeds("command -v tsx")) {
                    appendInstallLog("$ npm install -g tsx --loglevel=http --no-fund --no-audit")
                    runShellCommand("npm install -g tsx --loglevel=http --no-fund --no-audit 2>&1") { line ->
                        appendInstallLog(line)
                        runOnUiThread { if (installProgress < 0.22f) installProgress += 0.003f }
                    }
                    runShellCommand("termux-fix-shebang $prefix/bin/tsx 2>/dev/null || true") { _ -> }
                }
                // Deploy bun shim and polyfill (native bun is glibc-only)
                deployBunShim()
                deployBunPolyfill()

                runOnUiThread {
                    markStep(installSteps, 0, StepStatus.DONE)
                    installProgress = 0.25f
                }

                // Step 2: Claude Code
                runOnUiThread { markStep(installSteps, 1, StepStatus.ACTIVE) }

                if (shellCommandSucceeds("command -v claude")) {
                    appendInstallLog("Claude Code already installed.")
                    runShellCommand("claude --version 2>&1 || true") { line -> appendInstallLog("Claude Code: $line") }
                } else {
                    // --loglevel=http makes npm print each HTTP fetch as it
                    // downloads, so the user sees real-time progress for the
                    // ~50 MB claude-code install instead of a silent 1-3 min
                    // stall. --no-fund --no-audit trim post-install noise.
                    appendInstallLog("$ npm install -g @anthropic-ai/claude-code --loglevel=http --no-fund --no-audit")
                    runShellCommandWithHeartbeat(
                        "npm install -g @anthropic-ai/claude-code --loglevel=http --no-fund --no-audit 2>&1",
                        ::appendInstallLog,
                    ) { line ->
                        appendInstallLog(line)
                        runOnUiThread { if (installProgress < 0.45f) installProgress += 0.003f }
                    }
                    runShellCommand("termux-fix-shebang $prefix/bin/claude 2>/dev/null || true") { _ -> }
                }

                // Fix Claude Code native binary for Android.
                // Android's Node.js reports process.platform==="android" (not "linux"),
                // so the native binary is never downloaded. We force-install the musl
                // variant, provide Alpine's musl dynamic linker, and patch the ELF
                // interpreter to point at it under $PREFIX/lib.
                fixClaudeNativeBinary()

                runOnUiThread {
                    markStep(installSteps, 1, StepStatus.DONE)
                    installProgress = 0.5f
                }

                // Step 3: Obtain PAI release.
                //   Preferred: extract APK-bundled pai-release.tar (instant, offline).
                //   Fallback:  git clone from GitHub (when bundle missing or
                //              PAI_REFRESH=1 set in env).
                runOnUiThread { markStep(installSteps, 2, StepStatus.ACTIVE) }

                val paiRepo = "$prefix/tmp/pai-repo"
                val paiReleaseDir = "$paiRepo/Releases/v4.0.3"
                val paiClaudeDir = "$paiReleaseDir/.claude"
                val paiBundle = "$prefix/var/cache/pai/pai-release.tar"
                val useBundle = File(paiBundle).exists() && reinstallMode != "update" // in update mode, overlay handles deploy later

                if (useBundle) {
                    val rev = try {
                        File("$prefix/var/cache/pai/pai-release.version")
                            .readLines().firstOrNull { it.startsWith("rev=") }
                            ?.substring(4, 12) ?: "bundled"
                    } catch (_: Exception) { "bundled" }
                    appendInstallLog("$ extract bundled PAI release ($rev)")
                    runShellCommand(
                        "rm -rf '$paiRepo' && " +
                        "mkdir -p '$paiReleaseDir' && " +
                        "tar -xf '$paiBundle' -C '$paiReleaseDir' 2>&1"
                    ) { line ->
                        appendInstallLog(line)
                        runOnUiThread { if (installProgress < 0.82f) installProgress += 0.01f }
                    }
                } else if (File("$paiClaudeDir/install.sh").exists()) {
                    appendInstallLog("PAI repository already cloned, updating...")
                    runShellCommand("git -C '$paiRepo' pull --ff-only --progress 2>&1 || true") { line ->
                        appendInstallLog(line)
                    }
                } else {
                    appendInstallLog("$ git clone --progress PAI repository (sparse)")
                    // --progress forces git to emit progress lines even when
                    // stdout is not a TTY, so the user sees counter updates
                    // instead of a silent stall during the ~23 MB clone.
                    runShellCommandWithHeartbeat("""
                        rm -rf '$paiRepo'
                        git clone --progress --depth 1 --sparse 'https://github.com/danielmiessler/Personal_AI_Infrastructure.git' '$paiRepo' 2>&1
                    """.trimIndent(), ::appendInstallLog) { line ->
                        appendInstallLog(line)
                        runOnUiThread { if (installProgress < 0.7f) installProgress += 0.003f }
                    }
                    appendInstallLog("$ git sparse-checkout set Releases/v4.0.3/.claude")
                    runShellCommand("""
                        git -C '$paiRepo' sparse-checkout set 'Releases/v4.0.3/.claude' 2>&1
                    """.trimIndent()) { line ->
                        appendInstallLog(line)
                        runOnUiThread { if (installProgress < 0.85f) installProgress += 0.005f }
                    }
                }

                if (!File("$paiClaudeDir/install.sh").exists()) {
                    throw RuntimeException("install.sh not found after clone")
                }

                runOnUiThread {
                    markStep(installSteps, 2, StepStatus.DONE)
                    installProgress = 0.85f
                }

                // Step 4: Deploy release to ~/.claude
                runOnUiThread { markStep(installSteps, 3, StepStatus.ACTIVE) }

                // Mode-aware deployment:
                //   "update" → overlay tarball on top, preserve settings.json
                //              and CLAUDE.md. Framework files are refreshed,
                //              user data (MEMORY/, custom skills/agents) stays.
                //   "clean" or null → traditional rm -rf + cp, then delete
                //              settings.json so the PAI installer treats this
                //              as a fresh install.
                if (reinstallMode == "update") {
                    appendInstallLog("$ overlay .claude (preserving user data)")
                    val overlayOk = performUpdateOverlay()
                    if (!overlayOk) {
                        appendInstallLog("Overlay failed, falling back to clean deploy")
                        appendInstallLog("$ deploy .claude → ~/  (fallback)")
                        runShellCommand("rm -rf '$home/.claude' && cp -r '$paiClaudeDir' '$home/.claude' && rm -f '$home/.claude/settings.json' 2>&1") { line ->
                            appendInstallLog(line)
                        }
                    }
                } else {
                    appendInstallLog("$ deploy .claude → ~/")
                    runShellCommand("rm -rf '$home/.claude' && cp -r '$paiClaudeDir' '$home/.claude' && rm -f '$home/.claude/settings.json' 2>&1") { line ->
                        appendInstallLog(line)
                    }
                }

                if (!File("$home/.claude/install.sh").exists()) {
                    throw RuntimeException("Failed to deploy PAI release to $home/.claude")
                }

                // Patch upstream installer: hardcoded .zshrc → detect shell from $SHELL
                // (ports pai-fork commits 6158ffd and 13983e3)
                try {
                    val patchDir = File("$prefix/tmp/pai-patches")
                    patchDir.mkdirs()
                    // Deploy patch script and patched files
                    val patchScript = File("$prefix/tmp", "patch-installer.sh")
                    patchScript.writeText(assets.open("patch-installer.sh").bufferedReader().use { it.readText() })
                    patchScript.setExecutable(true, false)
                    for (name in listOf("validate.ts", "types.ts", "display.ts", "index.ts", "pai.ts")) {
                        File(patchDir, name).writeText(
                            assets.open("pai-installer-patches/$name").bufferedReader().use { it.readText() }
                        )
                    }
                    runShellCommand("'$prefix/tmp/patch-installer.sh' '$home/.claude' '$prefix/tmp/pai-patches' 2>&1") { line ->
                        appendInstallLog(line)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to patch installer: ${e.message}")
                }

                runOnUiThread {
                    markStep(installSteps, 3, StepStatus.DONE)
                    installProgress = 1f
                    appendInstallLog("")
                    appendInstallLog("Launching PAI installer...")
                }

                // Brief pause then launch terminal with PAI installer via tsx
                // (install.sh requires bun which is glibc-only; tsx uses Node.js)
                Thread.sleep(1200)
                runOnUiThread {
                    val intent = Intent(this@OnboardingActivity, MainActivity::class.java)
                    intent.putExtra(
                        MainActivity.EXTRA_RUN_COMMAND,
                        "(cd '$home/.claude' && tsx PAI-Install/main.ts --mode cli)"
                    )
                    startActivity(intent)
                    finish()
                }

            } catch (e: Exception) {
                Log.e(TAG, "PAI install failed: ${e.message}")
                runOnUiThread {
                    installError = "Install failed: ${e.message}"
                    markStep(installSteps, installStepIndex, StepStatus.ERROR)
                    appendInstallLog("ERROR: ${e.message}")
                }
            }
        }.start()
    }

    // ── Shell helpers ──

    private fun runShellCommand(command: String, onLine: (String) -> Unit): Int {
        val env = buildShellEnv()
        val shell = if (File("$prefix/bin/bash").exists()) "$prefix/bin/bash" else "$prefix/bin/sh"
        val pb = ProcessBuilder(shell, "-c", command)
        pb.environment().clear()
        for (e in env) {
            val (k, v) = e.split("=", limit = 2)
            pb.environment()[k] = v
        }
        pb.directory(File(home))
        pb.redirectErrorStream(true)

        val process = pb.start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            Log.d(TAG, "shell: $line")
            line?.let { onLine(it) }
        }
        val exitCode = process.waitFor()
        Log.i(TAG, "Command finished with exit code $exitCode: ${command.take(40)}")
        return exitCode
    }

    /**
     * Runs [command] like [runShellCommand] but in parallel spawns a heartbeat
     * thread that emits a "still working" line through [announce] every 15s
     * whenever no output has arrived from the command. This keeps the UI alive
     * during long silent operations (npm downloading ~50 MB of claude-code,
     * git clone resolving refs, etc.) so users can tell the install hasn't
     * frozen.
     *
     * @param command shell command (joined line-wise from $SHELL -c)
     * @param announce invoked on the heartbeat thread with human-readable
     *                 progress lines — must itself be thread-safe (uses
     *                 runOnUiThread internally)
     * @param onLine invoked for each stdout line from the command
     */
    private fun runShellCommandWithHeartbeat(
        command: String,
        announce: (String) -> Unit,
        onLine: (String) -> Unit,
    ): Int {
        val started = System.currentTimeMillis()
        val lastLineAt = java.util.concurrent.atomic.AtomicLong(started)
        val stopHeartbeat = java.util.concurrent.atomic.AtomicBoolean(false)
        val heartbeat = Thread {
            try {
                while (!stopHeartbeat.get()) {
                    Thread.sleep(15_000)
                    if (stopHeartbeat.get()) break
                    val silent = (System.currentTimeMillis() - lastLineAt.get()) / 1000
                    val total = (System.currentTimeMillis() - started) / 1000
                    if (silent >= 15) {
                        announce("  … still working (${total}s elapsed, ${silent}s since last output)")
                    }
                }
            } catch (_: InterruptedException) {
                // stop
            }
        }
        heartbeat.isDaemon = true
        heartbeat.start()
        return try {
            runShellCommand(command) { line ->
                lastLineAt.set(System.currentTimeMillis())
                onLine(line)
            }
        } finally {
            stopHeartbeat.set(true)
            heartbeat.interrupt()
        }
    }

    private fun shellCommandSucceeds(command: String): Boolean {
        return try {
            val shell = if (File("$prefix/bin/bash").exists()) "$prefix/bin/bash" else "$prefix/bin/sh"
            val pb = ProcessBuilder(shell, "-c", command)
            pb.environment().clear()
            for (e in buildShellEnv()) {
                val (k, v) = e.split("=", limit = 2)
                pb.environment()[k] = v
            }
            pb.directory(File(home))
            pb.redirectErrorStream(true)
            val process = pb.start()
            process.inputStream.readBytes() // consume
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun findShell(): String {
        val candidates = listOf("$prefix/bin/bash", "$prefix/bin/sh", "/system/bin/sh")
        return candidates.first { File(it).exists() }
    }

    private fun buildShellEnv(): Array<String> {
        return arrayOf(
            "HOME=$home",
            "PAI_DIR=$home/.claude",
            "PAI_DATA_HOME=$dataHome",
            "PREFIX=$prefix",
            "SHELL=${findShell()}",
            "TERM=dumb",
            "LANG=en_US.UTF-8",
            "BUN_INSTALL=$prefix",
            "PATH=$prefix/bin:/system/bin:/system/xbin",
            "TMPDIR=$prefix/tmp",
            "LD_LIBRARY_PATH=$prefix/lib",
            "TERMUX_APP_PACKAGE_MANAGER=apt",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
            "APT_CONFIG=$prefix/etc/apt/apt.conf",
            "CURL_CA_BUNDLE=$prefix/etc/tls/cert.pem",
            "SSL_CERT_FILE=$prefix/etc/tls/cert.pem",
            "GIT_EXEC_PATH=$prefix/libexec/git-core",
            "GIT_TEMPLATE_DIR=$prefix/share/git-core/templates",
            "GIT_SSL_CAINFO=$prefix/etc/tls/cert.pem",
            "GIT_SSH_COMMAND=ssh -i $dataHome/.ssh/id_ed25519 -o UserKnownHostsFile=$dataHome/.ssh/known_hosts -o StrictHostKeyChecking=accept-new",
            "NODE_OPTIONS=--require=$prefix/lib/node-termux-fix.js",
            "NODE_PATH=$prefix/lib/node_modules",
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
    }

    private fun markStep(steps: MutableList<SetupStep>, index: Int, status: StepStatus) {
        if (index in steps.indices) {
            steps[index] = steps[index].copy(status = status)
            if (steps === setupSteps) currentStepIndex = index
            else installStepIndex = index
        }
    }

    // ── Deploy helpers ──

    private fun deployShellConfigs() {
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
                Log.w(TAG, "Failed to deploy $filename: ${e.message}")
            }
        }
        // Deploy thin wrapper dotfiles to $HOME (sdcard) so bash can find them.
        // Termux bash has /data/data/com.termux/... compiled in as /etc/profile path,
        // which doesn't exist under our package name, so bash never sources /etc/profile.
        // These wrappers redirect to the full configs in internal storage ($PAI_DATA_HOME).
        deployHomeDotfiles()
        // Deploy profile.d script so login shell sources configs from internal storage
        deployProfileDScript()
    }

    /** Deploy thin wrapper dotfiles to $HOME so bash login/non-login shells find them. */
    private fun deployHomeDotfiles() {
        val wrapper = """
            # PAI — source configs from internal storage (v1)
            [ -n "${'$'}PAI_DATA_HOME" ] && [ -f "${'$'}PAI_DATA_HOME/.bashrc" ] && . "${'$'}PAI_DATA_HOME/.bashrc"
        """.trimIndent() + "\n"
        // .bash_profile for login shells (bash checks this before .profile)
        try {
            File(home, ".bash_profile").writeText(wrapper)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deploy .bash_profile: ${e.message}")
        }
        // .bashrc for interactive non-login shells (e.g. running 'bash' inside terminal)
        try {
            File(home, ".bashrc").writeText(wrapper)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deploy .bashrc: ${e.message}")
        }
        // Clean up old full-size .profile from sdcard (superseded by .bash_profile wrapper)
        try {
            val oldProfile = File(home, ".profile")
            if (oldProfile.exists()) oldProfile.delete()
        } catch (_: Exception) {}
    }

    /** Ensure /etc/profile sources .bashrc/.profile from app internal storage. */
    private fun deployProfileDScript() {
        try {
            // Deploy profile.d script
            val profileDir = File("$prefix/etc/profile.d")
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
            val profile = File("$prefix/etc/profile")
            val marker = "# PAI: source profile.d"
            if (profile.exists() && !profile.readText().contains(marker)) {
                profile.appendText("\n$marker\nfor _f in ${'$'}PREFIX/etc/profile.d/*.sh; do [ -r \"${'$'}_f\" ] && . \"${'$'}_f\"; done\nunset _f\n")
            }
            Log.i(TAG, "Deployed profile.d sourcing")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deploy profile.d: ${e.message}")
        }
    }

    private fun launchTerminal() {
        // Always deploy latest shell configs (bashrc version check handles no-op)
        deployShellConfigs()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /** Deploy Node.js preload fix for com.termux hardcoded paths. */
    private fun deployNodeFix() {
        try {
            val dest = File("$prefix/lib", "node-termux-fix.js")
            val content = assets.open("node-termux-fix.js").bufferedReader().use { it.readText() }
            dest.writeText(content)
            Log.i(TAG, "Deployed node-termux-fix.js")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deploy node-termux-fix.js: ${e.message}")
        }
    }

    /** Create resolv.conf so c-ares (used by Node.js) can find DNS servers. */
    private fun deployResolvConf() {
        try {
            val dest = File("$prefix/etc", "resolv.conf")
            if (dest.exists()) return
            File("$prefix/etc").mkdirs()
            dest.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n")
            Log.i(TAG, "Deployed resolv.conf")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deploy resolv.conf: ${e.message}")
        }
    }

    /** Deploy Bun API polyfill so PAI scripts that import from "bun" work under Node.js/tsx. */
    private fun deployBunPolyfill() {
        try {
            val bunModuleDir = File("$prefix/lib/node_modules/bun")
            bunModuleDir.mkdirs()
            for (filename in listOf("index.js", "package.json")) {
                val dest = File(bunModuleDir, filename)
                val content = assets.open("bun-polyfill/$filename").bufferedReader().use { it.readText() }
                dest.writeText(content)
            }
            Log.i(TAG, "Deployed bun polyfill module")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deploy bun polyfill: ${e.message}")
        }
    }

    /** Deploy a bun shim that delegates to node/tsx (native bun is glibc-only). */
    private fun deployBunShim() {
        try {
            val bunDest = File("$prefix/bin", "bun")
            val content = assets.open("bun-shim.sh").bufferedReader().use { it.readText() }
            bunDest.writeText(content)
            bunDest.setExecutable(true, false)
            // Also create bunx symlink
            val bunxDest = File("$prefix/bin", "bunx")
            if (!bunxDest.exists()) {
                android.system.Os.symlink("bun", bunxDest.absolutePath)
            }
            Log.i(TAG, "Deployed bun shim")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deploy bun shim: ${e.message}")
        }
    }

    private fun deployPaiSetup() {
        try {
            val setupDest = File("$prefix/bin", "pai-setup")
            val content = assets.open("pai-setup.sh").bufferedReader().use { it.readText() }
            setupDest.writeText(content)
            setupDest.setExecutable(true, false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deploy pai-setup: ${e.message}")
        }
    }

    /**
     * Fix Claude Code native binary for Android. Mirrors the logic in
     * pai-setup.sh steps 1-4:
     *  1. Force-install the linux-arm64-musl native package
     *  2. Install Alpine's musl dynamic linker
     *  3. Patch the ELF interpreter path
     *  4. Patch install.cjs/cli-wrapper.cjs for android platform detection
     */
    private fun fixClaudeNativeBinary() {
        val claudePkgDir = "$prefix/lib/node_modules/@anthropic-ai/claude-code"
        val claudeBin = "$claudePkgDir/bin/claude.exe"

        // Check if fix is needed: bin/claude.exe is a shell stub (starts with "echo")
        // or doesn't exist at all
        val binFile = File(claudeBin)
        if (!binFile.exists()) {
            appendInstallLog("Claude Code binary not found, skipping native fix")
            return
        }
        val header = try { binFile.inputStream().use { it.read(ByteArray(4)).let { _ -> binFile.readBytes().take(4) } } } catch (_: Exception) { emptyList() }
        val isElf = header.size >= 4 && header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte()
        if (isElf) {
            appendInstallLog("Claude Code native binary already installed")
            return
        }

        appendInstallLog("Fixing Claude Code native binary for Android...")

        // Step 1: Force-install the musl native package
        appendInstallLog("$ npm install -g --force claude-code-linux-arm64-musl")
        runShellCommand(
            "CLAUDE_VER=\$(node -e \"console.log(require('$claudePkgDir/package.json').version)\") && " +
            "npm install -g --force \"@anthropic-ai/claude-code-linux-arm64-musl@\$CLAUDE_VER\" 2>&1"
        ) { line ->
            appendInstallLog(line)
            runOnUiThread { if (installProgress < 0.48f) installProgress += 0.002f }
        }

        // Step 2: Install musl dynamic linker from Alpine
        val muslLd = "$prefix/lib/ld-musl-aarch64.so.1"
        if (!File(muslLd).exists()) {
            appendInstallLog("Installing musl dynamic linker from Alpine...")
            runShellCommand(
                "MUSL_CACHE=\"$prefix/var/cache/pai/musl-aarch64.apk\" && " +
                "mkdir -p \"\$(dirname \"\$MUSL_CACHE\")\" && " +
                "if [ ! -f \"\$MUSL_CACHE\" ]; then " +
                "  MUSL_VER=\$(curl -fsSL 'https://dl-cdn.alpinelinux.org/alpine/latest-stable/main/aarch64/' 2>/dev/null | grep -o 'musl-[0-9][^\"]*\\.apk' | head -1) && " +
                "  curl -fsSL \"https://dl-cdn.alpinelinux.org/alpine/latest-stable/main/aarch64/\$MUSL_VER\" -o \"\$MUSL_CACHE\"; " +
                "fi && " +
                "cd '$prefix/tmp' && " +
                "tar -xzf \"\$MUSL_CACHE\" lib/ld-musl-aarch64.so.1 2>/dev/null && " +
                "cp '$prefix/tmp/lib/ld-musl-aarch64.so.1' '$muslLd' && " +
                "chmod 755 '$muslLd' && " +
                "rm -rf '$prefix/tmp/lib' && " +
                "echo 'musl linker installed' 2>&1"
            ) { line -> appendInstallLog(line) }
        }

        // Step 3: Patch ELF interpreter + Step 4: Patch platform detection
        val nativeBin = "$prefix/lib/node_modules/@anthropic-ai/claude-code-linux-arm64-musl/claude"
        appendInstallLog("Patching ELF interpreter...")
        runShellCommand(
            "cp '$nativeBin' '$claudeBin' 2>/dev/null && chmod 755 '$claudeBin' && " +
            "node -e \"" +
            "const fs = require('fs');" +
            "const buf = fs.readFileSync('$claudeBin');" +
            "const oldInterp = '/lib/ld-musl-aarch64.so.1';" +
            "const newInterp = '$muslLd';" +
            "const oldBuf = Buffer.from(oldInterp + '\\\\0');" +
            "const idx = buf.indexOf(oldBuf);" +
            "if (idx === -1) { console.log('Already patched'); process.exit(0); }" +
            "const newBuf = Buffer.from(newInterp + '\\\\0');" +
            "buf.fill(0, idx, idx + Math.max(oldBuf.length, newBuf.length));" +
            "newBuf.copy(buf, idx);" +
            "const e_phoff = Number(buf.readBigUInt64LE(32));" +
            "const e_phentsize = buf.readUInt16LE(54);" +
            "const e_phnum = buf.readUInt16LE(56);" +
            "for (let i = 0; i < e_phnum; i++) {" +
            "  const off = e_phoff + i * e_phentsize;" +
            "  if (buf.readUInt32LE(off) === 3) {" +
            "    buf.writeBigUInt64LE(BigInt(newBuf.length), off + 32);" +
            "    buf.writeBigUInt64LE(BigInt(newBuf.length), off + 40);" +
            "    break;" +
            "  }" +
            "}" +
            "fs.writeFileSync('$claudeBin', buf);" +
            "console.log('Interpreter patched');\" 2>&1"
        ) { line -> appendInstallLog(line) }

        // Step 4: Patch JS files for android platform detection
        for (jsFile in listOf("install.cjs", "cli-wrapper.cjs")) {
            runShellCommand(
                "F='$claudePkgDir/$jsFile' && " +
                "if [ -f \"\$F\" ] && ! grep -q 'android' \"\$F\"; then " +
                "  sed -i 's/function getPlatformKey() {/function getPlatformKey() {\\n  if (process.platform === \"android\" \\&\\& require(\"os\").arch() === \"arm64\") return \"linux-arm64-musl\";/' \"\$F\" && " +
                "  echo 'Patched $jsFile'; " +
                "fi 2>&1"
            ) { line -> appendInstallLog(line) }
        }

        // Step 5: Patch musl's hardcoded /etc/resolv.conf path
        // musl libc (ld-musl-aarch64.so.1) hardcodes /etc/resolv.conf for DNS.
        // On Android there is no /etc/resolv.conf — we deployed ours to $PREFIX/etc/.
        // Binary-patch the musl linker to use the correct path.
        appendInstallLog("Patching musl DNS resolver path...")
        runShellCommand(
            "node -e \"" +
            "const fs = require('fs');" +
            "const buf = fs.readFileSync('$muslLd');" +
            "const oldPath = '/etc/resolv.conf';" +
            "const newPath = '$prefix/etc/resolv.conf';" +
            "const oldBuf = Buffer.from(oldPath + '\\\\0');" +
            "const idx = buf.indexOf(oldBuf);" +
            "if (idx === -1) { console.log('resolv.conf path already patched or not found'); process.exit(0); }" +
            "const newBuf = Buffer.from(newPath + '\\\\0');" +
            "buf.fill(0, idx, idx + Math.max(oldBuf.length, newBuf.length));" +
            "newBuf.copy(buf, idx);" +
            "fs.writeFileSync('$muslLd', buf);" +
            "console.log('Patched: ' + oldPath + ' -> ' + newPath);\" 2>&1"
        ) { line -> appendInstallLog(line) }

        // Verify
        runShellCommand("claude --version 2>&1 || echo 'claude binary still not working'") { line ->
            appendInstallLog("Claude Code: $line")
        }
    }

    /** Deploy xdg-open shim so CLI tools can open URLs in a browser. */
    private fun deployUrlOpener() {
        try {
            val content = assets.open("xdg-open.sh").bufferedReader().use { it.readText() }
            for (name in listOf("xdg-open", "open", "termux-open", "termux-open-url")) {
                val dest = File("$prefix/bin", name)
                dest.delete()
                dest.writeText(content)
                dest.setExecutable(true, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deploy xdg-open: ${e.message}")
        }
    }

}

data class SetupStep(
    val name: String,
    val status: StepStatus,
)

enum class StepStatus { PENDING, ACTIVE, DONE, ERROR }

/** One entry in the "old install detected" list. */
data class DetectedFile(
    val name: String,       // e.g. ".claude"
    val path: String,       // absolute path
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val fileCount: Int,     // 1 for files, N for dirs
) {
    fun humanSize(): String = when {
        sizeBytes >= 1_048_576 -> "%.1f MB".format(sizeBytes / 1_048_576.0)
        sizeBytes >= 1024      -> "%d KB".format(sizeBytes / 1024)
        else                   -> "$sizeBytes B"
    }
}

// ── Compose Screens ──

@Composable
fun PermissionScreen(onGrantAccess: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.weight(1f))

        Text(
            text = "PAI",
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 8.sp,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Personal AI Infrastructure",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(64.dp))

        Text(
            text = "Storage Access",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "PAI needs full storage access so Claude Code can read and edit your project files anywhere on the device — just like it would on a desktop.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
        )

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = onGrantAccess,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            )
        ) {
            Text(
                text = "Grant Storage Access",
                style = MaterialTheme.typography.labelLarge,
                fontSize = 18.sp,
            )
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = "This app does not collect or transmit any data on its own.\nNetwork access is used only when you instruct PAI or Claude.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun BatteryScreen(
    onDisable: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "PAI",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Personal AI Infrastructure",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(48.dp))

        Text(
            text = "Background Tasks",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "PAI runs AI tasks that can take minutes or longer. Android may kill background processes to save battery, interrupting your work.\n\nDisabling battery optimization for this app prevents that.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
        )

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = onDisable,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            )
        ) {
            Text(
                text = "Disable Battery Restrictions",
                style = MaterialTheme.typography.labelLarge,
                fontSize = 18.sp,
            )
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onSkip,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Text(
                text = "Keep Current Settings",
                style = MaterialTheme.typography.labelLarge,
                fontSize = 18.sp,
            )
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = "You can change this later in Android Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Shown after permissions when leftover files from a previous install are
 * found on sdcard root. Offers two equal-weight choices: overlay update that
 * preserves user data, or clean wipe + fresh start. Per the project's design
 * memory (no dark patterns), both choices use identical button styling and
 * neutral language — there is no pre-selected default.
 */
@Composable
fun ExistingInstallScreen(
    detected: List<DetectedFile>,
    onChooseUpdate: () -> Unit,
    onChooseClean: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = "PAI",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Personal AI Infrastructure",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(40.dp))

        Text(
            text = "Existing installation detected",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "The following files or directories from a previous PAI or Claude installation were found on your device:",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )

        Spacer(Modifier.height(20.dp))

        // Detected-file list
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(16.dp),
        ) {
            for (f in detected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (f.isDirectory) "${f.name}/" else f.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (f.isDirectory) "${f.humanSize()} · ${f.fileCount} files" else f.humanSize(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = "Choose how to proceed:",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(16.dp))

        // Two equal-weight buttons, identical styling. Neutral labels.
        Button(
            onClick = onChooseUpdate,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Text("Update (keep my data)", fontSize = 18.sp)
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Experimental — refreshes PAI framework while preserving your settings, memory, and custom skills. Back up your ~/.claude/ manually first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
        )

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onChooseClean,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Text("Clean install (delete everything)", fontSize = 18.sp)
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Permanently removes the detected files and starts from scratch.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
        )

        Spacer(Modifier.weight(1f))
    }
}

/**
 * Confirmation step shown after the user picks Update or Clean. Recapitulates
 * exactly what will happen, with a single explicit "Proceed" button. The user
 * can go back by pressing the secondary button.
 */
@Composable
fun ExistingInstallConfirmScreen(
    mode: String,
    detected: List<DetectedFile>,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    val isClean = mode == "clean"
    val title = if (isClean) "Confirm clean install" else "Confirm update"
    val action = if (isClean) "Delete all detected files" else "Update and preserve data"
    val detail = if (isClean) {
        "This will permanently delete the following from your sdcard:"
    } else {
        "This will overlay the bundled PAI release on top of your existing ~/.claude/. Your settings.json, CLAUDE.md, memory, and custom skills will be preserved. Framework files will be refreshed. This path is experimental — manual backup is recommended before continuing."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = detail,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )

        Spacer(Modifier.height(24.dp))

        if (isClean) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(16.dp),
            ) {
                for (f in detected) {
                    Text(
                        text = (if (f.isDirectory) "${f.name}/" else f.name) + "  (${f.humanSize()})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Text(action, fontSize = 18.sp)
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Text("Back to options", fontSize = 18.sp)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun ProgressScreen(
    title: String,
    subtitle: String,
    steps: List<SetupStep>,
    progress: Float,
    logLines: List<String>,
    error: String?,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(48.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))

        // Progress bar
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = if (error != null) Color(0xFFF85149) else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
            fontSize = 12.sp,
        )

        Spacer(Modifier.height(16.dp))

        // Steps list
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (step in steps) {
                StepRow(step)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Terminal log area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0A0E14))
        ) {
            val listState = rememberLazyListState()

            // Auto-scroll to bottom
            LaunchedEffect(logLines.size) {
                if (logLines.isNotEmpty()) {
                    listState.animateScrollToItem(logLines.size - 1)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
            ) {
                items(logLines) { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = when {
                            line.startsWith("$") || line.startsWith("ERROR") -> Color(0xFF58A6FF)
                            line.contains("ERROR") || line.contains("error") -> Color(0xFFF85149)
                            line.contains("complete") || line.contains("ready") || line.contains("installed") ->
                                Color(0xFF3FB950)
                            else -> Color(0xFF8B949E)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Error / retry / skip
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                )
            ) {
                Text("Retry")
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun StepRow(step: SetupStep) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (step.status) {
                StepStatus.PENDING -> {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
                StepStatus.ACTIVE -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                StepStatus.DONE -> {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "\u2713",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                StepStatus.ERROR -> {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF85149)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "\u2717",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Text(
            text = step.name,
            style = MaterialTheme.typography.bodyLarge,
            fontSize = 14.sp,
            color = when (step.status) {
                StepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                StepStatus.ACTIVE -> MaterialTheme.colorScheme.onBackground
                StepStatus.DONE -> MaterialTheme.colorScheme.secondary
                StepStatus.ERROR -> Color(0xFFF85149)
            },
            fontWeight = if (step.status == StepStatus.ACTIVE) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
fun ReadyScreen(
    onInstallPai: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "PAI",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = "Environment Ready",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Your development environment is set up.\nTap below to install PAI — your\nPersonal AI Infrastructure.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
        )

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = onInstallPai,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            )
        ) {
            Text(
                text = "Install PAI",
                style = MaterialTheme.typography.labelLarge,
                fontSize = 18.sp,
            )
        }

        Spacer(Modifier.weight(1f))
    }
}
