package app.parda.core

import app.parda.core.checkout.CheckoutGate
import app.parda.core.checkout.DarkPatternScanner
import app.parda.core.checkout.Precedents
import app.parda.core.checkout.ScreenNode
import app.parda.core.policy.DarkPatternKind
import app.parda.core.policy.Policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The patterns regulators and users have reported in Indian apps, rebuilt from those reports.
 * Wording on the real screens varies; when a phone capture shows a miss, add it to RealAppsTest.
 */
class LiveAppsTest {
    private val scanner = DarkPatternScanner()
    private var ids = 0
    private fun n(text: String? = null, vararg children: ScreenNode, checkable: Boolean = false, checked: Boolean = false) =
        ScreenNode("n${ids++}", text = text, checkable = checkable, checked = checked, children = children.toList())

    private fun kinds(root: ScreenNode) = scanner.scan(root).findings.map { it.kind }.toSet()

    @Test fun `Zepto - Pass ticked in the cart and a handling charge at the last step`() {
        val cart = n(
            null,
            n(null, n("Amul Taaza Toned Milk"), n("₹170")),
            n(null, n("Zepto Pass"), n("₹49"), n(null, checkable = true, checked = true)),
            n(null, n("Bill Summary"), n(null, n("Item Total"), n("₹170")), n(null, n("Handling Charge"), n("₹7.40")), n(null, n("To Pay"), n("₹226.40"))),
        )
        val scan = scanner.scan(cart)
        assertEquals(setOf(DarkPatternKind.SUBSCRIPTION_TRAP, DarkPatternKind.DRIP_PRICING), scan.findings.map { it.kind }.toSet(), scan.findings.toString())
        assertEquals(4_900L, scan.recoverable)
        assertEquals(740L, scan.disclosedOnly)
    }

    @Test fun `PharmEasy - PLUS membership added at checkout`() {
        val cart = n(
            null,
            n("Price details"),
            n(null, n("Dolo 650 Tablet"), n("₹30.91")),
            n(null, n("PLUS membership for 3 months"), n("₹99"), checkable = true, checked = true),
            n(null, n("Total amount"), n("₹129.91")),
        )
        val f = scanner.scan(cart).findings.single()
        assertEquals(DarkPatternKind.SUBSCRIPTION_TRAP, f.kind)
        assertTrue(f.fixable)
        assertEquals(9_900L, f.cost)
    }

    @Test fun `Physics Wallah - pre-selected PW Foundation donation`() {
        val cart = n(
            null,
            n("Order Summary"),
            n(null, n("Arjuna JEE 2027"), n("₹4,500")),
            n(null, n("Donate ₹10 to PW Foundation"), n(null, checkable = true, checked = true)),
            n(null, n("Total Amount"), n("₹4,510")),
        )
        val f = scanner.scan(cart).findings.single()
        assertEquals(DarkPatternKind.BASKET_SNEAKING, f.kind)
        assertEquals(1_000L, f.cost)
    }

    @Test fun `BookMyShow - pre-ticked Re 1 BookASmile contribution per ticket`() {
        val page = n(
            null,
            n("Booking Summary"),
            n(null, n("2 Ticket(s)"), n("₹500.00")),
            n(null, n("Convenience fees"), n("₹59.00")),
            n(null, n("Contribute to BookASmile"), n("₹2"), checkable = true, checked = true),
            n(null, n("Amount Payable"), n("₹561.00")),
        )
        val scan = scanner.scan(page)
        assertEquals(setOf(DarkPatternKind.BASKET_SNEAKING, DarkPatternKind.DRIP_PRICING), scan.findings.map { it.kind }.toSet())
        assertEquals(200L, scan.recoverable)
    }

    @Test fun `SpiceJet - SpiceClub enrolment and promotional consent ticked, declining worded as untick`() {
        val page = n(
            null,
            n("Review booking"),
            n(null, n("Base fare"), n("₹4,120")),
            n(null, n("Total fare"), n("₹4,980")),
            n("Enrol me in SpiceClub and earn points on this booking", checkable = true, checked = true),
            n("Untick this box if you do not wish to receive promotional offers", checkable = true, checked = true),
        )
        val scan = scanner.scan(page)
        assertEquals(2, scan.findings.count { it.kind == DarkPatternKind.FORCED_ACTION }, scan.findings.toString())
        assertTrue(scan.findings.filter { it.kind == DarkPatternKind.FORCED_ACTION }.all { it.fixable })
        assertTrue(DarkPatternKind.TRICK_WORDING in scan.findings.map { it.kind })
        assertTrue(CheckoutGate.plan(scan, Policy()).shouldIntercept)
    }

