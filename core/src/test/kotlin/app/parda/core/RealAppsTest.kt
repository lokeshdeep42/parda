package app.parda.core

import app.parda.core.checkout.CheckoutGate
import app.parda.core.checkout.DarkPatternScanner
import app.parda.core.checkout.Money
import app.parda.core.checkout.ScreenNode
import app.parda.core.policy.DarkPatternKind
import app.parda.core.policy.Policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Screens as the shield read them in real apps on the phone (PardaShield debug dumps, trimmed
 * to the parts that matter), so each miss found on the device stays fixed.
 */
class RealAppsTest {
    private val scanner = DarkPatternScanner()
    private var ids = 0
    private fun n(text: String? = null, vararg children: ScreenNode, checkable: Boolean = false, checked: Boolean = false) =
        ScreenNode("n${ids++}", text = text, checkable = checkable, checked = checked, children = children.toList())

    /** Swiggy Instamart: prices spelled out for screen readers, and the fee three levels below its row. */
    @Test fun `Swiggy - handling fee nested away from its price, prices in words`() {
        val cart = n(
            null,
            n("Your Cart"),
            n(null, n("Fast & Up Reload Zero Sugar, 100 g, quantity 1, price 230 rupees"), n("Remove 1 item from cart")),
            n(null, n("Save the Environment"), n("Take the pledge for a greener future - opt for a no bag delivery!"), n("Enable reusable bag", checkable = true)),
            n(null, n("Tip 10 rupees"), n("Most tipped 20 rupees"), n("Tip 30 rupees")),
            n(
                null,
                n("BILL DETAILS"),
                n(null, n(null, n("Item Total")), n("₹230.00")),
                n(null, n(null, n(null, n(null, n("Handling Fee")))), n("₹12.00")),
                n(null, n(null, n("Delivery Partner Tip")), n("Add a tip")),
                n(null, n(null, n("To Pay")), n("₹242")),
            ),
        )
        val scan = scanner.scan(cart)
        assertTrue(scan.isCheckout)
        assertEquals(listOf(DarkPatternKind.DRIP_PRICING to 1_200L), scan.findings.map { it.kind to it.cost })
        assertEquals(listOf(23_000L, 1_000L), Money.oneOffAmounts("price 230 rupees, Tip 10 rupees"))
    }

    /** A membership that has made it into the bill is disclosed; one that is only offered is not. */
    @Test fun `a membership in the bill is flagged, an offer to add one is not`() {
        val offered = n(null, n("Bill Summary"), n(null, n("Item total"), n("₹353")), n(null, n("Save ₹28 with free delivery"), n("Add Gold at ₹1 for 3 months")), n(null, n("To pay"), n("₹437")))
        assertEquals(emptyList(), scanner.scan(offered).findings)

        val added = n(null, n("Bill Summary"), n(null, n("Item total"), n("₹353")), n(null, n("Gold membership, 3 months"), n("₹1")), n(null, n("To pay"), n("₹438")))
        assertEquals(listOf(DarkPatternKind.SUBSCRIPTION_TRAP), scanner.scan(added).findings.map { it.kind })
    }

    /** Zomato adds a free course to the cart ("ADDED · FREE"): not money, so not a finding. */
    @Test fun `Zomato - a free item marked ADDED costs nothing and is not flagged`() {
        val cart = n(
            null,
            n(null, n("Special offer for you"), n("Master Claude & AI tools"), n(null, n("ADDED")), n("FREE")),
            n(null, n("Classic Paneer Steamed Momos (6pcs)"), n("₹353")),
            n(null, n("Bill Summary"), n(null, n("Item total"), n("₹353")), n(null, n("To pay"), n("₹437.13"))),
        )
        val scan = scanner.scan(cart)
        assertTrue(scan.isCheckout)
        assertEquals(emptyList(), scan.findings)
    }

    /** Goibibo's search page in Chrome: the label arrived wrapped in HTML. */
    @Test fun `Goibibo - markup in a label is stripped`() {
        val page = n(
            null,
            n("Up to ₹ 600 off"), n("Get Up To ₹7500 OFF* on Flight Bookings"), n("payment"),
            n("<font color='#000000'>Price Drop Protection will be added</font>", n(null, checkable = true, checked = true), checkable = true, checked = true),
        )
        val found = scanner.scan(page).findings
        assertEquals(1, found.size, found.toString())
        val f = found.single()
        assertEquals(DarkPatternKind.BASKET_SNEAKING, f.kind)
        assertEquals("Price Drop Protection will be added", f.evidence)
    }

    /**
     * Goibibo's traveller page, from the screenshot: no "total" or "pay" anywhere, a donation
     * switch left on, and price-drop cover added with only a REMOVE link to take it out.
     * (How Chrome exposes the switch is assumed: a checked, checkable node beside the text.)
     */
    @Test fun `Goibibo - traveller page, donation switch and price-drop cover with a REMOVE link`() {
        val page = n(
            null,
            n("New Delhi to Mumbai"), n("Traveller details"),
            n("I have a GST number (Optional)", checkable = true),
            n("Confirm and save billing details to your profile", checkable = true, checked = true),
            n(null, n("Swipe right to contribute ₹ 10 towards plantation of 4 million trees"), n(null, checkable = true, checked = true)),
            n(null, n("If price drops, get your money back!"), n("REMOVE")),
            n(null, n("₹ 6,768"), n("FOR 1 ADULT")), n("CONTINUE"),
        )
        val scan = scanner.scan(page)
        assertTrue(scan.isCheckout, "a traveller page is the last step before payment")
        assertEquals(2, scan.findings.count { it.kind == DarkPatternKind.BASKET_SNEAKING }, scan.findings.toString())
        assertTrue(scan.findings.all { it.fixable }, "a switch can be turned off and REMOVE can be tapped")
        assertEquals(1_000L, scan.findings.first { "contribute" in it.evidence }.cost)
        assertTrue(scan.findings.any { it.evidence == "If price drops, get your money back!" })
        assertTrue(CheckoutGate.plan(scan, Policy()).shouldIntercept)
    }

    @Test fun `only an exact Remove label counts as a remove control`() {
        assertTrue(DarkPatternScanner.isRemoveControl("REMOVE"))
        assertTrue(!DarkPatternScanner.isRemoveControl("Remove 1 item from cart"))
    }
}
