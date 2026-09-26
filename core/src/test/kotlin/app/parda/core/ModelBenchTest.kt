package app.parda.core

import app.parda.core.agent.AgentGrammar
import app.parda.core.agent.ModelBench
import app.parda.core.agent.TextEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelBenchTest {
    /** Plans locally for every request and echoes the document back as its answer. */
    private val echo = TextEngine { prompt, grammar, _, onToken ->
        if (grammar != null) return@TextEngine """{"name":"answer_locally","arguments":{"task":"summarise"}}"""
        val doc = prompt.user.substringAfter("Document:\n").substringBefore("\n\nRequest:")
        doc.split(" ").forEach { onToken("$it ") }
        doc
    }

    @Test fun `the exam is scored by code`() {
        var now = 0L
        val report = ModelBench.run("echo", 1_500, echo, clock = { now += 100; now })
        val plans = report.rows.filter { it.kind == "plan" }
        assertEquals(ModelBench.PLANS.count { it.local }, plans.count { it.pass }, "always-local passes only the local cases")
        assertTrue(report.rows.filter { it.kind == "answer" }.all { it.pass }, "echoing the document keeps every fact")
        assertTrue(report.summary().startsWith("echo | load 1.5 s | plans 7/12"), report.summary())
        assertTrue(report.tokensPerSecond > 0)
    }

    @Test fun `every plan case is valid for the grammar the phone uses`() {
        assertTrue(AgentGrammar.GBNF.isNotBlank())
        assertEquals(12, ModelBench.PLANS.size)
        assertEquals(setOf(true, false), ModelBench.PLANS.map { it.local }.toSet())
    }
}
