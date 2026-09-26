package app.parda.core.document

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Plain text out of Office Open XML files, read on the device with the platform's own zip and
 * SAX parsers. Only the text is kept: that is what the Airlock classifies and sanitizes.
 */
object OfficeText {

    /** Word: paragraphs become lines, tabs stay tabs. */
    fun docx(input: InputStream): String {
        val xml = entries(input) { it == "word/document.xml" }["word/document.xml"]
            ?: throw IllegalArgumentException("Not a Word document")
        val out = StringBuilder()
        parse(xml, object : DefaultHandler() {
            private var inText = false
            override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes?) {
                when (qName) {
                    "w:t" -> inText = true
                    "w:tab" -> out.append('\t')
                    "w:br", "w:cr" -> out.append('\n')
                }
            }
            override fun endElement(uri: String?, local: String?, qName: String) {
                when (qName) {
                    "w:t" -> inText = false
                    "w:p" -> out.append('\n')
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText) out.appendRange(ch, start, start + length)
            }
        })
        return out.toString().trim()
    }

    /** Excel: every sheet, one row per line, cells separated by tabs. */
    fun xlsx(input: InputStream): String {
        val files = entries(input) { it == SHARED || (it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml")) }
        val shared = files[SHARED]?.let(::sharedStrings).orEmpty()
        val sheets = files.keys.filter { it != SHARED }
            .sortedBy { it.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: 0 }
        if (sheets.isEmpty()) throw IllegalArgumentException("Not an Excel workbook")
        return sheets.joinToString("\n\n") { sheet(files.getValue(it), shared) }.trim()
    }

    private fun sheet(xml: ByteArray, shared: List<String>): String {
        val rows = mutableListOf<String>()
        parse(xml, object : DefaultHandler() {
            private val cells = mutableListOf<String>()
            private var type: String? = null
            private var capture = false
            private val value = StringBuilder()
            override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes) {
                when (qName) {
                    "row" -> cells.clear()
                    "c" -> { type = attrs.getValue("t"); value.clear() }
                    "v", "t" -> capture = true
                }
            }
            override fun endElement(uri: String?, local: String?, qName: String) {
                when (qName) {
                    "v", "t" -> capture = false
                    "c" -> cells += if (type == "s") shared.getOrElse(value.toString().trim().toIntOrNull() ?: -1) { "" } else value.toString()
                    "row" -> if (cells.any { it.isNotBlank() }) rows += cells.joinToString("\t")
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capture) value.appendRange(ch, start, start + length)
            }
        })
        return rows.joinToString("\n")
    }

    private fun sharedStrings(xml: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        parse(xml, object : DefaultHandler() {
            private val current = StringBuilder()
            private var capture = false
            override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes?) {
                when (qName) {
                    "si" -> current.clear()
                    "t" -> capture = true
                }
            }
            override fun endElement(uri: String?, local: String?, qName: String) {
                when (qName) {
                    "t" -> capture = false
                    "si" -> strings += current.toString()
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capture) current.appendRange(ch, start, start + length)
            }
        })
        return strings
    }

    private fun entries(input: InputStream, wanted: (String) -> Boolean): Map<String, ByteArray> {
        val found = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e ->
                if (!e.isDirectory && wanted(e.name)) found[e.name] = zip.readBytes()
            }
        }
        return found
    }

    private fun parse(xml: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
        // Office files never need external entities; refusing them closes the XXE door.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        factory.newSAXParser().parse(ByteArrayInputStream(xml), handler)
    }

    private const val SHARED = "xl/sharedStrings.xml"
}
