package app.parda.core.disclosure

import app.parda.core.detect.Classifier
import app.parda.core.detect.Detection
import app.parda.core.policy.DataCategory
import app.parda.core.policy.DisclosureAction
import app.parda.core.policy.Policy

/** One substitution made at the gate. Held on the device only. */
data class VaultEntry(
    val token: String,
    val real: String,
    val category: DataCategory,
    val action: DisclosureAction,
) {
    /** Only surrogates can be swapped back; blocked and partially masked values never left. */
    val restorable: Boolean get() = action == DisclosureAction.SURROGATE
}

/** Surrogate token -> real value map. Never serialised, never transmitted. */
class Vault(val entries: List<VaultEntry>) {
    private val byToken = entries.filter { it.restorable }.associateBy { it.token.removeSurrounding("<", ">") }

    /**
     * Re-inserts real values into a reply that came back from an outside model. Accepts the
     * token with or without its angle brackets, since models often drop them.
     */
    fun rehydrate(reply: String): String = TOKEN.replace(reply) { m ->
        val key = m.groups[1]?.value ?: m.groups[2]!!.value
        byToken[key]?.real ?: m.value
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
        val vault = mutableListOf<VaultEntry>()
        val out = StringBuilder()
        var cursor = 0

        for (d in detections) {
            out.append(text, cursor, d.start)
            when (val action = policy.actionFor(d.category)) {
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
