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
import app.parda.core.policy.Policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HindiTest {
    private val letter = """
        विषय: वेतन संशोधन, वित्त वर्ष 2026-27
        प्रिय श्री राजेश कुमार,
        आपका संशोधित वार्षिक वेतन 18,40,000 रुपये है, जो 01 अप्रैल 2026 से लागू होगा।
        भुगतान एचडीएफसी बैंक के खाता संख्या 50100234567891 में जारी रहेगा।
        पैन: ABCPK1234M
        पता: 12-4-89, कोंडापुर मेन रोड, हैदराबाद 500084
        संपर्क: +91 98490 12345
    """.trimIndent()

    /** The Hindi side of an Aadhaar-style card: labels in Hindi, the number in Latin digits. */
    private val card = "नाम: सुनीता देवी\nजन्म तिथि: 14/03/1991\nमहिला\n2345 6789 0124"

    private fun found(text: String) = Classifier().classify(text).map { it.category to it.value }

    @Test fun `a Hindi letter has its personal data found`() {
        val f = found(letter)
        assertTrue(DataCategory.PERSON_NAME to "राजेश कुमार" in f, f.toString())
        assertTrue(DataCategory.MONEY_AMOUNT to "18,40,000 रुपये" in f, f.toString())
        assertTrue(DataCategory.BANK_ACCOUNT to "50100234567891" in f, f.toString())
        assertTrue(DataCategory.GOV_ID to "ABCPK1234M" in f, f.toString())
        assertTrue(DataCategory.ADDRESS to "12-4-89, कोंडापुर मेन रोड, हैदराबाद 500084" in f, f.toString())
        assertTrue(DataCategory.PHONE in f.map { it.first })
    }

    @Test fun `a Hindi ID card has its name, date of birth and number found`() {
        val f = found(card)
        assertTrue(DataCategory.PERSON_NAME to "सुनीता देवी" in f, f.toString())
        assertTrue(DataCategory.DATE_OF_BIRTH to "14/03/1991" in f, f.toString())
        assertTrue(DataCategory.GOV_ID to "2345 6789 0124" in f, f.toString())
    }

    /** Exactly what ML Kit read from a Hindi card on the phone: the colons came back as visargas (ः). */
    @Test fun `OCR's visarga counts as a colon`() {
        val ocr = listOf("नामः सुनीता देवी", "जन्म तिथिः 14/03/1991", "पताः 12-4-89, कोंडापुर मेन रोड, हैदराबाद 500084").joinToString("\n")
        val f = found(ocr)
        assertTrue(DataCategory.PERSON_NAME to "सुनीता देवी" in f, f.toString())
        assertTrue(DataCategory.DATE_OF_BIRTH to "14/03/1991" in f, f.toString())
        assertTrue(DataCategory.ADDRESS to "12-4-89, कोंडापुर मेन रोड, हैदराबाद 500084" in f, f.toString())
    }

    @Test fun `a Hindi name stops before a postposition and at a danda`() {
        assertEquals(listOf("अनिल नायर"), found("श्रीमती अनिल नायर को सूचित किया जाता है।").map { it.second })
        assertEquals(listOf("मीना"), found("प्रिय मीना।").map { it.second })
    }

    @Test fun `rupee amounts written in Hindi are counted`() {
        assertEquals(listOf(2_400_000L, 12_000_000L), Money.oneOffAmounts("किराया रु. 24,000 और जमा 1,20,000 रुपये"))
    }

    @Test fun `a Hindi document gets Hindi questions, each routed by the rules`() {
        val chips = SuggestedQuestions.of(letter)
        assertEquals(listOf("सारांश", "क्या वेतन कम है?"), chips.map { it.label })
        val rules = RuleBasedAgent()
        assertEquals(AgentPlan.AnswerLocally(LocalTask.SUMMARISE), rules.plan(chips[0].request, letter))
        assertEquals(AgentPlan.HandBack(HandBackReason.NEEDS_OUTSIDE_KNOWLEDGE), rules.plan(chips[1].request, letter))
        assertEquals(listOf("निजी जानकारी", "आईडी जाँचें"), SuggestedQuestions.of(card).map { it.label })
    }

    @Test fun `a nukta typed as one code point still routes`() {
        val precomposed = "बा" + 0x095B.toChar() + "ार" // ज़ as a single character
        assertEquals(AgentPlan.HandBack(HandBackReason.NEEDS_OUTSIDE_KNOWLEDGE), RuleBasedAgent().plan("$precomposed दर क्या है?", letter))
    }

    @Test fun `a handed-back Hindi letter carries no name, account or PAN`() {
        val d = DisclosureGate(RuleBasedAgent()).handle(letter, SuggestedQuestions.of(letter)[1].request, Policy())
        val out = assertIs<GateDecision.HandedBack>(d).outbound
        listOf("राजेश कुमार", "50100234567891", "ABCPK1234M").forEach { assertFalse(it in out, "$it leaked: $out") }
    }
}
