package app.parda.core.checkout

import app.parda.core.policy.CheckoutAction
import app.parda.core.policy.DarkPatternKind
import app.parda.core.policy.Policy

/**
 * A platform-neutral view of one on-screen element. The Android build fills this from
 * AccessibilityNodeInfo; tests build it by hand.
 */
data class ScreenNode(
    val id: String,
    val text: String? = null,
    val contentDescription: String? = null,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val children: List<ScreenNode> = emptyList(),
) {
    /** What the node says. Web pages sometimes leak markup into labels ("<font …>Price Drop…</font>"). */
    val label: String get() = listOfNotNull(text, contentDescription).firstOrNull { it.isNotBlank() }.orEmpty()
        .let { if ('<' in it) it.replace(TAG, "").trim() else it }

    private companion object {
        val TAG = Regex("""</?[a-zA-Z][^<>]*>""")
    }
}

data class Finding(
    val kind: DarkPatternKind,
    /** What the screen says, as read. */
    val evidence: String,
    /** The node a fix would untick. Only set when the fix is possible. */
    val nodeId: String?,
    /** One-off charge added by this pattern, in paise. */
    val cost: Long = 0,
    /** Monthly charge this pattern commits the user to, in paise. */
    val recurring: Long = 0,
) {
    val fixable: Boolean get() = nodeId != null && kind.fixable

    /** Stable identity across re-renders. Digits are ignored so a ticking countdown stays one finding. */
    val key: String get() = kind.name + ":" + evidence.replace(DIGITS, "#")

    private companion object {
        val DIGITS = Regex("""\d""")
    }
}

data class CheckoutScan(val isCheckout: Boolean, val findings: List<Finding>) {
    val recoverable: Long get() = findings.filter { it.fixable }.sumOf { it.cost }
    val recurring: Long get() = findings.sumOf { it.recurring }
    val disclosedOnly: Long get() = findings.filterNot { it.fixable }.sumOf { it.cost }

    companion object {
        val NONE = CheckoutScan(false, emptyList())
    }
}

/** Channel A classifier. Reads a screen; never touches it. */
class DarkPatternScanner {

