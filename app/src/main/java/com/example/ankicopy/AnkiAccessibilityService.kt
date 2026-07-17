package com.example.ankicopy

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import com.example.ankicopy.llm.GraniteSlot
import com.example.ankicopy.llm.LlmManager
import com.example.ankicopy.overlay.TooltipOverlay

/**
 * Button mapping:
 *
 *   A / B      – passthrough (AnkiDroid's own "Show Answer" / "Again")
 *   X          – bring AnkiDroid to fullscreen (collapse split)
 *   Y          – open AnkiDroid + selected AI app in split screen
 *                (default = ChatGPT; cycle AI target with SELECT)
 *   SELECT     – cycle which AI app Y targets (ChatGPT → Claude → Gemini)
 *
 *   D-pad Up    – very slow smooth scroll up
 *   D-pad Down  – very slow smooth scroll down
 *   D-pad Left  – show the on-device Granite explanation tooltip for the
 *                 current card (see "why D-pad Left, not a screen gesture"
 *                 below)
 *
 *   L1         – toggle the on-device Granite tooltip on / off
 *   R1         – cycle active Granite model
 *                (Granite 4.1:3b → Granite 4:micro → Granite 4:1b)
 *
 *   L2         – passthrough (AnkiDroid's menu nav)
 *   R2         – go home
 *
 *   Start      – copy card + explanation prompt to clipboard
 *                (no app launch — paste manually or use Y to open the AI)
 *
 * ── Why D-pad Left instead of long-press/double-tap on the card ──────────
 * An earlier version of this service tried to detect long-press/double-tap
 * on card text by turning Android's touch-exploration mode on/off per
 * foreground app. That caused system-wide input freezes requiring a hard
 * restart: touch exploration isn't a clean per-service on/off switch (the
 * OS documents that toggling it doesn't deterministically track a single
 * service's flag, and TYPE_WINDOW_STATE_CHANGED fires far more often than
 * "entered/left AnkiDroid" - every dialog, fragment change, and system
 * popup triggers it), so the mode was flipping rapidly and repeatedly and
 * wedging the system's touch-exploration handler. That mechanism has been
 * removed entirely - it never touches serviceInfo/setServiceInfo now, and
 * canRequestTouchExplorationMode is no longer requested. D-pad Left is a
 * plain button press, same mechanism as every other mapped button here -
 * no touch-mode side effects possible.
 */
class AnkiAccessibilityService : AccessibilityService() {

