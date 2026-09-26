package app.parda.core

import app.parda.core.detect.Classifier
import app.parda.core.document.DocumentOverview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentOverviewTest {
    private val records = (0 until 500).joinToString("\n") { i ->
        "Record $i: Mr Anil Nair, PAN ABCPK1234M, phone +91 98490 12345, paid Rs ${1_000 + i}."
    }

    @Test fun `records are counted across the whole document`() {
        val o = DocumentOverview.of(records, pages = 12)
        assertTrue(o.dataHeavy)
        assertTrue("12 pages, 500 lines" in o.text, o.text)
        assertTrue("500 lines start with “Record”" in o.text, o.text)
        assertTrue("500 government ID numbers" in o.text && "500 phone numbers" in o.text, o.text)
        // 1,000 + 1,001 + ... + 1,499
        assertTrue("totalling ₹6,24,750, from ₹1,000 to ₹1,499" in o.text, o.text)
    }

    @Test fun `a spreadsheet is described by its columns`() {
        val sheet = "Employee\tPAN\tAccount\n" + (1..40).joinToString("\n") { "Name $it\tABCPK1234M\t5010023456789$it" }
        val o = DocumentOverview.of(sheet)
        assertTrue("A table of 40 rows; columns: Employee, PAN, Account." in o.text, o.text)
        assertTrue(o.dataHeavy)
    }

    @Test fun `a CSV export is described by its columns`() {
        val csv = "Customer,Email,Phone,City\n" + (1..30).joinToString("\n") { "Anil Nair,anil$it@example.com,+91 98490 12345,Pune" }
        val o = DocumentOverview.of(csv)
        assertTrue("A table of 30 rows; columns: Customer, Email, Phone, City." in o.text, o.text)
        assertTrue(o.dataHeavy)
    }

    @Test fun `prose is not data-heavy`() {
        val prose = "This agreement sets out the terms of the lease between the parties. ".repeat(80)
        assertFalse(DocumentOverview.of(prose).dataHeavy)
    }

    @Test fun `the same document always gives the same summary`() {
        assertEquals(DocumentOverview.of(records).text, DocumentOverview.of(records).text)
    }

    /** The overlap check used to compare every match with every other: 600 pages took minutes. */
    @Test fun `classifying a very large document is fast`() {
        val big = (0 until 20_000).joinToString("\n") { "Record $it: Mr Anil Nair, PAN ABCPK1234M, paid Rs 1,000." }
        val started = System.nanoTime()
        val found = Classifier().classify(big)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(60_000, found.size)
        assertTrue(ms < 5_000, "took $ms ms")
    }
}
