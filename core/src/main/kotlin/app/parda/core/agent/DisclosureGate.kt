package app.parda.core.agent

import app.parda.core.disclosure.SanitizeResult
import app.parda.core.disclosure.Sanitizer
import app.parda.core.document.DocumentOverview
import app.parda.core.policy.Policy

/** What happened at the data boundary. */
sealed interface GateDecision {
    val result: SanitizeResult
    val plannedBy: Planner

    /** Answered on the device. Nothing was prepared for anyone else. */
    data class HeldLocally(
        val answer: String,
        override val result: SanitizeResult,
        override val plannedBy: Planner = Planner.RULES,
    ) : GateDecision

    /**
     * The local model could not help. [outbound] is the only text Parda will ever put on the
     * clipboard or share sheet, and it is produced by the sanitizer, never by the model.
     */
    data class HandedBack(
        val outbound: String,
        val reason: HandBackReason,
        override val result: SanitizeResult,
        override val plannedBy: Planner = Planner.RULES,
    ) : GateDecision
}

/**
 * Channel B. The agent plans; deterministic code decides and executes. A wrong plan produces
 * a wrong suggestion, never a wrong disclosure.
 */
class DisclosureGate(
    private val agent: LocalAgent,
    private val fallback: LocalAgent = RuleBasedAgent(),
    private val sanitizer: Sanitizer = Sanitizer(),
) {
    /**
     * [document] is what the user sees and what a sanitized copy is made from. [full] is the
     * whole file when that is longer (a 600-page PDF): summaries are computed over all of it.
     */
    fun handle(
        document: String,
        request: String,
        policy: Policy,
        full: String = document,
        pages: Int? = null,
        onToken: (String) -> Unit = {},
    ): GateDecision {
        val combined = if (request.isBlank()) document else "$document\n\n$request"
        val result = sanitizer.sanitize(combined, policy)

        val planned = runCatching { agent.planned(request, document) }
            .getOrDefault(Planned(AgentPlan.HandBack(HandBackReason.MODEL_OUTPUT_INVALID), Planner.MODEL))
        val plan = planned.plan
        if (plan is AgentPlan.AnswerLocally) {
            val answer = if (plan.task == LocalTask.SUMMARISE && full.length > DocumentOverview.SHORT) {
                summariseLong(request, document, full, pages, onToken)
            } else if (plan.task == LocalTask.EXTRACT) {
                // Listing personal data is the detectors' job: exact, over the whole file, never invented.
                fallback.answer(LocalTask.EXTRACT, request, full)
            } else {
                runCatching { agent.answer(plan.task, request, document, onToken) }.getOrNull()
                    ?: fallback.answer(plan.task, request, document)
            }
            if (answer != null) return GateDecision.HeldLocally(answer, result, planned.by)
        }
        val reason = (plan as? AgentPlan.HandBack)?.reason ?: HandBackReason.TOO_COMPLEX
        return GateDecision.HandedBack(result.sanitized, reason, result, planned.by)
    }

    /**
     * Too long for the model to read: the overview is computed over the whole document. Only
     * prose gets a model gist, and only of its opening, labelled as such.
     */
    private fun summariseLong(request: String, document: String, full: String, pages: Int?, onToken: (String) -> Unit): String {
        val overview = DocumentOverview.of(full, pages)
        if (overview.dataHeavy) return overview.text
        val head = overview.text + "\n\nThe opening, summarised on this phone:\n"
        onToken(head)
        val opening = document.take(DocumentOverview.SHORT)
        val gist = runCatching { agent.answer(LocalTask.SUMMARISE, request, opening, onToken) }.getOrNull()
            ?: fallback.answer(LocalTask.SUMMARISE, request, opening)
        return if (gist.isNullOrBlank()) overview.text else head + gist
    }
}
