package app.parda.service

import app.parda.R
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.parda.core.checkout.DarkPatternScanner
import app.parda.core.checkout.DemoCarts
import app.parda.core.checkout.Money
import app.parda.core.policy.DarkPatternKind
import app.parda.ui.components.Dot
import app.parda.ui.components.FrostBackground
import app.parda.ui.components.GlassCard
import app.parda.ui.components.Icons
import app.parda.ui.components.Pill
import app.parda.ui.components.QuietButton
import app.parda.ui.components.StrokeIcon
import app.parda.ui.components.asHeading
import app.parda.ui.screens.ScreenColumn
import app.parda.ui.theme.Frost
import app.parda.ui.theme.PardaTheme
import app.parda.ui.title

/** Lists the demo carts; each opens in [DemoCheckoutActivity], where the shield reads it. */
class DemoStoresActivity : ComponentActivity() {
    private var shieldOn by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { PardaTheme { FrostBackground { Stores() } } }
    }

    override fun onResume() {
        super.onResume()
        // Checked each time, so coming back from Settings clears the warning.
        shieldOn = CheckoutWatchService.running
    }

    @Composable
    private fun Stores() {
        ScreenColumn {
            Text(stringResource(R.string.demo_stores), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.asHeading())
            Text(stringResource(R.string.ds_sub), style = MaterialTheme.typography.bodyLarge, color = Frost.Ink2)
            if (!shieldOn) {
                // The stores still open without the shield, but nothing reads them.
                Pill(
                    stringResource(R.string.home_off_turn_on), bg = Frost.WarnBg, fg = Frost.WarnInk,
                    onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                )
            }
            TRICKS.forEach { (cart, kinds) -> StoreRow(cart, kinds) }
            QuietButton(stringResource(R.string.close), onClick = { finish() })
        }
    }

    @Composable
    private fun StoreRow(cart: DemoCarts.Cart, kinds: List<DarkPatternKind>) {
        GlassCard(
            radius = 22.dp, padding = 16.dp, spacing = 12.dp,
            modifier = Modifier.clickable(role = Role.Button) {
                startActivity(Intent(this, DemoCheckoutActivity::class.java).putExtra(DemoCheckoutActivity.EXTRA_CART, cart.id))
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Frost.Night), contentAlignment = Alignment.Center) {
                    Text(cart.store.take(1), style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(cart.store, style = MaterialTheme.typography.titleMedium)
                    Text(
                        (KIND[cart.id]?.let { stringResource(it) } ?: cart.id) + " · " + Money.format(cart.total()),
                        style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2,
                    )
                }
                StrokeIcon(Icons.Chevron, tint = Frost.Ink2, size = 20.dp)
            }
            // One dot per kind of trick the shield finds in this cart, in the colours used on Home.
            val count = pluralStringResource(R.plurals.pl_tricks, kinds.size, kinds.size)
            val said = (listOf(count) + kinds.map { it.title }).joinToString(", ")
            Row(
                Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = said },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (kinds.isEmpty()) Dot(Frost.OkDot, size = 8.dp) else kinds.forEach { Dot(Frost.patternColor(it), size = 8.dp) }
                Text(count, style = MaterialTheme.typography.labelMedium, color = if (kinds.isEmpty()) Frost.Ok else Frost.Ink2)
            }
        }
    }

    private companion object {
        val KIND = mapOf(
            "fashion" to R.string.ds_fashion, "food" to R.string.ds_food, "flight" to R.string.ds_flight,
            "movie" to R.string.ds_movie, "grocery" to R.string.ds_grocery, "honest" to R.string.ds_honest,
        )

        /** What the shield finds in each cart, scanned once from the same tree it reads on screen. */
        val TRICKS: List<Pair<DemoCarts.Cart, List<DarkPatternKind>>> by lazy {
            val scanner = DarkPatternScanner()
            DemoCarts.ALL.map { cart -> cart to scanner.scan(cart.screen()).findings.map { it.kind }.distinct() }
        }
    }
}
