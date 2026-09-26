package app.parda.core.document

import app.parda.core.checkout.Money
import app.parda.core.detect.Classifier
import app.parda.core.policy.DataCategory

/**
 * A summary of a whole document built by plain code, not the model: what it holds and how much.
 * It reads every character (a 600-page PDF included), gives the same answer every time, and
 * cannot invent anything. A small on-device model can only read a few pages at once, so for
 * long or tabular documents this is the summary; the model at most adds a gist of the opening.
 */
object DocumentOverview {
    /** Up to this length the model can read the whole document itself. */
    const val SHORT = 3_000

    /** Personal-data hits per 1,000 characters above which a document is data, not prose. */
    private const val DATA_DENSITY = 8.0

    data class Overview(
        val text: String,
        /** True for records, tables and exports: a model gist of the opening would add nothing. */
        val dataHeavy: Boolean,
    )

    fun of(document: String, pages: Int? = null, classifier: Classifier = Classifier()): Overview {
        val lines = document.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val found = classifier.classify(document)
        val out = mutableListOf<String>()

        out += "Covers the whole document: " + listOfNotNull(
            pages?.let { "${fmt(it)} page" + plural(it) },
            "${fmt(lines.size)} line" + plural(lines.size),
            "${fmt(document.length)} characters",
        ).joinToString(", ") + "."

        structure(lines)?.let { out += it }

        val byCategory = found.groupingBy { it.category }.eachCount().entries.sortedByDescending { it.value }
        if (byCategory.isNotEmpty()) {
            out += "Personal data: " + byCategory.joinToString(" · ") { (c, n) -> "${fmt(n)} ${label(c, n)}" } + "."
        }

        val amounts = found.filter { it.category == DataCategory.MONEY_AMOUNT }
            .flatMap { Money.oneOffAmounts(it.value) }
            .filter { it > 0 }
        if (amounts.size > 1) {
            out += "Amounts: ${fmt(amounts.size)} mentioned, totalling ${Money.format(amounts.sum())}, " +
                "from ${Money.format(amounts.min())} to ${Money.format(amounts.max())}."
        } else if (amounts.size == 1) {
            out += "Amount: ${Money.format(amounts.single())}."
        }

        val density = if (document.isEmpty()) 0.0 else found.size * 1000.0 / document.length
        val tabular = separator(lines) != null
        return Overview(out.joinToString("\n"), dataHeavy = density >= DATA_DENSITY || tabular)
    }

    /**
     * Tab (Excel) or comma (CSV) when most lines are split by it into three or more cells. Prose
     * records have commas too ("Mr Anil Nair, PAN …, paid Rs 1,000"), so a CSV must also open
     * with a header row: short cells without digits.
     */
    private fun separator(lines: List<String>): Char? {
        if (lines.size < 3) return null
        return listOf('\t', ',').firstOrNull { sep ->
            val header = lines.first().split(sep)
            val looksLikeHeader = sep == '\t' ||
                header.all { cell -> cell.none(Char::isDigit) && cell.trim().split(' ').size <= 4 }
            header.size >= 3 && looksLikeHeader && lines.count { l -> l.count { it == sep } >= 2 } > lines.size / 2
        }
    }

    /** Repeated shape: a spreadsheet's header row, or many lines that open with the same word. */
    private fun structure(lines: List<String>): String? {
        if (lines.size < 3) return null
        separator(lines)?.let { sep ->
            val columns = lines.first().split(sep).map { it.trim().trim('"') }.filter { it.isNotEmpty() }
            return "A table of ${fmt(lines.size - 1)} rows; columns: ${columns.joinToString(", ")}."
        }
        val (word, count) = lines.map { it.substringBefore(' ').trimEnd(':', ',', '.') }
            .filter { it.length > 1 && it.any(Char::isLetter) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        return if (count >= 5 && count >= lines.size / 4) "${fmt(count)} lines start with “$word”, like repeated records." else null
    }

    private fun label(c: DataCategory, n: Int): String {
        val one = n == 1
        return when (c) {
            DataCategory.GOV_ID -> if (one) "government ID number" else "government ID numbers"
            DataCategory.CARD -> if (one) "card number" else "card numbers"
            DataCategory.BANK_ACCOUNT -> if (one) "bank account number" else "bank account numbers"
            DataCategory.PERSON_NAME -> if (one) "name" else "names"
            DataCategory.MONEY_AMOUNT -> if (one) "amount" else "amounts"
            DataCategory.PHONE -> if (one) "phone number" else "phone numbers"
            DataCategory.EMAIL -> if (one) "email address" else "email addresses"
            DataCategory.ADDRESS -> if (one) "address" else "addresses"
            DataCategory.DATE_OF_BIRTH -> if (one) "date of birth" else "dates of birth"
            DataCategory.HEALTH_ID -> if (one) "health record ID" else "health record IDs"
        }
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    /** Indian digit grouping: 150000 -> "1,50,000". */
    private fun fmt(n: Int): String = Money.format(n * 100L).removePrefix("₹")
}