    fun scan(root: ScreenNode): CheckoutScan {
        val parents = HashMap<String, ScreenNode>()
        val nextSibling = HashMap<String, ScreenNode>()
        val all = mutableListOf<ScreenNode>()
        fun walk(n: ScreenNode) {
            all += n
            n.children.zipWithNext { a, b -> nextSibling[a.id] = b }
            n.children.forEach { parents[it.id] = n; walk(it) }
        }
        walk(root)

        val screenText = all.joinToString("\n") { it.label }
        // A checkout word alone is not enough (news feeds say "payment"); a cart shows prices.
        // A ride app's "tip for faster pickup" screen is where money is asked for, though it names no checkout.
        val asked = CHECKOUT.containsMatchIn(screenText) || PRIORITY.containsMatchIn(screenText)
        if (!asked || Money.oneOffAmounts(screenText).size < MIN_PRICES) {
            return CheckoutScan.NONE
        }
        fun textOf(n: ScreenNode): String {
            val row = rowOf(n, parents)
            if (row !== n) return rowText(row, n)
            // Flat layout (web pages in Chrome): the price is the next sibling, not a child.
            val next = nextSibling[n.id]?.takeIf { !it.checkable && PRICE_ONLY.matches(it.label.trim()) }
            // A checkable row can carry its label and price in its own children.
            return listOfNotNull(rowText(n, n).ifBlank { null }, next?.label).joinToString("  ")
        }

        val findings = mutableListOf<Finding>()
        val rowsSeen = HashSet<String>()

        // 1. Pre-ticked items: add-ons and trials.
        for (n in all.filter { it.checkable && it.checked }) {
            // Web checkboxes often nest a ticked box inside a ticked label: report the outer one.
            if (generateSequence(parents[n.id]) { parents[it.id] }.any { it.checkable && it.checked }) continue
            val row = rowOf(n, parents)
            val text = textOf(n)
            val kind = when {
                SUBSCRIPTION.containsMatchIn(text) -> DarkPatternKind.SUBSCRIPTION_TRAP
                ADDON.containsMatchIn(text) -> DarkPatternKind.BASKET_SNEAKING
                // Costs nothing now, but signs the user up for something they did not ask for.
                CONSENT.containsMatchIn(text) -> DarkPatternKind.FORCED_ACTION
                else -> continue
            }
            rowsSeen += row.id
            findings += Finding(
                kind = kind,
                evidence = cleanLabel(n.label.ifBlank { text }), // the item itself; its price is shown separately
                nodeId = n.id,
                cost = Money.oneOffAmounts(text).firstOrNull() ?: 0,
                recurring = Money.recurringAmount(text) ?: 0,
            )
        }

        // 1b. Extras already in the cart with no checkbox, only a "Remove" link beside them
        // ("If price drops, get your money back!  REMOVE"). The link is what a fix would tap.
        for (n in all.filter { !it.checkable && isRemoveControl(it.label) }) {
            val row = rowOf(n, parents)
            if (row === n) continue
            val text = rowText(row, n).replace(n.label, "").trim()
            val kind = when {
                SUBSCRIPTION.containsMatchIn(text) -> DarkPatternKind.SUBSCRIPTION_TRAP
                ADDON.containsMatchIn(text) -> DarkPatternKind.BASKET_SNEAKING
                else -> continue
            }
            if (!rowsSeen.add(row.id)) continue
            findings += Finding(kind, cleanLabel(text), n.id, Money.oneOffAmounts(text).firstOrNull() ?: 0, Money.recurringAmount(text) ?: 0)
        }

        // 1c. A membership that is already a line of the bill, not merely offered ("Add Gold at ₹1").
        for (n in all.filter { !it.checkable && SUBSCRIPTION.containsMatchIn(it.label) }) {
            val row = rowOf(n, parents)
            val text = textOf(n)
            if (OFFER.containsMatchIn(text) || Money.oneOffAmounts(text).isEmpty() && Money.recurringAmount(text) == null) continue
            if (!rowsSeen.add(row.id)) continue
            findings += Finding(
                DarkPatternKind.SUBSCRIPTION_TRAP, cleanLabel(n.label), null,
                Money.oneOffAmounts(text).firstOrNull() ?: 0, Money.recurringAmount(text) ?: 0,
            )
        }

        // 2. Fees that appear only at the last step.
        for (n in all.filter { !it.checkable && FEE.containsMatchIn(it.label) }) {
            val row = rowOf(n, parents)
            if (!rowsSeen.add(row.id)) continue
            val text = textOf(n)
            val cost = Money.oneOffAmounts(text).firstOrNull() ?: continue
            if (cost > 0) findings += Finding(DarkPatternKind.DRIP_PRICING, cleanLabel(n.label.ifBlank { text }), null, cost)
        }

        // 3. Pressure: countdowns and scarcity.
        all.map { it.label }.filter { URGENCY.containsMatchIn(it) }.distinct()
            .forEach { findings += Finding(DarkPatternKind.FALSE_URGENCY, it.trim(), null) }

        // 4. Guilt-worded decline options.
        all.map { it.label }.filter { SHAMING.containsMatchIn(it) }.distinct()
            .forEach { findings += Finding(DarkPatternKind.CONFIRM_SHAMING, it.trim(), null) }

        // 5. Paying more presented as the way to be served sooner ("Add a tip for faster pickup").
        all.map { it.label }.filter { PRIORITY.containsMatchIn(it) }.distinct()
            .forEach { findings += Finding(DarkPatternKind.PAY_FOR_PRIORITY, it.trim(), null, Money.oneOffAmounts(it).firstOrNull() ?: 0) }

        // 6. Wording that makes declining the thing you have to do ("untick this box if you do not wish…").
        all.map { it.label }.filter { TRICK.containsMatchIn(it) }.distinct()
            .forEach { findings += Finding(DarkPatternKind.TRICK_WORDING, it.trim(), null) }

        return CheckoutScan(true, findings)
    }

    /**
     * The row a node belongs to: the nearest ancestor that is still small enough to be a single
     * line item (a checkbox, a label and a price). It climbs past wrappers until it reaches a
     * price, because apps nest them: Swiggy puts "Handling Fee" three levels below the row that
     * holds "₹12.00". A node with no small ancestor is its own row.
     */
    private fun rowOf(n: ScreenNode, parents: Map<String, ScreenNode>): ScreenNode {
        var row = n
        while (true) {
            val p = parents[row.id] ?: break
            if (countLabelled(p) > MAX_ROW_TEXTS) break
            row = p
            if (hasPrice(row)) break
        }
        return row
    }

