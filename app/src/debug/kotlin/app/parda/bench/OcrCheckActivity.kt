package app.parda.bench

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import app.parda.core.policy.Policy
import app.parda.data.ImageAirlock
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs the image Airlock on every picture in files/ocr, as a share would, and logs what was read
 * and covered under the tag "PardaOCR". Debug builds only.
 *
 *   adb push card.png /sdcard/Android/data/app.parda/files/ocr/
 *   adb shell am start -n app.parda/app.parda.bench.OcrCheckActivity
 */
class OcrCheckActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = getExternalFilesDir("ocr") ?: return finish()
        thread(name = "ocr-check") {
            for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
                val started = System.currentTimeMillis()
                runCatching { ImageAirlock.read(this, Uri.fromFile(f), Policy()) }
                    .onSuccess { r ->
                        Log.i(TAG, "${f.name}: ${r.plan.fields.size} field(s) masked in ${System.currentTimeMillis() - started} ms: " +
                            r.plan.fields.joinToString { "${it.category} '${it.value}'" })
                    }
                    .onFailure { Log.w(TAG, "${f.name}: could not read", it) }
            }
            Log.i(TAG, "DONE")
            runOnUiThread { finish() }
        }
    }

    private companion object {
        const val TAG = "PardaOCR"
    }
}
