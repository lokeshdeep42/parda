package app.parda.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.parda.ui.components.GlassCard
import app.parda.ui.components.Icons
import app.parda.ui.components.NightCard
import app.parda.ui.components.Pill
import app.parda.ui.components.PrimaryButton
import app.parda.ui.components.StrokeIcon
import app.parda.ui.theme.Frost

@Composable
fun OnboardingScreen(
    accessibilityOn: Boolean,
    notificationsOn: Boolean,
    onAccessibility: () -> Unit,
    onNotifications: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.size(88.dp).clip(RoundedCornerShape(30.dp)).background(Frost.Glass),
                contentAlignment = Alignment.Center,
            ) { StrokeIcon(Icons.Shield, size = 40.dp) }
            Text(
                "Parda watches the screen,\nnot you.",
                style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
            )
            Text(
                "Two permissions let it catch tricks at checkout and tell you when it paused one.",
                style = MaterialTheme.typography.bodyLarge, color = Frost.Ink2, textAlign = TextAlign.Center,
            )
            PermissionRow(
                Icons.Shield, "Accessibility",
                "Reads checkout screens to spot pre-ticked boxes and fake timers",
                accessibilityOn, onAccessibility,
            )
            PermissionRow(
                Icons.Ledger, "Notifications", "Tells you when a checkout is flagged",
                notificationsOn, onNotifications,
            )
            NightCard {
                Text(
                    "The model runs on this phone. No account, no cloud, no analytics — Parda does not declare the internet permission at all. Works in flight mode.",
                    style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD5D8DE),
                )
            }
        }
        PrimaryButton("Continue", onClick = onContinue)
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, body: String, granted: Boolean, onGrant: () -> Unit) {
    GlassCard(radius = 22.dp, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Frost.GlassStrong), contentAlignment = Alignment.Center) {
                StrokeIcon(icon, size = 20.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2)
            }
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StrokeIcon(Icons.Check, tint = Frost.Ok, size = 16.dp)
                    Text("On", style = MaterialTheme.typography.labelMedium, color = Frost.Ok)
                }
            } else {
                Pill("Allow", strong = true, onClick = onGrant)
            }
        }
    }
}

