package app.parda.core.checkout

import app.parda.core.agent.ChatPrompt
import app.parda.core.agent.Planner
import app.parda.core.agent.TextEngine
import app.parda.core.policy.DarkPatternKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A second look at a checkout by the on-device model, for tricks worded in ways the rules do not
 * know yet ("FreshCare+", "Nightowl Circle", "surcharge").
 *
 * The model only points: it names numbered lines and a kind, constrained by [GBNF], so it cannot
 * write an item or an amount. Every claim is then checked against the screen by plain code
 * ([verify]): a removable trick must be a ticked box, a fee must carry a price, and so on. What
 * survives is offered to the user like any other finding, but the gate never removes it unasked.
 */
class CheckoutReviewer(private val engine: TextEngine, private val scanner: DarkPatternScanner = DarkPatternScanner()) {

    /** A claim as the model made it: a line number and a kind. */
    data class Claim(val line: Int, val kind: DarkPatternKind)

    /** What the model found that the rules did not, already verified. Empty if the model is unsure or wrong. */
    fun review(screen: ScreenNode, scan: CheckoutScan): List<Finding> {
        if (!scan.isCheckout) return emptyList()
        val lines = scanner.lines(screen)
        if (lines.isEmpty()) return emptyList()
        val output = runCatching { engine.complete(prompt(lines), GBNF, MAX_TOKENS) {} }.getOrElse { return emptyList() }
        return verify(lines, parse(output), scan)
    }

