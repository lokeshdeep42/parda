package app.parda.bench

import android.app.Activity
import android.os.Bundle
import android.util.Log
import app.parda.store
import kotlin.concurrent.thread

/**
 * Puts the model to rest, then asks a question only the model can plan, through the real gate,
 * and logs how long waking took. No taps needed. Debug builds only; tag "PardaWake".
 *
 *   adb shell am start -n app.parda/app.parda.bench.ModelWakeCheckActivity
 */
class ModelWakeCheckActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thread(name = "wake-check") {
            val store = applicationContext.store
            Log.i(TAG, "before: ${store.model.value}")
            store.restModel()
            Log.i(TAG, "rested: ${store.model.value}")
            val t0 = System.currentTimeMillis()
            val d = runCatching {
                store.gate.handle(LETTER, "What does this letter want from me?", store.policy.value)
            }.onFailure { Log.w(TAG, "failed", it) }.getOrNull()
            Log.i(TAG, "answered in ${System.currentTimeMillis() - t0} ms, planned by ${d?.plannedBy}, now: ${store.model.value}")
            Log.i(TAG, "DONE")
            runOnUiThread { finish() }
        }
    }

    private companion object {
        const val TAG = "PardaWake"
        const val LETTER = "Dear Mr Rajesh Kumar,\nYour revised annual compensation is Rs 18,40,000 effective 01 April 2026.\nPlease confirm receipt within seven working days."
    }
}
