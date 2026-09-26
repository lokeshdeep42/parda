package app.parda.core

import app.parda.core.agent.AgentGrammar
import app.parda.core.agent.AgentPlan
import app.parda.core.agent.DisclosureGate
import app.parda.core.agent.GateDecision
import app.parda.core.agent.AgentPrompt
import app.parda.core.agent.ChatPrompt
import app.parda.core.agent.HandBackReason
import app.parda.core.agent.LocalTask
import app.parda.core.agent.ModelAgent
import app.parda.core.agent.TextEngine
import app.parda.core.agent.Planner
import app.parda.core.agent.Planned
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
        override fun complete(prompt: ChatPrompt, grammar: String?, maxTokens: Int, onToken: (String) -> Unit): String {
            grammars += grammar
            val out = if (grammar != null) planOutput else answerOutput
            if (grammar == null) out.split(" ").forEach { onToken("$it ") }
            return out
        }
    }

    private val summarisePlan = """{"name":"answer_locally","arguments":{"task":"summarise"}}"""

    /** A request no keyword rule can place, so the model has to plan it. */
    private val ambiguous = "What does this letter want from me?"

    @Test fun `model planning is always grammar-constrained`() {
        val engine = FakeEngine(summarisePlan)
        val planned = ModelAgent(engine).planned(ambiguous, Samples.SALARY_LETTER)
        assertEquals(Planned(AgentPlan.AnswerLocally(LocalTask.SUMMARISE), Planner.MODEL), planned)
        assertEquals(listOf<String?>(AgentGrammar.GBNF), engine.grammars)
    }

    @Test fun `obvious requests are planned by the rules without asking the model`() {
        val engine = FakeEngine("""{"name":"hand_back","arguments":{"reason":"too_complex"}}""")
        val planned = ModelAgent(engine).planned("Summarise this", Samples.SALARY_LETTER)
        assertEquals(Planned(AgentPlan.AnswerLocally(LocalTask.SUMMARISE), Planner.RULES), planned)
        assertTrue(engine.grammars.isEmpty())
    }

    @Test fun `a model answer is held locally and streamed`() {
        val engine = FakeEngine(summarisePlan, "• Revised pay ₹18,40,000 from 1 April 2026")
        val streamed = StringBuilder()
        val d = DisclosureGate(ModelAgent(engine)).handle(Samples.SALARY_LETTER, "Summarise this", Policy()) { streamed.append(it) }
        assertIs<GateDecision.HeldLocally>(d)
        assertTrue("₹18,40,000" in d.answer)
        assertTrue("₹18,40,000" in streamed)
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
        val d = DisclosureGate(ModelAgent(TextEngine { _, _, _, _ -> error("native crash") }))
            .handle(Samples.SALARY_LETTER, ambiguous, Policy())
        assertIs<GateDecision.HandedBack>(d)
        assertEquals(HandBackReason.MODEL_OUTPUT_INVALID, d.reason)
    }

    @Test fun `a crash while answering falls back to the rule-based answer`() {
        val d = DisclosureGate(ModelAgent(TextEngine { _, _, _, _ -> error("native crash") }))
            .handle(Samples.SALARY_LETTER, "Summarise this", Policy())
        assertIs<GateDecision.HeldLocally>(d)
        assertTrue(d.answer.isNotBlank())
    }

    /** A 600-page file: the summary counts the whole of it, and the model is not asked. */
    @Test fun `long data-heavy documents are summarised by code over the full text`() {
        val engine = FakeEngine(summarisePlan, "made up")
        val full = (0 until 3000).joinToString("\n") { "Record $it: Mr Anil Nair, PAN ABCPK1234M, paid Rs 1,000." }
        val d = DisclosureGate(ModelAgent(engine))
            .handle(full.take(20_000), "Summarise this", Policy(), full = full, pages = 600)
        assertIs<GateDecision.HeldLocally>(d)
        assertTrue("600 pages" in d.answer && "3,000 lines" in d.answer, d.answer)
        assertTrue("₹30,00,000" in d.answer, d.answer)
        assertFalse("made up" in d.answer)
        assertTrue(engine.grammars.isEmpty())
    }

    @Test fun `the plan prompt carries the tool list and the request, not a chat template`() {
        val p = AgentPrompt.plan("Am I underpaid?", Samples.SALARY_LETTER)
        assertTrue("\"hand_back\"" in p.user && "Request: Am I underpaid?" in p.user)
        assertFalse("<|im_start|>" in p.system + p.user, "the engine applies the model's own template")
    }
}
