package app.parda.core.detect

import app.parda.core.policy.DataCategory

/** One sensitive span found in a piece of text. [start] inclusive, [end] exclusive. */
data class Detection(
    val category: DataCategory,
    val value: String,
    val start: Int,
    val end: Int,
    val detector: String,
)

/** A deterministic recogniser for one shape of personal data. */
interface Detector {
    val category: DataCategory
    val name: String
    fun find(text: String): List<Detection>
}

/**
 * Regex detector. If the pattern has a group named `v`, only that group is reported, so a
 * label such as "DOB:" can anchor the match without being masked itself.
 */
class RegexDetector(
    override val category: DataCategory,
    override val name: String,
    private val regex: Regex,
    private val accept: (String) -> Boolean = { true },
) : Detector {
    private val hasValueGroup = "(?<v>" in regex.pattern

    override fun find(text: String): List<Detection> = regex.findAll(text).mapNotNull { m ->
        val group = (if (hasValueGroup) m.groups["v"] else null) ?: m.groups[0]!!
        val value = group.value
        if (!accept(value)) return@mapNotNull null
        Detection(category, value, group.range.first, group.range.last + 1, name)
    }.toList()
}

/**
 * Indian postal addresses: a line that carries a comma and ends its address part in a
 * six-digit PIN code. Label prefixes such as "Registered address:" are not masked.
 */
object AddressDetector : Detector {
    override val category = DataCategory.ADDRESS
    override val name = "Address"
    private val pin = Regex("""(?<!\d)[1-9]\d{2}\s?\d{3}(?!\d)""")
    // OCR often reads a colon after Devanagari as the visarga (ः), which looks the same.
    private val label = Regex("""^\s*[A-Za-z\u0900-\u097F][A-Za-z\u0900-\u097F ]{0,30}[:\u0903]\s*""")

    override fun find(text: String): List<Detection> {
        val out = mutableListOf<Detection>()
        var lineStart = 0
        for (line in text.split('\n')) {
            val pinMatch = pin.findAll(line).lastOrNull()
            if (pinMatch != null && ',' in line.substring(0, pinMatch.range.first) &&
                line.substring(0, pinMatch.range.first).any { it.isLetter() }
            ) {
                val from = label.find(line)?.range?.last?.plus(1) ?: line.indexOfFirst { !it.isWhitespace() }
                val to = pinMatch.range.last + 1
                if (from in 0 until to) {
                    out += Detection(category, line.substring(from, to), lineStart + from, lineStart + to, name)
                }
            }
            lineStart += line.length + 1
        }
        return out
    }
}

object Checksums {
    /** Luhn check used by payment card numbers. */
    fun luhn(digits: String): Boolean {
        if (digits.isEmpty() || digits.any { !it.isDigit() }) return false
        var sum = 0
        digits.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }

    private val verhoeffD = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
        intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6), intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
        intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8), intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
        intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2), intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
        intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4), intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
    )
    private val verhoeffP = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
        intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2), intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
        intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0), intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
        intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5), intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
    )

    /** Verhoeff check used by Aadhaar numbers. */
    fun verhoeff(digits: String): Boolean {
        if (digits.isEmpty() || digits.any { !it.isDigit() }) return false
        var c = 0
        digits.reversed().forEachIndexed { i, ch -> c = verhoeffD[c][verhoeffP[i % 8][ch - '0']] }
        return c == 0
    }
}

private fun String.digitsOnly() = filter { it.isDigit() }

/**
 * The default detector set, in priority order: when two detections overlap, the one from
 * the earlier detector wins, then the longer one.
 */
