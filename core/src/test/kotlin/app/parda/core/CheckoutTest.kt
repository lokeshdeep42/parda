package app.parda.core

import app.parda.core.checkout.CheckoutGate
import app.parda.core.checkout.DarkPatternScanner
import app.parda.core.checkout.Money
import app.parda.core.checkout.ScreenNode
import app.parda.core.policy.CheckoutAction
import app.parda.core.policy.DarkPatternKind
import app.parda.core.policy.Policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckoutTest {
    private val scan = DarkPatternScanner().scan(Samples.CART)

    @Test fun `finds all five patterns on the sample cart`() {
        assertTrue(scan.isCheckout)
        assertEquals(
            setOf(
                DarkPatternKind.BASKET_SNEAKING, DarkPatternKind.SUBSCRIPTION_TRAP, DarkPatternKind.DRIP_PRICING,
                DarkPatternKind.FALSE_URGENCY, DarkPatternKind.CONFIRM_SHAMING,
            ),
            scan.findings.map { it.kind }.toSet(),
        )
        assertEquals(2, scan.findings.count { it.kind == DarkPatternKind.BASKET_SNEAKING })
    }

    @Test fun `costs are attributed correctly`() {
        assertEquals(15900, scan.recoverable) // ₹10 + ₹149; the trial is free today
        assertEquals(29900, scan.recurring)   // ₹299/mo
        assertEquals(4900, scan.disclosedOnly) // handling fee, cannot be unticked
    }

    @Test fun `the main item and delivery are not flagged`() {
        assertFalse(scan.findings.any { "kurta" in it.evidence || "delivery" in it.evidence.lowercase() })
    }

    @Test fun `gate only ever authorises unticking what it found`() {
        assertTrue(CheckoutGate.mayUntick("cb-prot", scan))
        assertFalse(CheckoutGate.mayUntick("pay", scan))
        assertFalse(CheckoutGate.mayUntick("amt-fee", scan))
    }

    @Test fun `policy routes findings`() {
        val policy = Policy()
            .with(DarkPatternKind.BASKET_SNEAKING, CheckoutAction.AUTO_REMOVE)
            .with(DarkPatternKind.DRIP_PRICING, CheckoutAction.AUTO_REMOVE) // not fixable: downgraded to ask
            .with(DarkPatternKind.FALSE_URGENCY, CheckoutAction.IGNORE)
        val plan = CheckoutGate.plan(scan, policy)
        assertEquals(2, plan.autoRemove.size)
        assertTrue(plan.ask.any { it.kind == DarkPatternKind.DRIP_PRICING })
        assertFalse((plan.ask + plan.flag).any { it.kind == DarkPatternKind.FALSE_URGENCY })
    }

    @Test fun `a ticking countdown keeps the same key`() {
        val a = scan.findings.first { it.kind == DarkPatternKind.FALSE_URGENCY }
        assertEquals(a.key, a.copy(evidence = a.evidence.replace("04:58", "04:57")).key)
    }

    /** Chrome flattens a web checkout: every label and price is a sibling under one container. */
    @Test fun `flat web checkouts pair each label with the price beside it`() {
        val labels = listOf(
            "Checkout", "Wireless earbuds", "₹2,499", "Delivery", "Free",
            "[x]Add 1-year warranty protection", "₹199", "[x]Donate to a cause", "₹5",
            "[x]Free 30-day trial of Plus membership, auto-renews at ₹179/mo", "Free",
            "Platform fee", "₹29", "Total payable", "₹2,732",
        )
        val flat = ScreenNode(
            "root",
            children = labels.mapIndexed { i, l ->
                val box = l.startsWith("[x]")
                ScreenNode("n$i", text = l.removePrefix("[x]"), checkable = box, checked = box)
            },
        )
        val web = DarkPatternScanner().scan(flat)
        assertEquals(20400, web.recoverable) // ₹199 + ₹5
        assertEquals(17900, web.recurring)
        assertEquals(2900, web.disclosedOnly) // platform fee
        assertFalse(web.findings.any { "earbuds" in it.evidence })
    }

    @Test fun `a word like payment without prices is not a checkout`() {
        val feed = ScreenNode(
            "root",
            children = listOf(
                ScreenNode("a", text = "New UPI payment rules explained"),
                ScreenNode("b", text = "Hurry: festive sale guide"),
            ),
        )
        assertFalse(DarkPatternScanner().scan(feed).isCheckout)
    }

    @Test fun `non-checkout screens are ignored`() {
        val chat = ScreenNode("root", children = listOf(ScreenNode("m", text = "Only 2 left in the group chat lol")))
        assertFalse(DarkPatternScanner().scan(chat).isCheckout)
    }

    @Test fun `unticked add-ons are not findings`() {
        val cart = ScreenNode(
            "root",
            children = listOf(
                ScreenNode("t", text = "To pay"),
                ScreenNode("r", children = listOf(ScreenNode("cb", text = "Add tip for rider", checkable = true), ScreenNode("a", text = "₹20"))),
            ),
        )
        assertTrue(DarkPatternScanner().scan(cart).findings.isEmpty())
    }

    @Test fun `money formats with indian grouping`() {
        assertEquals("₹18,40,000", Money.format(184000000))
        assertEquals("₹149", Money.format(14900))
        assertEquals("₹1,299.50", Money.format(129950))
        assertEquals(29900L, Money.recurringAmount("auto-renews at ₹299/mo"))
        assertEquals(listOf(1000L), Money.oneOffAmounts("₹10 now, then ₹299 per month"))
    }
}
