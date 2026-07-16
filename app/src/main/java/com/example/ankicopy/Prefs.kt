package com.example.ankicopy

import android.content.Context
import android.content.SharedPreferences
import com.example.ankicopy.llm.GraniteSlot

enum class AiTarget(
    val label: String,
    val packageName: String,
    val inputHints: List<String>,
    val sendHints: List<String>
) {
    CHATGPT(
        "ChatGPT",
        "com.openai.chatgpt",
        listOf("prompt_textarea", "message", "compose", "input", "text_input"),
        listOf("send", "send message", "send prompt")
    ),
    CLAUDE(
        "Claude",
        "com.anthropic.claude",
        listOf("compose", "message", "input", "prompt"),
        listOf("send", "send message")
    ),
    GEMINI(
        "Gemini",
        "com.google.android.apps.bard",
        listOf("input", "compose", "message", "prompt"),
        listOf("send", "send message")
    );

    companion object {
        fun next(current: AiTarget): AiTarget {
            val values = values()
            return values[(current.ordinal + 1) % values.size]
        }
    }
}

object Prefs {
    private const val FILE = "ankicopy_prefs"
    private const val KEY_TARGET             = "ai_target"
    private const val KEY_EXPLANATION_SUFFIX = "explanation_suffix"
    private const val KEY_SCENARIO_SUFFIX    = "scenario_suffix"
    private const val KEY_DEBUG_MODE         = "debug_mode"
    private const val KEY_LLM_SLOT           = "llm_slot"
    private const val KEY_LLM_ENABLED        = "llm_enabled"

    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ── AI chat target (Y opens it, Select cycles it) ────────────────────────
    fun getTarget(context: Context): AiTarget {
        val name = sp(context).getString(KEY_TARGET, AiTarget.CHATGPT.name)
        return try { AiTarget.valueOf(name ?: AiTarget.CHATGPT.name) }
        catch (e: Exception) { AiTarget.CHATGPT }
    }
    fun setTarget(context: Context, target: AiTarget) {
        sp(context).edit().putString(KEY_TARGET, target.name).apply()
    }

    // ── Active Granite slot (R1 cycles) ──────────────────────────────────────
    fun getActiveSlot(context: Context): GraniteSlot {
        val name = sp(context).getString(KEY_LLM_SLOT, GraniteSlot.GRANITE_4_1_3B.name)
        return try { GraniteSlot.valueOf(name ?: GraniteSlot.GRANITE_4_1_3B.name) }
        catch (e: Exception) { GraniteSlot.GRANITE_4_1_3B }
    }
    fun setActiveSlot(context: Context, slot: GraniteSlot) {
        sp(context).edit().putString(KEY_LLM_SLOT, slot.name).apply()
    }

    // ── LLM on/off (L1 toggles) ──────────────────────────────────────────────
    fun getLlmEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_LLM_ENABLED, true)
    fun setLlmEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_LLM_ENABLED, enabled).apply()
    }

    // ── Per-slot GGUF file path (set via the file picker in MainActivity) ───
    fun getModelPath(context: Context, slot: GraniteSlot): String? =
        sp(context).getString(slot.prefsKeySuffix, null)
    fun setModelPath(context: Context, slot: GraniteSlot, path: String) {
        sp(context).edit().putString(slot.prefsKeySuffix, path).apply()
    }

    // ── Prompt suffixes ──────────────────────────────────────────────────────
    fun getExplanationSuffix(context: Context): String =
        sp(context).getString(KEY_EXPLANATION_SUFFIX,
            "Explain this concept from the card clearly.") ?: ""
    fun setExplanationSuffix(context: Context, suffix: String) {
        sp(context).edit().putString(KEY_EXPLANATION_SUFFIX, suffix).apply()
    }

    fun getScenarioSuffix(context: Context): String =
        sp(context).getString(KEY_SCENARIO_SUFFIX,
            "Give me a specific factual scenario where this rule would apply, and walk through how it applies.") ?: ""
    fun setScenarioSuffix(context: Context, suffix: String) {
        sp(context).edit().putString(KEY_SCENARIO_SUFFIX, suffix).apply()
    }

    // ── Debug mode ───────────────────────────────────────────────────────────
    fun getDebugMode(context: Context): Boolean =
        sp(context).getBoolean(KEY_DEBUG_MODE, false)
    fun setDebugMode(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_DEBUG_MODE, enabled).apply()
    }
}