object Detectors {
    val EMAIL = RegexDetector(
        DataCategory.EMAIL, "Email",
        Regex("""\b[\w.+-]+@[\w-]+(?:\.[\w-]+)*\.[A-Za-z]{2,}\b"""),
    )
    val AADHAAR = RegexDetector(
        DataCategory.GOV_ID, "Aadhaar",
        Regex("""(?<![\d-])[2-9]\d{3}[ -]?\d{4}[ -]?\d{4}(?![\d-])"""),
        accept = { Checksums.verhoeff(it.digitsOnly()) },
    )
    val MASKED_AADHAAR = RegexDetector(
        DataCategory.GOV_ID, "Aadhaar (masked)",
        Regex("""(?<![\w*])[Xx*]{4}[ -]?[Xx*]{4}[ -]?\d{4}(?!\d)"""),
    )
    val PAN = RegexDetector(
        DataCategory.GOV_ID, "PAN",
        Regex("""\b[A-Z]{3}[ABCFGHLJPT][A-Z]\d{4}[A-Z]\b"""),
    )
    val CARD = RegexDetector(
        DataCategory.CARD, "Card number",
        Regex("""(?<!\d)\d(?:[ -]?\d){12,18}(?!\d)"""),
        accept = { v -> v.digitsOnly().let { it.length in 13..19 && Checksums.luhn(it) } },
    )
    val PHONE = RegexDetector(
        DataCategory.PHONE, "Phone",
        Regex("""(?<![\d+])(?:\+91[\s-]?|0)?[6-9]\d{4}[\s-]?\d{5}(?!\d)"""),
    )
    val BANK_ACCOUNT = RegexDetector(
        DataCategory.BANK_ACCOUNT, "Account number",
        Regex("""(?<!\d)\d{9,18}(?!\d)"""),
    )
    val DOB = RegexDetector(
        DataCategory.DATE_OF_BIRTH, "Date of birth",
        Regex(
            """(?i)\b(?:dob|d\.o\.b\.?|date of birth|born on)\s*[:\-]?\s*""" +
                """(?<v>\d{1,2}[/.\- ]\d{1,2}[/.\- ]\d{2,4}|\d{1,2}\s+[A-Za-z]{3,9},?\s+\d{4})""",
        ),
    )
    val MONEY = RegexDetector(
        DataCategory.MONEY_AMOUNT, "Amount",
        Regex(
            """(?:₹|\bRs\.?|\bINR)\s?\d[\d,]*(?:\.\d{1,2})?(?:\s?(?:lakhs?|crores?|cr|L|k)\b)?""" +
                // Not a lab value: "2.1 lakh/cumm" is a platelet count, not money.
                """|\b\d+(?:\.\d+)?\s?(?:LPA|lakhs?|crores?)\b(?!\s*/)""" +
                // Hindi: "रु. 24,000", "18,40,000 रुपये", "18 लाख"
                """|(?<![\u0900-\u097F])(?:रु\.?|रुपये)[ \t]?\d[\d,]*(?:\.\d{1,2})?""" +
                """|(?<![\d,])\d[\d,]*(?:\.\d{1,2})?[ \t]?(?:रुपये|रुपए|लाख|करोड़)""",
        ),
    )
    private const val CAP = """[A-Z][a-z]+"""
    // Name parts are joined by spaces or tabs only: a name never continues onto the next line.
    private const val NAME = """(?:[A-Z]\.[ \t]?)*$CAP(?:[ \t]+$CAP){0,2}"""
    val NAME_HONORIFIC = RegexDetector(
        DataCategory.PERSON_NAME, "Name",
        Regex("""\b(?:Mr|Ms|Mrs|Dr|Shri|Smt|Kumari)\.?[ \t]+(?<v>$NAME)\b"""),
    )
    val NAME_LABELLED = RegexDetector(
        DataCategory.PERSON_NAME, "Name",
        Regex("""(?:\b(?:[Nn]ame|NAME)[ \t]*[:\-][ \t]*|\bDear[ \t]+)(?<v>$NAME)\b"""),
        // "Patient Name : Mrs. Lakshmi" -> the title is not the name; NAME_HONORIFIC takes the name.
        accept = { it !in setOf("Sir", "Madam", "Customer", "Team", "User", "Friend", "All", "Mr", "Mrs", "Ms", "Dr", "Shri", "Smt") },
    )

    /** ABHA (Ayushman Bharat Health Account) number: 14 digits, printed 2-4-4-4. */
    val ABHA_NUMBER = RegexDetector(
        DataCategory.GOV_ID, "ABHA number",
        Regex("""(?<![\d-])\d{2}[ -]\d{4}[ -]\d{4}[ -]\d{4}(?![\d-])"""),
    )

    /** ABHA address, the health ID's handle: "lakshmi.n@abdm". */
    val ABHA_ADDRESS = RegexDetector(
        DataCategory.HEALTH_ID, "ABHA address",
        Regex("""(?i)\b[\w.]+@(?:abdm|sbx)\b"""),
    )

