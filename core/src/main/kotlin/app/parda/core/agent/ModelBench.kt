package app.parda.core.agent

/**
 * A fixed exam for an on-device model, scored by code, so models can be compared on the phone
 * with one command: does it plan the requests the keyword rules cannot place, do its answers keep
 * the key facts, and how long until the first words appear.
 */
object ModelBench {
    private val SALARY = """
        Subject: Salary revision, FY 2026-27
        Dear Mr Rajesh Kumar,
        Your revised annual compensation is ₹18,40,000 effective 01 April 2026.
        Payments continue to account number 50100234567891 held with HDFC Bank.
        Please confirm receipt within seven working days.
    """.trimIndent()

    private val RENT = """
        This Leave and Licence Agreement is made at Hyderabad between the Landlord, Mr Suresh Rao, and the Tenant, Ms Kavya Menon.
        The Tenant shall pay a monthly rent of Rs 24,000 on or before the fifth day of each month.
        The Tenant shall keep a refundable deposit of Rs 1,20,000 with the Landlord.
        Either party may end this agreement by giving the other one month of written notice.
    """.trimIndent()

    private val STATEMENT = """
        HDFC Bank account statement, August 2026
        Opening balance Rs 42,310.00
        02/08 UPI debit, grocery store Rs 1,240.00
        05/08 Salary credit Rs 85,000.00
        19/08 UPI debit, electricity bill Rs 2,115.00
        Closing balance Rs 1,23,955.00
    """.trimIndent()

    private val HINDI = """
        विषय: वेतन संशोधन, वित्त वर्ष 2026-27
        प्रिय श्री राजेश कुमार,
        आपका संशोधित वार्षिक वेतन ₹18,40,000 है, जो 01 अप्रैल 2026 से लागू होगा।
        भुगतान एचडीएफसी बैंक के खाता संख्या 50100234567891 में जारी रहेगा।
    """.trimIndent()

    data class PlanCase(val doc: String, val document: String, val request: String, val local: Boolean)
    data class AnswerCase(val doc: String, val task: LocalTask, val document: String, val request: String, val mustMention: List<String>)

    val PLANS = listOf(
        PlanCase("salary", SALARY, "What does this letter want from me?", local = true),
        PlanCase("salary", SALARY, "When does the new pay start?", local = true),
        PlanCase("salary", SALARY, "Which bank is my salary paid into?", local = true),
        PlanCase("salary", SALARY, "Write a polite reply accepting the revision.", local = true),
        PlanCase("salary", SALARY, "Is ₹18 lakh a good package for Hyderabad?", local = false),
        PlanCase("salary", SALARY, "How much income tax will I owe on this?", local = false),
        PlanCase("rent", RENT, "How much notice do I have to give before leaving?", local = true),
        PlanCase("rent", RENT, "What do similar flats in Kondapur rent for?", local = false),
        PlanCase("statement", STATEMENT, "What was my opening balance?", local = true),
        PlanCase("statement", STATEMENT, "Do other banks charge less for UPI payments?", local = false),
        PlanCase("hindi", HINDI, "इस पत्र में मेरा नया वेतन कितना है?", local = true),
        PlanCase("hindi", HINDI, "क्या यह वेतन बाज़ार के हिसाब से ठीक है?", local = false),
    )

    val ANSWERS = listOf(
        AnswerCase("salary", LocalTask.SUMMARISE, SALARY, "Summarise this letter.", listOf("18,40,000", "April")),
        AnswerCase("salary", LocalTask.SUMMARISE, SALARY, "When does the new pay start?", listOf("April")),
        AnswerCase("rent", LocalTask.SUMMARISE, RENT, "Summarise this agreement.", listOf("24,000", "1,20,000")),
        AnswerCase("statement", LocalTask.EXTRACT, STATEMENT, "List the amounts.", listOf("42,310", "85,000")),
        AnswerCase("hindi", LocalTask.SUMMARISE, HINDI, "इस पत्र का सारांश दें।", listOf("18,40,000")),
    )

    data class Row(val kind: String, val case: String, val pass: Boolean, val ms: Long, val firstMs: Long, val tokens: Int, val output: String)

    data class Report(val model: String, val loadMs: Long, val rows: List<Row>) {
        private val plans get() = rows.filter { it.kind == "plan" }
        private val answers get() = rows.filter { it.kind == "answer" }

        /** Generated tokens per second, after the first one (prompt reading is counted in [Row.firstMs]). */
        val tokensPerSecond: Double
            get() = answers.sumOf { it.tokens - 1 }.coerceAtLeast(0) * 1000.0 / answers.sumOf { it.ms - it.firstMs }.coerceAtLeast(1)

        fun summary(): String =
            "$model | load ${secs(loadMs)} | plans ${plans.count { it.pass }}/${plans.size}, ${secs(plans.avg { it.ms })} each | " +
                "answers ${answers.count { it.pass }}/${answers.size}, first words ${secs(answers.avg { it.firstMs })}, " +
                "${"%.1f".format(tokensPerSecond)} tok/s"

        fun text(): String = buildString {
            appendLine(summary())
            rows.forEach { r ->
                val mark = if (r.pass) "PASS" else "FAIL"
                appendLine("  $mark ${r.kind} [${secs(r.ms)}${if (r.kind == "answer") ", first ${secs(r.firstMs)}" else ""}] ${r.case}")
                appendLine("       -> " + r.output.trim().replace("\n", " / ").take(300))
            }
        }

        private fun List<Row>.avg(f: (Row) -> Long) = if (isEmpty()) 0 else sumOf(f) / size
        private fun secs(ms: Long) = "%.1f s".format(ms / 1000.0)
    }

    fun run(model: String, loadMs: Long, engine: TextEngine, clock: () -> Long = System::currentTimeMillis, progress: (Row) -> Unit = {}): Report {
        val rows = mutableListOf<Row>()
        for (c in PLANS) {
            val t0 = clock()
            val out = runCatching { engine.complete(AgentPrompt.plan(c.request, c.document), AgentGrammar.GBNF, 48) {} }.getOrDefault("")
            val plan = AgentGrammar.parse(out)
            val ms = clock() - t0
            rows += Row("plan", "${c.doc} · ${c.request} (want ${if (c.local) "local" else "hand back"})", (plan is AgentPlan.AnswerLocally) == c.local, ms, ms, 0, out)
                .also(progress)
        }
        for (c in ANSWERS) {
            val t0 = clock()
            var first = -1L
            var tokens = 0
            val out = runCatching {
                engine.complete(AgentPrompt.answer(c.task, c.request, c.document), null, 220) {
                    if (first < 0) first = clock() - t0
                    tokens++
                }
            }.getOrDefault("")
            val ms = clock() - t0
            val pass = c.mustMention.all { it.lowercase() in out.lowercase() }
            rows += Row("answer", "${c.doc} · ${c.request} (must mention ${c.mustMention.joinToString()})", pass, ms, if (first < 0) ms else first, tokens, out)
                .also(progress)
        }
        return Report(model, loadMs, rows)
    }
}
