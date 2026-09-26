package app.parda.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.parda.core.agent.GateDecision
import app.parda.core.ledger.Channel
import app.parda.core.ledger.Verdict
import app.parda.store
import app.parda.ui.components.GlassCard
import app.parda.ui.components.NightCard
import app.parda.ui.components.Pill
import app.parda.ui.components.PrimaryButton
import app.parda.ui.components.QuietButton
import app.parda.ui.components.SectionLabel
import app.parda.ui.theme.Frost

/**
 * Channel B. Paste or share text in, ask a question. The local agent answers if it can; if it
 * can't, Parda prepares a sanitized copy for the user to take elsewhere by their own hand.
 */
@Composable
fun AirlockScreen(initialText: String?) {
    val context = LocalContext.current
    val store = context.store
    val clipboard = LocalClipboardManager.current

    var document by rememberSaveable(initialText) { mutableStateOf(initialText ?: SAMPLE) }
    var request by rememberSaveable { mutableStateOf("") }
    var reply by rememberSaveable { mutableStateOf("") }
    var decision by remember { mutableStateOf<GateDecision?>(null) }

    ScreenColumn {
        Column {
            Text("Airlock", style = MaterialTheme.typography.headlineLarge)
            Text("Mask it before you share it", style = MaterialTheme.typography.bodyLarge, color = Frost.Ink2)
        }

        GlassCard(padding = 16.dp) {
            SectionLabel("Inside this phone")
            OutlinedTextField(
                value = document,
                onValueChange = { document = it; decision = null },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                label = { Text("Text you are about to send") },
                colors = fieldColors(),
            )
            OutlinedTextField(
                value = request,
                onValueChange = { request = it; decision = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("What do you want to know?") },
                placeholder = { Text("Summarise the key terms") },
                colors = fieldColors(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Summarise", onClick = { request = EASY; decision = null })
                Pill("Am I underpaid?", onClick = { request = HARD; decision = null })
            }
            PrimaryButton("Ask the agent", enabled = document.isNotBlank(), onClick = {
                val d = store.gate.handle(document, request.ifBlank { EASY }, store.policy.value)
                decision = d
                when (d) {
                    is GateDecision.HeldLocally -> store.record(
                        Channel.B, Verdict.HELD_LOCALLY,
                        "${d.result.detections.size} sensitive item(s) classified; answered on the device, nothing prepared to send",
                    )
                    is GateDecision.HandedBack -> store.record(
                        Channel.B, Verdict.HANDED_BACK,
                        "${d.result.vault.withheld} item(s) withheld (${d.result.blockedCount} blocked); sanitized copy prepared, nothing transmitted",
                        masked = d.result.vault.withheld,
                    )
                }
            })
        }

        when (val d = decision) {
            null -> Unit
            is GateDecision.HeldLocally -> {
                NightCard {
                    SectionLabel("Answered on this phone", Frost.NightInk2)
                    Text("Nothing needed to leave — not even a surrogate.", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                }
                GlassCard(padding = 16.dp) {
                    SectionLabel("Answer")
                    SelectionContainer { Text(d.answer, style = MaterialTheme.typography.bodyLarge) }
                }
            }
            is GateDecision.HandedBack -> {
                NightCard {
                    SectionLabel("Handed back sanitized", Frost.NightInk2)
                    Text(
                        "${d.reason.explanation} Parda prepared a safe copy. It did not send it — it has no way to.",
                        color = Color.White, style = MaterialTheme.typography.bodyLarge,
                    )
                }
                GlassCard(padding = 16.dp) {
                    SectionLabel("What you can take outside · ${d.result.vault.withheld} withheld")
                    Mono(d.outbound)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill("Copy", strong = true, onClick = { clipboard.setText(AnnotatedString(d.outbound)) })
                        Pill("Share…", onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, d.outbound)
                            context.startActivity(Intent.createChooser(send, "Send sanitized text"))
                        })
                    }
                }
                GlassCard(padding = 16.dp) {
                    SectionLabel("Vault — never leaves this phone")
                    d.result.vault.entries.forEach { e ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(e.token, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            Text(e.real, style = MaterialTheme.typography.bodyMedium, color = Frost.WarnInk)
                        }
                    }
                }
                GlassCard(padding = 16.dp) {
                    SectionLabel("Bring the reply back")
                    OutlinedTextField(
                        value = reply,
                        onValueChange = { reply = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        label = { Text("Paste the outside model's answer") },
                        colors = fieldColors(),
                    )
                    if (reply.isNotBlank()) {
                        SectionLabel("Reads back to you as")
                        SelectionContainer { Text(d.result.vault.rehydrate(reply), style = MaterialTheme.typography.bodyLarge) }
                    }
                }
                QuietButton("Start over", onClick = { decision = null; reply = "" })
            }
        }
    }
}

@Composable
private fun Mono(text: String) {
    SelectionContainer {
        Text(
            text,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(12.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White.copy(alpha = 0.8f),
    focusedBorderColor = Frost.Night,
    unfocusedBorderColor = Color.Transparent,
)

private const val EASY = "Summarise the key terms of this letter in three lines."
private const val HARD = "Compare this against typical FY26 compensation bands for my role and tell me if I am underpaid."

private const val SAMPLE = """Subject: Salary revision — FY 2026-27

Dear Mr Rajesh Kumar,

Your revised annual compensation is ₹18,40,000 effective 01 April 2026.
Payments continue to account number 50100234567891 held with HDFC Bank.
PAN on record: ABCPK1234M
Registered address: 12-4-89, Kondapur Main Road, Hyderabad 500084
Contact: rajesh.kumar@example.com / +91 98490 12345

Please confirm receipt within seven working days."""
