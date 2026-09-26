package app.parda.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.parda.core.checkout.Money
import app.parda.core.ledger.Channel
import app.parda.core.ledger.Ledger
import app.parda.core.ledger.LedgerEntry
import app.parda.core.policy.DarkPatternKind
import app.parda.ui.components.Dot
import app.parda.ui.components.GlassCard
import app.parda.ui.components.Icons
import app.parda.ui.components.NightCard
import app.parda.ui.components.Pill
import app.parda.ui.components.SectionLabel
import app.parda.ui.components.Stat
import app.parda.ui.components.StrokeIcon
import app.parda.ui.theme.Frost

@Composable
fun HomeScreen(
    serviceOn: Boolean,
    totals: Ledger.Totals,
    egressBytes: Long,
    entries: List<LedgerEntry>,
    onEnableService: () -> Unit,
    onOpenLedger: () -> Unit,
    onTryDemo: () -> Unit,
) {
    ScreenColumn {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StrokeIcon(Icons.Shield, size = 22.dp)
            Text("parda", style = MaterialTheme.typography.titleLarge)
        }
        Text("Your checkout\nshield", style = MaterialTheme.typography.headlineLarge)

        GlassCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (serviceOn) {
                    Row(
                        Modifier.clip(CircleShape).background(Frost.GlassStrong),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.width(12.dp)); Dot(Frost.OkDot, 8.dp)
                        Text("  Active · on-device", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 7.dp, bottom = 7.dp, end = 12.dp))
                    }
                    Pill("Try a demo checkout", strong = true, onClick = onTryDemo)
                } else {
                    Pill("Shield is off — turn on", strong = true, onClick = onEnableService)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("${totals.patternsCaught}", "Dark patterns caught", Modifier.weight(1f))
                Stat(Money.format(totals.savedPaise), "Saved", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("${totals.fieldsMasked}", "Personal fields masked", Modifier.weight(1f))
                Stat(formatBytes(egressBytes), "Sent to any server", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    DarkPatternKind.BASKET_SNEAKING to 5f, DarkPatternKind.FALSE_URGENCY to 4f,
                    DarkPatternKind.DRIP_PRICING to 2f, DarkPatternKind.CONFIRM_SHAMING to 1f,
                ).forEach { (k, w) ->
                    Box(Modifier.weight(w).height(8.dp).clip(CircleShape).background(Frost.patternColor(k)))
                }
            }
        }

        val lastA = entries.lastOrNull { it.channel == Channel.A }
        NightCard(Modifier.clickable(role = Role.Button, onClick = onOpenLedger)) {
            SectionLabel("Last intercept", Frost.NightInk2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        lastA?.detail ?: "Nothing yet. Shop as usual — Parda reads the checkout screen.",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize),
                        color = Color.White,
                    )
                    if (lastA != null) Text(timeAgo(lastA.timeMillis), style = MaterialTheme.typography.bodyMedium, color = Frost.NightInk2)
                }
                if (lastA != null && lastA.savedPaise > 0) {
                    Text("−" + Money.format(lastA.savedPaise), style = MaterialTheme.typography.titleLarge, color = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    StrokeIcon(Icons.Chevron, tint = Frost.Night, contentDescription = "Open ledger")
                }
            }
        }

        entries.lastOrNull { it.channel == Channel.B }?.let { lastB ->
            GlassCard(radius = 22.dp, padding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Frost.GlassStrong), contentAlignment = Alignment.Center) {
                        StrokeIcon(Icons.Lock, size = 20.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(lastB.detail, style = MaterialTheme.typography.bodyLarge)
                        Text("Airlock · ${timeAgo(lastB.timeMillis)}", style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

internal fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> "${b / (1024 * 1024)} MB"
}

internal fun timeAgo(millis: Long): String {
    val s = (System.currentTimeMillis() - millis) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} hr ago"
        else -> "${s / 86400} d ago"
    }
}
