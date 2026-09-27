package app.parda.core.disclosure

import app.parda.core.detect.Classifier
import app.parda.core.detect.Detection
import app.parda.core.policy.DataCategory
import app.parda.core.policy.DisclosureAction
import app.parda.core.policy.Policy

/** One substitution made at the gate. Held on the device only. */
@kotlinx.serialization.Serializable
data class VaultEntry(
    val token: String,
    val real: String,
    val category: DataCategory,
    val action: DisclosureAction,
) {
    /** Only surrogates and stand-ins can be swapped back; blocked and partially masked values never left. */
    val restorable: Boolean get() = action == DisclosureAction.SURROGATE || action == DisclosureAction.STAND_IN
}

/** Surrogate token -> real value map. Never transmitted; kept on the phone by [VaultArchive], encrypted. */
class Vault(val entries: List<VaultEntry>) {
    private val byToken = entries.filter { it.action == DisclosureAction.SURROGATE }.associateBy { it.token.removeSurrounding("<", ">") }

    /**
     * Stand-in text -> real text, lowercased. A name also maps its first word and its last word
     * on their own, since replies say "Mr. Mehta" or "Arjun's": they go back to the real first
     * and last names.
     */
    private val byStandIn: Map<String, String> = buildMap {
        for (e in this@Vault.entries.filter { it.action == DisclosureAction.STAND_IN }) {
            put(e.token.lowercase(), e.real)
            if (e.category != DataCategory.PERSON_NAME) continue
            val fake = e.token.split(' ')
            val real = e.real.split(' ', '\t').filter { it.isNotBlank() }
            if (fake.size > 1) {
                putIfAbsent(fake.first().lowercase(), real.first())
                putIfAbsent(fake.last().lowercase(), real.last())
            }
        }
    }
    private val standInPattern: Regex? = byStandIn.keys.sortedByDescending { it.length }.takeIf { it.isNotEmpty() }
        ?.joinToString("|", """(?<![\p{L}\p{N}.@])(""", """)(?![\p{L}\p{N}@]|\.[\p{L}\p{N}])""") { Regex.escape(it) }
        ?.let { Regex(it, RegexOption.IGNORE_CASE) }

    /**
     * Re-inserts real values into a reply that came back from an outside model. Accepts the
     * token with or without its angle brackets, since models often drop them. Stand-ins never
     * share a word with the original text, so this cannot rewrite anything real.
     */
    fun rehydrate(reply: String): String {
        val tokens = TOKEN.replace(reply) { m ->
            val key = m.groups[1]?.value ?: m.groups[2]!!.value
            byToken[key]?.real ?: m.value
        }
        return standInPattern?.replace(tokens) { m -> byStandIn[m.value.lowercase()] ?: m.value } ?: tokens
    }

    val withheld: Int get() = entries.size

    companion object {
        private val TOKEN = Regex("""<([A-Z]+_\d+)>|\b([A-Z]+_\d+)\b""")
    }
}

data class SanitizeResult(
    val original: String,
    val sanitized: String,
    val detections: List<Detection>,
    val vault: Vault,
) {
    val blockedCount: Int get() = vault.entries.count { it.action == DisclosureAction.BLOCK }
    val countsByCategory: Map<DataCategory, Int> get() = detections.groupingBy { it.category }.eachCount()
}

/**
 * The outbound gate for text. Deterministic: the same text and policy always produce the
 * same output, whatever any model said about it.
 */
class Sanitizer(private val classifier: Classifier = Classifier()) {

    fun sanitize(text: String, policy: Policy): SanitizeResult {
        val detections = classifier.classify(text)
        val counters = mutableMapOf<String, Int>()
        val tokenForValue = mutableMapOf<Pair<DataCategory, String>, String>()
        val standIns = StandIns(text)
        val standInForValue = mutableMapOf<Pair<DataCategory, String>, String?>()
        val vault = mutableListOf<VaultEntry>()
        val out = StringBuilder()
        var cursor = 0

        for (d in detections) {
            out.append(text, cursor, d.start)
            var action = policy.actionFor(d.category)
            if (action == DisclosureAction.STAND_IN) {
                val key = d.category to normalise(d.value)
                val standIn = standInForValue.getOrPut(key) { standIns.next(d.category, d.value, text.substring(maxOf(0, d.start - 10), d.start)) }
                if (standIn != null) {
                    out.append(standIn)
                    if (vault.none { it.token == standIn }) vault += VaultEntry(standIn, d.value, d.category, action)
                    cursor = d.end
                    continue
                }
                action = DisclosureAction.SURROGATE // none to give (a name in another script): a placeholder instead
            }
            when (action) {
                DisclosureAction.STAND_IN -> error("handled above")
                DisclosureAction.ALLOW -> out.append(d.value)
                DisclosureAction.BLOCK -> {
                    out.append(BLOCKED)
                    vault += VaultEntry(BLOCKED, d.value, d.category, action)
                }
                DisclosureAction.KEEP_LAST_4 -> {
                    val masked = keepLast4(d.value)
                    out.append(masked)
                    vault += VaultEntry(masked, d.value, d.category, action)
                }
                DisclosureAction.SURROGATE -> {
                    val key = d.category to normalise(d.value)
                    val token = tokenForValue.getOrPut(key) {
                        val prefix = d.category.tokenPrefix
                        val n = (counters[prefix] ?: 0) + 1
                        counters[prefix] = n
                        "<${prefix}_$n>"
                    }
                    out.append(token)
                    if (vault.none { it.token == token }) vault += VaultEntry(token, d.value, d.category, action)
                }
            }
            cursor = d.end
        }
        out.append(text, cursor, text.length)
        return SanitizeResult(text, out.toString(), detections, Vault(vault))
    }

    companion object {
        const val BLOCKED = "[BLOCKED]"

        /** Replaces every letter and digit except the last four with X, keeping separators. */
        fun keepLast4(value: String): String {
            val alnumCount = value.count { it.isLetterOrDigit() }
            var seen = 0
            return buildString {
                for (c in value) {
                    if (c.isLetterOrDigit()) {
                        seen++
                        append(if (seen > alnumCount - 4) c else 'X')
                    } else append(c)
                }
            }
        }

        private fun normalise(v: String) = v.filterNot { it.isWhitespace() || it == '-' }.lowercase()
    }
}
