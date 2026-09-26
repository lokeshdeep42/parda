package app.parda.service

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.parda.core.checkout.Finding
import app.parda.core.checkout.Money
import app.parda.core.ledger.Channel
import app.parda.core.ledger.Verdict
import app.parda.store
import app.parda.ui.components.Icons
import app.parda.ui.components.Pill
import app.parda.ui.components.PrimaryButton
import app.parda.ui.components.QuietButton
import app.parda.ui.components.StrokeIcon
import app.parda.ui.theme.Frost
import app.parda.ui.theme.PardaTheme

/**
 * The "Parda paused this checkout" sheet, drawn over the shopping app. The user decides; the
 * service then re-reads the screen and unticks only what the user approved.
 */
class InterceptActivity : ComponentActivity() {
    /** The checkout on screen. Replaced in [onNewIntent]: this activity is single-instance, so a
     *  new intercept can arrive while an old sheet is still alive. */
    private var intercept by mutableStateOf<Intercept?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intercept = InterceptState.current.value ?: run { finish(); return }
        setContent {
            val current = intercept ?: return@setContent
            PardaTheme {
                key(current) {
                    BackHandler { keep(current) }
                    Sheet(current, onRemove = { remove(current, it) }, onKeep = { keep(current) })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        InterceptState.current.value?.let { intercept = it }
    }

    private fun remove(intercept: Intercept, approved: List<Finding>) {
        val service = CheckoutWatchService.instance
        if (approved.isNotEmpty() && service != null) {
            service.applyFixes(intercept.packageName, approved.map { it.key }.toSet())
        }
        InterceptState.clear()
        finish()
    }

    private fun keep(intercept: Intercept) {
        store.record(Channel.A, Verdict.KEPT, "You kept everything on a checkout in ${intercept.appLabel}")
        InterceptState.clear()
        finish()
    }
}

@Composable
private fun Sheet(intercept: Intercept, onRemove: (List<Finding>) -> Unit, onKeep: () -> Unit) {
    val findings = intercept.plan.ask
    val remove = remember { mutableStateMapOf<String, Boolean>().apply { findings.filter { it.fixable }.forEach { put(it.key, true) } } }
    val saving = findings.filter { it.fixable && remove[it.key] == true }.sumOf { it.cost }
    val monthly = findings.filter { it.fixable && remove[it.key] == true }.sumOf { it.recurring }

    Box(Modifier.fillMaxSize().background(Color(0x471E222C)), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(10.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(36.dp))
                .background(Color.White.copy(alpha = 0.94f))
                .border(1.dp, Color.White, RoundedCornerShape(36.dp))
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(5.dp).clip(CircleShape).background(Frost.Ink.copy(alpha = 0.2f)))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Frost.Night), contentAlignment = Alignment.Center) {
                    StrokeIcon(Icons.Shield, tint = Color.White, size = 24.dp)
                }
                Column {
                    Text("Parda paused this checkout", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${findings.size} thing(s) you didn't ask for · ${intercept.appLabel} · checked on this phone",
                        style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                findings.forEach { f -> FindingRow(f, remove[f.key] == true) { remove[f.key] = it } }
            }

            if (saving > 0 || monthly > 0) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("You keep", style = MaterialTheme.typography.bodyLarge, color = Frost.Ink2)
                    Text(
                        Money.format(saving) + (if (monthly > 0) " + ${Money.format(monthly)}/mo" else ""),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }

            val approved = findings.filter { it.fixable && remove[it.key] == true }
            PrimaryButton(
                if (approved.isEmpty()) "Continue" else "Remove add-ons & continue",
                onClick = { onRemove(approved) },
            )
            QuietButton("Keep everything", onClick = onKeep)
        }
    }
}

@Composable
private fun FindingRow(f: Finding, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val base = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White)
    Row(
        (if (f.fixable) base.toggleable(checked, role = Role.Checkbox, onValueChange = onChecked) else base)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(f.evidence, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Text(
                "${f.kind.label} · ${f.kind.code}" + if (f.recurring > 0) " · then ${Money.format(f.recurring)}/mo" else "",
                style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2,
            )
        }
        if (f.cost > 0) Text(Money.format(f.cost), style = MaterialTheme.typography.titleMedium)
        if (f.fixable) {
            Checkbox(
                checked = checked, onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = Frost.Night),
            )
        } else {
            Pill(if (f.cost > 0) "Can't remove" else "Ignore it", bg = Frost.WarnBg, fg = Frost.WarnInk)
        }
    }
}
