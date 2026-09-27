package app.parda.service

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.clickable
import app.parda.ui.title
import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
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
        remember(pkg, snapshot, scan)
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
            if (now - (lastCheckoutAt[pkg] ?: 0L) > FORGET_AFTER_MS) { seen.clear(); unticked.clear(); modelFound.remove(pkg) }
            return
        }
        lastCheckoutAt[pkg] = now
        review(pkg, snapshot, scan)
        // A box seen empty on this checkout and ticked later was ticked by the user: their choice.
        val chosen = unticked.toSet()
        fun collect(n: ScreenNode) { if (n.checkable && !n.checked) unticked += n.label.trim(); n.children.forEach(::collect) }
        collect(snapshot)
        val fresh = scan.findings.filter { it.key !in seen && !(it.nodeId != null && it.evidence in chosen) }
        if (fresh.isEmpty()) return
        seen += fresh.map { it.key }

        val plan = CheckoutGate.plan(CheckoutScan(true, fresh), store.policy.value, app = pkg)
        val app = appLabel(pkg)
        store.record(
            Channel.A, Verdict.FLAGGED,
            "${fresh.size} dark pattern(s) on a checkout in $app",
            patterns = fresh.size,
        )

        var saved = emptyList<Finding>()
        if (plan.autoRemove.isNotEmpty()) {
            saved = untick(root, scan, plan.autoRemove)
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
            plan.shouldNotify -> notify(app, plan.flag, saved)
        }
    }

    /**
     * The on-device model's second look, for tricks worded in ways the rules do not know. Runs off
     * the main thread, once per distinct screen (digits ignored, so a ticking countdown is one
     * screen). What it finds has already been checked against the screen in core, and is only ever
     * offered: added to the sheet if it is up, or shown on its own if the rules found nothing.
     */
    private fun review(pkg: String, snapshot: ScreenNode, scan: CheckoutScan) {
        val reviewer = store.reviewer ?: return
        val signature = pkg + "\n" + store.scanner.lines(snapshot).joinToString("\n") { it.text.replace(DIGITS, "#") }
        synchronized(reviewed) {
            if (!reviewed.add(signature)) return
            if (reviewed.size > MAX_REVIEWED) reviewed.remove(reviewed.first())
        }
        val started = SystemClock.elapsedRealtime()
        reviews.execute {
            val found = reviewer.review(snapshot, scan)
            val took = SystemClock.elapsedRealtime() - started
            if (debuggable) {
                Log.i(TAG, "model review of $pkg: ${found.size} finding(s) in $took ms")
                found.forEach { Log.i(TAG, "model finding ${it.kind} fixable=${it.fixable} cost=${it.cost}: ${it.evidence}") }
            }
            // Too late to matter: the user has moved on or already paid.
            if (found.isEmpty() || took > REVIEW_BUDGET_MS) return@execute
            handler.post { offer(pkg, found) }
        }
    }

    private fun offer(pkg: String, found: List<Finding>) {
        val showing = InterceptState.current.value?.takeIf { it.packageName == pkg }
        if (showing == null) {
            // Only while that checkout is still on screen.
            val root = rootInActiveWindow ?: return
            if (root.packageName?.toString() != pkg) return
            if (!store.scanner.scan(ScreenSnapshot.capture(root)).isCheckout) return
        }
        val seen = handled.getOrPut(pkg) { HashSet() }
        val new = found.filter { it.key !in seen }
        if (new.isEmpty()) return
        seen += new.map { it.key }
        modelFound.getOrPut(pkg) { mutableListOf() }.addAll(new)

        val app = appLabel(pkg)
        store.record(
            Channel.A, Verdict.FLAGGED,
            "The on-device model found ${new.size} more thing(s) on a checkout in $app",
            patterns = new.size,
        )
        val plan = CheckoutGate.plan(CheckoutScan(true, new), store.policy.value, app = pkg)
        when {
            showing != null && plan.ask.isNotEmpty() -> {
                InterceptState.show(showing.copy(plan = showing.plan.copy(ask = showing.plan.ask + plan.ask)))
                openSheet()
            }
            showing == null && plan.shouldIntercept -> {
                InterceptState.show(Intercept(pkg, app, plan))
                openSheet()
            }
            plan.flag.isNotEmpty() -> notify(app, plan.flag)
        }
    }

    private fun openSheet() = startActivity(
        Intent(this, InterceptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
    )

    /**
     * Keeps the last few screens that showed prices, in memory only, so the user can report one
     * Parda got wrong. A screen of the same app replaces the previous one.
     */
    private fun remember(pkg: String, snapshot: ScreenNode, scan: CheckoutScan) {
        val dump = ScreenSnapshot.dump(snapshot)
        if (!scan.isCheckout && Money.oneOffAmounts(dump).size < 2) return
        val seen = Seen(pkg, appLabel(pkg), System.currentTimeMillis(), dump.take(MAX_DUMP), scan.isCheckout, scan.findings.map { "${it.kind.label}: ${it.evidence}" })
        synchronized(recent) {
            recent.removeAll { it.app == pkg }
            recent.addFirst(seen)
            while (recent.size > MAX_RECENT) recent.removeLast()
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
            val snapshot = ScreenSnapshot.capture(root)
            val rules = store.scanner.scan(snapshot)
            // What the model found and the user approved, if it is still a ticked box on screen.
            val model = modelFound[pkg].orEmpty().filter { f ->
                f.fixable && f.key in approvedKeys && f.key !in rules.findings.map { it.key } && isTicked(snapshot, f.nodeId!!)
            }
            val scan = rules.copy(findings = rules.findings + model)
            val targets = scan.findings.filter { it.fixable && it.key in approvedKeys }
            val removed = untick(root, scan, targets)
            if (removed.isNotEmpty()) {
                // Remembered per app, so after a couple of times Parda can offer to do it unasked.
                store.recordRemovals(pkg, removed.map { it.kind })
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

    /**
     * Puts the masked copy (already on the clipboard) in place of [original] in the focused text
     * box of [pkg], just after "Mask with Parda" closes. Leaving the browser for that moment
     * collapses its selection to a cursor after the words, which is why a page then adds the text
     * as a new paragraph. So the words are selected again first, then pasted over: every web editor
     * replaces a selection on paste. Only an editable box of the app that asked is touched, and only
     * if it still holds [original]; [onDone] says whether the text was replaced.
     */
    fun pasteMasked(pkg: String, original: String, onDone: (Boolean) -> Unit) {
        fun done(ok: Boolean, why: String) {
            if (debuggable) Log.i(TAG, "mask in $pkg: ${if (ok) "replaced" else "not replaced ($why)"}")
            onDone(ok)
        }
        fun attempt(retries: Int) {
            val root = rootInActiveWindow
            // The browser's window takes a moment to be active again after Parda's closes.
            if (root?.packageName?.toString() != pkg) {
                if (retries > 0) handler.postDelayed({ attempt(retries - 1) }, PASTE_RETRY_MS) else done(false, "window ${root?.packageName}")
                return
            }
            // Chrome does not always report its web text box as the focused input: then look for the
            // editable box that holds the words (the focused one first, if several do).
            val box = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable && original in (it.text ?: "") }
                ?: editableHolding(root, original)
                ?: return if (retries > 0) handler.postDelayed({ attempt(retries - 1) }, PASTE_RETRY_MS).let {} else done(false, "no box holds the words")
            val text = box.text?.toString().orEmpty()
            val end = box.textSelectionEnd
            // The words just before the cursor, else their first appearance in the box.
            val start = (end - original.length).takeIf { it >= 0 && text.regionMatches(it, original, 0, original.length) }
                ?: text.indexOf(original).takeIf { it >= 0 }
                ?: return done(false, "words not found in ${text.length} chars, cursor $end")
            val range = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, start + original.length)
            }
            if (!box.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, range)) return done(false, "selection refused")
            handler.postDelayed({
                done(box.performAction(AccessibilityNodeInfo.ACTION_PASTE), "paste refused")
            }, SELECT_TO_PASTE_MS)
        }
        handler.postDelayed({ attempt(PASTE_RETRIES) }, PASTE_AFTER_MS)
    }

    /** An editable box under [root] whose text contains [words]; a focused one wins. */
    private fun editableHolding(root: AccessibilityNodeInfo, words: String): AccessibilityNodeInfo? {
        val found = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque(listOf(root))
        var seen = 0
        while (queue.isNotEmpty() && seen++ < MAX_NODES) {
            val n = queue.removeFirst()
            if (n.isEditable && words in (n.text ?: "")) found += n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return found.firstOrNull { it.isFocused } ?: found.firstOrNull()
    }

    private fun isTicked(n: ScreenNode, id: String): Boolean =
        if (n.id == id) n.checkable && n.checked else n.children.any { isTicked(it, id) }

    /** [removed]: extras taken out without asking, by a rule the user set for this app. */
    private fun notify(app: String, flagged: List<Finding>, removed: List<Finding> = emptyList()) {
        if (flagged.isEmpty() && removed.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (removed.isNotEmpty()) {
            resources.getQuantityString(R.plurals.pl_notif_removed, removed.size, removed.size, app) +
                removed.sumOf { it.cost }.let { if (it > 0) getString(R.string.notif_kept, Money.format(it)) else "" }
        } else {
            resources.getQuantityString(R.plurals.pl_notif_flagged, flagged.size, flagged.size, app)
        }
        val text = (removed.map { it.evidence } + flagged.map { it.kind.title }).joinToString(" · ")
        NotificationManagerCompat.from(this).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, PardaApp.CHANNEL_INTERCEPTS)
                .setSmallIcon(R.drawable.ic_parda)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun appLabel(pkg: String): String = if (pkg == packageName) getString(R.string.the_demo_store) else runCatching {
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

        /** What the model found on the current checkout, per app, so an approved fix can be applied. */
        private val modelFound = HashMap<String, MutableList<Finding>>()

        /** Screens the model has already looked at, oldest first. */
        private val reviewed = LinkedHashSet<String>()
        private const val MAX_REVIEWED = 40
        private val DIGITS = Regex("""\d""")

        /** One review at a time, off the main thread; the engine answers one request at a time anyway. */
        private val reviews = Executors.newSingleThreadExecutor { r -> Thread(r, "parda-checkout-review").apply { isDaemon = true } }

        /** A review that takes longer than this is dropped: by then the user has moved on. */
        private const val REVIEW_BUDGET_MS = 15_000L
        private const val AFTER_SHEET_MS = 450L
        private const val PASTE_AFTER_MS = 350L
        private const val PASTE_RETRY_MS = 250L
        private const val PASTE_RETRIES = 4
        private const val SELECT_TO_PASTE_MS = 120L
        private const val MAX_NODES = 3000
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

        /** A screen with prices the shield saw, for "Report a miss". Never written to disk. */
        data class Seen(val app: String, val label: String, val at: Long, val dump: String, val checkout: Boolean, val findings: List<String>)

        private val recent = ArrayDeque<Seen>()
        private const val MAX_RECENT = 5
        private const val MAX_DUMP = 30_000

        /** Newest first. */
        val recentScreens: List<Seen> get() = synchronized(recent) { recent.toList() }

        /** Bound by the system right now. Switched on in Settings is not enough: OEMs stop services. */
        val running: Boolean get() = instance != null

        /** Forgets what was handled in [pkg], so the next checkout there is reported afresh. */
        fun forget(pkg: String) {
            handled.remove(pkg)
            lastCheckoutAt.remove(pkg)
            seenUnticked.remove(pkg)
            modelFound.remove(pkg)
            synchronized(reviewed) { reviewed.removeAll { it.startsWith(pkg + "\n") } }
        }

        fun isEnabled(context: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { it.startsWith(context.packageName + "/") }
        }
    }
}
