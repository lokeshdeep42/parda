package app.parda.core

import app.parda.core.detect.Classifier
import app.parda.core.document.OfficeText
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OfficeTextTest {
    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            files.forEach { (name, body) -> z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() }
        }
        return bytes.toByteArray()
    }

    @Test fun `docx paragraphs and runs become lines`() {
        val doc = zip(
            "word/document.xml" to """<?xml version="1.0"?><w:document xmlns:w="x"><w:body>
                <w:p><w:r><w:t>Dear Mr Rajesh </w:t></w:r><w:r><w:t>Kumar,</w:t></w:r></w:p>
                <w:p><w:r><w:t>PAN on record:</w:t><w:tab/><w:t>ABCPK1234M</w:t></w:r></w:p>
                </w:body></w:document>""",
        )
        val text = OfficeText.docx(doc.inputStream())
        assertEquals("Dear Mr Rajesh Kumar,\nPAN on record:\tABCPK1234M", text)
        assertTrue(Classifier().classify(text).any { it.value == "ABCPK1234M" })
    }

    @Test fun `xlsx resolves shared strings and keeps numbers`() {
        val book = zip(
            "xl/sharedStrings.xml" to """<sst><si><t>Name</t></si><si><t>Account</t></si><si><r><t>Rajesh </t></r><r><t>Kumar</t></r></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData>
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                <row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>50100234567891</v></c></row>
                </sheetData></worksheet>""",
        )
        assertEquals("Name\tAccount\nRajesh Kumar\t50100234567891", OfficeText.xlsx(book.inputStream()))
    }

    @Test fun `a zip that is not an office file is refused`() {
        assertFailsWith<IllegalArgumentException> { OfficeText.docx(zip("hello.txt" to "hi").inputStream()) }
    }
}
