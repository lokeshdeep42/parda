package app.parda.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.parda.MainActivity
import app.parda.PardaApp
import app.parda.R
import app.parda.core.checkout.CheckoutGate
import app.parda.core.checkout.CheckoutScan
import app.parda.core.checkout.DarkPatternScanner
import app.parda.core.checkout.Finding
import app.parda.core.checkout.Money
import app.parda.core.checkout.ScreenNode
import app.parda.core.ledger.Channel
import app.parda.core.ledger.Verdict
import app.parda.store

/**
 * Channel A. Reads checkout screens while the phone is fully online (that is when people
 * shop), finds dark patterns on the device, and applies the user's policy. The only action it
 * can take on another app is unticking a box that [CheckoutGate] has identified. It never taps
 * Pay.
 */
class CheckoutWatchService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null


    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        lastEventAt = System.currentTimeMillis()
        val pkg = event.packageName?.toString() ?: return
        if (pkg in IGNORED_PACKAGES) return
        // Parda's own screens are never scanned, except the demo store (see DemoCheckoutActivity).
        if (pkg == packageName && !DemoCheckoutActivity.visible) return
        pending?.let(handler::removeCallbacks)
        pending = Runnable { inspect(pkg) }.also { handler.postDelayed(it, DEBOUNCE_MS) }
    }

    private fun inspect(pkg: String) {
        if (InterceptActivity.onScreen) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        if (pkg == packageName && !DemoCheckoutActivity.visible) return

        val snapshot = ScreenSnapshot.capture(root)
        val scan = store.scanner.scan(snapshot)
        if (debuggable && scan.isCheckout) {
            // Debug builds only: what the shield read and concluded, for tuning against real apps.
            Log.i(TAG, "checkout in $pkg\n" + ScreenSnapshot.dump(snapshot))
            scan.findings.forEach { Log.i(TAG, "finding ${it.kind} fixable=${it.fixable} cost=${it.cost}: ${it.evidence}") }
        }
        val now = System.currentTimeMillis()
        val seen = handled.getOrPut(pkg) { HashSet() }
        val unticked = seenUnticked.getOrPut(pkg) { HashSet() }
        if (!scan.isCheckout) {
            // A half-drawn screen during a transition can look like "not a checkout". Only
            // forget what was handled once the user has really been away for a while.
            if (now - (lastCheckoutAt[pkg] ?: 0L) > FORGET_AFTER_MS) { seen.clear(); unticked.clear() }
            return
        }
        lastCheckoutAt[pkg] = now
        // A box seen empty on this checkout and ticked later was ticked by the user: their choice.
        val chosen = unticked.toSet()
        fun collect(n: ScreenNode) { if (n.checkable && !n.checked) unticked += n.label.trim(); n.children.forEach(::collect) }
        collect(snapshot)
        val fresh = scan.findings.filter { it.key !in seen && !(it.nodeId != null && it.evidence in chosen) }
        if (fresh.isEmpty()) return
        seen += fresh.map { it.key }

        val plan = CheckoutGate.plan(CheckoutScan(true, fresh), store.policy.value)
        val app = appLabel(pkg)
        store.record(
            Channel.A, Verdict.FLAGGED,
            "${fresh.size} dark pattern(s) on a checkout in $app",
            patterns = fresh.size,
        )

        if (plan.autoRemove.isNotEmpty()) {
            val saved = untick(root, scan, plan.autoRemove)
            if (saved.isNotEmpty()) {
                store.record(
                    Channel.A, Verdict.FIXED,
                    "Auto-removed ${saved.size} pre-ticked item(s) in $app",
                    savedPaise = saved.sumOf { it.cost },
                )
            }
        }

        when {
            plan.shouldIntercept -> {
                InterceptState.show(Intercept(pkg, app, plan))
                startActivity(
                    Intent(this, InterceptActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            }
            plan.shouldNotify -> notify(app, plan.flag + plan.autoRemove)
        }
    }

    /**
     * Unticks the boxes behind the given findings, after checking each one against a fresh
     * reading of the screen. Returns the findings that were actually removed.
     */
    private fun untick(root: AccessibilityNodeInfo, scan: CheckoutScan, targets: List<Finding>): List<Finding> =
        targets.filter { f ->
            val id = f.nodeId ?: return@filter false
            if (!CheckoutGate.mayUntick(id, scan)) return@filter false
            val node = ScreenSnapshot.resolve(root, id) ?: return@filter false
            // Either a ticked box, or the "Remove" link beside an extra that has no box.
            val ok = if (node.isCheckable) with(ScreenSnapshot) { node.isCheckedCompat }
            else DarkPatternScanner.isRemoveControl((node.text ?: node.contentDescription ?: "").toString())
            if (!ok) return@filter false
            clickable(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        repeat(3) {
            if (n?.isClickable == true) return n
            n = n?.parent
        }
        return null
    }

    /**
     * Called when the user taps "Remove" on the intercept sheet. Waits for the sheet to close,
     * re-reads the shopping app's screen, and removes only what is still there and still
     * matches what the user approved.
     */
    fun applyFixes(pkg: String, approvedKeys: Set<String>) {
        handler.postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed
            if (root.packageName?.toString() != pkg) return@postDelayed
            val scan = store.scanner.scan(ScreenSnapshot.capture(root))
            val targets = scan.findings.filter { it.fixable && it.key in approvedKeys }
            val removed = untick(root, scan, targets)
            if (removed.isNotEmpty()) {
                val recurring = removed.sumOf { it.recurring }
                store.record(
                    Channel.A, Verdict.FIXED,
                    "Removed ${removed.size} item(s) in ${appLabel(pkg)}" +
                        (if (recurring > 0) ", avoiding ${Money.format(recurring)}/month" else ""),
                    savedPaise = removed.sumOf { it.cost },
                )
            }
        }, AFTER_SHEET_MS)
    }

    private fun notify(app: String, findings: List<Finding>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = findings.joinToString(" · ") { it.kind.label }
        NotificationManagerCompat.from(this).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, PardaApp.CHANNEL_INTERCEPTS)
                .setSmallIcon(R.drawable.ic_parda)
                .setContentTitle("Parda flagged ${findings.size} thing(s) in $app")
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun appLabel(pkg: String): String = if (pkg == packageName) "the demo store" else runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private val debuggable by lazy { applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }

    companion object {
        private const val TAG = "PardaShield"
        private const val DEBOUNCE_MS = 600L
        private const val FORGET_AFTER_MS = 10_000L

        /**
         * Findings already acted on, per app, so one checkout is not reported twice. Per app
         * because the active window flickers between apps while sheets open and close; held
         * here, not on the instance, because some OEMs (iQOO) rebind the service every few
         * seconds and each rebind is a fresh object.
         */
        private val handled = HashMap<String, MutableSet<String>>()
        private val lastCheckoutAt = HashMap<String, Long>()

        /** Labels of boxes seen unticked on the current checkout, per app. */
        private val seenUnticked = HashMap<String, MutableSet<String>>()
        private const val AFTER_SHEET_MS = 450L
        private const val NOTIFICATION_ID = 1

        /** System surfaces that are never checkouts. */
        private val IGNORED_PACKAGES = setOf("com.android.systemui", "android", "com.android.settings")

        @Volatile
        var instance: CheckoutWatchService? = null
            private set

        /** When the shield last saw anything on screen: proof it is really running, not just switched on. */
        @Volatile
        var lastEventAt = 0L
            private set

        /** Bound by the system right now. Switched on in Settings is not enough: OEMs stop services. */
        val running: Boolean get() = instance != null

        /** Forgets what was handled in [pkg], so the next checkout there is reported afresh. */
        fun forget(pkg: String) {
            handled.remove(pkg)
            lastCheckoutAt.remove(pkg)
            seenUnticked.remove(pkg)
        }

        fun isEnabled(context: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { it.startsWith(context.packageName + "/") }
        }
    }
}
