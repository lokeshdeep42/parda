package app.parda.core.agent

import app.parda.core.detect.Classifier

/**
 * Keyword planner and extractive answerer. Used when no on-device model is installed. It is
 * deliberately conservative: anything it does not recognise as a local task is handed back.
 */
class RuleBasedAgent(private val classifier: Classifier = Classifier()) : LocalAgent {

    override fun plan(request: String, document: String): AgentPlan {
        val r = request.lowercase()
        if (OUTSIDE.any { it in r }) return AgentPlan.HandBack(HandBackReason.NEEDS_OUTSIDE_KNOWLEDGE)
        return when {
            SUMMARISE.any { it in r } -> AgentPlan.AnswerLocally(LocalTask.SUMMARISE)
            EXTRACT.any { it in r } -> AgentPlan.AnswerLocally(LocalTask.EXTRACT)
            else -> AgentPlan.HandBack(HandBackReason.TOO_COMPLEX)
        }
    }

    override fun answer(task: LocalTask, request: String, document: String, onToken: (String) -> Unit): String? = when (task) {
        LocalTask.SUMMARISE -> summarise(document)
        LocalTask.EXTRACT -> extract(document)
        LocalTask.REWRITE -> null
    }

    /** Picks the substantive lines of the document: the ones that carry facts. */
    private fun summarise(document: String): String {
        val lines = document.lines().map { it.trim() }
            .filter { it.length >= 20 }
            .filterNot { l -> SKIP_PREFIXES.any { l.startsWith(it, ignoreCase = true) } }
        val withFacts = lines.filter { classifier.classify(it).isNotEmpty() }
        val chosen = (withFacts + lines).distinct().take(3)
        return if (chosen.isEmpty()) "There is not enough in this text to summarise."
        else chosen.joinToString("\n") { "• $it" }
    }

    private fun extract(document: String): String {
        val found = classifier.classify(document)
        if (found.isEmpty()) return "No names, numbers or amounts found."
        val counts = found.groupingBy { it.category.label }.eachCount().entries.sortedByDescending { it.value }
        val head = "${found.size} item(s): " + counts.joinToString(" · ") { (k, n) -> "$n ${k.lowercase()}" }
        val shown = found.take(MAX_LISTED).joinToString("\n") { "${it.detector}: ${it.value}" }
        val more = if (found.size > MAX_LISTED) "\n…and ${found.size - MAX_LISTED} more, all found on this phone." else ""
        return "$head\n\n$shown$more"
    }

    private companion object {
        const val MAX_LISTED = 40
        val OUTSIDE = listOf(
            "compare", "typical", "market", "benchmark", "average", "industry", "latest", "current rate",
            "news", "underpaid", "overpaid", "is this fair", "should i", "research", "search", "look up",
            "legal", " law", "tax rule", "regulation",
        )
        val SUMMARISE = listOf("summar", "tl;dr", "tldr", "key terms", "key points", "gist", "in short", "main points")
        val EXTRACT = listOf("extract", "list the", "pull out", "what are the numbers", "find the", "which amounts")
        val SKIP_PREFIXES = listOf("subject:", "dear ", "regards", "thanks", "thank you", "sincerely")
    }
}
