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
        // A mostly Hindi document gets its questions in Hindi; the keyword rules route both.
        // A mostly Hindi or Telugu document gets its questions in that language; the keyword rules route all three.
        val letters = text.count { it.isLetter() }
        val hi = text.count { it in '\u0900'..'\u097F' } * 3 > letters
        val te = !hi && text.count { it in '\u0C00'..'\u0C7F' } * 3 > letters
        fun q(label: String, request: String, hiLabel: String, hiRequest: String) = when {
            hi -> Suggestion(hiLabel, hiRequest)
            te -> TELUGU[label]?.let { (l, r) -> Suggestion(l, r) } ?: Suggestion(label, request)
            else -> Suggestion(label, request)
        }
        val list = q("List personal data", "List the personal data in this document.", "निजी जानकारी", "इस दस्तावेज़ की निजी जानकारी की सूची दें।")
        fun summarise(request: String) = q("Summarise", request, "सारांश", "इसका सारांश दें।")

        return when {
            // A medical report: explaining results needs outside knowledge, so it is handed back
            // with everything that identifies the patient masked and the results left in.
            DataCategory.HEALTH_ID in found || has(
                lower, "patient", "haemoglobin", "hemoglobin", "reference range", "diagnosis", "prescription",
                "lab report", "test report", "मरीज", "रोगी", "जाँच रिपोर्ट", "రోగి", "నిర్ధారణ", "పరీక్ష నివేదిక",
            ) -> listOf(
                summarise("Summarise this report."),
                list,
                q(
                    "Explain my results", "Compare these results with normal ranges and tell me what they mean.",
                    "रिपोर्ट समझाएँ", "इन परिणामों की सामान्य सीमा से तुलना करें और बताएँ इनका क्या मतलब है।",
                ),
            )
            has(lower, "statement", "opening balance", "closing balance", "debit", "credit", "withdrawal", "खाता विवरण", "शेष राशि", "निकासी", "ఖాతా వివరాలు", "నిల్వ", "ఉపసంహరణ") -> listOf(
                summarise("Summarise this statement."),
                list,
                q(
                    "Unusual spending?", "Compare this spending with typical households and tell me if anything is unusual.",
                    "असामान्य खर्च?", "इस खर्च की तुलना आम परिवारों से करें और बताएँ कि कुछ असामान्य है क्या।",
                ),
            )
            has(lower, "salary", "compensation", "ctc", "payslip", "pay slip", "gross pay", "net pay", "वेतन", "జీతం", "వేతనం") && !tabular -> listOf(
                summarise("Summarise the key terms of this letter in three lines."),
                q(
                    "Am I underpaid?", "Compare this against typical FY26 compensation bands for my role and tell me if I am underpaid.",
                    "क्या वेतन कम है?", "मेरे पद के लिए बाज़ार के वेतन से इसकी तुलना करें और बताएँ कि क्या मुझे कम मिल रहा है।",
                ),
            )
            has(lower, "agreement", "lease", "tenant", "landlord", "clause", "terms and conditions", "hereby", "अनुबंध", "समझौता", "किरायेदार", "मकान मालिक", "ఒప్పందం", "అద్దె", "యజమాని") -> listOf(
                summarise("Summarise the key terms of this agreement."),
                list,
                q("Is it legal?", "Is anything in this agreement against Indian law?", "क्या यह कानूनी है?", "क्या इस अनुबंध में कुछ भारतीय कानून के विरुद्ध है?"),
            )
            DataCategory.GOV_ID in found && lines.size < 30 -> listOf(
                list,
                q("Verify this ID", "Look up whether this ID is valid.", "आईडी जाँचें", "इस आईडी को ऑनलाइन जाँचें।"),
            )
            tabular || lines.size > 50 -> listOf(
                summarise("Summarise this file."),
                list,
                q(
                    if (DataCategory.MONEY_AMOUNT in found) "Compare amounts" else "Compare with others",
                    "Compare these figures with market rates and tell me what stands out.",
                    "तुलना करें", "इन आँकड़ों की बाज़ार दरों से तुलना करें।",
                ),
            )
            else -> listOf(summarise("Summarise this."), list)
        }
    }

    private fun has(text: String, vararg words: String) = words.any { it in text }

    /** The Telugu for each question, by its English label; requests use words the keyword rules know. */
    private val TELUGU = mapOf(
        "Summarise" to ("సారాంశం" to "దీని సారాంశం ఇవ్వండి."),
        "List personal data" to ("వ్యక్తిగత వివరాలు" to "ఈ పత్రంలోని వ్యక్తిగత వివరాల జాబితా ఇవ్వండి."),
        "Explain my results" to ("ఫలితాలు వివరించండి" to "ఈ ఫలితాలను సాధారణ పరిమితులతో పోల్చి, వాటి అర్థం చెప్పండి."),
        "Unusual spending?" to ("అసాధారణ ఖర్చు?" to "ఈ ఖర్చును సాధారణ కుటుంబాలతో పోల్చి, ఏదైనా అసాధారణంగా ఉందా చెప్పండి."),
        "Am I underpaid?" to ("జీతం తక్కువా?" to "నా ఉద్యోగానికి మార్కెట్ జీతాలతో పోల్చి, నాకు తక్కువ వస్తోందా చెప్పండి."),
        "Is it legal?" to ("చట్టబద్ధమేనా?" to "ఈ ఒప్పందంలో ఏదైనా భారత చట్టానికి విరుద్ధంగా ఉందా?"),
        "Verify this ID" to ("ఐడీ తనిఖీ" to "ఈ ఐడీని ఆన్‌లైన్‌లో తనిఖీ చేయండి."),
        "Compare amounts" to ("పోల్చండి" to "ఈ అంకెలను మార్కెట్ రేట్లతో పోల్చండి."),
        "Compare with others" to ("పోల్చండి" to "ఈ అంకెలను మార్కెట్ రేట్లతో పోల్చండి."),
    )
}
