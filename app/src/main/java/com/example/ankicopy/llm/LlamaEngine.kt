package com.example.ankicopy.llm

/**
 * Thin Kotlin wrapper around the native llama.cpp JNI bridge
 * (see app/src/main/cpp/llm_bridge.cpp).
 *
 * Each instance wraps ONE loaded GGUF model + its llama_context. AnkiCopy
 * keeps up to three of these alive at once (one per Granite model) so R1
 * can switch models instantly without a reload - see LlmManager.kt.
 *
 * All calls that touch the native context (generate, resetContext) must be
 * externally serialized per-instance; llama.cpp contexts are not thread-safe
 * for concurrent decode calls. LlmManager runs everything on a single
 * background dispatcher to guarantee this.
 */
class LlamaEngine private constructor(
    private var handle: Long,
    val modelPath: String,
) {
    val isLoaded: Boolean get() = handle > 0

    fun generate(
        systemPrompt: String,
        userText: String,
        maxTokens: Int = 350,
        temperature: Float = 0.3f,
    ): String {
        if (!isLoaded) return "[error] model not loaded"
        return try {
            nativeGenerate(handle, systemPrompt, userText, maxTokens, temperature)
        } catch (e: Exception) {
            "[error] generation failed: ${e.message}"
        }
    }

    /** Clears KV cache so the next generate() call starts from a clean context. */
    fun resetContext() {
        if (isLoaded) nativeResetContext(handle)
    }

    fun unload() {
        if (isLoaded) {
            nativeFree(handle)
            handle = -1
        }
    }

    companion object {
        init {
            System.loadLibrary("ankicopy_llm")
        }

        /**
         * Loads a GGUF file from an absolute filesystem path. Returns null on
         * failure (bad path, unsupported/corrupt GGUF, out-of-memory, etc.).
         *
         * [threads] defaults to a sane fraction of available cores - Granite
         * 1b/3b/micro don't benefit much past 4-6 threads on typical phone
         * SoCs, and using every core tends to throttle faster.
         */
        fun load(path: String, threads: Int = defaultThreadCount(), contextSize: Int = 2048): LlamaEngine? {
            val handle = nativeLoad(path, threads, contextSize)
            if (handle <= 0) return null
            return LlamaEngine(handle, path)
        }

        private fun defaultThreadCount(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return (cores - 2).coerceIn(2, 6)
        }

        @JvmStatic private external fun nativeLoad(modelPath: String, nThreads: Int, nCtx: Int): Long
        @JvmStatic private external fun nativeGenerate(
            handle: Long, systemPrompt: String, userText: String,
            maxTokens: Int, temperature: Float,
        ): String
        @JvmStatic private external fun nativeResetContext(handle: Long)
        @JvmStatic private external fun nativeFree(handle: Long)
    }
}
