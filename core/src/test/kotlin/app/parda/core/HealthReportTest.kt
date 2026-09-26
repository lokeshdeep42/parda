package app.parda.core

import app.parda.core.agent.AgentPlan
import app.parda.core.agent.DisclosureGate
import app.parda.core.agent.GateDecision
import app.parda.core.agent.HandBackReason
import app.parda.core.agent.RuleBasedAgent
import app.parda.core.document.SuggestedQuestions
import app.parda.core.policy.Policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A lab report shared with an assistant to have it explained: everything that says who the
 * patient is must be masked, while the results themselves stay, since they are the question.
 */
class HealthReportTest {
    private val report = """
        SUNRISE DIAGNOSTICS, Kondapur, Hyderabad
        Patient Name : Mrs. Lakshmi Narayanan
        Age / Sex : 46 Y / Female          DOB: 12/07/1979
        UHID : SRD-2026-004512             Lab No : 26091844
        ABHA Number : 91-2345-6789-0123
        ABHA Address : lakshmi.n@abdm
        Mobile : +91 98480 22331
        Address : 8-2-293, Road No. 12, Banjara Hills, Hyderabad 500034
        Referred By : Dr. Anil Menon
        Sample Collected : 24/09/2026 08:10

        COMPLETE BLOOD COUNT
        Haemoglobin            10.2 g/dL      (12.0 - 15.5)   L
        Total WBC count        11,800 /cumm   (4,000 - 11,000) H
        Platelet count         2.1 lakh/cumm  (1.5 - 4.5)
        HbA1c                  7.4 %          (< 5.7)          H
        Impression: Mild anaemia. Raised HbA1c, suggestive of diabetes.
    """.trimIndent()

    private fun masked() = assertIs<GateDecision.HandedBack>(
        DisclosureGate(RuleBasedAgent()).handle(report, "Compare these results with normal ranges and tell me what they mean.", Policy()),
    ).outbound

    @Test fun `everything that identifies the patient is masked`() {
        val out = masked()
        listOf(
            "Lakshmi Narayanan", "12/07/1979", "SRD-2026-004512", "26091844", "91-2345-6789-0123",
            "lakshmi.n@abdm", "98480 22331", "Banjara Hills",
        ).forEach { assertFalse(it in out, "$it leaked:\n$out") }
    }

    @Test fun `the results themselves stay, so the question can still be answered`() {
        val out = masked()
        listOf("Haemoglobin", "10.2 g/dL", "2.1 lakh/cumm", "HbA1c", "7.4 %", "Mild anaemia").forEach { assertTrue(it in out, "$it was lost:\n$out") }
    }

    @Test fun `a lab report gets health questions, and explaining results is handed back masked`() {
        val chips = SuggestedQuestions.of(report)
        assertEquals(listOf("Summarise", "List personal data", "Explain my results"), chips.map { it.label })
        assertEquals(AgentPlan.HandBack(HandBackReason.NEEDS_OUTSIDE_KNOWLEDGE), RuleBasedAgent().plan(chips[2].request, report))
    }
}
