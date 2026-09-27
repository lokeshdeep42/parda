package app.parda.core.policy

import kotlinx.serialization.Serializable

/** Kinds of personal data Parda recognises at the data boundary (Channel B). */
@Serializable
enum class DataCategory(val label: String, val tokenPrefix: String) {
    GOV_ID("Government ID numbers", "GOVID"),
    CARD("Card numbers", "CARD"),
    BANK_ACCOUNT("Bank account numbers", "ACCT"),
    PERSON_NAME("Personal names", "PERSON"),
    MONEY_AMOUNT("Salary and amounts", "AMOUNT"),
    PHONE("Phone numbers", "PHONE"),
    EMAIL("Email addresses", "EMAIL"),
    ADDRESS("Addresses", "ADDRESS"),
    DATE_OF_BIRTH("Dates of birth", "DOB"),
    /** Hospital and lab record numbers (UHID, MRN, lab no.) and ABHA addresses: they point to one patient. */
    HEALTH_ID("Health record IDs", "HEALTHID"),
    /**
     * Diagnoses and conditions. Let through by default, since explaining a report needs them;
     * masking them is the stricter mode, for a report going to an employer or an insurer.
     */
    HEALTH_CONDITION("Health conditions", "CONDITION"),
    /** Images only: a face, usually the photo on an ID card. */
    FACE_PHOTO("Face photos", "FACE"),
    /** Images only: QR codes and barcodes. The QR on an Aadhaar carries the name, address, date of birth and photo. */
    QR_CODE("QR codes and barcodes", "QR"),
}

/** What the outbound gate does with one detected item. */
@Serializable
enum class DisclosureAction(val label: String) {
    BLOCK("Block outright"),
    SURROGATE("Replace with a surrogate"),
    KEEP_LAST_4("Mask all but the last 4"),
    ALLOW("Let it pass"),
    /**
     * A made-up value of the same shape ("Arjun Mehta", "arjun.mehta@example.com"), so the copy
     * reads naturally. Chosen by code from fixed lists; swapped back like a surrogate.
     */
    STAND_IN("Swap for a realistic stand-in"),
    ;

    /** Stand-ins exist only where a fake value cannot mislead or belong to someone real. */
    fun appliesTo(category: DataCategory): Boolean = this != STAND_IN || category in STAND_IN_CATEGORIES

    companion object {
        /**
         * Amounts are left out because a fake salary gives a confidently wrong answer, and
         * phone numbers because India has no reserved range, so a fake one may be a real person's.
         */
        val STAND_IN_CATEGORIES = setOf(DataCategory.PERSON_NAME, DataCategory.EMAIL, DataCategory.ADDRESS)
    }
}

/**
 * Tricks Parda recognises at the money boundary (Channel A). [code] is the pattern's number in
 * Annexure 1 of the CCPA Guidelines for Prevention and Regulation of Dark Patterns, 2023.
 */
@Serializable
enum class DarkPatternKind(val label: String, val hint: String, val code: String) {
    BASKET_SNEAKING("Pre-ticked add-ons", "Insurance, tips, donations", "CCPA 2"),
    FALSE_URGENCY("Fake urgency", "Timers that reset, “only 2 left”", "CCPA 1"),
    SUBSCRIPTION_TRAP("Subscription traps", "Memberships and trials that renew", "CCPA 5"),
    CONFIRM_SHAMING("Confirmshaming", "“No, I will take the risk”", "CCPA 3"),
    DRIP_PRICING("Last-step fees", "Charges that appear at payment", "CCPA 8"),
    /** A box ticked for the user that signs them up or opts them in: a loyalty club, promotions. */
    FORCED_ACTION("Pre-ticked sign-ups", "Loyalty clubs, promotional messages", "CCPA 4"),
    /** Paying extra is presented as the way to be served sooner: "tip for faster pickup", fare boosts. */
    PAY_FOR_PRIORITY("Pay for priority", "“Add a tip for faster pickup”", "CCPA 6"),
    /** Wording that makes the user untick to decline, or reads the opposite of what it does. */
    TRICK_WORDING("Trick wording", "“Untick if you don’t want…”", "CCPA 11"),
    ;

    /** Only pre-ticked items can be undone by unticking them; everything else is disclosed. */
    val fixable: Boolean get() = this == BASKET_SNEAKING || this == SUBSCRIPTION_TRAP || this == FORCED_ACTION
}