    @Test fun `IndiGo - insurance ticked, opt-out worded as taking a risk`() {
        val page = n(
            null,
            n("Fare summary"),
            n(null, n("Airfare"), n("₹5,210")),
            n(null, n("Travel insurance"), n("₹249"), checkable = true, checked = true),
            n("No, I will take the risk"),
        )
        assertEquals(setOf(DarkPatternKind.BASKET_SNEAKING, DarkPatternKind.CONFIRM_SHAMING), kinds(page))
        // The neutral wording IndiGo moved to is not shaming.
        val fixed = n(null, n("Fare summary"), n(null, n("Airfare"), n("₹5,210")), n(null, n("Total"), n("₹5,210")), n("No, I will not add to the trip"))
        assertEquals(emptySet(), kinds(fixed))
    }

    @Test fun `Uber - advance tip for faster pickup before a driver is assigned`() {
        val page = n(
            null,
            n("Looking for nearby drivers"),
            n("Add a tip for faster pickup. You can't change it later."),
            n(null, n("₹50"), n("₹75"), n("₹100")),
        )
        val scan = scanner.scan(page)
        assertTrue(scan.isCheckout)
        assertEquals(listOf(DarkPatternKind.PAY_FOR_PRIORITY), scan.findings.map { it.kind })
        assertTrue(CheckoutGate.plan(scan, Policy()).shouldIntercept)
    }

    @Test fun `Rapido - raise your fare prompts`() {
        val page = n(
            null,
            n("Captains are busy"),
            n("Increase your fare to get a ride faster"),
            n(null, n("+₹10"), n("+₹20"), n("+₹30")),
            n(null, n("Bike"), n("₹64")),
        )
        assertEquals(setOf(DarkPatternKind.PAY_FOR_PRIORITY), kinds(page))
    }

    @Test fun `Zomato and Blinkit - Feeding India donation already in the bill with a Remove link`() {
        val cart = n(
            null,
            n(null, n("Paneer Tikka"), n("₹280")),
            n(null, n("Feeding India donation ₹2"), n("Remove")),
            n(null, n("Bill Summary"), n(null, n("Item total"), n("₹280")), n(null, n("Platform fee"), n("₹10")), n(null, n("To pay"), n("₹292"))),
        )
        val scan = scanner.scan(cart)
        val donation = scan.findings.single { it.kind == DarkPatternKind.BASKET_SNEAKING }
        assertTrue(donation.fixable)
        assertEquals(200L, donation.cost)
        assertTrue(DarkPatternKind.DRIP_PRICING in scan.findings.map { it.kind })
    }

    @Test fun `MakeMyTrip - zero cancellation ticked and a convenience fee`() {
        val page = n(
            null,
            n("Review your booking"),
            n(null, n("Base fare"), n("₹3,850")),
            n(null, n("Zero Cancellation"), n("₹599"), checkable = true, checked = true),
            n(null, n("Convenience fee"), n("₹399")),
            n(null, n("Total amount"), n("₹4,848")),
        )
        assertEquals(setOf(DarkPatternKind.BASKET_SNEAKING, DarkPatternKind.DRIP_PRICING), kinds(page))
    }

    @Test fun `a checkout box the user needs is not a sign-up`() {
        val page = n(
            null,
            n("Order Summary"),
            n(null, n("Kurta"), n("₹1,299")),
            n("I agree to the Terms and Conditions", checkable = true, checked = true),
            n("Confirm and save billing details to your profile", checkable = true, checked = true),
            n(null, n("Total"), n("₹1,299")),
        )
        assertEquals(emptySet(), kinds(page))
    }

    @Test fun `paying for express delivery is a choice, not pay-for-priority`() {
        val page = n(null, n("Bill Summary"), n(null, n("Item total"), n("₹353")), n(null, n("Add ₹25 for priority delivery"), n("₹25")), n(null, n("To pay"), n("₹378")))
        assertTrue(DarkPatternKind.PAY_FOR_PRIORITY !in kinds(page))
    }

    @Test fun `precedent - regulator actions are matched to the app, allegations are not`() {
        assertTrue(Precedents.forApp("com.zeptoconsumerapp", "Zepto")!!.summary.contains("₹7 lakh"))
        assertEquals("BookMyShow", Precedents.forApp("com.bt.bms", "BookMyShow")?.company)
        assertEquals("Uber", Precedents.forApp("com.ubercab", "Uber")?.company)
        assertEquals("Physics Wallah", Precedents.forApp("xyz.penpencil.physicswala", "Physics Wallah")?.company)
        assertNull(Precedents.forApp("com.application.zomato", "Zomato"))
        assertNull(Precedents.forApp("com.android.chrome", "Chrome"))
    }

    @Test fun `CCPA codes follow the numbering in Annexure 1 of the 2023 guidelines`() {
        assertEquals("CCPA 1", DarkPatternKind.FALSE_URGENCY.code)
        assertEquals("CCPA 2", DarkPatternKind.BASKET_SNEAKING.code)
        assertEquals("CCPA 3", DarkPatternKind.CONFIRM_SHAMING.code)
        assertEquals("CCPA 5", DarkPatternKind.SUBSCRIPTION_TRAP.code)
        assertEquals("CCPA 8", DarkPatternKind.DRIP_PRICING.code)
    }
}
