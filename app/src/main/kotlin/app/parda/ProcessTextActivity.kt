package app.parda

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import app.parda.core.ledger.Channel
import app.parda.core.ledger.Verdict
import app.parda.service.CheckoutWatchService

/**
 * "Mask with Parda" in any app's text-selection menu. For editable text, the selection is
 * replaced in place with its sanitized form before the user sends it. For read-only text the
 * Airlock opens with it instead.
 *
 * In a browser the text goes back by paste instead: Chrome hands a processed result to the page's
 * own editor, and the rich editors of chat and AI sites add it as a new paragraph rather than
 * replacing the selection. They all handle a paste correctly, so Parda puts the masked copy on
 * the clipboard and its accessibility service pastes it into the same box.
 */
class ProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        if (text.isNullOrBlank()) {
            finish()
            return
        }

        if (readOnly) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            finish()
            return
        }

        val result = store.sanitizer.sanitize(text, store.policy.value)
        val withheld = result.vault.withheld
        if (withheld > 0) {
            store.record(
                Channel.B, Verdict.BLOCKED,
                "Masked $withheld item(s) in place before sending (${result.blockedCount} blocked)",
                masked = withheld,
            )
        }
        val app = applicationContext
        Toast.makeText(
            app,
            if (withheld > 0) resources.getQuantityString(R.plurals.pl_masked_items, withheld, withheld) else getString(R.string.qm_nothing_personal),
            Toast.LENGTH_SHORT,
        ).show()

        val caller = callingPackage
        if (caller != null && isBrowser(caller)) {
            // Chrome inserts nothing itself; the paste below replaces the selection instead.
            setResult(RESULT_CANCELED)
            if (withheld > 0) {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(getString(R.string.pt_clip_label), result.sanitized))
                val shield = CheckoutWatchService.instance
                val fallback = { Toast.makeText(app, R.string.pt_paste_to_replace, Toast.LENGTH_LONG).show() }
                if (shield != null) shield.pasteMasked(caller, text) { pasted -> if (!pasted) fallback() } else fallback()
            }
            finish()
            return
        }

        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result.sanitized))
        finish()
    }

    private companion object {
        /** Browsers whose web pages get the processed text; ordinary text boxes replace it correctly already. */
        val BROWSERS = setOf(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "org.chromium.chrome",
            "com.brave.browser", "com.microsoft.emmx", "com.opera.browser", "com.opera.mini.native", "com.kiwibrowser.browser",
            "org.mozilla.firefox", "org.mozilla.fenix", "com.sec.android.app.sbrowser", "com.vivo.browser",
            "com.UCMobile.intl", "com.duckduckgo.mobile.android",
        )

        fun isBrowser(pkg: String) = pkg in BROWSERS
    }
}
