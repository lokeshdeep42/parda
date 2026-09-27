package app.parda.core

import app.parda.core.agent.Planner
import app.parda.core.agent.TextEngine
import app.parda.core.checkout.CheckoutGate
import app.parda.core.checkout.CheckoutReviewer
import app.parda.core.checkout.DarkPatternScanner
import app.parda.core.checkout.DemoCarts
import app.parda.core.checkout.ScreenNode
import app.parda.core.policy.CheckoutAction
import app.parda.core.policy.DarkPatternKind
import app.parda.core.policy.Policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The on-device model's second look at a checkout, with a stand-in model that answers what a
 * test tells it to. What matters is what survives: only claims the screen itself bears out.
 */
class CheckoutReviewerTest {
    private val scanner = DarkPatternScanner()
    private val sneaky = DemoCarts.SNEAKY.screen()

    private fun model(reply: String) = TextEngine { _, _, _, _ -> reply }
    private fun number(screen: ScreenNode, words: String) = scanner.lines(screen).first { words in it.text }.number
    private fun claims(screen: ScreenNode, vararg c: Pair<String, String>) =
        """{"findings": [${c.joinToString { (words, kind) -> """{"line": ${number(screen, words)}, "kind": "$kind"}""" }}]}"""

    @Test fun `the rules see a clean checkout where the tricks are newly worded`() {
        val scan = scanner.scan(sneaky)
        assertTrue(scan.isCheckout)
        assertEquals(emptyList(), scan.findings)
    }

    @Test fun `the model sees numbered lines with the ticked boxes marked`() {
        val lines = scanner.lines(sneaky)
        val care = lines.first { "FreshCare+" in it.text }
        assertTrue(care.box != null && care.ticked, care.toString())
        assertEquals("FreshCare+ for this order", care.boxLabel)
        assertTrue("₹39" in care.text, care.text)
        val user = CheckoutReviewer.prompt(lines).user
        assertTrue("${care.number}. [x] FreshCare+" in user, user)
        assertTrue(lines.any { "Late-order surcharge" in it.text && "₹25" in it.text }, lines.toString())
    }

    @Test fun `what the model points at is checked against the screen and offered`() {
        val scan = scanner.scan(sneaky)
        val reply = claims(
            sneaky,
            "FreshCare+" to "basket_sneaking", "Nightowl Circle" to "forced_action", "surcharge" to "drip_pricing",
            "closing soon" to "false_urgency", "eat it cold" to "confirm_shaming",
        )
        val found = CheckoutReviewer(model(reply)).review(sneaky, scan).associateBy { it.kind }

        assertEquals(5, found.size, found.toString())
        assertTrue(found.values.all { it.by == Planner.MODEL })
        val care = found.getValue(DarkPatternKind.BASKET_SNEAKING)
        assertEquals("FreshCare+ for this order", care.evidence)
        assertEquals(3_900, care.cost)
        assertTrue(care.fixable)
        assertTrue(found.getValue(DarkPatternKind.FORCED_ACTION).fixable)
        val fee = found.getValue(DarkPatternKind.DRIP_PRICING)
        assertEquals(2_500, fee.cost)
        assertTrue(!fee.fixable)
    }

