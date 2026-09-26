package app.parda.core

import app.parda.core.checkout.CheckoutGate
import app.parda.core.checkout.DarkPatternScanner
import app.parda.core.checkout.RemovalHistory
import app.parda.core.policy.CheckoutAction
import app.parda.core.policy.DarkPatternKind.BASKET_SNEAKING
import app.parda.core.policy.DarkPatternKind.DRIP_PRICING
import app.parda.core.policy.DarkPatternKind.SUBSCRIPTION_TRAP
import app.parda.core.policy.Policy
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Parda offers to remove what the user keeps removing in one app, and does so only there once asked. */
class LearningTest {
    private val swiggy = "in.swiggy.android"
    private val zomato = "com.application.zomato"

    @Test fun `an offer comes after two removals of the same kind in the same app`() {
        var h = RemovalHistory()
        h = h.record(swiggy, listOf(BASKET_SNEAKING))
        assertEquals(emptyList(), h.offers(swiggy, listOf(BASKET_SNEAKING), Policy()))
        h = h.record(swiggy, listOf(BASKET_SNEAKING, BASKET_SNEAKING))
        assertEquals(listOf(BASKET_SNEAKING), h.offers(swiggy, listOf(BASKET_SNEAKING), Policy()))
        assertEquals(emptyList(), h.offers(zomato, listOf(BASKET_SNEAKING), Policy()), "other apps are not affected")
        assertEquals(emptyList(), h.offers(swiggy, listOf(SUBSCRIPTION_TRAP), Policy()), "only kinds on screen now")
    }

    @Test fun `a fee cannot be removed, so it is never offered`() {
        val h = RemovalHistory().record(swiggy, listOf(DRIP_PRICING)).record(swiggy, listOf(DRIP_PRICING))
        assertEquals(emptyList(), h.offers(swiggy, listOf(DRIP_PRICING), Policy()))
    }

    @Test fun `once accepted the rule applies in that app only, and is not offered again`() {
        val policy = Policy().autoRemove(swiggy, BASKET_SNEAKING, true)
        assertEquals(CheckoutAction.AUTO_REMOVE, policy.actionFor(BASKET_SNEAKING, swiggy))
        assertEquals(CheckoutAction.ASK_ME, policy.actionFor(BASKET_SNEAKING, zomato))
        assertEquals(CheckoutAction.ASK_ME, policy.actionFor(BASKET_SNEAKING))
        val h = RemovalHistory().record(swiggy, listOf(BASKET_SNEAKING)).record(swiggy, listOf(BASKET_SNEAKING))
        assertEquals(emptyList(), h.offers(swiggy, listOf(BASKET_SNEAKING), policy))
        assertEquals(Policy(), policy.autoRemove(swiggy, BASKET_SNEAKING, false), "turning it off leaves no trace")
    }

    @Test fun `the gate removes on its own only in the app with the rule`() {
        val scan = DarkPatternScanner().scan(Samples.CART)
        val policy = Policy().autoRemove(swiggy, BASKET_SNEAKING, true)
        val there = CheckoutGate.plan(scan, policy, swiggy)
        assertTrue(there.autoRemove.all { it.kind == BASKET_SNEAKING } && there.autoRemove.size == 2)
        assertTrue(there.ask.none { it.kind == BASKET_SNEAKING })
        assertTrue(CheckoutGate.plan(scan, policy, zomato).autoRemove.isEmpty())
    }

    @Test fun `a policy saved before per-app rules still loads`() {
        val old = """{"disclosure":{"PERSON_NAME":"ALLOW"},"checkout":{"BASKET_SNEAKING":"ASK_ME"}}"""
        val p = Json { ignoreUnknownKeys = true }.decodeFromString(Policy.serializer(), old)
        assertEquals(emptyMap(), p.perApp)
        val round = Json.decodeFromString(Policy.serializer(), Json.encodeToString(Policy.serializer(), Policy().autoRemove(swiggy, SUBSCRIPTION_TRAP, true)))
        assertEquals(setOf(SUBSCRIPTION_TRAP), round.perApp[swiggy])
    }
}
