package app.parda.core

import app.parda.core.agent.AgentPlan
import app.parda.core.agent.DisclosureGate
import app.parda.core.agent.GateDecision
import app.parda.core.agent.HandBackReason
import app.parda.core.agent.LocalTask
import app.parda.core.agent.RuleBasedAgent
import app.parda.core.checkout.Money
import app.parda.core.detect.Classifier
import app.parda.core.document.SuggestedQuestions
import app.parda.core.policy.DataCategory
import app.parda.core.policy.DisclosureAction
import app.parda.core.policy.Policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TeluguTest {
    private val letter = """
        విషయం: జీతం సవరణ, ఆర్థిక సంవత్సరం 2026-27
        ప్రియమైన శ్రీ రాజేష్ కుమార్ గారికి,
        మీ సవరించిన వార్షిక జీతం 18,40,000 రూపాయలు, 01 ఏప్రిల్ 2026 నుండి అమలులోకి వస్తుంది.
        చెల్లింపు హెచ్‌డిఎఫ్‌సి బ్యాంక్ ఖాతా సంఖ్య 50100234567891 కు కొనసాగుతుంది.
        పాన్: ABCPK1234M
        చిరునామా: 12-4-89, కొండాపూర్ మెయిన్ రోడ్, హైదరాబాద్ 500084
        సంప్రదించండి: +91 98490 12345
    """.trimIndent()

    /** The Telugu side of an ID card: labels in Telugu, the number in Latin digits. */
    private val card = """
        పేరు: సునీత దేవి
        పుట్టిన తేదీ: 14/03/1991
        స్త్రీ
        2345 6789 0124
    """.trimIndent()

    private fun found(text: String) = Classifier().classify(text).map { it.category to it.value }

    @Test fun `a Telugu letter has its personal data found`() {
        val f = found(letter)
        assertTrue(DataCategory.PERSON_NAME to "రాజేష్ కుమార్" in f, f.toString())
        assertTrue(DataCategory.MONEY_AMOUNT to "18,40,000 రూపాయలు" in f, f.toString())
        assertTrue(DataCategory.BANK_ACCOUNT to "50100234567891" in f, f.toString())
        assertTrue(DataCategory.GOV_ID to "ABCPK1234M" in f, f.toString())
        assertTrue(DataCategory.ADDRESS to "12-4-89, కొండాపూర్ మెయిన్ రోడ్, హైదరాబాద్ 500084" in f, f.toString())
        assertTrue(DataCategory.PHONE in f.map { it.first })
    }

    @Test fun `a Telugu ID card has its name, date of birth and number found`() {
        val f = found(card)
        assertTrue(DataCategory.PERSON_NAME to "సునీత దేవి" in f, f.toString())
        assertTrue(DataCategory.DATE_OF_BIRTH to "14/03/1991" in f, f.toString())
        assertTrue(DataCategory.GOV_ID to "2345 6789 0124" in f, f.toString())
    }

    @Test fun `a Telugu name stops before a postposition`() {
        assertEquals(listOf("అనిల్ నాయర్"), found("శ్రీమతి అనిల్ నాయర్ కు తెలియజేయడమైనది.").map { it.second })
    }

    @Test fun `rupee amounts written in Telugu are counted`() {
        assertEquals(listOf(2_400_000L, 12_000_000L), Money.oneOffAmounts("అద్దె రూ. 24,000 మరియు డిపాజిట్ 1,20,000 రూపాయలు"))
    }

    @Test fun `a Telugu document gets Telugu questions, each routed by the rules`() {
        val chips = SuggestedQuestions.of(letter)
        assertEquals(listOf("సారాంశం", "జీతం తక్కువా?"), chips.map { it.label })
        val rules = RuleBasedAgent()
        assertEquals(AgentPlan.AnswerLocally(LocalTask.SUMMARISE), rules.plan(chips[0].request, letter))
        assertEquals(AgentPlan.HandBack(HandBackReason.NEEDS_OUTSIDE_KNOWLEDGE), rules.plan(chips[1].request, letter))
        val idChips = SuggestedQuestions.of(card)
        assertEquals(listOf("వ్యక్తిగత వివరాలు", "ఐడీ తనిఖీ"), idChips.map { it.label })
        assertEquals(AgentPlan.AnswerLocally(LocalTask.EXTRACT), rules.plan(idChips[0].request, card))
        assertEquals(AgentPlan.HandBack(HandBackReason.NEEDS_OUTSIDE_KNOWLEDGE), rules.plan(idChips[1].request, card))
    }

    @Test fun `a handed-back Telugu letter carries no name, account or PAN`() {
        val d = DisclosureGate(RuleBasedAgent()).handle(letter, SuggestedQuestions.of(letter)[1].request, Policy())
        val out = assertIs<GateDecision.HandedBack>(d).outbound
        listOf("రాజేష్ కుమార్", "50100234567891", "ABCPK1234M").forEach { assertFalse(it in out, "$it leaked: $out") }
    }

    @Test fun `Telugu diagnoses are masked in the strict health mode`() {
        val report = """
            రోగి పేరు: లక్ష్మి నారాయణన్
            నిర్ధారణ: మధుమేహం
            హిమోగ్లోబిన్ 10.2 g/dL
        """.trimIndent()
        val strict = Policy().with(DataCategory.HEALTH_CONDITION, DisclosureAction.SURROGATE)
        val out = app.parda.core.disclosure.Sanitizer().sanitize(report, strict).sanitized
        assertFalse("మధుమేహం" in out, out)
        assertFalse("లక్ష్మి నారాయణన్" in out, out)
        assertTrue("10.2 g/dL" in out, out)
    }
}
