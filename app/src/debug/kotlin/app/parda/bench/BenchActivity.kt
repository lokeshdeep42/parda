package app.parda.bench

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import app.parda.core.agent.ModelBench
import app.parda.llm.LlamaEngine
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs [ModelBench] on every .gguf in the models folder, one at a time, in its own process.
 *
 *   adb shell am start -n app.parda/app.parda.bench.BenchActivity [--es only gemma]
 *
 * Reports land in files/bench/ (pull with adb) and in Logcat under the tag "PardaBench".
 */
class BenchActivity : Activity() {
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        log = TextView(this).apply { setPadding(32, 96, 32, 32); textSize = 11f }
        setContentView(ScrollView(this).apply { addView(log) })
        current = this
        // The activity can be recreated (rotation, screen off); the run belongs to the process.
        if (!running.compareAndSet(false, true)) return
        val only = intent.getStringExtra("only")?.lowercase()
        thread(name = "bench") { runAll(only) }
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    private fun runAll(only: String?) {
        val out = File(getExternalFilesDir(null), "bench").apply { mkdirs() }
        val models = getExternalFilesDir("models")?.listFiles { f -> f.extension.equals("gguf", true) }.orEmpty()
            .filter { only == null || only in it.name.lowercase() }
            .sortedBy { it.length() }
        say("Models: ${models.joinToString { it.name }}")
        val summaries = mutableListOf<String>()
        for (file in models) {
            say("\nLoading ${file.name} (${file.length() / 1_000_000} MB)…")
            val t0 = System.currentTimeMillis()
            val loaded = runCatching { LlamaEngine.load(applicationInfo.nativeLibraryDir, file) }
            val engine = loaded.getOrNull()
            if (engine == null) {
                say("  could not load: ${loaded.exceptionOrNull()?.message}")
                continue
            }
            val report = engine.use {
                ModelBench.run(file.nameWithoutExtension, System.currentTimeMillis() - t0, it) { r ->
                    say("  ${if (r.pass) "PASS" else "FAIL"} ${r.kind} ${"%.1f".format(r.ms / 1000.0)} s  ${r.case.take(60)}")
                }
            }
            File(out, "${file.nameWithoutExtension}.txt").writeText(report.text())
            summaries += report.summary()
            say(report.summary())
        }
        File(out, "summary.txt").writeText(summaries.joinToString("\n") + "\n")
        say("\nDONE")
        running.set(false)
    }

    private fun say(line: String) {
        Log.i("PardaBench", line)
        transcript.append(line).append('\n')
        current?.let { a -> a.runOnUiThread { a.log.text = transcript } }
    }

    private companion object {
        val running = java.util.concurrent.atomic.AtomicBoolean(false)
        val transcript = StringBuffer()
        @Volatile var current: BenchActivity? = null
    }
}
