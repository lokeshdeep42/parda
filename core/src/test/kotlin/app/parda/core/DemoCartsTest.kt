package app.parda.core

import app.parda.core.checkout.CheckoutGate
import app.parda.core.checkout.DarkPatternScanner
import app.parda.core.checkout.DemoCarts
import app.parda.core.checkout.DemoCarts.Cart
import app.parda.core.checkout.DemoCarts.Kind
import app.parda.core.checkout.ScreenNode
import app.parda.core.policy.DarkPatternKind
import app.parda.core.policy.DarkPatternKind.BASKET_SNEAKING
import app.parda.core.policy.DarkPatternKind.CONFIRM_SHAMING
import app.parda.core.policy.DarkPatternKind.DRIP_PRICING
import app.parda.core.policy.DarkPatternKind.FALSE_URGENCY
import app.parda.core.policy.DarkPatternKind.SUBSCRIPTION_TRAP
import app.parda.core.policy.Policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every demo cart through the shield, as the phone would read it: the patterns found, what can
 * be recovered, whether the sheet is shown, and the total once the user removes the extras.
 */
class DemoCartsTest {
    private val scanner = DarkPatternScanner()

    /** The accessibility tree of the demo store: one row per line, the checkbox beside its price. */
    private fun app(cart: Cart, unticked: Set<String> = emptySet()): ScreenNode = cart.screen(unticked)

    /** The same cart as a web page in Chrome: everything is a sibling, the price follows its label. */
    private fun web(cart: Cart) = ScreenNode("root", children = app(cart).children.flatMap { if (it.children.isEmpty()) listOf(it) else it.children })

    private fun kinds(root: ScreenNode) = scanner.scan(root).findings.groupingBy { it.kind }.eachCount()

    private data class Expect(val kinds: Map<DarkPatternKind, Int>, val recoverable: Long, val recurring: Long, val disclosed: Long, val totalAfter: Long)

    private val expected = mapOf(
        "fashion" to Expect(mapOf(BASKET_SNEAKING to 2, SUBSCRIPTION_TRAP to 1, DRIP_PRICING to 1, FALSE_URGENCY to 1, CONFIRM_SHAMING to 1), 15_900, 29_900, 4_900, 134_800),
        "food" to Expect(mapOf(BASKET_SNEAKING to 1, SUBSCRIPTION_TRAP to 1, DRIP_PRICING to 2, FALSE_URGENCY to 1, CONFIRM_SHAMING to 1), 3_000, 14_900, 3_500, 63_300),
        "flight" to Expect(mapOf(BASKET_SNEAKING to 2, DRIP_PRICING to 1, FALSE_URGENCY to 1, CONFIRM_SHAMING to 1), 30_900, 0, 35_000, 524_900),
        "movie" to Expect(mapOf(BASKET_SNEAKING to 1, DRIP_PRICING to 1, FALSE_URGENCY to 1), 2_900, 0, 7_080, 63_080),
        "grocery" to Expect(mapOf(BASKET_SNEAKING to 1, SUBSCRIPTION_TRAP to 1, DRIP_PRICING to 2, FALSE_URGENCY to 1), 2_000, 9_900, 4_400, 15_500),
    )

    @Test fun `every dishonest cart is caught, costed and intercepted`() {
        for ((id, e) in expected) {
            val cart = DemoCarts.byId(id)
            val scan = scanner.scan(app(cart))
            assertTrue(scan.isCheckout, id)
            assertEquals(e.kinds, kinds(app(cart)), id)
            assertEquals(e.recoverable, scan.recoverable, "$id recoverable")
            assertEquals(e.recurring, scan.recurring, "$id recurring")
            assertEquals(e.disclosed, scan.disclosedOnly, "$id fees")
            assertTrue(CheckoutGate.plan(scan, Policy()).shouldIntercept, "$id shows the sheet")
        }
    }

    @Test fun `removing the extras leaves the promised total and nothing left to fix`() {
        for ((id, e) in expected) {
            val cart = DemoCarts.byId(id)
            val before = scanner.scan(app(cart))
            assertEquals(cart.total() - e.recoverable, e.totalAfter, "$id: the sheet's new total")
            val removed = before.findings.filter { it.fixable }.map { f -> cart.lines.first { it.label == f.evidence }.label }.toSet()
            val after = scanner.scan(app(cart, unticked = removed))
            assertFalse(after.findings.any { it.fixable }, "$id still has something to untick")
            assertEquals(e.totalAfter, cart.total { it.kind == Kind.TICKED && it.label !in removed }, id)
        }
    }

    @Test fun `the same carts are caught as web pages`() {
        for ((id, e) in expected) assertEquals(e.kinds, kinds(web(DemoCarts.byId(id))), "$id in a browser")
    }

    @Test fun `an honest cart is a checkout with nothing to report`() {
        val scan = scanner.scan(app(DemoCarts.HONEST))
        assertTrue(scan.isCheckout)
        assertEquals(emptyList(), scan.findings)
        assertFalse(CheckoutGate.plan(scan, Policy()).shouldNotify)
    }

    @Test fun `every cart has an id the demo store can open`() {
        assertEquals(DemoCarts.ALL.size, DemoCarts.ALL.map { it.id }.toSet().size)
        assertEquals(expected.keys + "honest", DemoCarts.ALL.map { it.id }.toSet())
    }
}
