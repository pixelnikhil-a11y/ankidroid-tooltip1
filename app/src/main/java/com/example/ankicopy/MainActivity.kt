package com.example.ankicopy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.ankicopy.llm.GraniteSlot
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    // Which slot the currently-open file picker is filling in.
    private var pickerTargetSlot: GraniteSlot? = null

    private val pickGgufLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val slot = pickerTargetSlot
        if (uri != null && slot != null) {
            val localPath = copyGgufIntoAppStorage(uri, slot)
            if (localPath != null) {
                Prefs.setModelPath(this, slot, localPath)
                Toast.makeText(this, "${slot.label} model set", Toast.LENGTH_SHORT).show()
                refreshSlotStatus()
            } else {
                Toast.makeText(this, "Couldn't read that file", Toast.LENGTH_SHORT).show()
            }
        }
        pickerTargetSlot = null
    }

    private lateinit var slotStatusViews: Map<GraniteSlot, TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ── Instructions ─────────────────────────────────────────────────────
        findViewById<TextView>(R.id.instructions).text = """
            One-time setup:
            1. Tap "Open Accessibility Settings", find AnkiCopy, turn it ON.
            2. Tap "Allow Display Over Other Apps" for the floating tooltip
               overlay used by the on-device Granite models.
            3. Below, tap each "Choose file..." button and pick the matching
               .gguf you've already downloaded to your phone.
            4. Edit prompts, then Save. Enable Debug Mode if a button isn't
               doing what you expect.

            Button mapping:
              A, B        – passthrough → AnkiDroid's own "Show Answer" / "Again"
              X           – bring AnkiDroid fullscreen (collapse split)
              Y           – split-screen: AnkiDroid + currently selected AI app
                            (default = ChatGPT; change with SELECT)
              SELECT      – cycle AI target: ChatGPT → Claude → Gemini (→ loop)
              D-pad Up/Dn – very slow smooth scroll
              D-pad Left  – show the on-device Granite explanation tooltip
                            for the current card
              L1          – toggle the on-device Granite tooltip ON / OFF
              R1          – cycle active Granite model:
                              Granite 4.1:3b → Granite 4:micro → Granite 4:1b
              L2          – passthrough (AnkiDroid menu nav)
              R2          – go home
              Start       – copy card + explanation prompt to clipboard
                            (no launch — use Y to open the AI)
        """.trimIndent()

        // ── Accessibility + overlay permissions ──────────────────────────────
        findViewById<Button>(R.id.openSettingsBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.overlayPermBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        // ── Granite model file pickers (one row per slot) ────────────────────
        slotStatusViews = mapOf(
            GraniteSlot.GRANITE_4_1_3B  to findViewById(R.id.slot1StatusText),
            GraniteSlot.GRANITE_4_MICRO to findViewById(R.id.slot2StatusText),
            GraniteSlot.GRANITE_4_1B    to findViewById(R.id.slot3StatusText),
        )
        findViewById<Button>(R.id.slot1PickBtn).setOnClickListener { launchPickerFor(GraniteSlot.GRANITE_4_1_3B) }
        findViewById<Button>(R.id.slot2PickBtn).setOnClickListener { launchPickerFor(GraniteSlot.GRANITE_4_MICRO) }
        findViewById<Button>(R.id.slot3PickBtn).setOnClickListener { launchPickerFor(GraniteSlot.GRANITE_4_1B) }
        refreshSlotStatus()

        // ── LLM enabled toggle ───────────────────────────────────────────────
        val llmSwitch = findViewById<Switch>(R.id.llmEnabledSwitch)
        llmSwitch.isChecked = Prefs.getLlmEnabled(this)
        llmSwitch.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setLlmEnabled(this, isChecked)
        }

        // ── Explanation prompt (Start / long-press tooltip) ──────────────────
        val explanationField = findViewById<EditText>(R.id.explanationSuffixField)
        explanationField.setText(Prefs.getExplanationSuffix(this))
        findViewById<Button>(R.id.saveExplanationBtn).setOnClickListener {
            Prefs.setExplanationSuffix(this, explanationField.text.toString())
            Toast.makeText(this, "Explanation prompt saved", Toast.LENGTH_SHORT).show()
        }

        // ── Scenario prompt (kept for manual/future use) ─────────────────────
        val scenarioField = findViewById<EditText>(R.id.scenarioSuffixField)
        scenarioField.setText(Prefs.getScenarioSuffix(this))
        findViewById<Button>(R.id.saveScenarioBtn).setOnClickListener {
            Prefs.setScenarioSuffix(this, scenarioField.text.toString())
            Toast.makeText(this, "Scenario prompt saved", Toast.LENGTH_SHORT).show()
        }

        // ── Debug switch ─────────────────────────────────────────────────────
        val debugSwitch = findViewById<Switch>(R.id.debugSwitch)
        debugSwitch.isChecked = Prefs.getDebugMode(this)
        debugSwitch.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setDebugMode(this, isChecked)
        }
    }

    private fun launchPickerFor(slot: GraniteSlot) {
        pickerTargetSlot = slot
        // MIME type for .gguf isn't standardized, so accept anything and
        // rely on the user picking the right file - Storage Access
        // Framework doesn't let us filter by extension directly.
        pickGgufLauncher.launch(arrayOf("*/*"))
    }

    /**
     * SAF gives us a content:// Uri, not a filesystem path, and llama.cpp's
     * loader needs a real path it can mmap/fopen. So we copy the picked
     * file once into app-private storage and hand THAT path to Prefs -
     * this also means the model survives even if the user later moves or
     * deletes the original download.
     */
    private fun copyGgufIntoAppStorage(uri: Uri, slot: GraniteSlot): String? {
        return try {
            val destDir = File(filesDir, "gguf_models").apply { mkdirs() }
            val destFile = File(destDir, "${slot.name.lowercase()}.gguf")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 8 * 1024 * 1024)
                }
            } ?: return null
            destFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun refreshSlotStatus() {
        for ((slot, view) in slotStatusViews) {
            val path = Prefs.getModelPath(this, slot)
            view.text = if (path.isNullOrBlank()) {
                "${slot.label}: not set"
            } else {
                val sizeMb = File(path).let { if (it.exists()) it.length() / (1024 * 1024) else 0 }
                "${slot.label}: ${File(path).name} (${sizeMb} MB)"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSlotStatus()
    }
}
