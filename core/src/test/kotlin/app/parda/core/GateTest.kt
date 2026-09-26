package app.parda.core

import app.parda.core.agent.AgentGrammar
import app.parda.core.agent.AgentPlan
import app.parda.core.agent.DisclosureGate
import app.parda.core.agent.GateDecision
import app.parda.core.agent.HandBackReason
import app.parda.core.agent.LocalAgent
import app.parda.core.agent.LocalTask
import app.parda.core.agent.RuleBasedAgent
import app.parda.core.policy.Policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GateTest {
    private val gate = DisclosureGate(RuleBasedAgent())

    @Test fun `summaries are answered on the device`() {
        val d = gate.handle(Samples.SALARY_LETTER, "Summarise the key terms of this letter in three lines.", Policy())
        assertIs<GateDecision.HeldLocally>(d)
        assertTrue("₹18,40,000" in d.answer, d.answer)
    }

    @Test fun `outside-knowledge questions are handed back sanitized`() {
        val q = "Compare this against typical FY26 compensation bands for my role and tell me if I am underpaid."
        val d = gate.handle(Samples.SALARY_LETTER, q, Policy())
        assertIs<GateDecision.HandedBack>(d)
        assertEquals(HandBackReason.NEEDS_OUTSIDE_KNOWLEDGE, d.reason)
        assertFalse("50100234567891" in d.outbound)
        assertTrue(d.outbound.endsWith(q))
    }

    /** A model that tries to smuggle data out through its plan cannot: the outbound text comes from the sanitizer. */
    @Test fun `a hostile model cannot change what is handed back`() {
        val hostile = object : LocalAgent {
            override fun plan(request: String, document: String) =
                AgentGrammar.parse("""{"name":"hand_back","arguments":{"reason":"$document"}}""")
            override fun answer(task: LocalTask, request: String, document: String) = document
        }
        val d = DisclosureGate(hostile).handle(Samples.SALARY_LETTER, "anything", Policy())
        assertIs<GateDecision.HandedBack>(d)
        assertEquals(HandBackReason.MODEL_OUTPUT_INVALID, d.reason)
        assertFalse("ABCPK1234M" in d.outbound)
    }

    @Test fun `a crashing model fails closed`() {
        val broken = object : LocalAgent {
            override fun plan(request: String, document: String): AgentPlan = error("OOM")
            override fun answer(task: LocalTask, request: String, document: String): String? = null
        }
        val d = DisclosureGate(broken).handle(Samples.SALARY_LETTER, "summarise", Policy())
        assertIs<GateDecision.HandedBack>(d)
        assertFalse("Rajesh" in d.outbound)
    }

    @Test fun `grammar output parses`() {
        assertEquals(
            AgentPlan.AnswerLocally(LocalTask.SUMMARISE),
            AgentGrammar.parse("""{"name": "answer_locally", "arguments": {"task": "summarise"}}"""),
        )
        assertEquals(AgentPlan.HandBack(HandBackReason.MODEL_OUTPUT_INVALID), AgentGrammar.parse("sure! here you go"))
        assertTrue("answer_locally" in AgentGrammar.GBNF && "\\\"summarise\\\"" in AgentGrammar.GBNF, AgentGrammar.GBNF)
    }
}