    companion object {
        const val ANKI_PACKAGE = "com.ichi2.anki"

        // Scroll is slower the higher this number.
        private const val SCROLL_DURATION_MS = 1500L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var statusOverlayView: TextView? = null
    private var overlayShown = false

    private lateinit var tooltipOverlay: TooltipOverlay

    override fun onServiceConnected() {
        super.onServiceConnected()
        tooltipOverlay = TooltipOverlay(this)
        toast("AnkiCopy active | AI: ${Prefs.getTarget(this).label} | Granite: ${Prefs.getActiveSlot(this).label} ${if (Prefs.getLlmEnabled(this)) "ON" else "OFF"}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // ──────────────────────────────────────────────────────────────────────────
    // D-pad Left: show the Granite explanation tooltip for the current card
    // ──────────────────────────────────────────────────────────────────────────

    private fun triggerExplanation() {
        if (!Prefs.getLlmEnabled(this)) {
            toast("Granite tooltip is OFF (L1 to enable)")
            return
        }
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != ANKI_PACKAGE) {
            toast("Open AnkiDroid first")
            return
        }
        val webViewNode = findNodeByClass(root, "android.webkit.WebView")
        if (webViewNode == null) {
            toast("Card content not found – try again")
            return
        }
        val sb = StringBuilder()
        collectText(webViewNode, sb)
        val cardText = cleanText(sb.toString())
        if (cardText.isBlank()) {
            toast("Nothing to explain")
            return
        }

        val slot = Prefs.getActiveSlot(this)
        if (!LlmManager.isSlotConfigured(this, slot)) {
            toast("${slot.label} has no file set - open AnkiCopy to pick one")
            return
        }
        val systemPrompt = Prefs.getExplanationSuffix(this).ifBlank {
            "Explain this concept from the card clearly."
        }

        // Anchor near the top-center of the screen - there's no tap point
        // to anchor to anymore since this is a button press, not a gesture.
        val dm = resources.displayMetrics
        val anchorX = dm.widthPixels / 2
        val anchorY = (dm.heightPixels * 0.25f).toInt()

        tooltipOverlay.showLoading(anchorX, anchorY)

        LlmManager.generateExplanation(
            context = this,
            slot = slot,
            systemPrompt = systemPrompt,
            cardText = cardText,
            maxTokens = 300,
            temperature = 0.3f,
        ) { result ->
            mainHandler.post {
                result.fold(
                    onSuccess = { text -> tooltipOverlay.showResult(text.trim()) },
                    onFailure = { err -> tooltipOverlay.showError(err.message ?: "Generation failed") }
                )
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.onKeyEvent(event)

        return when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B ->
                super.onKeyEvent(event)                                  // passthrough

            KeyEvent.KEYCODE_BUTTON_X ->
                { bringAnkiToFullscreen(); true }

            KeyEvent.KEYCODE_BUTTON_Y ->
                { openSplitScreen(Prefs.getTarget(this)); true }         // opens selected AI

            KeyEvent.KEYCODE_BUTTON_SELECT ->
                { cycleAiTarget(); true }                                // cycles AI app

            KeyEvent.KEYCODE_DPAD_UP ->
                { controlledScroll(down = false); true }

            KeyEvent.KEYCODE_DPAD_DOWN ->
                { controlledScroll(down = true); true }

            KeyEvent.KEYCODE_DPAD_LEFT ->
                { triggerExplanation(); true }                           // Granite tooltip

            // L1 = toggle Granite tooltip on/off
            KeyEvent.KEYCODE_BUTTON_L1 ->
                { toggleLlm(); true }

            // R1 = cycle to next Granite model slot
            KeyEvent.KEYCODE_BUTTON_R1 ->
                { cycleLlmModel(); true }

            KeyEvent.KEYCODE_BUTTON_L2 ->
                super.onKeyEvent(event)                                  // passthrough

            KeyEvent.KEYCODE_BUTTON_R2 ->
                { performGlobalAction(GLOBAL_ACTION_HOME); true }

            // Start = clipboard copy only (no launch)
            KeyEvent.KEYCODE_BUTTON_START ->
                { copyCardToClipboard(); true }

            else -> {
                if (Prefs.getDebugMode(this))
                    toast("Unmapped key: ${KeyEvent.keyCodeToString(event.keyCode)}")
                super.onKeyEvent(event)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Start: copy card + explanation prompt to clipboard (no AI launch)
    // ──────────────────────────────────────────────────────────────────────────

    private fun copyCardToClipboard() {
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != ANKI_PACKAGE) {
            toast("Open AnkiDroid first")
            return
        }
        val webViewNode = findNodeByClass(root, "android.webkit.WebView")
        if (webViewNode == null) {
            toast("Card content not found – try again")
            return
        }
        val sb = StringBuilder()
        collectText(webViewNode, sb)
        val cleaned = cleanText(sb.toString())
        if (cleaned.isBlank()) {
            toast("Nothing to copy")
            return
        }
        val suffix = Prefs.getExplanationSuffix(this)
        val finalText = if (suffix.isNotBlank()) "$cleaned\n\n$suffix" else cleaned
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("anki_card", finalText))
        toast("Card + explanation prompt copied ✓")
        if (overlayShown) updateOverlayText()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SELECT: cycle AI app target (ChatGPT → Claude → Gemini → ChatGPT …)
    // ──────────────────────────────────────────────────────────────────────────

    private fun cycleAiTarget() {
        val next = AiTarget.next(Prefs.getTarget(this))
        Prefs.setTarget(this, next)
        toast("Y opens: ${next.label}")
        if (overlayShown) updateOverlayText() else showOverlay()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // L1: toggle Granite tooltip on/off
    // ──────────────────────────────────────────────────────────────────────────

    private fun toggleLlm() {
        val next = !Prefs.getLlmEnabled(this)
        Prefs.setLlmEnabled(this, next)
        val state = if (next) "ON ✓" else "OFF ✗"
        toast("Granite tooltip: $state (${Prefs.getActiveSlot(this).label})")
        if (!next && ::tooltipOverlay.isInitialized && tooltipOverlay.isShowing) tooltipOverlay.hide()
        if (overlayShown) updateOverlayText() else showOverlay()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // R1: cycle Granite model slot
    // ──────────────────────────────────────────────────────────────────────────

    private fun cycleLlmModel() {
        val next = GraniteSlot.next(Prefs.getActiveSlot(this))
        Prefs.setActiveSlot(this, next)
        val enabledState = if (Prefs.getLlmEnabled(this)) "ON" else "OFF"
        val configured = LlmManager.isSlotConfigured(this, next)
        val suffix = if (!configured) " (no file set yet - open AnkiCopy)" else ""
        toast("Model: ${next.label} ($enabledState)$suffix")
        if (overlayShown) updateOverlayText() else showOverlay()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // X: bring Anki to fullscreen
    // ──────────────────────────────────────────────────────────────────────────

    private fun bringAnkiToFullscreen() {
        val intent = packageManager.getLaunchIntentForPackage(ANKI_PACKAGE)
        if (intent == null) { toast("AnkiDroid not installed"); return }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Y: split screen AnkiDroid + selected AI
    // ──────────────────────────────────────────────────────────────────────────

    private fun openSplitScreen(target: AiTarget) {
        val ankiIntent = packageManager.getLaunchIntentForPackage(ANKI_PACKAGE)
        val aiIntent   = packageManager.getLaunchIntentForPackage(target.packageName)
        if (ankiIntent == null) { toast("AnkiDroid not installed"); return }
        if (aiIntent   == null) { toast("${target.label} not installed"); return }
        ankiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(ankiIntent)
        mainHandler.postDelayed({
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                aiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            else
                aiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(aiIntent)
        }, 400L)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // D-pad Up/Down: very slow controlled scroll
    // ──────────────────────────────────────────────────────────────────────────

    private fun controlledScroll(down: Boolean) {
        val dm    = resources.displayMetrics
        val width = dm.widthPixels
        val height= dm.heightPixels
        val startY = if (down) height * 0.78f else height * 0.22f
        val endY   = if (down) height * 0.28f else height * 0.72f
        val x      = width * 0.5f
        val path   = Path().apply { moveTo(x, startY); lineTo(x, endY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, SCROLL_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, null, null)
        if (!dispatched && Prefs.getDebugMode(this))
            toast("Scroll gesture failed to dispatch")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Status overlay (top-right corner text, separate from the tooltip card)
    // ──────────────────────────────────────────────────────────────────────────

    private fun overlayText(): String {
        val ai    = Prefs.getTarget(this).label
        val model = Prefs.getActiveSlot(this).label
        val llm   = if (Prefs.getLlmEnabled(this)) "ON" else "OFF"
        return "AI:$ai | Granite:$model $llm"
    }

    private fun showOverlay() {
        if (statusOverlayView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val tv = TextView(this).apply {
            text = overlayText()
            setBackgroundColor(0xCC0d0d14.toInt())
            setTextColor(0xFFe0e0f0.toInt())
            textSize = 11f
            setPadding(14, 6, 14, 6)
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16; y = 48
        }
        wm.addView(tv, params)
        statusOverlayView = tv
        overlayShown = true
    }

    private fun updateOverlayText() {
        statusOverlayView?.text = overlayText()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        val view = statusOverlayView
        if (view != null) {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
            statusOverlayView = null
            overlayShown = false
        }
        if (::tooltipOverlay.isInitialized && tooltipOverlay.isShowing) tooltipOverlay.hide()
        LlmManager.unloadAll()
        return super.onUnbind(intent)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Node tree helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun findNodeByClass(node: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        if (node.className == className) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByClass(child, className)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun collectText(node: AccessibilityNodeInfo, out: StringBuilder) {
        val nodeText = node.text
        if (!nodeText.isNullOrBlank()) out.append(nodeText).append("\n")
        val desc = node.contentDescription
        if (!desc.isNullOrBlank() && desc.toString() != nodeText?.toString()) out.append(desc).append("\n")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out)
            child.recycle()
        }
    }

    private fun cleanText(raw: String): String {
        val deduped = mutableListOf<String>()
        for (line in raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }) {
            if (deduped.isEmpty() || deduped.last() != line) deduped.add(line)
        }
        return deduped.joinToString("\n")
    }

    private fun toast(msg: String) {
        mainHandler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }
}
