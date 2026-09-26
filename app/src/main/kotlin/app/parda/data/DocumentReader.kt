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

    class Unsupported(message: String) : Exception(message)

    data class Result(val name: String, val text: String, val truncated: Boolean)

    /** Blocking: call off the main thread. */
    fun read(context: Context, uri: Uri): Result {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "document"
        val mime = resolver.getType(uri).orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()

        val text = resolver.openInputStream(uri)!!.use { input ->
            when {
                mime == "application/pdf" || ext == "pdf" -> {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    PDDocument.load(input).use { PDFTextStripper().getText(it) }
                }
                ext == "docx" || mime.endsWith("wordprocessingml.document") -> OfficeText.docx(input)
                ext == "xlsx" || mime.endsWith("spreadsheetml.sheet") -> OfficeText.xlsx(input)
                mime.startsWith("text/") || ext in setOf("txt", "csv") -> input.bufferedReader().readText()
                ext == "doc" || ext == "xls" -> throw Unsupported("Old .$ext files are not supported. Save it as .${ext}x and try again.")
                else -> throw Unsupported("Parda reads PDF, Word (.docx), Excel (.xlsx) and text files.")
            }
        }.trim()

        if (text.isEmpty()) {
            throw Unsupported("No text found in $name. If it is a scanned image, it has no text layer to read.")
        }
        return Result(name, text.take(MAX_CHARS), text.length > MAX_CHARS)
    }
}
