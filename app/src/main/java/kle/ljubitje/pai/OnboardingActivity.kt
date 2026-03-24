package kle.ljubitje.pai

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import kle.ljubitje.pai.ui.theme.PAITheme
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

    // UI state
    private var currentScreen by mutableStateOf("permission") // permission, setup, install, ready
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Already bootstrapped + has permission → go straight to terminal
        if (hasStoragePermission() && BootstrapInstaller.isBootstrapped(prefix)) {
            launchTerminal()
            return
        }

        // Has permission but not bootstrapped → skip to setup
        if (hasStoragePermission()) {
            currentScreen = "setup"
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
        }
    }

    override fun onResume() {
        super.onResume()
        if (currentScreen == "permission" && hasStoragePermission()) {
            currentScreen = "setup"
            startSetup()
        }
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
        setupError = null
        logLines.clear()
        for (i in setupSteps.indices) {
            setupSteps[i] = setupSteps[i].copy(status = StepStatus.PENDING)
        }

        listOf("$prefix/bin", "$prefix/lib", "$prefix/etc", "$prefix/tmp").forEach {
            File(it).mkdirs()
        }
        deployShellConfigs()

        if (BootstrapInstaller.isBootstrapped(prefix)) {
            markStep(setupSteps, 0, StepStatus.DONE)
            markStep(setupSteps, 1, StepStatus.DONE)
            appendLog("Base system already installed.")
            appendLog("Package manager already configured.")
            runPostBootstrapSetup()
        } else {
            markStep(setupSteps, 0, StepStatus.ACTIVE)
            runBootstrap()
        }
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
                appendLog("$ apt update -y")
                runShellCommand("apt update -y") { line ->
                    appendLog(line)
                }
                runOnUiThread {
                    markStep(setupSteps, 2, StepStatus.DONE)
                    markStep(setupSteps, 3, StepStatus.ACTIVE)
                    setupProgress = 0.6f
                }

                deployNodeFix()
                appendLog("$ apt install -y git proot openssh unzip")
                val aptExitCode = runShellCommand("apt install -y git proot openssh unzip 2>&1") { line ->
                    appendLog(line)
                    runOnUiThread {
                        if (setupProgress < 0.85f) setupProgress += 0.005f
                    }
                }

                // Verify critical binaries were actually installed
                val requiredBins = listOf("git", "proot", "ssh")
                val missingBins = requiredBins.filter { !File("$prefix/bin/$it").exists() }
                if (missingBins.isNotEmpty()) {
                    throw RuntimeException("Failed to install packages. Missing: ${missingBins.joinToString()}. Check your internet connection.")
                }

                runOnUiThread {
                    markStep(setupSteps, 3, StepStatus.DONE)
                    markStep(setupSteps, 4, StepStatus.ACTIVE)
                    setupProgress = 0.85f
                }

                // Install Bun (best-effort — may not be in default Termux repo)
                if (shellCommandSucceeds("command -v bun")) {
                    appendLog("Bun already installed.")
                } else {
                    // Try adding tur-repo first (Termux User Repository has bun)
                    appendLog("$ apt install -y tur-repo && apt update && apt install -y bun")
                    runShellCommand("apt install -y tur-repo 2>&1 && apt update -y 2>&1 && apt install -y bun 2>&1") { line ->
                        appendLog(line)
                        runOnUiThread { if (setupProgress < 0.92f) setupProgress += 0.005f }
                    }
                    if (!File("$prefix/bin/bun").exists()) {
                        appendLog("Bun not available — will use Node.js instead.")
                    }
                }

                runOnUiThread {
                    markStep(setupSteps, 4, StepStatus.DONE)
                    markStep(setupSteps, 5, StepStatus.ACTIVE)
                    setupProgress = 0.93f
                }

                appendLog("Generating SSH keys...")
                runShellCommand("mkdir -p ~/.ssh && [ -f ~/.ssh/id_ed25519 ] || ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519 -N '' 2>&1") { line ->
                    appendLog(line)
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
                // Also install tsx (TypeScript runner — needed because bun.sh binary is glibc, not Android-compatible)
                if (!shellCommandSucceeds("command -v tsx")) {
                    appendInstallLog("$ npm install -g tsx")
                    runShellCommand("npm install -g tsx 2>&1") { line ->
                        appendInstallLog(line)
                        runOnUiThread { if (installProgress < 0.22f) installProgress += 0.003f }
                    }
                    runShellCommand("termux-fix-shebang $prefix/bin/tsx 2>/dev/null || true") { _ -> }
                }

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
                    appendInstallLog("$ npm install -g @anthropic-ai/claude-code")
                    runShellCommand("npm install -g @anthropic-ai/claude-code 2>&1") { line ->
                        appendInstallLog(line)
                        runOnUiThread { if (installProgress < 0.45f) installProgress += 0.003f }
                    }
                    runShellCommand("termux-fix-shebang $prefix/bin/claude 2>/dev/null || true") { _ -> }
                }
                runOnUiThread {
                    markStep(installSteps, 1, StepStatus.DONE)
                    installProgress = 0.5f
                }

                // Step 3: Clone PAI repo (full clone)
                runOnUiThread { markStep(installSteps, 2, StepStatus.ACTIVE) }

                val paiRepo = "$prefix/tmp/pai-repo"
                val paiReleaseDir = "$paiRepo/Releases/v4.0.3"
                val paiClaudeDir = "$paiReleaseDir/.claude"

                if (File("$paiClaudeDir/install.sh").exists()) {
                    appendInstallLog("PAI repository already cloned, updating...")
                    runShellCommand("git -C '$paiRepo' pull --ff-only 2>/dev/null || true") { line ->
                        appendInstallLog(line)
                    }
                } else {
                    appendInstallLog("$ git clone PAI repository")
                    runShellCommand("""
                        rm -rf '$paiRepo'
                        git clone 'https://github.com/danielmiessler/Personal_AI_Infrastructure.git' '$paiRepo' 2>&1
                    """.trimIndent()) { line ->
                        appendInstallLog(line)
                        runOnUiThread { if (installProgress < 0.85f) installProgress += 0.003f }
                    }
                }

                if (!File("$paiClaudeDir/install.sh").exists()) {
                    throw RuntimeException("install.sh not found after clone")
                }

                runOnUiThread {
                    markStep(installSteps, 2, StepStatus.DONE)
                    installProgress = 0.85f
                }

                // Step 4: Copy .claude release to home directory
                runOnUiThread { markStep(installSteps, 3, StepStatus.ACTIVE) }

                appendInstallLog("$ cp -r .claude ~/")
                runShellCommand("rm -rf '$home/.claude' && cp -r '$paiClaudeDir' '$home/.claude' 2>&1") { line ->
                    appendInstallLog(line)
                }

                if (!File("$home/.claude/install.sh").exists()) {
                    throw RuntimeException("Failed to copy PAI release to $home/.claude")
                }

                runOnUiThread {
                    markStep(installSteps, 3, StepStatus.DONE)
                    installProgress = 1f
                    appendInstallLog("")
                    appendInstallLog("PAI deployed! Launching installer...")
                }

                // Brief pause then launch terminal with PAI installer via tsx
                // (install.sh requires bun which is glibc-only; tsx uses Node.js)
                Thread.sleep(1200)
                runOnUiThread {
                    val intent = Intent(this@OnboardingActivity, MainActivity::class.java)
                    intent.putExtra(
                        MainActivity.EXTRA_RUN_COMMAND,
                        "cd '$home/.claude' && tsx PAI-Install/main.ts --mode cli"
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

    private fun buildShellEnv(): Array<String> = arrayOf(
        "HOME=$home",
        "PAI_DIR=$home/.claude",
        "PREFIX=$prefix",
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
        "NODE_OPTIONS=--require=$prefix/lib/node-termux-fix.js",
    )

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
            val dest = File(home, filename)
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
    }

    private fun launchTerminal() {
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

}

data class SetupStep(
    val name: String,
    val status: StepStatus,
)

enum class StepStatus { PENDING, ACTIVE, DONE, ERROR }

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
            text = "PAI needs access to your device storage to set up the development environment and manage project files.",
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
            text = "No data leaves your device without your permission.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
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
