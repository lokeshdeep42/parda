package app.parda.ui.screens

import app.parda.ui.components.SectionLabel
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.parda.core.policy.CheckoutAction
import app.parda.core.policy.DarkPatternKind
import app.parda.core.policy.DataCategory
import app.parda.core.policy.DisclosureAction
import app.parda.core.policy.Policy
import app.parda.ui.components.Dot
import app.parda.ui.components.GlassCard
import app.parda.ui.components.Pill
import app.parda.ui.theme.Frost

/** One policy, both boundaries. Tap an action to cycle it. */
@Composable
fun FirewallScreen(policy: Policy, onPolicy: (Policy) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ScreenColumn {
        Text("Firewall", style = MaterialTheme.typography.headlineLarge)

        Row(
            Modifier.fillMaxWidth().clip(CircleShape).background(Frost.Glass).padding(4.dp),
        ) {
            listOf("Checkout", "Your data").forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(CircleShape)
                        .background(if (tab == i) Color.White else Color.Transparent)
                        .clickable(role = Role.Tab) { tab = i },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.titleMedium, color = if (tab == i) Frost.Ink else Frost.Ink2)
                }
            }
        }

        if (tab == 0) {
            GlassCard(radius = 26.dp, padding = 0.dp, spacing = 0.dp) {
                DarkPatternKind.entries.forEachIndexed { i, kind ->
                    val action = policy.actionFor(kind)
                    RuleRow(
                        dot = Frost.patternColor(kind),
                        title = kind.label,
                        hint = kind.hint,
                        action = action.label,
                        strong = action == CheckoutAction.AUTO_REMOVE,
                        last = i == DarkPatternKind.entries.lastIndex,
                    ) { onPolicy(policy.with(kind, nextCheckout(kind, action))) }
                }
            }
            Text(
                "Parda never taps Pay. Auto-remove only unticks boxes it found pre-ticked; everything else is shown to you.",
                style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2,
            )
            // Rules the user accepted on a checkout sheet ("next time, remove these here").
            val rules = policy.perApp.flatMap { (app, kinds) -> kinds.map { app to it } }
            if (rules.isNotEmpty()) {
                val context = LocalContext.current
                GlassCard(radius = 26.dp, padding = 16.dp) {
                    SectionLabel("Removed without asking, in these apps only")
                    rules.forEach { (app, kind) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(appName(context, app), style = MaterialTheme.typography.titleMedium)
                                Text(kind.label, style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2)
                            }
                            Pill("Ask me again", onClick = { onPolicy(policy.autoRemove(app, kind, false)) })
                        }
                    }
                }
            }
        } else {
            GlassCard(radius = 26.dp, padding = 0.dp, spacing = 0.dp) {
                DataCategory.entries.forEachIndexed { i, cat ->
                    val action = policy.actionFor(cat)
                    RuleRow(
                        dot = if (action == DisclosureAction.BLOCK) Frost.AlertInk else Frost.Accent,
                        title = cat.label,
                        hint = if (cat == DataCategory.HEALTH_CONDITION) {
                            "Diagnoses. Let through so a report can be explained; mask them before sending one to an employer or insurer"
                        } else {
                            "Shown to others as <${cat.tokenPrefix}_1>"
                        },
                        action = action.label,
                        strong = action == DisclosureAction.BLOCK,
                        last = i == DataCategory.entries.lastIndex,
                    ) { onPolicy(policy.with(cat, nextDisclosure(action))) }
                }
            }
            Text(
                "Applies to the Airlock and to “Mask with Parda” in any app's text menu.",
                style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2,
            )
        }
    }
}

@Composable
private fun RuleRow(dot: Color, title: String, hint: String, action: String, strong: Boolean, last: Boolean, onCycle: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Dot(dot)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2)
            }
            Pill(action, strong = strong, onClick = onCycle)
        }
        if (!last) HorizontalDivider(color = Frost.Ink.copy(alpha = 0.06f))
    }
}

private fun nextCheckout(kind: DarkPatternKind, current: CheckoutAction): CheckoutAction {
    val options = CheckoutAction.entries.filter { it != CheckoutAction.AUTO_REMOVE || kind.fixable }
    return options[(options.indexOf(current) + 1) % options.size]
}

private fun nextDisclosure(current: DisclosureAction): DisclosureAction =
    DisclosureAction.entries[(current.ordinal + 1) % DisclosureAction.entries.size]

private fun appName(context: Context, pkg: String): String = if (pkg == context.packageName) "Demo stores" else runCatching {
    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)