    companion object {
        private const val MAX_TOKENS = 160
        private const val MAX_FINDINGS = 5

        /** The kinds, as the model is told about them. Fixed text first, so the engine can reuse it between screens. */
        private val KINDS = linkedMapOf(
            DarkPatternKind.BASKET_SNEAKING to "an extra item, add-on, donation, tip, insurance or care plan that is already ticked for the user",
            DarkPatternKind.SUBSCRIPTION_TRAP to "a membership, pass or free trial that renews or charges later",
            DarkPatternKind.FORCED_ACTION to "a ticked box that signs the user up to a club, rewards or messages",
            DarkPatternKind.DRIP_PRICING to "a fee, charge or surcharge added to the bill",
            DarkPatternKind.FALSE_URGENCY to "a countdown, \"only a few left\", or pressure to hurry",
            DarkPatternKind.CONFIRM_SHAMING to "a way to say no that is worded to make the user feel bad",
            DarkPatternKind.PAY_FOR_PRIORITY to "paying more to be served or picked up faster",
            DarkPatternKind.TRICK_WORDING to "wording where the user must act to say no",
        )

        private fun wire(kind: DarkPatternKind) = kind.name.lowercase()

        val SYSTEM: String = buildString {
            appendLine("You check a shopping checkout on the user's phone for tricks that make people pay for things they did not choose.")
            appendLine("Kinds of trick:")
            KINDS.forEach { (k, d) -> appendLine("- ${wire(k)}: $d") }
            appendLine("Point only at numbered lines. Lines marked [x] have a ticked box; [ ] an unticked box.")
            append("Items the user chose, plain delivery and the total are not tricks. If there are none, reply with an empty list.")
        }

        fun prompt(lines: List<ScreenLine>) = ChatPrompt(
            system = SYSTEM,
            user = buildString {
                appendLine("Checkout screen:")
                lines.forEach { l ->
                    val box = when { l.box == null -> ""; l.ticked -> "[x] "; else -> "[ ] " }
                    appendLine("${l.number}. $box${l.text}")
                }
                append("Reply as {\"findings\": [{\"line\": <number>, \"kind\": <kind>}]}.")
            },
        )

        /** At most [MAX_FINDINGS] claims, each a line number and one of the eight kinds: nothing else can be written. */
        val GBNF: String = """
            root  ::= "{" ws "\"findings\"" ws ":" ws "[" ws list? ws "]" ws "}" ws
            list  ::= item (ws "," ws item (ws "," ws item (ws "," ws item (ws "," ws item)?)?)?)?
            item  ::= "{" ws "\"line\"" ws ":" ws num ws "," ws "\"kind\"" ws ":" ws kind ws "}"
            num   ::= [1-9] [0-9]?
            kind  ::= ${KINDS.keys.joinToString(" | ") { "\"\\\"${wire(it)}\\\"\"" }}
            ws    ::= [ \t\n]*
        """.trimIndent()

        private val json = Json { ignoreUnknownKeys = true }

        /** Reads the model's claims. Anything unreadable is no claim at all. */
        fun parse(output: String): List<Claim> = runCatching {
            json.parseToJsonElement(output.trim()).jsonObject["findings"]!!.jsonArray.mapNotNull { e ->
                val o = e.jsonObject
                val kind = DarkPatternKind.entries.firstOrNull { wire(it) == o["kind"]!!.jsonPrimitive.content } ?: return@mapNotNull null
                Claim(o["line"]!!.jsonPrimitive.int, kind)
            }
        }.getOrDefault(emptyList())

        private val DIGIT = Regex("""\d""")
        private val PRESSURE = Regex("""(?i)\b(hurry|left|ends?|ending|closing|closes|limited|last|only|today|tonight|soon|fast|expir\w*|remaining|now)\b""")
        private val DECLINE = Regex("""(?i)\b(no|not|don'?t|won'?t|skip|rather|risk|without|never)\b""")
        private val SOONER = Regex("""(?i)\b(faster|quicker|sooner|priority|boost|increase|raise|jump)\b""")
        /** A row that could be a subscription at all: the item and the total cannot. */
        private val RENEWS = Regex("""(?i)(\b(trial|membership|subscri\w*|renew\w*|pass|plus|monthly|per month|yearly|per year)\b|/mo\b|/yr\b)""")
        /** A box that signs the user up rather than adding something to pay for. */
        private val SIGNUP = Regex(
            """(?i)\b(circle|club|member|members|rewards?|points|deals|offers|updates|newsletter|whatsapp|sms|notifications?|alerts|join|enrol\w*|enroll\w*|promotions?|promotional|marketing|sign (me )?up)\b""",
        )
        /** A row that could be a fee at all: an item the user chose cannot. */
        private val FEE_WORD = Regex("""(?i)\b(fee|fees|charge|charges|surcharge|levy|cess|convenience|handling|platform|packaging|packing|service|processing|gateway)\b""")
        private val OPT_OUT = Regex("""(?i)\b(tick|untick|check|uncheck|box|opt|select|deselect)\b""")
        private val URGENT = Regex("""(?i)(\b\d{1,2}:\d{2}\b|\bonly \d+\b|\b\d+ (left|remaining|people)\b)|""" + PRESSURE.pattern.removePrefix("(?i)"))

        /** The words each text-only kind needs before a line can be called that. */
        private val TEXT_CHECKS = mapOf(
            DarkPatternKind.TRICK_WORDING to OPT_OUT,
            DarkPatternKind.PAY_FOR_PRIORITY to SOONER,
            DarkPatternKind.CONFIRM_SHAMING to DECLINE,
            DarkPatternKind.FALSE_URGENCY to URGENT,
        )

        /**
         * Keeps only claims the screen bears out, and names each by what the line is. A small model
         * is good at noticing which line is off and poor at naming the trick (Hammer 2.1 tends to
         * hand out kinds in the order they were listed), so its kind is only a hint: a ticked box
         * is an add-on, a sign-up or a subscription by its words; a priced line is a fee only if it
         * says fee; and a line matching none of them is dropped. The model can point at the wrong
         * line; it cannot make a line say something it does not.
         */
        fun verify(lines: List<ScreenLine>, claims: List<Claim>, scan: CheckoutScan): List<Finding> {
            val known = scan.findings
            val out = LinkedHashMap<String, Finding>()
            for (c in claims) {
                val line = lines.getOrNull(c.line - 1) ?: continue
                // The rules already report this row.
                if (line.box != null && known.any { it.nodeId == line.box }) continue
                if (known.any { covers(it.evidence, line.text) }) continue
                val finding = judge(line, c.kind) ?: continue
                out.putIfAbsent(finding.key, finding)
                if (out.size >= MAX_FINDINGS) break
            }
            return out.values.toList()
        }

        /** What [line] is, if it is a trick at all. [hint] is the model's kind, used only where the words leave a choice. */
        private fun judge(line: ScreenLine, hint: DarkPatternKind): Finding? {
            val text = line.text
            val oneOff = Money.oneOffAmounts(text).firstOrNull() ?: 0L
            val monthly = Money.recurringAmount(text) ?: 0L
            val hasPrice = oneOff > 0 || monthly > 0
            fun found(kind: DarkPatternKind, evidence: String = text, node: String? = null, cost: Long = oneOff, recurring: Long = monthly) =
                Finding(kind, evidence, node, cost, recurring, Planner.MODEL)

            // A box: only a ticked one is a trick (an offer the user has not taken is fine), and only it can be undone.
            if (line.box != null) {
                if (!line.ticked) return null
                val kind = when {
                    monthly > 0 || RENEWS.containsMatchIn(text) -> DarkPatternKind.SUBSCRIPTION_TRAP
                    SIGNUP.containsMatchIn(text) -> DarkPatternKind.FORCED_ACTION
                    oneOff > 0 -> DarkPatternKind.BASKET_SNEAKING
                    hint in BOX_KINDS -> hint
                    else -> DarkPatternKind.FORCED_ACTION
                }
                return found(kind, line.boxLabel ?: text, line.box)
            }
            // A priced line with no box: a fee, a subscription already on the bill, or a paid priority.
            if (hasPrice) return when {
                FEE_WORD.containsMatchIn(text) -> found(DarkPatternKind.DRIP_PRICING, recurring = 0)
                RENEWS.containsMatchIn(text) -> found(DarkPatternKind.SUBSCRIPTION_TRAP)
                SOONER.containsMatchIn(text) -> found(DarkPatternKind.PAY_FOR_PRIORITY, recurring = 0)
                else -> null // an item the user chose, the total, the Pay button
            }
            // Words only: the model's kind first if the words fit it, else the first kind they do fit.
            val kind = (listOf(hint) + TEXT_KINDS).firstOrNull { k -> TEXT_CHECKS[k]?.containsMatchIn(text) == true } ?: return null
            return found(kind, cost = 0, recurring = 0)
        }

        private val BOX_KINDS = setOf(DarkPatternKind.BASKET_SNEAKING, DarkPatternKind.FORCED_ACTION, DarkPatternKind.SUBSCRIPTION_TRAP)
        private val TEXT_KINDS = listOf(
            DarkPatternKind.TRICK_WORDING, DarkPatternKind.PAY_FOR_PRIORITY, DarkPatternKind.CONFIRM_SHAMING, DarkPatternKind.FALSE_URGENCY,
        )

        /** True when a rules finding and a line are the same words (ignoring spacing, case and digits). */
        private fun covers(evidence: String, line: String): Boolean {
            fun norm(s: String) = s.lowercase().replace(DIGIT, "#").replace(Regex("""\s+"""), " ").trim()
            val e = norm(evidence)
            val l = norm(line)
            return e.length >= 6 && (e in l || l in e)
        }
    }
}
