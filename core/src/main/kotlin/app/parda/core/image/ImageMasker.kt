package app.parda.core.image

import app.parda.core.detect.Classifier
import app.parda.core.detect.Detection
import app.parda.core.detect.Detector
import app.parda.core.detect.Detectors
import app.parda.core.detect.RegexDetector
import app.parda.core.policy.DataCategory
import app.parda.core.policy.DisclosureAction
import app.parda.core.policy.Policy

/** A rectangle in image pixels. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** One word as the on-device OCR read it, with where it sits in the image. */
data class OcrWord(val text: String, val box: Box)

data class OcrLine(val words: List<OcrWord>)

/** One piece of personal data found in the image, and the rectangles that cover it. */
data class MaskedField(
    val category: DataCategory,
    val value: String,
    val boxes: List<Box>,
    /** True when the policy leaves the last 4 characters readable (like a masked Aadhaar). */
    val keepsLast4: Boolean,
)

data class ImageMaskPlan(val text: String, val fields: List<MaskedField>) {
    val categories: List<DataCategory> get() = fields.map { it.category }.distinct()

    /** The rectangles to paint, for the categories the user left ticked. */
    fun boxes(masked: Set<DataCategory>): List<Box> = fields.filter { it.category in masked }.flatMap { it.boxes }
}

/**
 * Channel B for images. OCR reads the picture; the same deterministic detectors and the same
 * policy as for text decide what is covered. The output is only rectangles: the painting and
 * re-encoding happen on the device, and the original image is never modified or sent.
 */
class ImageMasker(private val classifier: Classifier = Classifier(IMAGE_DETECTORS)) {

    fun plan(lines: List<OcrLine>, policy: Policy): ImageMaskPlan {
        // Rebuild the page as text (words joined by spaces, lines by newlines), remembering
        // where each word sits, so a detection's character range maps back to pixels.
        val text = StringBuilder()
        // Each word with its character range and the right edge of the word before it on the line.
        val spans = mutableListOf<Triple<IntRange, OcrWord, Int?>>()
        lines.forEachIndexed { li, line ->
            if (li > 0) text.append('\n')
            line.words.forEachIndexed { wi, word ->
                if (wi > 0) text.append(' ')
                val start = text.length
                text.append(word.text)
                spans += Triple(start until text.length, word, line.words.getOrNull(wi - 1)?.box?.right)
            }
        }

        val fields = classifier.classify(text.toString()).mapNotNull { d ->
            val action = policy.actionFor(d.category)
            if (action == DisclosureAction.ALLOW) return@mapNotNull null
            val keepLast4 = action == DisclosureAction.KEEP_LAST_4
            val coverEnd = if (keepLast4) lastFourStart(text, d) else d.end
            val boxes = spans.mapNotNull { (range, word, prevRight) -> cover(range, word, prevRight, d.start, coverEnd) }
            if (boxes.isEmpty()) null else MaskedField(d.category, d.value, boxes, keepLast4)
        }
        return ImageMaskPlan(text.toString(), fields)
    }

    /** Where the last four digits (or characters) of a detection begin in the page text. */
    private fun lastFourStart(text: CharSequence, d: Detection): Int {
        var kept = 0
        for (i in d.end - 1 downTo d.start) {
            if (text[i].isLetterOrDigit()) kept++
            if (kept == 4) return i
        }
        return d.start
    }

    /**
     * The part of [word] that falls inside the character range [from, to), as a box. Character
     * widths are taken as equal, which is close enough for digits and a little padding covers
     * the rest.
     */
    private fun cover(range: IntRange, word: OcrWord, prevRight: Int?, from: Int, to: Int): Box? {
        val a = maxOf(from, range.first)
        val b = minOf(to, range.last + 1)
        if (a >= b) return null
        val len = range.last + 1 - range.first
        val w = word.box.right - word.box.left
        // Starting inside a word (OCR merged "Address:12-4-89"): widths are only estimated, so
        // reach one character further left rather than leave the first one showing.
        val early = if (a > range.first) 1 else 0
        val left = word.box.left + w * (a - early - range.first) / len
        val right = word.box.left + w * (b - range.first) / len
        val h = word.box.bottom - word.box.top
        // OCR boxes hug the ink and can clip a thin first glyph ("1"), so where a bar reaches
        // the edge of a word it overreaches sideways. Inside a word (the edge kept visible by
        // "keep last 4") it stops exactly.
        val outer = h / 3
        val padLeft = if (a == range.first) outer else 0
        val padRight = if (b == range.last + 1) outer else 0
        val padY = h / 8
        // Never reach back over the word before (usually the label, "Name:"), which stays readable.
        val floor = if (a == range.first && prevRight != null) prevRight + 1 else Int.MIN_VALUE
        return Box(maxOf(left - padLeft, floor), word.box.top - padY, right + padRight, word.box.bottom + padY)
    }

    companion object {
        /**
         * OCR can misread one digit, which breaks the Aadhaar checksum. On images a 4-4-4 digit
         * group is covered as an ID regardless: over-masking a picture is the safe failure.
         */
        val AADHAAR_LOOSE = RegexDetector(
            DataCategory.GOV_ID, "Aadhaar (OCR)",
            Regex("""(?<![\d-])\d{4}[ -]\d{4}[ -]\d{4}(?![\d-])"""),
        )

        val IMAGE_DETECTORS: List<Detector> = Detectors.DEFAULT.flatMap {
            if (it === Detectors.AADHAAR) listOf(it, AADHAAR_LOOSE) else listOf(it)
        }
    }
}
