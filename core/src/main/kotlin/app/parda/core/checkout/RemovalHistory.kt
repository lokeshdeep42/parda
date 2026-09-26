package app.parda.core.checkout

import app.parda.core.policy.CheckoutAction
import app.parda.core.policy.DarkPatternKind
import app.parda.core.policy.Policy
import kotlinx.serialization.Serializable

/**
 * How often the user removed each kind of extra in each app, so Parda can offer to do it for
 * them next time. It only ever offers: a rule starts when the user ticks the offer.
 */
@Serializable
data class RemovalHistory(val counts: Map<String, Int> = emptyMap()) {

    fun record(app: String, kinds: Collection<DarkPatternKind>): RemovalHistory =
        RemovalHistory(counts + kinds.distinct().associate { key(app, it) to (counts[key(app, it)] ?: 0) + 1 })

    fun count(app: String, kind: DarkPatternKind): Int = counts[key(app, kind)] ?: 0

    /** Kinds among [present] the user has removed here often enough, and that are not automatic yet. */
    fun offers(app: String, present: Collection<DarkPatternKind>, policy: Policy): List<DarkPatternKind> =
        present.distinct().filter { kind ->
            kind.fixable && count(app, kind) >= LEARN_AFTER && policy.actionFor(kind, app) != CheckoutAction.AUTO_REMOVE
        }

    companion object {
        /** Removed this many times in one app before Parda offers to do it without asking. */
        const val LEARN_AFTER = 2

        private fun key(app: String, kind: DarkPatternKind) = "$app|${kind.name}"
    }
}
