package app.parda.core.agent

import app.parda.core.disclosure.SanitizeResult
import app.parda.core.disclosure.Sanitizer
import app.parda.core.policy.Policy

/** What happened at the data boundary. */
sealed interface GateDecision {
    val result: SanitizeResult

    /** Answered on the device. Nothing was prepared for anyone else. */
    data class HeldLocally(val answer: String, override val result: SanitizeResult) : GateDecision

    /**
     * The local model could not help. [outbound] is the only text Parda will ever put on the
     * clipboard or share sheet, and it is produced by the sanitizer, never by the model.
     */
    data class HandedBack(
        val outbound: String,
        val reason: HandBackReason,
        override val result: SanitizeResult,
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
    fun handle(document: String, request: String, policy: Policy): GateDecision {
        val combined = if (request.isBlank()) document else "$document\n\n$request"
        val result = sanitizer.sanitize(combined, policy)

        val plan = runCatching { agent.plan(request, document) }
            .getOrDefault(AgentPlan.HandBack(HandBackReason.MODEL_OUTPUT_INVALID))
        if (plan is AgentPlan.AnswerLocally) {
            val answer = runCatching { agent.answer(plan.task, request, document) }.getOrNull()
                ?: fallback.answer(plan.task, request, document)
            if (answer != null) return GateDecision.HeldLocally(answer, result)
        }
        val reason = (plan as? AgentPlan.HandBack)?.reason ?: HandBackReason.TOO_COMPLEX
        return GateDecision.HandedBack(result.sanitized, reason, result)
    }
}
