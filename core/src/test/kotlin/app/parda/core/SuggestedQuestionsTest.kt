package app.parda.core

import app.parda.core.agent.AgentPlan
import app.parda.core.agent.HandBackReason
import app.parda.core.agent.LocalTask
import app.parda.core.agent.RuleBasedAgent
import app.parda.core.document.SuggestedQuestions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuggestedQuestionsTest {
    private fun labels(doc: String) = SuggestedQuestions.of(doc).map { it.label }

    private val statement = """
        HDFC Bank account statement, 01-31 August 2026
        Opening balance Rs 42,310.00
        02/08 UPI grocery debit Rs 1,240.00
        05/08 Salary credit Rs 85,000.00
        Closing balance Rs 1,02,114.00
    """.trimIndent()

    private val lease = """
        This Leave and Licence Agreement is made between the Landlord, Mr Suresh Rao, and the Tenant.
        Clause 4: the tenant shall pay a deposit of Rs 1,20,000.
    """.trimIndent()

    private val records = (1..60).joinToString("\n") { "Record $it: Mr Anil Nair, paid Rs 1,000." }

    @Test fun `questions follow the kind of document`() {
        assertEquals(listOf("Summarise", "Am I underpaid?"), labels(Samples.SALARY_LETTER))
        assertEquals(listOf("Summarise", "List personal data", "Unusual spending?"), labels(statement))
        assertEquals(listOf("Summarise", "List personal data", "Is it legal?"), labels(lease))
        assertEquals(listOf("Summarise", "List personal data", "Compare amounts"), labels(records))
        assertEquals(listOf("List personal data", "Verify this ID"), labels("Name: Rajesh Kumar\nPAN: ABCPK1234M"))
    }

    /** Every chip must land where its label promises, without waiting on the model to plan. */
    @Test fun `each suggestion is planned by the rules, answered locally or handed back as labelled`() {
        val rules = RuleBasedAgent()
        for (doc in listOf(Samples.SALARY_LETTER, statement, lease, records, "Name: Rajesh Kumar\nPAN: ABCPK1234M")) {
            for (s in SuggestedQuestions.of(doc)) {
                val plan = rules.plan(s.request, doc)
                val expected = when (s.label) {
                    "Summarise" -> AgentPlan.AnswerLocally(LocalTask.SUMMARISE)
                    "List personal data" -> AgentPlan.AnswerLocally(LocalTask.EXTRACT)
                    else -> AgentPlan.HandBack(HandBackReason.NEEDS_OUTSIDE_KNOWLEDGE)
                }
                assertEquals(expected, plan, "${s.label} on ${doc.take(30)}")
            }
        }
    }

    @Test fun `listing personal data counts everything and shows a capped sample`() {
        val many = (1..100).joinToString("\n") { "PAN ABCPK${1000 + it}M" }
        val answer = RuleBasedAgent().answer(LocalTask.EXTRACT, "List the personal data", many)!!
        assertTrue(answer.startsWith("100 item(s)"), answer)
        assertTrue("…and 60 more" in answer, answer)
    }
}
