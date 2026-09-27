package app.parda.core

import app.parda.core.disclosure.Sanitizer
import app.parda.core.policy.DataCategory
import app.parda.core.policy.DisclosureAction
import app.parda.core.policy.Policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandInTest {
    private val sanitizer = Sanitizer()
    private val policy = Policy()
        .with(DataCategory.PERSON_NAME, DisclosureAction.STAND_IN)
        .with(DataCategory.EMAIL, DisclosureAction.STAND_IN)
        .with(DataCategory.ADDRESS, DisclosureAction.STAND_IN)

    @Test fun `the salary letter reads naturally, with nothing real in it`() {
        val out = sanitizer.sanitize(Samples.SALARY_LETTER, policy).sanitized
        assertTrue("Dear Mr Arjun Mehta," in out, out)
        assertTrue("ananya.rao@example.com" in out, out)
        assertTrue("Registered address: Plot 17, Lakeview Colony, Hyderabad\n" in out, out)
        listOf("Rajesh", "Kumar", "Kondapur", "500084", "rajesh.kumar@").forEach { assertFalse(it in out, "$it leaked:\n$out") }
        // Amounts and phone numbers keep their placeholders; account and PAN stay blocked.
        assertTrue("<AMOUNT_1>" in out && "<PHONE_1>" in out && Sanitizer.BLOCKED in out, out)
    }

    @Test fun `a reply that uses the stand-ins, whole or in part, comes back with the real values`() {
        val result = sanitizer.sanitize(Samples.SALARY_LETTER, policy)
        val reply = "Arjun Mehta earns <AMOUNT_1>. Mr. Mehta can write to ananya.rao@example.com. " +
            "Arjun's home is Plot 17, Lakeview Colony, Hyderabad."
        assertEquals(
            "Rajesh Kumar earns ₹18,40,000. Mr. Kumar can write to rajesh.kumar@example.com. " +
                "Rajesh's home is 12-4-89, Kondapur Main Road, Hyderabad 500084.",
            result.vault.rehydrate(reply),
        )
    }

    @Test fun `the same text always gets the same stand-ins, and one person keeps one stand-in`() {
        val text = "Dear Mrs. Lakshmi Reddy, thank you. Name: Lakshmi Reddy"
        val a = sanitizer.sanitize(text, policy).sanitized
        assertEquals(a, sanitizer.sanitize(text, policy).sanitized)
        assertEquals("Dear Mrs. Ananya Rao, thank you. Name: Ananya Rao", a)
    }

    @Test fun `a stand-in never uses a word already in the text`() {
        val text = "Mr. Arjun Mehta and Mr. Rohan Iyer met Mr. Suresh Babu."
        val out = sanitizer.sanitize(text, policy).sanitized
        assertEquals("Mr. Vikram Bhatia and Mr. Karthik Menon met Mr. Aditya Joshi.", out)
    }

    @Test fun `a name in another script falls back to a placeholder`() {
        val out = sanitizer.sanitize("श्री रमेश कुमार को वेतन", policy)
        assertTrue(out.vault.entries.none { it.action == DisclosureAction.STAND_IN }, out.sanitized)
    }

    @Test fun `stand-ins are refused where a fake could mislead or belong to someone real`() {
        val strict = Policy().with(DataCategory.MONEY_AMOUNT, DisclosureAction.STAND_IN).with(DataCategory.PHONE, DisclosureAction.STAND_IN)
        assertEquals(DisclosureAction.SURROGATE, strict.actionFor(DataCategory.MONEY_AMOUNT))
        val out = sanitizer.sanitize(Samples.SALARY_LETTER, strict).sanitized
        assertTrue("<AMOUNT_1>" in out && "<PHONE_1>" in out, out)
    }
}
