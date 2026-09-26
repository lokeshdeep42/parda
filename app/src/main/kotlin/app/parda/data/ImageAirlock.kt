package app.parda.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import app.parda.core.image.Box
import app.parda.core.image.ImageMaskPlan
import app.parda.core.image.ImageMasker
import app.parda.core.image.OcrLine
import app.parda.core.image.OcrWord
import app.parda.core.policy.Policy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

/**
 * The Airlock for pictures: a photo of an ID card, a screenshot of a bank app. ML Kit's bundled
 * OCR reads it on the device, [ImageMasker] decides what to cover under the user's policy, and
 * a flattened copy is painted here. The original file is never modified or sent.
 */
object ImageAirlock {
    private const val TAG = "PardaOCR"
    /** Large enough to read an ID card, small enough to keep OCR quick and memory low. */
    private const val MAX_SIDE = 2000

    class Result(val original: Bitmap, val plan: ImageMaskPlan)

    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val devanagari by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }
    private val masker = ImageMasker()

    /** Blocking: call off the main thread. */
    fun read(context: Context, uri: Uri, policy: Policy): Result = read(decode(context, uri), policy)

    /**
     * A scanned PDF has no text layer, only a picture of each page. Its first page is rendered
     * on the device and masked like a photo (ID scans are one page).
     */
    fun readScannedPdf(context: Context, uri: Uri, policy: Policy): Result {
        val bitmap = context.contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
            PdfRenderer(fd).use { pdf ->
                pdf.openPage(0).use { page ->
                    val scale = MAX_SIDE.toFloat() / maxOf(page.width, page.height)
                    val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
        return read(bitmap, policy)
    }

    private fun read(bitmap: Bitmap, policy: Policy): Result {
        // Both models ship inside the app. Devanagari also reads Latin; when it finds no Hindi,
        // the Latin model, which is tuned for it, reads the image instead.
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = Tasks.await(devanagari.process(image)).takeIf { t -> t.text.any { it in '\u0900'..'\u097F' } }
            ?: Tasks.await(latin.process(image))
        val lines = text.textBlocks.flatMap { it.lines }.map { line ->
            OcrLine(
                line.elements.mapNotNull { e ->
                    val r = e.boundingBox ?: return@mapNotNull null
                    OcrWord(e.text, Box(r.left, r.top, r.right, r.bottom))
                },
            )
        }
        val plan = masker.plan(lines, policy)
        if (BuildConfigCompat.debuggable) {
            // Debug builds only: what OCR read and what was covered, for tuning.
            lines.forEach { l -> Log.i(TAG, l.words.joinToString(" | ") { "${it.text}@${it.box.left}-${it.box.right}" }) }
            plan.fields.forEach { f -> Log.i(TAG, "field ${f.category} '${f.value}' ${f.boxes}") }
        }
        return Result(bitmap, plan)
    }

    /** A new bitmap with solid bars over [boxes]; [original] is left as it was. */
    fun render(original: Bitmap, boxes: List<Box>): Bitmap {
        val copy = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }
        val corner = original.width / 150f
        boxes.forEach { b -> canvas.drawRoundRect(RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()), corner, corner, paint) }
        return copy
    }

    /**
     * Writes [masked] as a fresh PNG in app-private cache and returns a shareable content URI.
     * Re-encoding also drops the photo's metadata (location, camera, time).
     */
    fun export(context: Context, masked: Bitmap): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only the latest masked copy is kept
        val file = File(dir, "masked-${System.currentTimeMillis()}.png")
        file.outputStream().use { masked.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    private fun decode(context: Context, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder applies the photo's EXIF rotation for us.
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = MAX_SIDE.toFloat() / maxOf(info.size.width, info.size.height)
                if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        return context.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw DocumentReader.Unsupported("Could not open that image.")
    }
}

/** Set once by [app.parda.PardaApp]; true in debug builds. */
object BuildConfigCompat {
    @Volatile var debuggable = false
}
