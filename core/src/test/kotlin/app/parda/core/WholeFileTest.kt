package app.parda.core

import app.parda.core.agent.DisclosureGate
import app.parda.core.agent.GateDecision
import app.parda.core.agent.HandBackReason
import app.parda.core.agent.ModelAgent
import app.parda.core.agent.Planner
import app.parda.core.agent.TextEngine
import app.parda.core.document.OfficeText
import app.parda.core.document.SuggestedQuestions
import app.parda.core.policy.Policy
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Airlock end to end, as on the phone: a file is read, its suggestion chips are shown, and
 * every chip is asked. The model is a fake that records each call, so these run in seconds and
 * check what the phone checks: whole-file summaries, data-specific chips, streaming, and that no
 * chip waits on the model to plan.
 */
class WholeFileTest {
    private class FakeEngine : TextEngine {
        var plans = 0
        val answered = mutableListOf<String>()
        override fun complete(prompt: String, grammar: String?, maxTokens: Int, onToken: (String) -> Unit): String {
            if (grammar != null) { plans++; return """{"name":"hand_back","arguments":{"reason":"too_complex"}}""" }
            answered += prompt
            val gist = "The tenant pays a monthly rent and a refundable deposit."
            gist.split(" ").forEach { onToken("$it ") }
            return gist
        }
    }

    private class Asked(val label: String, val decision: GateDecision, val streamed: String)

    /** What the app does after reading a file: the box shows the first 20,000 characters, summaries get all of it. */
    private fun ask(text: String, pages: Int? = null): Pair<FakeEngine, List<Asked>> {
        val engine = FakeEngine()
        val gate = DisclosureGate(ModelAgent(engine))
        val preview = text.take(20_000)
        val asked = SuggestedQuestions.of(preview).map { s ->
            val streamed = StringBuilder()
            Asked(s.label, gate.handle(preview, s.request, Policy(), full = text, pages = pages) { streamed.append(it) }, streamed.toString())
        }
        assertEquals(0, engine.plans, "every chip is planned by the keyword rules")
        asked.forEach { assertEquals(Planner.RULES, it.decision.plannedBy, it.label) }
        return engine to asked
    }

