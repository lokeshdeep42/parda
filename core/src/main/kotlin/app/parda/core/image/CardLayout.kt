package app.parda.core.image

import app.parda.core.detect.Detection
import app.parda.core.policy.DataCategory
import kotlin.math.abs

/**
 * Reads an ID card by where things sit, not only by what OCR could make of them.
 *
 * On-device OCR has no model for most Indian scripts, so a Telugu or Tamil line arrives as stray
 * glyphs. An Aadhaar also prints the name with no "Name:" label and the address over several
 * lines. On a card, position says what a line is: the lines just above "DOB" are the name, and
 * the lines under "Address:" down to the PIN are the address, readable or not. The photo sits
 * left of the details and the QR code right of the address. Covering an unreadable line in one
 * of those places is the safe failure; leaving it is not.
 *
 * [zones] are character ranges in the page text, masked word by word like any detection.
 * [boxes] are drawn directly: a face or a code the picture detectors found, and layout guesses
 * where there is nothing to read.
 */
internal class CardLayout(
    lines: List<OcrLine>,
    private val ranges: List<IntRange>,
    private val page: String,
    regions: List<ImageRegion>,
) {
    class Drawn(val category: DataCategory, val value: String, val box: Box)

    private class Row(val index: Int, val box: Box, val h: Int, val text: String) {
        val lower = text.lowercase()
    }

    private val rows: List<Row> = lines.mapIndexedNotNull { i, l ->
        if (l.words.isEmpty()) return@mapIndexedNotNull null
        val b = Box(l.words.minOf { it.box.left }, l.words.minOf { it.box.top }, l.words.maxOf { it.box.right }, l.words.maxOf { it.box.bottom })
        val h = l.words.map { it.box.bottom - it.box.top }.sorted()[l.words.size / 2].coerceAtLeast(1)
        Row(i, b, h, l.words.joinToString(" ") { it.text })
    }
    private val top = rows.minOfOrNull { it.box.top } ?: 0
    private val bottom = rows.maxOfOrNull { it.box.bottom } ?: 0

    /** Card titles sit in the top third; "India" further down is part of an address. */
    private fun isHeader(r: Row) =
        r.box.top <= top + (bottom - top) * 0.35 && HEADER.containsMatchIn(r.lower) && !CONTACT.containsMatchIn(r.lower)

    private val headerBottom: Int? = rows.filter(::isHeader).maxOfOrNull { it.box.bottom }
    private fun belowHeader(r: Row) = headerBottom == null || r.box.top >= headerBottom - r.h / 4

    val isAadhaar: Boolean = run {
        val signals = SIGNALS.count { it.containsMatchIn(page) }
        (ID_NUMBER.containsMatchIn(page) && signals >= 1) || signals >= 3
    }

    val zones = mutableListOf<Detection>()
    val boxes = mutableListOf<Drawn>()

    private val nameRows = mutableListOf<Row>()
    private val addressRows = mutableListOf<Row>()
    private var dobRow: Row? = null

    init {
        addressBlock()
        if (isAadhaar) {
            nameBlock()
            photoGuess(regions)
            codeGuess(regions)
        }
        regions.forEach { r ->
            val b = r.box
            val w = b.right - b.left
            val h = b.bottom - b.top
            boxes += when (r.kind) {
                // A face box hugs forehead to chin; the printed photo around it is wider and taller.
                ImageRegion.Kind.FACE -> Drawn(DataCategory.FACE_PHOTO, "Face", Box(maxOf(0, b.left - w * 3 / 5), maxOf(0, b.top - h * 7 / 10), b.right + w * 3 / 5, b.bottom + h))
                ImageRegion.Kind.CODE -> Drawn(DataCategory.QR_CODE, "Code", Box(maxOf(0, b.left - w / 16), maxOf(0, b.top - h / 16), b.right + w / 16, b.bottom + h / 16))
            }
        }
    }

    private fun zone(category: DataCategory, row: Row, from: Int = 0) {
        val range = ranges[row.index]
        val start = range.first + from
        val end = range.last + 1
        if (start < end) zones += Detection(category, page.substring(start, end), start, end, "Card layout")
    }

    /** Where a line's value starts, after a leading "Label:" if it has one. */
    private fun afterLabel(r: Row): Int = LABEL.find(r.text)?.takeIf { it.range.last + 1 < r.text.length }?.let { it.range.last + 1 } ?: 0

    /**
     * "Address:" and every line under it, down to the one with the PIN. On an Aadhaar, also the
     * same address in the regional script, printed just above the label.
     */
    private fun addressBlock() {
        val label = rows.firstOrNull { ADDRESS_LABEL.containsMatchIn(it.text) } ?: return
        val m = ADDRESS_LABEL.find(label.text)!!
        val h = label.h
        val block = mutableListOf<Row>()
        var reachedPin = false
        val rest = label.text.substring(m.range.last + 1)
        if (rest.isNotBlank()) {
            zone(DataCategory.ADDRESS, label, m.range.last + 1 + (rest.length - rest.trimStart().length))
            block += label
            reachedPin = PIN.containsMatchIn(rest)
        }
        if (!reachedPin) {
            var prevBottom = label.box.bottom
            val below = rows.filter { it !== label && it.box.top >= label.box.bottom - h / 2 && abs(it.box.left - label.box.left) <= h * 5 / 2 }
                .sortedBy { it.box.top }
            for (r in below) {
                if (r.box.top - prevBottom > h * 8 / 5 || ID_NUMBER.containsMatchIn(r.text) || CONTACT.containsMatchIn(r.lower) || block.size == MAX_BLOCK) break
                block += r
                prevBottom = r.box.bottom
                if (PIN.containsMatchIn(r.text)) { reachedPin = true; break }
            }
        }
        // Under a label with no PIN in reach, the lines may be anything (a form, a note): only an
        // ID card's layout is trusted without one.
        if (!reachedPin && !isAadhaar) return
        block.filter { it !== label }.forEach { zone(DataCategory.ADDRESS, it) }
        addressRows += block
        if (!isAadhaar) return
        var prevTop = label.box.top
        val above = rows.filter { it !== label && it.box.bottom <= label.box.top + h / 2 && abs(it.box.left - label.box.left) <= h * 5 / 2 && belowHeader(it) }
            .sortedByDescending { it.box.bottom }
        var taken = 0
        for (r in above) {
            if (prevTop - r.box.bottom > h * 8 / 5 || isHeader(r) || ID_NUMBER.containsMatchIn(r.text) || taken == MAX_BLOCK) break
            zone(DataCategory.ADDRESS, r)
            addressRows += r
            prevTop = r.box.top
            taken++
        }
    }

    /**
     * The name on an Aadhaar has no label: it is the line above the date of birth, with the same
     * name in the regional script above that. When OCR returned nothing at all for the regional
     * line, the band where it is printed is covered instead.
     */
    private fun nameBlock() {
        val dob = rows.firstOrNull { DOB_LABEL.containsMatchIn(it.text) } ?: return
        dobRow = dob
        val h = dob.h
        var prevTop = dob.box.top
        val above = rows.filter { it !== dob && it.box.bottom <= dob.box.top + h / 2 && abs(it.box.left - dob.box.left) <= h * 3 && belowHeader(it) }
            .sortedByDescending { it.box.bottom }
        for (r in above) {
            if (prevTop - r.box.bottom > h * 8 / 5 || isHeader(r) || nameRows.size == 2) break
            zone(DataCategory.PERSON_NAME, r, afterLabel(r))
            nameRows += r
            prevTop = r.box.top
        }
        val only = nameRows.singleOrNull() ?: return
        val bandBottom = only.box.top - only.h / 6
        val bandTop = maxOf(bandBottom - only.h * 6 / 5, (headerBottom ?: Int.MIN_VALUE / 2) + only.h / 6, 0)
        if (bandBottom - bandTop >= only.h / 2) {
            boxes += Drawn(DataCategory.PERSON_NAME, "Name (unreadable script)", Box(only.box.left, bandTop, only.box.right, bandBottom))
        }
    }

    /** No face found (glare, a worn card): the photo is printed left of the details, as tall as they are. */
    private fun photoGuess(regions: List<ImageRegion>) {
        if (regions.any { it.kind == ImageRegion.Kind.FACE }) return
        val dob = dobRow ?: return
        val h = dob.h
        val number = rows.filter { it.box.top > dob.box.top && ID_NUMBER.containsMatchIn(it.text) }.minByOrNull { it.box.top }
        val column = nameRows + dob + listOfNotNull(number)
        val left = column.minOf { it.box.left }
        val topY = nameRows.minOfOrNull { it.box.top } ?: (dob.box.top - h * 2)
        val bottomY = number?.box?.bottom ?: (dob.box.bottom + h * 5)
        val right = left - h / 3
        val width = (bottomY - topY) * 4 / 5
        if (right - maxOf(0, right - width) < h * 2) return
        boxes += Drawn(DataCategory.FACE_PHOTO, "Photo", Box(maxOf(0, right - width), maxOf(0, topY - h / 3), right, bottomY))
    }

    /** No code decoded: on the back of an Aadhaar the QR fills the space right of the address. */
    private fun codeGuess(regions: List<ImageRegion>) {
        if (regions.any { it.kind == ImageRegion.Kind.CODE } || addressRows.isEmpty()) return
        val h = addressRows.map { it.h }.sorted()[addressRows.size / 2]
        val left = addressRows.maxOf { it.box.right } + h
        val topY = headerBottom?.plus(h / 2) ?: addressRows.minOf { it.box.top }
        val lastLine = addressRows.maxOf { it.box.top }
        val footer = rows.filter { CONTACT.containsMatchIn(it.lower) && it.box.top > lastLine }.minByOrNull { it.box.top }
        val number = rows.filter { ID_NUMBER.containsMatchIn(it.text) && it.box.top > lastLine }.minByOrNull { it.box.top }
        val bottomY = footer?.box?.top?.minus(h / 3) ?: number?.box?.bottom ?: (addressRows.maxOf { it.box.bottom } + h * 2)
        val right = rows.maxOf { it.box.right } + h
        if (right - left < h * 3 || bottomY - topY < h * 3) return
        boxes += Drawn(DataCategory.QR_CODE, "QR code", Box(left, topY, right, bottomY))
    }

    companion object {
        private const val MAX_BLOCK = 8
        val ID_NUMBER = Regex("""(?<![\d-])\d{4}[ -]\d{4}[ -]\d{4}(?![\d-])""")
        private val PIN = Regex("""(?<!\d)[1-9]\d{2}\s?\d{3}(?!\d)""")
        private val HEADER = Regex("""gov\w{0,6}ment|\bindia|lndia|unique|identif|authori|भारत|सरकार|विशिष्ट|प्राधिकरण""")
        private val CONTACT = Regex("""uidai|@|www\.|\b1947\b""")
        private val DOB_LABEL = Regex("""(?i)\b(?:dob|d\.o\.b|yob|year of birth|date of birth)\b|जन्म""")
        private val ADDRESS_LABEL = Regex(
            """(?i)^[^\p{L}\p{N}]{0,3}(?:(?:registered|permanent|residential|present|correspondence)\s+)?(?:address|addr\.?|पता|చిరునామా)\s*[:\-ः]?\s*""",
        )
        private val LABEL = Regex("""^\s*[\p{L} ]{1,20}[:ः]\s*""")
        private val SIGNALS = listOf(
            Regex("""(?i)gov\w{0,6}ment|भारत सरकार"""),
            Regex("""(?i)unique|identification|uidai"""),
            Regex("""(?i)aadh?aa?r|आधा"""),
            Regex("""(?i)\b(?:dob|yob)\b|year of birth|जन्म"""),
            Regex("""(?i)\b(?:fe)?male\b|पुरुष|महिला"""),
            Regex("""(?i)\baddress\b|पता"""),
        )
    }
}