    private fun hasPrice(n: ScreenNode): Boolean =
        PRICE_ONLY.containsMatchIn(n.label) || n.children.any { hasPrice(it) }

    private fun countLabelled(n: ScreenNode): Int =
        (if (n.label.isNotBlank()) 1 else 0) + n.children.sumOf { countLabelled(it) }

    private fun rowText(row: ScreenNode, node: ScreenNode): String {
        val parts = mutableListOf<String>()
        fun collect(x: ScreenNode) { if (x.label.isNotBlank()) parts += x.label; x.children.forEach(::collect) }
        collect(row)
        if (node.label.isNotBlank() && node.label !in parts) parts.add(0, node.label)
        return parts.joinToString("  ")
    }

    private fun cleanLabel(text: String) =
        text.replace(Regex("""[☑☐✓✔]\s*"""), "").replace(Regex("""\s{2,}"""), "  ").trim()

    companion object {
        private const val MAX_ROW_TEXTS = 4
        private const val MIN_PRICES = 2

        private val PRICE_ONLY = Regex("""(?i)(\bfree\b|(₹|\brs\.?|\binr)\s?\d[\d,]*(\.\d{1,2})?|\d[\d,]*(\.\d{1,2})?\s?rupees?\b)""")

        // Real carts name their last step many ways: Swiggy "To Pay", Zomato "Bill Summary",
        // Goibibo's traveller page only "₹ 6,768 FOR 1 ADULT".
        private val CHECKOUT = Regex(
            """\b(checkout|check out|to pay|total payable|amount payable|place order|pay now|proceed to pay|order summary|bill details|payment""" +
                """|bill summary|total bill|grand total|item total|total amount|amount to pay|fare summary|fare breakup|traveller details|review booking|your cart|for \d+ adults?""" +
                // BookMyShow "Booking Summary", PharmEasy "Price details", ride apps before a booking.
                """|booking summary|payment summary|price details|price summary|order details|review (your )?(order|trip|booking)""" +
                """|choose a ride|confirm (pickup|ride|booking)|book (ride|auto|bike|cab)|fare (details|estimate))\b""",
            RegexOption.IGNORE_CASE,
        )

        /** A link or button that takes an extra back out of the cart. */
        private val REMOVE = Regex("""(?i)^\s*(remove|remove add-?on|remove item|✕)\s*$""")

        /** Wording of an offer the user has not taken up yet. */
        private val OFFER = Regex("""(?i)(^|\s)(add|get|join|try|unlock|upgrade|save|buy)\s""")

        /** True for a "Remove" control: a fix may tap it to take out an extra Parda found. */
        fun isRemoveControl(label: String): Boolean = REMOVE.matches(label)
        private val SUBSCRIPTION = Regex(
            """\b(free trial|trial|auto[- ]?renew\w*|membership|subscribe|subscription|per month|/mo\b""" +
                // Paid memberships that are named for the brand, not "membership" (Zepto Pass, Swiggy One).
                """|(zepto|swiggy|super|delivery|savings?) pass|swiggy one|zomato gold|pharmeasy plus|one blck)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val ADDON = Regex(
            """\b(protection|insurance|insure|warranty|donat\w*|charity|contribut\w*|plantation|plant\w* trees?|round[- ]?up|tip|gift wrap|priority""" +
                """|care plan|cover|safety fee|price drops?|money back|trip secure""" +
                // Named causes and travel extras: Zomato and Blinkit, Physics Wallah, BookMyShow, MakeMyTrip.
                """|feeding india|pw foundation|book ?a ?smile|zero cancellation|cancellation (protection|protect|cover|shield|guard)|trip guard|travel guard|assurance)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val FEE = Regex(
            """\b(handling|convenience|platform|packaging|packing|service|small[- ](cart|order)|processing|surge|rain|late[- ]night|booking|payment gateway|gateway) (fee|charge)s?\b""",
            RegexOption.IGNORE_CASE,
        )

        /** A box that signs the user up or opts them in, rather than adding an item: SpiceJet's SpiceClub. */
        private val CONSENT = Regex(
            """\b(enrol\w*|enroll\w*|sign me up|loyalty|rewards|frequent flyer|\w*club|promotional|promotions|marketing|newsletter""" +
                """|(receive|send me|get) (offers|updates|deals|communications?)|whatsapp (updates|notifications|alerts))\b""",
            RegexOption.IGNORE_CASE,
        )

        /** Paying more framed as being served sooner: Uber's "advance tip", Rapido's fare boost. */
        private val PRIORITY = Regex(
            """\b(tip|boost|increase|raise|add|pay)\b[^.\n]{0,40}\b(faster|quicker|sooner|priority)\s+(pickup|pick-up|pick up|ride|driver|captain|acceptance|allocation|match\w*)\b""" +
                """|\b(get|find)\s+(a |your )?(ride|driver|captain|cab|auto)\s+(faster|sooner|quicker)\b""" +
                """|\bset your (own )?(price|fare)\b|\b(increase|raise|boost)\s+(your |the )?(fare|price|offer)\b""",
            RegexOption.IGNORE_CASE,
        )

        /** Declining is the action: "untick this box if you do not wish to receive…". */
        private val TRICK = Regex(
            """\b(un-?tick|uncheck|de-?select|clear)\s+(this|the)\s+(check\s?)?box\s+(if|to)\b""" +
                """|\b(tick|check|select)\s+(this|the)\s+(check\s?)?box\s+if\s+you\s+(do not|don'?t)\b""" +
                """|\bif\s+you\s+(do not|don'?t)\s+(wish|want)\s+to\s+(receive|be\s+(contacted|enrolled|enroled))\b""",
            RegexOption.IGNORE_CASE,
        )
        private val URGENCY = Regex(
            """(\b(ends|expires|closes)\s+in\s+\d{1,2}:\d{2}\b)|(\bonly\s+\d+\s+(left|remaining)\b)|(\bhurry\b)|(\bselling fast\b)|(\d+\s+people\s+(are\s+)?(viewing|looking))""",
            RegexOption.IGNORE_CASE,
        )
        private val SHAMING = Regex(
            """(^\s*no,?\s+thanks?\b.*\b(don'?t|do not|hate|rather|prefer|not interested in saving))|(\bi\s+(don'?t|do not)\s+(care|like|want)\b.*\b(sav\w+|support\w*|protect\w*|help\w*))""" +
                // IndiGo's "No I will take risk" (since changed to "No, I will not add to the trip").
                """|(\bi('ll|\s+will)\s+take\s+(the\s+|my\s+|a\s+)?(risk|chances?)\b)|(\bi('ll|\s+will)\s+risk\s+it\b)|(\bleave\s+me\s+unprotected\b)""" +
                """|(\bi\s+(don'?t|do not)\s+want\s+to\s+be\s+(protected|covered|insured|safe)\b)|(\bi('d|\s+would)\s+rather\s+(pay|risk|lose)\b)""",
            RegexOption.IGNORE_CASE,
        )
    }
}

/** What Parda will do about one scan, after the user's policy has had its say. */
data class CheckoutPlan(
    val scan: CheckoutScan,
    val autoRemove: List<Finding>,
    val ask: List<Finding>,
    val flag: List<Finding>,
) {
    val shouldIntercept: Boolean get() = ask.isNotEmpty()
    val shouldNotify: Boolean get() = ask.isNotEmpty() || flag.isNotEmpty() || autoRemove.isNotEmpty()
}

/**
 * Channel A gate. Decides which findings are acted on, and is the only thing that may
 * authorise an on-screen action: unticking a checkbox Parda itself identified. It has no
 * way to authorise tapping Pay, or any other node.
 */
object CheckoutGate {
    /** [app] is the package on screen, so the user's per-app rules apply there and nowhere else. */
    fun plan(scan: CheckoutScan, policy: Policy, app: String? = null): CheckoutPlan {
        val auto = mutableListOf<Finding>()
        val ask = mutableListOf<Finding>()
        val flag = mutableListOf<Finding>()
        for (f in scan.findings) {
            when (policy.actionFor(f.kind, app)) {
                CheckoutAction.AUTO_REMOVE -> if (f.fixable) auto += f else ask += f
                CheckoutAction.ASK_ME -> ask += f
                CheckoutAction.FLAG_ONLY -> flag += f
                CheckoutAction.IGNORE -> Unit
            }
        }
        return CheckoutPlan(scan, auto, ask, flag)
    }

    fun mayUntick(nodeId: String, scan: CheckoutScan): Boolean =
        scan.findings.any { it.fixable && it.nodeId == nodeId }
}