/** What Parda does when a dark pattern is found. Parda never taps Pay. */
@Serializable
enum class CheckoutAction(val label: String) {
    AUTO_REMOVE("Auto-remove"),
    ASK_ME("Ask me"),
    FLAG_ONLY("Flag only"),
    IGNORE("Ignore"),
}

/**
 * The one policy the user owns, governing both boundaries. The agent may propose;
 * only this policy (applied by deterministic code) decides.
 */
@Serializable
data class Policy(
    val disclosure: Map<DataCategory, DisclosureAction> = DEFAULT_DISCLOSURE,
    val checkout: Map<DarkPatternKind, CheckoutAction> = DEFAULT_CHECKOUT,
    /** Per app (package name): the kinds the user chose to have removed there without being asked. */
    val perApp: Map<String, Set<DarkPatternKind>> = emptyMap(),
) {
    fun actionFor(category: DataCategory): DisclosureAction =
        (disclosure[category] ?: DEFAULT_DISCLOSURE.getValue(category))
            .let { if (it.appliesTo(category)) it else DisclosureAction.SURROGATE }

    /** The action in [app]: the user's rule for that app first, then the global one. */
    fun actionFor(kind: DarkPatternKind, app: String? = null): CheckoutAction {
        if (app != null && kind.fixable && kind in perApp[app].orEmpty()) return CheckoutAction.AUTO_REMOVE
        val action = checkout[kind] ?: DEFAULT_CHECKOUT.getValue(kind)
        // Auto-remove is meaningless for patterns that cannot be undone on screen.
        return if (action == CheckoutAction.AUTO_REMOVE && !kind.fixable) CheckoutAction.ASK_ME else action
    }

    /** Turns removing [kind] without asking in [app] on or off. Only patterns a fix can undo qualify. */
    fun autoRemove(app: String, kind: DarkPatternKind, on: Boolean): Policy {
        if (!kind.fixable) return this
        val kinds = perApp[app].orEmpty().let { if (on) it + kind else it - kind }
        return copy(perApp = if (kinds.isEmpty()) perApp - app else perApp + (app to kinds))
    }

    fun with(category: DataCategory, action: DisclosureAction) =
        copy(disclosure = disclosure + (category to action))

    fun with(kind: DarkPatternKind, action: CheckoutAction) =
        copy(checkout = checkout + (kind to action))

    companion object {
        val DEFAULT_DISCLOSURE: Map<DataCategory, DisclosureAction> = mapOf(
            DataCategory.GOV_ID to DisclosureAction.BLOCK,
            DataCategory.CARD to DisclosureAction.BLOCK,
            DataCategory.BANK_ACCOUNT to DisclosureAction.BLOCK,
            DataCategory.PERSON_NAME to DisclosureAction.SURROGATE,
            DataCategory.MONEY_AMOUNT to DisclosureAction.SURROGATE,
            DataCategory.PHONE to DisclosureAction.SURROGATE,
            DataCategory.EMAIL to DisclosureAction.SURROGATE,
            DataCategory.ADDRESS to DisclosureAction.SURROGATE,
            DataCategory.DATE_OF_BIRTH to DisclosureAction.SURROGATE,
            DataCategory.HEALTH_ID to DisclosureAction.SURROGATE,
            DataCategory.HEALTH_CONDITION to DisclosureAction.ALLOW,
            DataCategory.FACE_PHOTO to DisclosureAction.BLOCK,
            DataCategory.QR_CODE to DisclosureAction.BLOCK,
        )

        val DEFAULT_CHECKOUT: Map<DarkPatternKind, CheckoutAction> = mapOf(
            DarkPatternKind.BASKET_SNEAKING to CheckoutAction.ASK_ME,
            DarkPatternKind.FALSE_URGENCY to CheckoutAction.FLAG_ONLY,
            DarkPatternKind.SUBSCRIPTION_TRAP to CheckoutAction.ASK_ME,
            DarkPatternKind.CONFIRM_SHAMING to CheckoutAction.FLAG_ONLY,
            DarkPatternKind.DRIP_PRICING to CheckoutAction.ASK_ME,
            DarkPatternKind.FORCED_ACTION to CheckoutAction.ASK_ME,
            DarkPatternKind.PAY_FOR_PRIORITY to CheckoutAction.ASK_ME,
            DarkPatternKind.TRICK_WORDING to CheckoutAction.FLAG_ONLY,
        )
    }
}
