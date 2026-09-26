package app.parda.llm

import app.parda.core.agent.TextEngine
import java.io.Closeable
import java.io.File

/** llama.cpp running on this phone's CPU. One request at a time; each starts from a clean context. */
class LlamaEngine private constructor(private var handle: Long, val modelName: String) : TextEngine, Closeable {

    @Synchronized
    override fun complete(prompt: String, grammar: String?, maxTokens: Int): String {
        check(handle != 0L) { "engine closed" }
        return LlamaNative.complete(handle, prompt, grammar, maxTokens).toString(Charsets.UTF_8)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) LlamaNative.free(handle)
        handle = 0L
    }

    companion object {
        private const val CONTEXT_TOKENS = 4096

        /** Loads [model]. Slow (seconds): call it off the main thread. */
        fun load(nativeLibDir: String, model: File): LlamaEngine {
            LlamaNative.ensureInit(nativeLibDir)
            val handle = LlamaNative.load(model.absolutePath, CONTEXT_TOKENS)
            require(handle != 0L) { "Could not load ${model.name}" }
            return LlamaEngine(handle, model.nameWithoutExtension)
        }
    }
}

internal object LlamaNative {
    private var initialised = false

    @Synchronized
    fun ensureInit(nativeLibDir: String) {
        if (initialised) return
        System.loadLibrary("parda_llm")
        init(nativeLibDir)
        initialised = true
    }

    @JvmStatic external fun init(nativeLibDir: String)
    @JvmStatic external fun load(path: String, nCtx: Int): Long
    @JvmStatic external fun complete(handle: Long, prompt: String, grammar: String?, maxTokens: Int): ByteArray
    @JvmStatic external fun free(handle: Long)
}
