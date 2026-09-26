package app.parda.core

import app.parda.core.detect.Classifier
import app.parda.core.disclosure.Sanitizer
import app.parda.core.policy.DataCategory
import app.parda.core.policy.DisclosureAction
import app.parda.core.policy.Policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The stricter health mode: diagnoses masked too, for a report going to an employer or insurer. */
class StrictHealthTest {
    private val report = """
        Patient Name : Mrs. Lakshmi Narayanan
        Haemoglobin            10.2 g/dL      (12.0 - 15.5)   L
        HbA1c                  7.4 %          (< 5.7)          H
        Impression: Mild anaemia. Raised HbA1c, suggestive of diabetes.
        Known case of hypertension, on treatment.
    """.trimIndent()

    private val strict = Policy().with(DataCategory.HEALTH_CONDITION, DisclosureAction.SURROGATE)

    @Test fun `by default conditions pass, so a report can still be explained`() {
        val out = Sanitizer().sanitize(report, Policy()).sanitized
        assertTrue("Mild anaemia" in out && "hypertension" in out, out)
        assertFalse("Lakshmi Narayanan" in out)
    }

    @Test fun `in strict mode the diagnosis is masked and the lab values stay`() {
        val out = Sanitizer().sanitize(report, strict).sanitized
        listOf("anaemia", "diabetes", "hypertension").forEach { assertFalse(it in out, "$it leaked:\n$out") }
        listOf("Haemoglobin", "10.2 g/dL", "7.4 %").forEach { assertTrue(it in out, "$it lost:\n$out") }
        assertTrue("<CONDITION_" in out, out)
    }

    @Test fun `conditions are found in running text and after Hindi labels`() {
        val found = Classifier().classify("She is pregnant. Tested positive for HIV.\nनिदान: मधुमेह").filter { it.category == DataCategory.HEALTH_CONDITION }
        assertEquals(listOf("pregnant", "HIV", "मधुमेह"), found.map { it.value })
    }

    @Test fun `everyday words are not conditions`() {
        val found = Classifier().classify("Please confirm the tumbler order and the anxiety of waiting.").filter { it.category == DataCategory.HEALTH_CONDITION }
        assertEquals(emptyList(), found.map { it.value })
    }
}
