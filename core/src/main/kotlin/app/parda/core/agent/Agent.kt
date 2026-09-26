package app.parda.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Work the on-device model can do without anything leaving the phone. */
enum class LocalTask(val wire: String) {
    SUMMARISE("summarise"),
    EXTRACT("extract"),
    REWRITE("rewrite"),
    ;

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
    }
}

/** Why a request cannot be handled on the device alone. */
enum class HandBackReason(val wire: String, val explanation: String) {
    NEEDS_OUTSIDE_KNOWLEDGE("needs_outside_knowledge", "It needs knowledge the local model does not have."),
    TOO_COMPLEX("too_complex", "It is beyond what the local model can do reliably."),
    MODEL_OUTPUT_INVALID("model_output_invalid", "The local model's plan could not be read, so Parda fell back to the safe path."),
    ;

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
    }
}

/** The agent's proposal. A proposal never carries data; the gate decides what the data becomes. */
sealed interface AgentPlan {
    data class AnswerLocally(val task: LocalTask) : AgentPlan
    data class HandBack(val reason: HandBackReason) : AgentPlan
}

/**
 * The planner. The production implementation runs Hammer2.1-1.5B through llama.cpp with
 * [AgentGrammar.GBNF] constraining its output; [RuleBasedAgent] is the deterministic
 * fallback used when no model is installed, and in tests.
 */
interface LocalAgent {
    fun plan(request: String, document: String): AgentPlan

    /** Performs a local task. Returns null if this agent cannot do it. */
    fun answer(task: LocalTask, request: String, document: String): String?
}

/**
 * Grammar and parser for the model's function-call output. The grammar makes it impossible
 * for the model to emit anything but one of two calls with enumerated arguments: no free
 * text, and therefore no user data, can appear in a plan.
 */
object AgentGrammar {
    val GBNF: String = """
        root        ::= "{" ws "\"name\"" ws ":" ws call "}"
        call        ::= local | handback
        local       ::= "\"answer_locally\"" ws "," ws "\"arguments\"" ws ":" ws "{" ws "\"task\"" ws ":" ws task ws "}" ws
        handback    ::= "\"hand_back\"" ws "," ws "\"arguments\"" ws ":" ws "{" ws "\"reason\"" ws ":" ws reason ws "}" ws
        task        ::= ${LocalTask.entries.joinToString(" | ") { "\"\\\"${it.wire}\\\"\"" }}
        reason      ::= ${listOf(HandBackReason.NEEDS_OUTSIDE_KNOWLEDGE, HandBackReason.TOO_COMPLEX).joinToString(" | ") { "\"\\\"${it.wire}\\\"\"" }}
        ws          ::= [ \t\n]*
    """.trimIndent()

    /** Tool definitions in the shape Hammer's prompt format expects. */
    val TOOLS_JSON: String = """
        [
          {"name": "answer_locally", "description": "Answer using only the document, on this device.",
           "parameters": {"task": {"type": "string", "enum": [${LocalTask.entries.joinToString { "\"${it.wire}\"" }}]}}},
          {"name": "hand_back", "description": "The request needs knowledge beyond the document.",
           "parameters": {"reason": {"type": "string", "enum": ["needs_outside_knowledge", "too_complex"]}}}
        ]
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses model output. Anything unexpected fails closed to a hand-back that sends nothing. */
    fun parse(output: String): AgentPlan = runCatching {
        val obj = json.parseToJsonElement(output.trim()).jsonObject
        val args = obj["arguments"] as JsonObject
        when (obj["name"]!!.jsonPrimitive.content) {
            "answer_locally" -> AgentPlan.AnswerLocally(LocalTask.fromWire(args["task"]!!.jsonPrimitive.content)!!)
            "hand_back" -> AgentPlan.HandBack(HandBackReason.fromWire(args["reason"]!!.jsonPrimitive.content)!!)
            else -> null
        }
    }.getOrNull() ?: AgentPlan.HandBack(HandBackReason.MODEL_OUTPUT_INVALID)
}
