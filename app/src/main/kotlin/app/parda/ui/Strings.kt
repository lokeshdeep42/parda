package app.parda.ui

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import app.parda.PardaApp
import app.parda.R
import app.parda.core.agent.HandBackReason
import app.parda.core.agent.Planner
import app.parda.core.ledger.Verdict
import app.parda.core.policy.CheckoutAction
import app.parda.core.policy.DarkPatternKind
import app.parda.core.policy.DataCategory
import app.parda.core.policy.DisclosureAction

/** A string in the app's language, where no composition is at hand: click handlers, notifications. */
fun str(@StringRes id: Int, vararg args: Any): String = PardaApp.instance.getString(id, *args)

/** "1 decision", "3 decisions": the count picks the form, in either language. */
fun plural(@PluralsRes id: Int, count: Int): String = PardaApp.instance.resources.getQuantityString(id, count, count)

// Core keeps English names for its enums; what the user reads comes from the app's resources.

val DarkPatternKind.title: String
    get() = str(
        when (this) {
            DarkPatternKind.BASKET_SNEAKING -> R.string.kind_basket
            DarkPatternKind.FALSE_URGENCY -> R.string.kind_urgency
            DarkPatternKind.SUBSCRIPTION_TRAP -> R.string.kind_subscription
            DarkPatternKind.CONFIRM_SHAMING -> R.string.kind_shaming
            DarkPatternKind.DRIP_PRICING -> R.string.kind_drip
        },
    )

val DarkPatternKind.hintText: String
    get() = str(
        when (this) {
            DarkPatternKind.BASKET_SNEAKING -> R.string.kind_basket_hint
            DarkPatternKind.FALSE_URGENCY -> R.string.kind_urgency_hint
            DarkPatternKind.SUBSCRIPTION_TRAP -> R.string.kind_subscription_hint
            DarkPatternKind.CONFIRM_SHAMING -> R.string.kind_shaming_hint
            DarkPatternKind.DRIP_PRICING -> R.string.kind_drip_hint
        },
    )

val DataCategory.title: String
    get() = str(
        when (this) {
            DataCategory.GOV_ID -> R.string.cat_gov_id
            DataCategory.CARD -> R.string.cat_card
            DataCategory.BANK_ACCOUNT -> R.string.cat_bank
            DataCategory.PERSON_NAME -> R.string.cat_name
            DataCategory.MONEY_AMOUNT -> R.string.cat_money
            DataCategory.PHONE -> R.string.cat_phone
            DataCategory.EMAIL -> R.string.cat_email
            DataCategory.ADDRESS -> R.string.cat_address
            DataCategory.DATE_OF_BIRTH -> R.string.cat_dob
            DataCategory.HEALTH_ID -> R.string.cat_health_id
            DataCategory.HEALTH_CONDITION -> R.string.cat_condition
        },
    )

val DisclosureAction.title: String
    get() = str(
        when (this) {
            DisclosureAction.BLOCK -> R.string.disc_block
            DisclosureAction.SURROGATE -> R.string.disc_surrogate
            DisclosureAction.KEEP_LAST_4 -> R.string.disc_last4
            DisclosureAction.ALLOW -> R.string.disc_allow
        },
    )

val CheckoutAction.title: String
    get() = str(
        when (this) {
            CheckoutAction.AUTO_REMOVE -> R.string.co_auto
            CheckoutAction.ASK_ME -> R.string.co_ask
            CheckoutAction.FLAG_ONLY -> R.string.co_flag
            CheckoutAction.IGNORE -> R.string.co_ignore
        },
    )

val Verdict.title: String
    get() = str(
        when (this) {
            Verdict.HELD_LOCALLY -> R.string.verdict_held
            Verdict.HANDED_BACK -> R.string.verdict_handed
            Verdict.BLOCKED -> R.string.verdict_blocked
            Verdict.FLAGGED -> R.string.verdict_flagged
            Verdict.FIXED -> R.string.verdict_fixed
            Verdict.KEPT -> R.string.verdict_kept
        },
    )

val HandBackReason.explanationText: String
    get() = str(
        when (this) {
            HandBackReason.NEEDS_OUTSIDE_KNOWLEDGE -> R.string.reason_outside
            HandBackReason.TOO_COMPLEX -> R.string.reason_complex
            HandBackReason.MODEL_OUTPUT_INVALID -> R.string.reason_invalid
        },
    )

val Planner.title: String
    get() = str(if (this == Planner.RULES) R.string.planner_rules else R.string.planner_model)

/**
 * English or Hindi, per app. Android 13+ keeps the choice itself (also under Settings → Apps →
 * Parda → Language); older phones follow the system language.
 */
object AppLanguage {
    val switchable: Boolean get() = Build.VERSION.SDK_INT >= 33

    fun isHindi(context: Context): Boolean = context.resources.configuration.locales[0].language == "hi"

    /** Switches between English and Hindi; Android recreates the screens in the new language. */
    fun toggle(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val next = if (isHindi(context)) "en" else "hi"
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(next)
    }
}
