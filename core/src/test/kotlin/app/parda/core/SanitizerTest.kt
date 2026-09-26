package app.parda.core

import app.parda.core.disclosure.Sanitizer
import app.parda.core.policy.DataCategory
import app.parda.core.policy.DisclosureAction
import app.parda.core.policy.Policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SanitizerTest {
    private val sanitizer = Sanitizer()

    @Test fun `no sensitive value survives the default policy`() {
        val r = sanitizer.sanitize(Samples.SALARY_LETTER, Policy())
        for (secret in listOf("Rajesh Kumar", "18,40,000", "50100234567891", "ABCPK1234M", "Kondapur", "98490", "rajesh.kumar@")) {
            assertFalse(secret in r.sanitized, "$secret leaked:\n${r.sanitized}")
        }
        assertTrue("<AMOUNT_1>" in r.sanitized)
        assertEquals(2, r.blockedCount) // account number and PAN
    }

    @Test fun `surrogates round-trip through a reply`() {
        val r = sanitizer.sanitize(Samples.SALARY_LETTER, Policy())
        val reply = "AMOUNT_1 sits at the upper end of the band. <PERSON_1> is well paid."
        assertEquals("₹18,40,000 sits at the upper end of the band. Rajesh Kumar is well paid.", r.vault.rehydrate(reply))
    }

    @Test fun `blocked values are never restored`() {
        val r = sanitizer.sanitize("PAN ABCPK1234M", Policy())
        assertEquals("PAN [BLOCKED]", r.sanitized)
        assertEquals("PAN [BLOCKED]", r.vault.rehydrate(r.sanitized))
    }

    @Test fun `repeated values share one token`() {
        val r = sanitizer.sanitize("Call 9849012345. Again: 98490 12345.", Policy())
        assertEquals("Call <PHONE_1>. Again: <PHONE_1>.", r.sanitized)
    }

    @Test fun `policy changes what leaves`() {
        val allowAmounts = Policy().with(DataCategory.MONEY_AMOUNT, DisclosureAction.ALLOW)
        assertTrue("₹18,40,000" in sanitizer.sanitize(Samples.SALARY_LETTER, allowAmounts).sanitized)
        val last4 = Policy().with(DataCategory.BANK_ACCOUNT, DisclosureAction.KEEP_LAST_4)
        assertTrue("XXXXXXXXXX7891" in sanitizer.sanitize(Samples.SALARY_LETTER, last4).sanitized)
    }

    @Test fun `keep last four preserves separators`() {
        assertEquals("XXXX XXXX 4821", Sanitizer.keepLast4("2345 6789 4821"))
    }

    @Test fun `unknown tokens in a reply are left alone`() {
        val r = sanitizer.sanitize("Mr Rajesh Kumar", Policy())
        assertEquals("<PERSON_7> and Rajesh Kumar", r.vault.rehydrate("<PERSON_7> and PERSON_1"))
    }
}
