package app.parda.ui.screens

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.parda.ui.components.asHeading
import androidx.compose.ui.platform.LocalContext
import app.parda.ui.AppLanguage
import app.parda.ui.str
import app.parda.R
import androidx.compose.ui.res.stringResource
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

/**
 * Whether the shield can really see checkouts. "Switched on" in Settings is not enough: some
 * phones (iQOO among them) stop accessibility services after an update or to save battery.
 */
data class ShieldStatus(
    val enabled: Boolean,
    val running: Boolean,
    /** When it last read anything on screen; 0 if never since the app started. */
    val lastEventAt: Long,
    val notificationsOn: Boolean,
)

@Composable
fun HomeScreen(
    shield: ShieldStatus,
    totals: Ledger.Totals,
    egressBytes: Long,
    entries: List<LedgerEntry>,
    onEnableService: () -> Unit,
    onBackgroundSettings: () -> Unit,
    onNotifications: () -> Unit,
    onOpenLedger: () -> Unit,
    onTryDemo: () -> Unit,
    onReportMiss: () -> Unit,
) {
    val serviceOn = shield.enabled && shield.running
    ScreenColumn {
        val context = LocalContext.current
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StrokeIcon(Icons.Shield, size = 22.dp)
            Text("parda", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            if (AppLanguage.switchable) {
                // Named in the language it switches to, so it can be found by someone who reads only that one.
                Pill(if (AppLanguage.isHindi(context)) "English" else "हिंदी", onClick = { AppLanguage.toggle(context) })
            }
        }
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.asHeading())

        GlassCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (serviceOn) {
                    Row(
                        Modifier.clip(CircleShape).background(Frost.GlassStrong),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.width(12.dp)); Dot(Frost.OkDot, 8.dp)
                        Text("  " + stringResource(R.string.home_active), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 7.dp, bottom = 7.dp, end = 12.dp))
                    }
                    Pill(stringResource(R.string.home_try_demo), strong = true, onClick = onTryDemo)
                } else if (shield.enabled) {
                    Pill(stringResource(R.string.home_stopped_fix), strong = true, onClick = onEnableService)
                } else {
                    Pill(stringResource(R.string.home_off_turn_on), strong = true, onClick = onEnableService)
                }
            }
            ShieldNote(shield, onBackgroundSettings, onNotifications)
            if (serviceOn) {
                Text(
                    stringResource(R.string.home_report_miss),
                    style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onReportMiss),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("${totals.patternsCaught}", stringResource(R.string.stat_caught), Modifier.weight(1f))
                Stat(Money.format(totals.savedPaise), stringResource(R.string.stat_saved), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("${totals.fieldsMasked}", stringResource(R.string.stat_masked), Modifier.weight(1f))
                Stat(formatBytes(egressBytes), stringResource(R.string.stat_sent), Modifier.weight(1f))
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
            SectionLabel(stringResource(R.string.home_last_intercept), Frost.NightInk2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        lastA?.detail ?: stringResource(R.string.home_nothing_yet),
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
                    StrokeIcon(Icons.Chevron, tint = Frost.Night, contentDescription = stringResource(R.string.home_open_ledger))
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
                        Text(stringResource(R.string.home_airlock_ago, timeAgo(lastB.timeMillis)), style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** One line under the shield status: what it last saw, or exactly what to do to get it back. */
@Composable
private fun ShieldNote(shield: ShieldStatus, onBackground: () -> Unit, onNotifications: () -> Unit) {
    val (text, action) = when {
        !shield.enabled -> stringResource(R.string.note_off) to null
        !shield.running -> stringResource(R.string.note_stopped) to (stringResource(R.string.note_background_settings) to onBackground)
        !shield.notificationsOn -> stringResource(R.string.note_notifications_off) to (stringResource(R.string.allow) to onNotifications)
        shield.lastEventAt > 0 -> stringResource(R.string.note_watching_last, timeAgo(shield.lastEventAt)) to null
        else -> stringResource(R.string.note_watching) to null
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (action != null || !shield.enabled) Frost.WarnInk else Frost.Ink2, modifier = Modifier.weight(1f))
        action?.let { (label, onClick) -> Spacer(Modifier.width(8.dp)); Pill(label, onClick = onClick) }
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
        s < 60 -> str(R.string.time_now)
        s < 3600 -> str(R.string.time_min, (s / 60).toInt())
        s < 86400 -> str(R.string.time_hr, (s / 3600).toInt())
        else -> str(R.string.time_day, (s / 86400).toInt())
    }
}
