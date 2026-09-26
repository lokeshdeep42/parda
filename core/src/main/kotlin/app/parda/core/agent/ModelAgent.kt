package app.parda.core.agent

/**
 * One turn of a chat. The engine wraps it in the model's own chat template (ChatML for Hammer
 * and Qwen, turn markers for Gemma), so any instruction-tuned GGUF can be dropped in.
 */
data class ChatPrompt(val system: String, val user: String)

/** A local text generator. On the phone this is llama.cpp; in tests it is a fake. */
fun interface TextEngine {
    /**
     * Completes [prompt]. When [grammar] is set, sampling is constrained to it, so the output
     * can only be a string the grammar accepts. [onToken] receives the text as it is generated.
     */
    fun complete(prompt: ChatPrompt, grammar: String?, maxTokens: Int, onToken: (String) -> Unit): String
}

/**
 * The on-device planner: Hammer2.1, Qwen2.5 or Gemma 3 behind [engine].
 * Planning is grammar-constrained, so the model can only pick one of the enumerated calls.
 * Local answers are free text, but they are only ever shown on this screen, never handed back.
 *
 * [guard] answers first. When its keywords settle the plan ("summarise", "extract", or a question
 * that plainly needs outside facts, which a small model can misroute), the model is not asked:
 * that saves several seconds and the outcome is deterministic. The model plans only the requests
 * the rules cannot place.
 */
class ModelAgent(
    private val engine: TextEngine,
    private val guard: LocalAgent = RuleBasedAgent(),
) : LocalAgent {

    override fun plan(request: String, document: String): AgentPlan = planned(request, document).plan

    override fun planned(request: String, document: String): Planned {
        val ruled = guard.plan(request, document)
        if (ruled != AgentPlan.HandBack(HandBackReason.TOO_COMPLEX)) return Planned(ruled, Planner.RULES)
        val output = engine.complete(AgentPrompt.plan(request, document), AgentGrammar.GBNF, PLAN_TOKENS) {}
        return Planned(AgentGrammar.parse(output), Planner.MODEL)
    }

    override fun answer(task: LocalTask, request: String, document: String, onToken: (String) -> Unit): String? =
        engine.complete(AgentPrompt.answer(task, request, document), null, ANSWER_TOKENS, onToken)
            .trim()
            .takeIf { it.isNotEmpty() }

    private companion object {
        const val PLAN_TOKENS = 48
        const val ANSWER_TOKENS = 220
    }
}

/** Prompts in Hammer2.1's tool-calling layout; other instruction-tuned models follow it too. */
object AgentPrompt {
    /** The planner only needs the gist of the document to decide; a short excerpt keeps it fast. */
    private const val PLAN_EXCERPT = 600
    private const val ANSWER_EXCERPT = 3000

    fun plan(request: String, document: String) = ChatPrompt(
        system = "You are a helpful assistant.",
        user = """
            |[BEGIN OF TASK INSTRUCTION]
            |You are a tool calling assistant running on the user's phone. Pick exactly one tool.
            |Use answer_locally when the request can be done using only the document: summarising it, extracting its facts, or rewriting it.
            |Use hand_back with reason "needs_outside_knowledge" when the request needs facts that are not in the document: market rates, pay bands, laws, news, comparisons, advice.
            |Use hand_back with reason "too_complex" only when the request is about the document itself but too hard to do reliably.
            |[END OF TASK INSTRUCTION]
            |
            |[BEGIN OF AVAILABLE TOOLS]
            |${AgentGrammar.TOOLS_JSON}
            |[END OF AVAILABLE TOOLS]
            |
            |[BEGIN OF FORMAT INSTRUCTION]
            |Reply with one JSON object {"name": <tool>, "arguments": {...}} and nothing else.
            |[END OF FORMAT INSTRUCTION]
            |
            |[BEGIN OF QUERY]
            |Document (excerpt):
            |${document.take(PLAN_EXCERPT)}
            |
            |Request: $request
            |[END OF QUERY]
        """.trimMargin(),
    )

    fun answer(task: LocalTask, request: String, document: String) = ChatPrompt(
        system = "You answer strictly from the document the user gives you. If the document does not say, reply that it does not say. Be brief. Reply in the language of the request.",
        user = "${instruction(task)}\n\nDocument:\n${document.take(ANSWER_EXCERPT)}\n\nRequest: $request",
    )

    private fun instruction(task: LocalTask) = when (task) {
        LocalTask.SUMMARISE -> "Summarise the document in at most three short bullet points."
        LocalTask.EXTRACT -> "List the names, amounts, dates and identifiers in the document, one per line."
        LocalTask.REWRITE -> "Rewrite the document as asked, keeping every fact unchanged."
    }
}
