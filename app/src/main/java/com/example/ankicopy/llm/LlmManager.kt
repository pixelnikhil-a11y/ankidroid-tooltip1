package com.example.ankicopy.llm

import android.content.Context
import android.util.Log
import com.example.ankicopy.Prefs
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * The three Granite model slots AnkiCopy supports. Each slot maps to a
 * user-picked GGUF file (see ModelFilePicker) and, once picked, a lazily
 * loaded LlamaEngine kept resident for as long as the accessibility service
 * is alive - so switching with R1 is instant (no reload), at the cost of
 * holding up to 3 models' worth of RAM simultaneously.
 *
 * If memory is tight on a given device, loadEngineFor() can be changed to
 * evict the previous slot's engine before loading the next - see the
 * commented-out eager-unload line below.
 */
enum class GraniteSlot(val label: String, val prefsKeySuffix: String) {
    GRANITE_4_1_3B("Granite 4.1:3b", "granite_4_1_3b_path"),
    GRANITE_4_MICRO("Granite 4:micro", "granite_4_micro_path"),
    GRANITE_4_1B("Granite 4:1b", "granite_4_1b_path");

    companion object {
        fun next(current: GraniteSlot): GraniteSlot {
            val v = values()
            return v[(current.ordinal + 1) % v.size]
        }
    }
}

object LlmManager {
    private const val TAG = "AnkiCopyLLM"

    // Single-thread executor: llama.cpp contexts are not safe for concurrent
    // decode, and we only ever want one explanation in flight at a time
    // anyway (a second long-press while one is generating should queue,
    // not race).
    private val executor = Executors.newSingleThreadExecutor()

    private val loadedEngines = mutableMapOf<GraniteSlot, LlamaEngine>()
    private var pendingLoad: Future<*>? = null

    /** Absolute file path for a slot, or null if the user hasn't picked one yet. */
    fun getModelPath(context: Context, slot: GraniteSlot): String? =
        Prefs.getModelPath(context, slot)

    fun setModelPath(context: Context, slot: GraniteSlot, path: String) {
        Prefs.setModelPath(context, slot, path)
        // Path changed - drop any stale loaded engine for this slot so the
        // next generate() call reloads from the new file.
        loadedEngines.remove(slot)?.unload()
    }

    fun isSlotConfigured(context: Context, slot: GraniteSlot): Boolean =
        !getModelPath(context, slot).isNullOrBlank()

    /**
     * Runs [onResult] on the calling thread's Handler-free callback path -
     * caller (AccessibilityService) is expected to post any UI work back to
     * the main thread itself, since this always calls back from the
     * executor's background thread.
     */
    fun generateExplanation(
        context: Context,
        slot: GraniteSlot,
        systemPrompt: String,
        cardText: String,
        maxTokens: Int,
        temperature: Float,
        onResult: (Result<String>) -> Unit,
    ) {
        val path = getModelPath(context, slot)
        if (path.isNullOrBlank()) {
            onResult(Result.failure(IllegalStateException(
                "No GGUF file selected for ${slot.label}. Open AnkiCopy and pick one.")))
            return
        }

        executor.execute {
            try {
                val engine = loadedEngines.getOrPut(slot) {
                    Log.i(TAG, "Loading ${slot.label} from $path")
                    LlamaEngine.load(path)
                        ?: throw IllegalStateException("Failed to load ${slot.label} - check the GGUF file is valid.")
                }
                engine.resetContext()
                val text = engine.generate(systemPrompt, cardText, maxTokens, temperature)
                onResult(Result.success(text))
            } catch (e: Exception) {
                Log.e(TAG, "generateExplanation failed", e)
                onResult(Result.failure(e))
            }
        }
    }

    /** Frees all resident models - call from onUnbind so memory is released
     *  when the accessibility service is torn down. */
    fun unloadAll() {
        loadedEngines.values.forEach { it.unload() }
        loadedEngines.clear()
    }
}
