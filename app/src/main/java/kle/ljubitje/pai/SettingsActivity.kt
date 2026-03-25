package kle.ljubitje.pai

import android.content.Context
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kle.ljubitje.pai.ui.theme.PAITheme
import java.io.File

class SettingsActivity : ComponentActivity() {

    companion object {
        const val PREFS_NAME = "pai_settings"
        const val KEY_FONT_SIZE = "terminal_font_size"
        const val DEFAULT_FONT_SIZE = 24
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = applicationContext.filesDir.absolutePath + "/usr"
        val home = Environment.getExternalStorageDirectory().absolutePath
        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }

        setContent {
            PAITheme {
                var fontSize by remember {
                    mutableIntStateOf(prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE))
                }

                SettingsScreen(
                    fontSize = fontSize,
                    onFontSizeChange = { newSize ->
                        fontSize = newSize
                        prefs.edit().putInt(KEY_FONT_SIZE, newSize).apply()
                    },
                    appVersion = appVersion,
                    prefix = prefix,
                    home = home,
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    fontSize: Int,
    onFontSizeChange: (Int) -> Unit,
    appVersion: String,
    prefix: String,
    home: String,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(48.dp))

        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "\u2190",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 20.sp,
                )
            }

            Spacer(Modifier.width(16.dp))

            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Spacer(Modifier.height(32.dp))

        // ── Terminal Section ──
        SectionHeader("Terminal")

        SettingsCard {
            // Font size
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Font Size",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Adjust terminal text size",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Minus button
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                if (fontSize > 10) onFontSizeChange(fontSize - 2)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "\u2212",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Text(
                        text = "$fontSize",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(36.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )

                    // Plus button
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                if (fontSize < 48) onFontSizeChange(fontSize + 2)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "+",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        // ── About Section ──
        SectionHeader("About")

        SettingsCard {
            InfoRow("App Version", appVersion)
            CardDivider()
            InfoRow("PAI Version", detectPaiVersion(home))
            CardDivider()
            InfoRow("Package", "kle.ljubitje.pai")
        }

        Spacer(Modifier.height(16.dp))

        // ── Components Section ──
        SectionHeader("Components")

        SettingsCard {
            ComponentRow("Node.js", detectVersion(prefix, "node --version"))
            CardDivider()
            ComponentRow("Claude Code", detectVersion(prefix, "claude --version"))
            CardDivider()
            ComponentRow("Git", detectVersion(prefix, "git --version"))
            CardDivider()
            ComponentRow("Bun (shim)", if (File("$prefix/bin/bun").exists()) "Active" else "Not found")
            CardDivider()
            ComponentRow("tsx", detectVersion(prefix, "tsx --version"))
            CardDivider()
            ComponentRow("proot", if (File("$prefix/bin/proot").exists()) "Installed" else "Not found")
        }

        Spacer(Modifier.height(28.dp))

        // ── Storage Section ──
        SectionHeader("Storage")

        SettingsCard {
            InfoRow("Home", home)
            CardDivider()
            InfoRow("Prefix", prefix)
            CardDivider()
            InfoRow("PAI Dir", "$home/.claude")
        }

        Spacer(Modifier.height(48.dp))

        // Footer
        Text(
            text = "PAI \u2014 Personal AI Infrastructure",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))
    }
}

// ── Reusable components ──

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
    )
}

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column { content() }
    }
}

@Composable
fun CardDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    )
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun ComponentRow(name: String, status: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        val isInstalled = status != "Not found" && status != "Error"
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isInstalled) Color(0xFF3FB950) else Color(0xFFF85149))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isInstalled) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFF85149),
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// ── Detection helpers ──

private fun detectVersion(prefix: String, command: String): String {
    return try {
        val shell = if (File("$prefix/bin/bash").exists()) "$prefix/bin/bash" else "$prefix/bin/sh"
        val pb = ProcessBuilder(shell, "-c", command)
        pb.environment()["PATH"] = "$prefix/bin:/system/bin"
        pb.environment()["HOME"] = Environment.getExternalStorageDirectory().absolutePath
        pb.environment()["PREFIX"] = prefix
        pb.environment()["LD_LIBRARY_PATH"] = "$prefix/lib"
        pb.redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        if (process.exitValue() == 0 && output.isNotEmpty()) {
            // Clean up common prefixes
            output.lines().first()
                .removePrefix("git version ")
                .removePrefix("v")
                .trim()
        } else "Not found"
    } catch (_: Exception) { "Not found" }
}

private fun detectPaiVersion(home: String): String {
    return try {
        val versionFile = File("$home/.claude/PAI/VERSION")
        if (versionFile.exists()) versionFile.readText().trim()
        else {
            // Try reading from SKILL.md or any version indicator
            val skillFile = File("$home/.claude/PAI/SKILL.md")
            if (skillFile.exists()) "Installed" else "Not installed"
        }
    } catch (_: Exception) { "Unknown" }
}
