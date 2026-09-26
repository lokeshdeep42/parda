package app.parda.core.checkout

/** Rupee amounts as whole paise, so arithmetic stays exact. */
object Money {
    private val AMOUNT = Regex(
        """(?:₹|\bRs\.?|\bINR|(?<![\u0900-\u097F])रु\.?|रुपये|(?<![\u0C00-\u0C7F])రూ\.?)\s?(\d[\d,]*(?:\.\d{1,2})?)""" +
            """|(?<![\d,])(\d[\d,]*(?:\.\d{1,2})?)[ \t]?(?:रुपये|रुपए|రూపాయలు)""" +
            // Screen-reader labels spell it out: "price 230 rupees", "Tip 10 rupees".
            """|(?<![\d,.])(\d[\d,]*(?:\.\d{1,2})?)\s?(?:[Rr]upees?|RUPEES?)\b""",
    )
    private val RECURRING = Regex(
        """(?:₹|\bRs\.?|\bINR)\s?(\d[\d,]*(?:\.\d{1,2})?)\s*(?:/\s*|per\s+|a\s+|every\s+)(?:mo|month|mth|yr|year|week|wk)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(number: String): Long {
        val clean = number.replace(",", "")
        val rupees = clean.substringBefore('.').toLongOrNull() ?: return 0
        val paise = clean.substringAfter('.', "").padEnd(2, '0').take(2).toLongOrNull() ?: 0
        return rupees * 100 + paise
    }

    /** One-off amounts in [text], ignoring amounts that are part of a recurring price. */
    fun oneOffAmounts(text: String): List<Long> {
        val recurringRanges = RECURRING.findAll(text).map { it.range }.toList()
        return AMOUNT.findAll(text)
            .filter { m -> recurringRanges.none { m.range.first in it } }
            .map { m -> parse(m.groupValues.drop(1).first { it.isNotEmpty() }) }
            .toList()
    }

    /** The first recurring charge in [text], normalised to a monthly figure where possible. */
    fun recurringAmount(text: String): Long? {
        val m = RECURRING.find(text) ?: return null
        val amount = parse(m.groupValues[1])
        val unit = m.value.lowercase()
        return when {
            "yr" in unit || "year" in unit -> amount / 12
            "week" in unit || "wk" in unit -> amount * 52 / 12
            else -> amount
        }
    }

    /** Formats paise as rupees with Indian digit grouping: 184000000 -> "₹18,40,000". */
    fun format(paise: Long): String {
        val negative = paise < 0
        val abs = kotlin.math.abs(paise)
        val rupees = (abs / 100).toString()
        val grouped = if (rupees.length <= 3) rupees else {
            val head = rupees.dropLast(3)
            head.reversed().chunked(2).joinToString(",").reversed() + "," + rupees.takeLast(3)
        }
        val fraction = abs % 100
        return (if (negative) "−₹" else "₹") + grouped + (if (fraction != 0L) ".%02d".format(fraction) else "")
    }
}