    /** Hospital and lab record numbers, found by their label; the value must contain a digit. */
    val RECORD_ID = RegexDetector(
        DataCategory.HEALTH_ID, "Health record ID",
        Regex(
            """(?i)\b(?:UHID|MRN|MR\s?No|CR\s?No|Patient\s?ID|Lab\s?(?:No|ID)|Sample\s?(?:No|ID)|Reg(?:istration)?\.?\s?No|IP\s?No|OP\s?No""" +
                """|Accession\s?No|Visit\s?(?:No|ID))\.?[ \t]*[:#\-]?[ \t]*(?<v>(?=[A-Z0-9/\-]*\d)[A-Z0-9][A-Z0-9/\-]{3,})""",
        ),
    )

    // Devanagari: letters and vowel signs, without the danda (।) or Devanagari digits. A Hindi
    // name runs at most three words and stops at a postposition ("राजेश कुमार को" -> "राजेश कुमार").
    private const val DEVA = """[\u0900-\u0963\u0971-\u097F]+"""
    private const val NOT_POSTPOSITION = """(?!(?:को|का|की|के|ने|से|में|पर|और|है|जी)(?![\u0900-\u097F]))"""
    private const val NAME_HI = """$DEVA(?:[ \t]+$NOT_POSTPOSITION$DEVA){0,2}"""
    val NAME_HONORIFIC_HI = RegexDetector(
        DataCategory.PERSON_NAME, "Name",
        Regex("""(?<![\u0900-\u097F])(?:श्रीमती|श्री|सुश्री|कुमारी|डॉ\.?)[ \t]+(?<v>$NAME_HI)"""),
    )
    val NAME_LABELLED_HI = RegexDetector(
        DataCategory.PERSON_NAME, "Name",
        Regex("""(?:(?<![\u0900-\u097F])नाम[ \t]*[:\u0903\-][ \t]*|(?<![\u0900-\u097F])प्रिय[ \t]+(?!श्री|सुश्री|कुमारी|डॉ))(?<v>$NAME_HI|$NAME)"""),
    )
    val DOB_HI = RegexDetector(
        DataCategory.DATE_OF_BIRTH, "Date of birth",
        Regex("""(?:जन्म[ \t]*(?:तिथि|तारीख)|जन्मतिथि)[ \t]*[:\u0903\-]?[ \t]*(?<v>\d{1,2}[/.\- ]\d{1,2}[/.\- ]\d{2,4})"""),
    )

    val DEFAULT: List<Detector> = listOf(
        ABHA_ADDRESS, EMAIL, AADHAAR, MASKED_AADHAAR, PAN, ABHA_NUMBER, CARD, AddressDetector, PHONE, RECORD_ID, BANK_ACCOUNT,
        DOB, DOB_HI, MONEY, NAME_HONORIFIC, NAME_LABELLED, NAME_HONORIFIC_HI, NAME_LABELLED_HI,
    )
}

/** Runs every detector and resolves overlaps into a clean, ordered list of spans. */
class Classifier(private val detectors: List<Detector> = Detectors.DEFAULT) {
    /**
     * A whole 600-page file is split at line breaks and classified on every core. No detector
     * matches across a line break, so splitting there changes nothing but the time taken.
     */
    fun classify(text: String): List<Detection> {
        if (text.length < PARALLEL_FROM) return classifyPart(text)
        val parts = mutableListOf<IntRange>()
        var start = 0
        while (start < text.length) {
            val cut = text.indexOf('\n', minOf(start + PART, text.length)).let { if (it < 0) text.length else it + 1 }
            parts += start until cut
            start = cut
        }
        return parts.parallelStream()
            .map { r -> classifyPart(text.substring(r.first, r.last + 1)).map { it.copy(start = it.start + r.first, end = it.end + r.first) } }
            .collect(java.util.stream.Collectors.toList())
            .flatten()
    }

    private fun classifyPart(text: String): List<Detection> {
        val candidates = detectors.flatMapIndexed { rank, d -> d.find(text).map { rank to it } }
            .sortedWith(compareBy<Pair<Int, Detection>> { it.first }.thenByDescending { it.second.end - it.second.start })
        // Taken spans never overlap, so a candidate can only collide with its nearest neighbour
        // on either side: a sorted map keeps this O(n log n) for a whole 600-page file.
        val taken = java.util.TreeMap<Int, Detection>()
        for ((_, c) in candidates) {
            val before = taken.floorEntry(c.start)?.value
            val after = taken.ceilingEntry(c.start)?.value
            if ((before == null || before.end <= c.start) && (after == null || after.start >= c.end)) taken[c.start] = c
        }
        return taken.values.toList()
    }

    private companion object {
        /** Below this, one core is quicker than splitting. */
        const val PARALLEL_FROM = 100_000
        const val PART = 50_000
    }
}