    @Test fun `the real Hammer reply on Nightowl is named by the screen, not by the model`() {
        // Hammer 2.1 1.5B Q4_0 through llama.cpp, same prompt and grammar, 27 Sep 2026: it points at
        // the right lines but hands out the kinds in the order they were listed.
        val lines = scanner.lines(sneaky)
        assertTrue("FreshCare+" in lines[4].text && "Circle" in lines[5].text && "surcharge" in lines[6].text, lines.toString())
        assertTrue("Total payable" in lines[7].text && "closing soon" in lines[8].text, lines.toString())
        val hammer = """{"findings": [{"line": 5, "kind": "basket_sneaking"}, {"line": 6, "kind": "subscription_trap"},""" +
            """ {"line": 7, "kind": "forced_action"}, {"line": 8, "kind": "drip_pricing"}, {"line": 9, "kind": "false_urgency"}]}"""
        val found = CheckoutReviewer(model(hammer)).review(sneaky, scanner.scan(sneaky))
        assertEquals(
            listOf(
                DarkPatternKind.BASKET_SNEAKING to "FreshCare+ for this order",
                DarkPatternKind.FORCED_ACTION to "Stay in the Nightowl Circle for late-night deals",
                DarkPatternKind.DRIP_PRICING to "Late-order surcharge  ₹25",
                DarkPatternKind.FALSE_URGENCY to "Kitchen closing soon — order in the next 09:59",
            ),
            found.map { it.kind to it.evidence },
        )
    }

    @Test fun `a claim the screen does not bear out is dropped`() {
        val scan = scanner.scan(sneaky)
        val wrong = claims(
            sneaky,
            "shawarma" to "basket_sneaking", // chosen by the user, and no box
            "shawarma" to "drip_pricing", // an item, not a fee
            "Delivery" to "drip_pricing", // free
            "Total payable" to "subscription_trap",
            "Total payable" to "false_urgency",
        )
        val reply = wrong.removeSuffix("]}") + """, {"line": 99, "kind": "basket_sneaking"}]}"""
        assertEquals(emptyList(), CheckoutReviewer(model(reply)).review(sneaky, scan))
    }

    @Test fun `an unticked offer is not a trick`() {
        val honest = DemoCarts.HONEST.screen()
        val reply = claims(honest, "Gift wrap" to "basket_sneaking")
        assertEquals(emptyList(), CheckoutReviewer(model(reply)).review(honest, scanner.scan(honest)))
    }

    @Test fun `what the rules already found is not reported again`() {
        val fashion = DemoCarts.FASHION.screen()
        val scan = scanner.scan(fashion)
        val reply = claims(fashion, "protection plan" to "basket_sneaking", "Handling fee" to "drip_pricing")
        assertEquals(emptyList(), CheckoutReviewer(model(reply)).review(fashion, scan))
    }

    @Test fun `a broken or silent model changes nothing`() {
        val scan = scanner.scan(sneaky)
        assertEquals(emptyList(), CheckoutReviewer(model("not json")).review(sneaky, scan))
        assertEquals(emptyList(), CheckoutReviewer(model("""{"findings": []}""")).review(sneaky, scan))
        assertEquals(emptyList(), CheckoutReviewer(TextEngine { _, _, _, _ -> error("model not loaded") }).review(sneaky, scan))
        // A screen that is not a checkout is never shown to the model.
        var asked = false
        val hello = ScreenNode("root", "Hello")
        CheckoutReviewer(TextEngine { _, _, _, _ -> asked = true; "" }).review(hello, scanner.scan(hello))
        assertTrue(!asked)
    }

    @Test fun `the gate never removes what the model found, even when the user said auto-remove`() {
        val scan = scanner.scan(sneaky)
        val found = CheckoutReviewer(model(claims(sneaky, "FreshCare+" to "basket_sneaking"))).review(sneaky, scan)
        val policy = Policy().with(DarkPatternKind.BASKET_SNEAKING, CheckoutAction.AUTO_REMOVE)
        val plan = CheckoutGate.plan(scan.copy(findings = found), policy)
        assertEquals(emptyList(), plan.autoRemove)
        assertEquals(found, plan.ask)
    }

    @Test fun `the grammar lets the model name only lines and the eight kinds`() {
        val g = CheckoutReviewer.GBNF
        DarkPatternKind.entries.forEach { assertTrue("\\\"${it.name.lowercase()}\\\"" in g, it.name) }
        // No rule that accepts free text: only digits, the fixed keys and the kinds.
        assertTrue("[^" !in g && "[a-z" !in g, g)
    }
}
