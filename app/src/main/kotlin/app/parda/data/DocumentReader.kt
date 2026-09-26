package app.parda.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.parda.core.document.OfficeText
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * Pulls the text out of a file the user picked or shared, on the device. The file itself is
 * never copied or sent anywhere; only its text goes into the Airlock.
 */
object DocumentReader {
    /** The MIME types the picker offers and the share target accepts. */
    val MIME_TYPES = arrayOf(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "text/plain",
        "text/csv",
    )

    /** Enough for any letter, statement or form; keeps the text box and the model responsive. */
    private const val MAX_CHARS = 20_000

    open class Unsupported(message: String) : Exception(message)

    /** The file opened but held no text; for a PDF that means a scan, which OCR can read. */
    class NoText(message: String, val pdf: Boolean) : Unsupported(message)

    /** Summaries read the whole file up to this (a 600-page PDF is about 1.5 million). */
    private const val MAX_FULL = 3_000_000

    data class Result(
        val name: String,
        /** What the text box shows and what a sanitized copy is made from: the first [MAX_CHARS]. */
        val text: String,
        val truncated: Boolean,
        /** The whole document as read so far, for summaries. */
        val full: String,
        val pages: Int?,
        /** False when only enough of a long PDF was read for the preview; read again with whole = true. */
        val complete: Boolean,
    )

    /**
     * Blocking: call off the main thread. A long PDF is slow to read whole, so by default only
     * enough for the preview is read and [Result.complete] is false; [whole] reads all of it.
     */
    fun read(context: Context, uri: Uri, whole: Boolean = false): Result {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "document"
        val mime = resolver.getType(uri).orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()

        var pages: Int? = null
        var complete = true
        val text = resolver.openInputStream(uri)!!.use { input ->
            when {
                mime == "application/pdf" || ext == "pdf" -> {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    PDDocument.load(input).use { pdf ->
                        pages = pdf.numberOfPages
                        val (read, all) = pdfText(pdf, if (whole) MAX_FULL else MAX_CHARS)
                        complete = all
                        read
                    }
                }
                ext == "docx" || mime.endsWith("wordprocessingml.document") -> OfficeText.docx(input)
                ext == "xlsx" || mime.endsWith("spreadsheetml.sheet") -> OfficeText.xlsx(input)
                mime.startsWith("text/") || ext in setOf("txt", "csv") -> input.bufferedReader().readText()
                ext == "doc" || ext == "xls" -> throw Unsupported("Old .$ext files are not supported. Save it as .${ext}x and try again.")
                else -> throw Unsupported("Parda reads PDF, Word (.docx), Excel (.xlsx) and text files.")
            }
        }.trim()

        if (text.isEmpty()) {
            throw NoText("No text found in $name.", pdf = mime == "application/pdf" || ext == "pdf")
        }
        val full = text.take(MAX_FULL)
        return Result(name, text.take(MAX_CHARS), text.length > MAX_CHARS, full, pages, complete && text.length <= MAX_FULL)
    }

    /** Page by page, stopping past [limit]. Returns the text and whether every page was read. */
    private fun pdfText(pdf: PDDocument, limit: Int): Pair<String, Boolean> {
        val stripper = PDFTextStripper()
        if (limit >= MAX_FULL) {
            // The whole file in one pass: asking page by page makes PDFBox walk the page tree
            // each time, which is quadratic (600 pages took 145 s instead of about 15 s).
            return stripper.getText(pdf) to true
        }
        val out = StringBuilder()
        for (page in 1..pdf.numberOfPages) {
            stripper.startPage = page
            stripper.endPage = page
            out.append(stripper.getText(pdf))
            if (out.length > limit) return out.toString() to (page == pdf.numberOfPages)
        }
        return out.toString() to true
    }
}