    private fun List<Asked>.labels() = map { it.label }
    private fun List<Asked>.answer(label: String) = assertIs<GateDecision.HeldLocally>(single { it.label == label }.decision).answer
    private fun List<Asked>.handedBack(label: String) = assertIs<GateDecision.HandedBack>(single { it.label == label }.decision)

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            files.forEach { (name, body) -> z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() }
        }
        return bytes.toByteArray()
    }

    private fun docx(paragraphs: List<String>) = OfficeText.docx(
        zip("word/document.xml" to paragraphs.joinToString("", "<w:document xmlns:w=\"x\"><w:body>", "</w:body></w:document>") {
            "<w:p><w:r><w:t>$it</w:t></w:r></w:p>"
        }).inputStream(),
    )

    private fun pan(i: Int) = "ABCP" + ('A' + i % 26) + (1000 + i % 9000) + ('A' + (i / 26) % 26)

    @Test fun `a 6,000-paragraph Word file is summarised whole, by code, quickly`() {
        val started = System.nanoTime()
        val text = docx((0 until 6_000).map { "Record $it: Mr Anil Nair, PAN ${pan(it)}, paid Rs ${1_000 + it}." })
        val (engine, asked) = ask(text)
        val ms = (System.nanoTime() - started) / 1_000_000

        assertEquals(listOf("Summarise", "List personal data", "Compare amounts"), asked.labels())
        val summary = asked.answer("Summarise")
        assertTrue("6,000 lines start with “Record”" in summary, summary)
        assertTrue("6,000 government ID numbers" in summary, summary)
        // 6,000 x 1,000 + (0 + 1 + ... + 5,999)
        assertTrue("totalling ₹2,39,97,000, from ₹1,000 to ₹6,999" in summary, summary)
        assertTrue(engine.answered.isEmpty(), "records get no model gist")
        assertTrue(asked.answer("List personal data").startsWith("18000 item(s)"), asked.answer("List personal data"))
        assertEquals(HandBackReason.NEEDS_OUTSIDE_KNOWLEDGE, asked.handedBack("Compare amounts").reason)
        assertTrue(ms < 10_000, "took $ms ms")
    }

    @Test fun `a 50,000-row spreadsheet is described by its columns`() {
        val names = listOf("Anil Nair", "Priya Sharma", "Ravi Teja", "Meena Iyer", "Kiran Rao")
        val shared = listOf("Employee", "PAN", "Monthly salary") + names
        val rows = (0 until 50_000).joinToString("") { r ->
            """<row><c t="s"><v>${3 + r % names.size}</v></c><c t="inlineStr"><is><t>${pan(r)}</t></is></c><c><v>${40_000 + r}</v></c></row>"""
        }
        val text = OfficeText.xlsx(
            zip(
                "xl/sharedStrings.xml" to shared.joinToString("", "<sst>", "</sst>") { "<si><t>$it</t></si>" },
                "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row><c t="s"><v>0</v></c><c t="s"><v>1</v></c><c t="s"><v>2</v></c></row>$rows</sheetData></worksheet>""",
            ).inputStream(),
        )
        val (_, asked) = ask(text)
        assertEquals(listOf("Summarise", "List personal data", "Compare with others"), asked.labels())
        val summary = asked.answer("Summarise")
        assertTrue("A table of 50,000 rows; columns: Employee, PAN, Monthly salary." in summary, summary)
    }

    @Test fun `a customer CSV export`() {
        val csv = "Customer,Email,Phone,City\n" + (1..2_000).joinToString("\n") {
            "Customer $it,customer$it@example.com,+91 98490 ${10_000 + it},Pune"
        }
        val (_, asked) = ask(csv)
        assertEquals(listOf("Summarise", "List personal data", "Compare with others"), asked.labels())
        val summary = asked.answer("Summarise")
        assertTrue("A table of 2,000 rows; columns: Customer, Email, Phone, City." in summary, summary)
        assertTrue("2,000 email addresses" in summary && "2,000 phone numbers" in summary, summary)
    }

    @Test fun `a bank statement gets statement questions and a whole-period total`() {
        val lines = (1..300).map { "${"%02d".format(1 + it % 28)}/08 UPI debit to merchant $it Rs ${100 + it}.00" }
        val text = "HDFC Bank account statement, August 2026\nAccount number 50100234567891\n" +
            "Opening balance Rs 42,310.00\n" + lines.joinToString("\n")
        val (engine, asked) = ask(text)
        assertEquals(listOf("Summarise", "List personal data", "Unusual spending?"), asked.labels())
        val summary = asked.answer("Summarise")
        assertTrue("Amounts: 301 mentioned" in summary, summary)
        assertTrue(engine.answered.isEmpty(), "a statement is data: no model gist")
        val out = asked.handedBack("Unusual spending?")
        assertEquals(HandBackReason.NEEDS_OUTSIDE_KNOWLEDGE, out.reason)
        assertFalse("50100234567891" in out.outbound, "the account number never leaves")
    }

    @Test fun `a rent agreement is prose - overview plus a streamed model gist of the opening`() {
        val clauses = listOf(
            "This Leave and Licence Agreement is made at Hyderabad between the Landlord, Mr Suresh Rao, and the Tenant, Ms Kavya Menon.",
            "The Landlord hereby grants the Tenant the use of the flat described in the schedule for eleven months.",
            "The Tenant shall pay a monthly rent of Rs 24,000 on or before the fifth day of each month.",
            "The Tenant shall keep a refundable deposit of Rs 1,20,000 with the Landlord for the term of this agreement.",
            "Either party may end this agreement by giving the other one month of written notice.",
            "The Tenant shall not sublet the flat or use it for any purpose other than residence.",
            "Repairs arising from ordinary wear are the Landlord's responsibility; damage caused by the Tenant is not.",
            "This agreement is governed by the laws in force in Telangana.",
        )
        val text = (1..6).joinToString("\n") { n -> clauses.joinToString("\n") { "Clause $n: $it" } }
        assertTrue(text.length > 3_000)
        val (engine, asked) = ask(text)
        assertEquals(listOf("Summarise", "List personal data", "Is it legal?"), asked.labels())

        val summarise = asked.single { it.label == "Summarise" }
        val summary = asked.answer("Summarise")
        assertTrue(summary.startsWith("Covers the whole document"), summary)
        assertTrue("The opening, summarised on this phone:\nThe tenant pays" in summary, summary)
        assertEquals(1, engine.answered.size, "the model is asked once, for the gist")
        assertTrue(summarise.streamed.startsWith("Covers the whole document") && "refundable deposit" in summarise.streamed)

        val legal = asked.handedBack("Is it legal?")
        assertFalse("Suresh Rao" in legal.outbound || "Kavya Menon" in legal.outbound, legal.outbound)
    }

    @Test fun `a salary letter keeps its own questions`() {
        val (_, asked) = ask(Samples.SALARY_LETTER)
        assertEquals(listOf("Summarise", "Am I underpaid?"), asked.labels())
        assertEquals(HandBackReason.NEEDS_OUTSIDE_KNOWLEDGE, asked.handedBack("Am I underpaid?").reason)
    }

    @Test fun `a government ID is listed and never sent for verification`() {
        val id = "INCOME TAX DEPARTMENT\nName: Rajesh Kumar\nDate of birth: 14/03/1991\nPermanent Account Number: ABCPK1234M"
        val (_, asked) = ask(id)
        assertEquals(listOf("List personal data", "Verify this ID"), asked.labels())
        assertTrue("ABCPK1234M" in asked.answer("List personal data"))
        val out = asked.handedBack("Verify this ID")
        assertFalse("ABCPK1234M" in out.outbound, out.outbound)
    }
}
