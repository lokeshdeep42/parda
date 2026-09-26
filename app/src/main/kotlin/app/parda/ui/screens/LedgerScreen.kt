package app.parda.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import app.parda.core.ledger.Channel
import app.parda.ui.components.Icons
import app.parda.ui.components.StrokeIcon
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

        var channel by rememberSaveable { mutableStateOf<Channel?>(null) }
        var verdict by rememberSaveable { mutableStateOf<Verdict?>(null) }
        val inChannel = entries.filter { channel == null || it.channel == channel }
        // Only offer the outcomes that actually occur, so every chip leads somewhere.
        val verdicts = Verdict.entries.filter { v -> inChannel.any { it.verdict == v } }
        if (verdict != null && verdict !in verdicts) verdict = null
        val shown = inChannel.filter { verdict == null || it.verdict == verdict }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to "All", Channel.A to "Checkout", Channel.B to "Airlock").forEach { (c, name) ->
                Pill(name, strong = channel == c, onClick = { channel = c })
            }
        }
        if (verdicts.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Any outcome", strong = verdict == null, onClick = { verdict = null })
                verdicts.forEach { v -> Pill(v.label, strong = verdict == v, onClick = { verdict = v }) }
            }
        }
        Text(summary(shown), style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2)

        GlassCard(radius = 26.dp, padding = 0.dp, spacing = 0.dp) {
            if (shown.isEmpty()) {
                Text(
                    if (entries.isEmpty()) "No decisions recorded yet." else "Nothing matches this filter.",
                    Modifier.padding(16.dp), color = Frost.Ink2,
                )
            }
            val latest = shown.asReversed().take(MAX_ROWS)
            latest.forEachIndexed { i, e ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Frost.GlassStrong), contentAlignment = Alignment.Center) {
                        StrokeIcon(if (e.channel == Channel.A) Icons.Shield else Icons.Lock, size = 18.dp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(e.detail, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            channelName(e.channel) + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.timeMillis)) +
                                (if (e.savedPaise > 0) " · saved ${Money.format(e.savedPaise)}" else ""),
                            style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2,
                        )
                    }
                    val (bg, fg) = verdictColors(e.verdict)
                    Pill(e.verdict.label, bg = bg, fg = fg)
                }
                if (i != latest.lastIndex) HorizontalDivider(color = Frost.Ink.copy(alpha = 0.06f))
            }
            if (shown.size > MAX_ROWS) {
                Text("Showing the latest $MAX_ROWS of ${shown.size}.", Modifier.padding(16.dp), color = Frost.Ink2)
            }
        }
    }
}

private const val MAX_ROWS = 200

private fun channelName(c: Channel) = if (c == Channel.A) "Checkout" else "Airlock"

private fun summary(shown: List<LedgerEntry>): String {
    val parts = mutableListOf("${shown.size} decision" + if (shown.size == 1) "" else "s")
    val saved = shown.sumOf { it.savedPaise }
    val masked = shown.sumOf { it.masked }
    val patterns = shown.sumOf { it.patterns }
    if (saved > 0) parts += "${Money.format(saved)} saved"
    if (patterns > 0) parts += "$patterns pattern" + if (patterns == 1) "" else "s"
    if (masked > 0) parts += "$masked field" + (if (masked == 1) "" else "s") + " masked"
    return parts.joinToString(" · ")
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

