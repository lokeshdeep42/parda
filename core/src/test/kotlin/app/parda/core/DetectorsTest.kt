package app.parda.core

import app.parda.core.detect.Checksums
import app.parda.core.detect.Classifier
import app.parda.core.policy.DataCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetectorsTest {
    private val classifier = Classifier()

    private fun categoriesOf(text: String) = classifier.classify(text).map { it.category to it.value }

    @Test fun `salary letter finds every sensitive field`() {
        val found = categoriesOf(Samples.SALARY_LETTER)
        assertTrue(DataCategory.PERSON_NAME to "Rajesh Kumar" in found, "$found")
        assertTrue(DataCategory.MONEY_AMOUNT to "₹18,40,000" in found, "$found")
        assertTrue(DataCategory.BANK_ACCOUNT to "50100234567891" in found, "$found")
        assertTrue(DataCategory.GOV_ID to "ABCPK1234M" in found, "$found")
        assertTrue(DataCategory.ADDRESS to "12-4-89, Kondapur Main Road, Hyderabad 500084" in found, "$found")
        assertTrue(DataCategory.EMAIL to "rajesh.kumar@example.com" in found, "$found")
        assertTrue(DataCategory.PHONE to "+91 98490 12345" in found, "$found")
    }

    @Test fun `detections never overlap`() {
        val found = classifier.classify(Samples.SALARY_LETTER)
        found.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start, "$a overlaps $b") }
    }

    @Test fun `aadhaar requires a valid verhoeff checksum`() {
        assertTrue(Checksums.verhoeff("234123412346"))
        assertEquals(listOf(DataCategory.GOV_ID), classifier.classify("Aadhaar 2341 2341 2346").map { it.category })
        // Same shape, bad checksum: still caught, but as a bare number rather than an ID.
        assertEquals(listOf(DataCategory.BANK_ACCOUNT), classifier.classify("Ref 234123412345").map { it.category })
    }

    @Test fun `card numbers need a luhn match`() {
        assertEquals(listOf(DataCategory.CARD), classifier.classify("card 4111 1111 1111 1111 exp").map { it.category })
        assertFalse(Checksums.luhn("4111111111111112"))
    }

    @Test fun `labels are not masked, only values`() {
        val d = classifier.classify("DOB: 14/08/1996").single()
        assertEquals(DataCategory.DATE_OF_BIRTH, d.category)
        assertEquals("14/08/1996", d.value)
        val n = classifier.classify("Name: A. Sharma").single()
        assertEquals("A. Sharma", n.value)
    }

    @Test fun `generic salutations are not names`() {
        assertTrue(classifier.classify("Dear Sir, thank you.").isEmpty())
    }

    @Test fun `plain prose has nothing to mask`() {
        assertTrue(classifier.classify("Summarise the key terms of this letter in three lines.").isEmpty())
    }
}
