package app.parda.core

import app.parda.core.agent.AgentGrammar
import app.parda.core.agent.AgentPlan
import app.parda.core.agent.DisclosureGate
import app.parda.core.agent.GateDecision
import app.parda.core.agent.HammerPrompt
import app.parda.core.agent.HandBackReason
import app.parda.core.agent.LocalTask
import app.parda.core.agent.ModelAgent
import app.parda.core.agent.TextEngine
import app.parda.core.policy.Policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelAgentTest {
    /** Records what the agent asked for and replies with canned output. */
    private class FakeEngine(private val planOutput: String, private val answerOutput: String = "") : TextEngine {
        val grammars = mutableListOf<String?>()
        override fun complete(prompt: String, grammar: String?, maxTokens: Int): String {
            grammars += grammar
            return if (grammar != null) planOutput else answerOutput
        }
    }

    private val summarisePlan = """{"name":"answer_locally","arguments":{"task":"summarise"}}"""

    @Test fun `planning is always grammar-constrained`() {
        val engine = FakeEngine(summarisePlan)
        val plan = ModelAgent(engine).plan("Summarise this", Samples.SALARY_LETTER)
        assertEquals(AgentPlan.AnswerLocally(LocalTask.SUMMARISE), plan)
        assertEquals(listOf<String?>(AgentGrammar.GBNF), engine.grammars)
    }

    @Test fun `a model answer is held locally`() {
        val engine = FakeEngine(summarisePlan, "• Revised pay ₹18,40,000 from 1 April 2026")
        val d = DisclosureGate(ModelAgent(engine)).handle(Samples.SALARY_LETTER, "Summarise this", Policy())
        assertIs<GateDecision.HeldLocally>(d)
        assertTrue("₹18,40,000" in d.answer)
    }

    @Test fun `an empty model answer falls back to the rule-based answerer`() {
        val engine = FakeEngine(summarisePlan, "   ")
        val d = DisclosureGate(ModelAgent(engine)).handle(Samples.SALARY_LETTER, "Summarise this", Policy())
        assertIs<GateDecision.HeldLocally>(d)
        assertTrue(d.answer.isNotBlank())
    }

    @Test fun `garbage from the model hands back only the sanitized copy`() {
        val engine = FakeEngine("ABCPK1234M 50100234567891")
        val d = DisclosureGate(ModelAgent(engine)).handle(Samples.SALARY_LETTER, "Draft a polite reply", Policy())
        assertIs<GateDecision.HandedBack>(d)
        assertEquals(HandBackReason.MODEL_OUTPUT_INVALID, d.reason)
        assertFalse("50100234567891" in d.outbound)
    }

    /** Hammer2.1 really did route this to answer_locally/rewrite in a desktop run. */
    @Test fun `outside-knowledge questions are vetoed before the model plans`() {
        val engine = FakeEngine("""{"name":"answer_locally","arguments":{"task":"rewrite"}}""")
        val plan = ModelAgent(engine).plan("Is a seven day confirmation deadline legal under Indian labour law?", Samples.SALARY_LETTER)
        assertEquals(AgentPlan.HandBack(HandBackReason.NEEDS_OUTSIDE_KNOWLEDGE), plan)
        assertTrue(engine.grammars.isEmpty())
    }

    @Test fun `a crashing engine fails closed`() {
        val d = DisclosureGate(ModelAgent(TextEngine { _, _, _ -> error("native crash") }))
            .handle(Samples.SALARY_LETTER, "Summarise this", Policy())
        assertIs<GateDecision.HandedBack>(d)
        assertEquals(HandBackReason.MODEL_OUTPUT_INVALID, d.reason)
    }

    @Test fun `the plan prompt is ChatML with the tool list and the request`() {
        val p = HammerPrompt.plan("Am I underpaid?", Samples.SALARY_LETTER)
        assertTrue(p.startsWith("<|im_start|>system\n"))
        assertTrue(p.endsWith("<|im_start|>assistant\n"))
        assertTrue("\"hand_back\"" in p && "Request: Am I underpaid?" in p)
    }
}
