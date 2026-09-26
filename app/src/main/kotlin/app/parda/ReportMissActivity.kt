package app.parda

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.parda.core.ledger.Channel
import app.parda.core.ledger.Verdict
import app.parda.core.policy.Policy
import app.parda.service.CheckoutWatchService
import app.parda.service.CheckoutWatchService.Companion.Seen
import app.parda.ui.components.FrostBackground
import app.parda.ui.components.GlassCard
import app.parda.ui.components.PrimaryButton
import app.parda.ui.components.QuietButton
import app.parda.ui.components.SectionLabel
import app.parda.ui.screens.ScreenColumn
import app.parda.ui.theme.Frost
import app.parda.ui.theme.PardaTheme
import java.io.File

/**
 * "Parda missed something?": pick one of the last screens with prices the shield saw, say what
 * it missed, and share a report of what it read. Personal data in the report is masked with the
 * strictest defaults, whatever the user's own policy lets through. Nothing is sent by Parda; the
 * user chooses where the report goes.
 */
class ReportMissActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screens = CheckoutWatchService.recentScreens
        setContent { PardaTheme { FrostBackground { Report(screens) } } }
    }

    @Composable
    private fun Report(screens: List<Seen>) {
        var picked by remember { mutableStateOf<Seen?>(null) }
        var note by remember { mutableStateOf("") }
        ScreenColumn {
            Text("Report a miss", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Pick the screen Parda got wrong. The report shows what Parda read there, with personal details masked, and you choose where to send it.",
                style = MaterialTheme.typography.bodyLarge, color = Frost.Ink2,
            )
            if (screens.isEmpty()) {
                GlassCard(padding = 16.dp) {
                    Text(
                        "No screens with prices yet. Open the checkout Parda missed, come back here, and it will be listed. " +
                            "Screens are held in memory only and forgotten when the app closes.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            screens.forEach { s ->
                val chosen = picked == s
                GlassCard(padding = 16.dp, modifier = Modifier.clickable { picked = s }) {
                    Text((if (chosen) "✓ " else "") + s.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${DateUtils.getRelativeTimeSpanString(s.at)} · " +
                            (if (s.checkout) "read as a checkout" else "not read as a checkout") +
                            " · ${s.findings.size} finding(s)",
                        style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2,
                    )
                }
            }
            picked?.let { s ->
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                    label = { Text("What did Parda miss? (e.g. a donation was already added)") },
                )
                PrimaryButton("Share the report", onClick = { share(s, note) })
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("What the report holds")
                Text(
                    "The app's name, the time, what Parda found, your note, and the text Parda read on that screen, with names, numbers, addresses and IDs masked.",
                    style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2,
                )
            }
            QuietButton("Close", onClick = ::finish)
        }
    }

    private fun share(s: Seen, note: String) {
        // The strictest defaults, not the user's policy: a report is for someone else.
        val masked = store.sanitizer.sanitize(s.dump, Policy()).sanitized
        val maskedNote = store.sanitizer.sanitize(note, Policy()).sanitized
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val report = buildString {
            appendLine("Parda miss report")
            appendLine("App: ${s.label} (${s.app})")
            appendLine("When: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(s.at))}")
            appendLine("Parda $version on Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})")
            appendLine("Read as a checkout: ${if (s.checkout) "yes" else "no"}")
            appendLine()
            appendLine("What the user says was missed:")
            appendLine(maskedNote.ifBlank { "(no note)" })
            appendLine()
            appendLine("What Parda found:")
            if (s.findings.isEmpty()) appendLine("(nothing)") else s.findings.forEach { appendLine("- $it") }
            appendLine()
            appendLine("What Parda read on the screen (personal details masked):")
            append(masked)
        }
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "parda-miss-${s.at}.txt").apply { writeText(report) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        store.record(Channel.A, Verdict.HANDED_BACK, "Prepared a miss report for ${s.label}, personal details masked; nothing sent")
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Parda missed something in ${s.label}")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Send the report",
            ),
        )
    }
}
