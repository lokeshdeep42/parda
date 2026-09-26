package app.parda

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import app.parda.core.ledger.Channel
import app.parda.core.ledger.Verdict

/**
 * "Mask with Parda" in any app's text-selection menu. For editable text, the selection is
 * replaced in place with its sanitized form before the user sends it. For read-only text the
 * Airlock opens with it instead.
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
        Toast.makeText(
            this,
            if (withheld > 0) resources.getQuantityString(R.plurals.pl_masked_items, withheld, withheld) else getString(R.string.qm_nothing_personal),
            Toast.LENGTH_SHORT,
        ).show()
        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result.sanitized))
        finish()
    }
}
