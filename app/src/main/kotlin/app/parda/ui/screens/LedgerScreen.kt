package app.parda.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.parda.core.checkout.Money
import app.parda.core.ledger.LedgerEntry
import app.parda.core.ledger.Verdict
import app.parda.ui.components.GlassCard
import app.parda.ui.components.NightCard
import app.parda.ui.components.Pill
import app.parda.ui.components.SectionLabel
import app.parda.ui.theme.Frost
import java.text.DateFormat
import java.util.Date

@Composable
fun LedgerScreen(entries: List<LedgerEntry>, chainIntact: Boolean, internetDeclared: Boolean, egressBytes: Long) {
    ScreenColumn {
        Column {
            Text("Ledger", style = MaterialTheme.typography.headlineLarge)
            Text("Every decision at either boundary. Append-only.", style = MaterialTheme.typography.bodyLarge, color = Frost.Ink2)
        }

        NightCard {
            SectionLabel("Egress receipt", Frost.NightInk2)
            ReceiptRow("Network permission", if (internetDeclared) "declared" else "not declared", !internetDeclared)
            ReceiptRow("Bytes this app has sent", formatBytes(egressBytes), egressBytes == 0L)
            ReceiptRow("Ledger chain", if (chainIntact) "intact" else "broken", chainIntact)
            Text(
                "Parda cannot open a socket. Anything that leaves does so through your share sheet, by your own hand.",
                style = MaterialTheme.typography.bodyMedium, color = Frost.NightInk2,
            )
        }

        GlassCard(radius = 26.dp, padding = 0.dp, spacing = 0.dp) {
            if (entries.isEmpty()) {
                Text("No decisions recorded yet.", Modifier.padding(16.dp), color = Frost.Ink2)
            }
            entries.asReversed().forEachIndexed { i, e ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(e.detail, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Channel ${e.channel} · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.timeMillis)) +
                                (if (e.savedPaise > 0) " · saved ${Money.format(e.savedPaise)}" else ""),
                            style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2,
                        )
                    }
                    val (bg, fg) = verdictColors(e.verdict)
                    Pill(e.verdict.label, bg = bg, fg = fg)
                }
                if (i != entries.lastIndex) HorizontalDivider(color = Frost.Ink.copy(alpha = 0.06f))
            }
        }
    }
}

@Composable
private fun ReceiptRow(key: String, value: String, good: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = Frost.NightInk2)
        Text(value, style = MaterialTheme.typography.titleMedium, color = if (good) Color(0xFF8FE0B0) else Color(0xFFFF9C8A))
    }
}

private fun verdictColors(v: Verdict): Pair<Color, Color> = when (v) {
    Verdict.HELD_LOCALLY, Verdict.FIXED -> Color(0xFFD9E7DE) to Frost.Ok
    Verdict.HANDED_BACK, Verdict.FLAGGED -> Frost.WarnBg to Frost.WarnInk
    Verdict.BLOCKED -> Frost.AlertBg to Frost.AlertInk
    Verdict.KEPT -> Frost.GlassStrong to Frost.Ink2
}

