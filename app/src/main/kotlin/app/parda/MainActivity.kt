package app.parda

import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.net.Uri
import android.widget.Toast
import app.parda.data.DocumentReader
import app.parda.service.CheckoutWatchService
import kotlin.concurrent.thread
import app.parda.service.DemoStoresActivity
import app.parda.ui.components.FrostBackground
import app.parda.ui.components.Icons
import app.parda.ui.components.StrokeIcon
import app.parda.ui.screens.AirlockScreen
import app.parda.ui.screens.FirewallScreen
import app.parda.ui.screens.HomeScreen
import app.parda.ui.screens.ShieldStatus
import app.parda.ui.screens.LedgerScreen
import app.parda.ui.screens.OnboardingScreen
import app.parda.ui.theme.Frost
import app.parda.ui.theme.PardaTheme

class MainActivity : ComponentActivity() {
    private var tab by mutableIntStateOf(TAB_HOME)
    private var sharedText by mutableStateOf<String?>(null)
    private var sharedImage by mutableStateOf<Uri?>(null)

    /** Bumped on resume so permission state is re-read after a trip to Settings. */
    private var resumeTick by mutableIntStateOf(0)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { PardaTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        when {
            stream != null && intent.type?.startsWith("image/") == true -> { sharedImage = stream; tab = TAB_AIRLOCK }
            stream != null -> thread(name = "parda-read") {
                val read = runCatching { DocumentReader.read(this, stream) }
                runOnUiThread {
                    read.onSuccess { sharedText = it.text; tab = TAB_AIRLOCK }
                        .onFailure { Toast.makeText(this, it.message ?: "Could not read that file.", Toast.LENGTH_LONG).show() }
                }
            }
            text != null -> { sharedText = text; tab = TAB_AIRLOCK }
        }
    }

    @Composable
    private fun App() {
        val store = store
        val policy by store.policy.collectAsStateWithLifecycle()
        val entries by store.entries.collectAsStateWithLifecycle()
        val onboarded by store.onboarded.collectAsStateWithLifecycle()

        val tick = resumeTick
        val serviceOn = remember(tick) { CheckoutWatchService.isEnabled(this) }
        val notificationsOn = remember(tick) { NotificationManagerCompat.from(this).areNotificationsEnabled() }
        val egress = remember(tick, entries) { store.egressBytes() }
        val totals = remember(entries) { store.totals() }
        val chainIntact = remember(entries) { store.ledgerIntact() }
        val internetDeclared = remember { declaresInternet() }

        FrostBackground {
            if (!onboarded) {
                OnboardingScreen(
                    accessibilityOn = serviceOn,
                    notificationsOn = notificationsOn,
                    onAccessibility = ::openAccessibilitySettings,
                    onNotifications = ::requestNotifications,
                    onContinue = store::setOnboarded,
                )
                return@FrostBackground
            }
            when (tab) {
                TAB_HOME -> HomeScreen(
                    ShieldStatus(serviceOn, CheckoutWatchService.running, CheckoutWatchService.lastEventAt, notificationsOn),
                    totals, egress, entries, ::openAccessibilitySettings,
                    onBackgroundSettings = ::openAppSettings,
                    onNotifications = ::requestNotifications,
                    onOpenLedger = { tab = TAB_LEDGER },
                    onTryDemo = { startActivity(Intent(this@MainActivity, DemoStoresActivity::class.java)) },
                    onReportMiss = { startActivity(Intent(this@MainActivity, ReportMissActivity::class.java)) },
                )
                TAB_FIREWALL -> FirewallScreen(policy, store::setPolicy)
                TAB_AIRLOCK -> AirlockScreen(sharedText, sharedImage)
                TAB_LEDGER -> LedgerScreen(entries, chainIntact, internetDeclared, egress)
            }
            FloatingNav(tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
        }
    }

    /** The app's own settings page, where phones put battery and background-running controls. */
    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    /** Reads the installed manifest, so the receipt reports what the APK actually declares. */
    private fun declaresInternet(): Boolean = runCatching {
        packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(Manifest.permission.INTERNET) == true
    }.getOrDefault(true)

    companion object {
        const val TAB_HOME = 0
        const val TAB_FIREWALL = 1
        const val TAB_AIRLOCK = 2
        const val TAB_LEDGER = 3
    }
}

@Composable
private fun FloatingNav(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val items: List<Pair<ImageVector, String>> = listOf(
        Icons.Home to stringResource(R.string.nav_home), Icons.Shield to stringResource(R.string.nav_firewall),
        Icons.Lock to stringResource(R.string.nav_airlock), Icons.Ledger to stringResource(R.string.nav_ledger),
    )
    Row(
        modifier
            .navigationBarsPadding()
            .padding(bottom = 20.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.7f))
            .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEachIndexed { i, (icon, label) ->
            val on = i == selected
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(if (on) Frost.Night else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = { onSelect(i) }, modifier = Modifier.semantics { this.selected = on }) {
                    StrokeIcon(icon, tint = if (on) Color.White else Frost.Ink, contentDescription = label)
                }
            }
        }
    }
}
