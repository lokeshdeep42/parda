package app.parda.core.document

import app.parda.core.detect.Classifier
import app.parda.core.policy.DataCategory

/** A question offered as a chip under the Airlock's text box. */
data class Suggestion(val label: String, val request: String)

/**
 * Questions that fit the document on screen, worked out by plain code from what it holds: a
 * salary letter gets "Am I underpaid?", a bank statement "Is any of this spending unusual?".
 * Each set has questions the phone answers itself and one that needs outside knowledge, so both
 * paths of the Airlock are one tap away.
 */
object SuggestedQuestions {
    private const val SAMPLE = 20_000

    fun of(document: String, classifier: Classifier = Classifier()): List<Suggestion> {
        val text = document.take(SAMPLE)
        if (text.isBlank()) return emptyList()
        val lower = text.lowercase()
        val found = classifier.classify(text).map { it.category }.toSet()
        val lines = text.lines().filter { it.isNotBlank() }
        val tabular = lines.size >= 3 && lines.count { '\t' in it || it.count { c -> c == ',' } >= 3 } > lines.size / 2
        val list = Suggestion("List personal data", "List the personal data in this document.")

        return when {
            has(lower, "statement", "opening balance", "closing balance", "debit", "credit", "withdrawal") -> listOf(
                Suggestion("Summarise", "Summarise this statement."),
                list,
                Suggestion("Unusual spending?", "Compare this spending with typical households and tell me if anything is unusual."),
            )
            has(lower, "salary", "compensation", "ctc", "payslip", "pay slip", "gross pay", "net pay") && !tabular -> listOf(
                Suggestion("Summarise", "Summarise the key terms of this letter in three lines."),
                Suggestion("Am I underpaid?", "Compare this against typical FY26 compensation bands for my role and tell me if I am underpaid."),
            )
            has(lower, "agreement", "lease", "tenant", "landlord", "clause", "terms and conditions", "hereby") -> listOf(
                Suggestion("Summarise", "Summarise the key terms of this agreement."),
                list,
                Suggestion("Is it legal?", "Is anything in this agreement against Indian law?"),
            )
            DataCategory.GOV_ID in found && lines.size < 30 -> listOf(
                list,
                Suggestion("Verify this ID", "Look up whether this ID is valid."),
            )
            tabular || lines.size > 50 -> listOf(
                Suggestion("Summarise", "Summarise this file."),
                list,
                Suggestion(
                    if (DataCategory.MONEY_AMOUNT in found) "Compare amounts" else "Compare with others",
                    "Compare these figures with market rates and tell me what stands out.",
                ),
            )
            else -> listOf(
                Suggestion("Summarise", "Summarise this."),
                list,
            )
        }
    }

    private fun has(text: String, vararg words: String) = words.any { it in text }
}
