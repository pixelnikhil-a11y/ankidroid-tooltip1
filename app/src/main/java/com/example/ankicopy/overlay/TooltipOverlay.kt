package com.example.ankicopy.overlay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.ankicopy.R

/**
 * Floating "explanation" tooltip drawn as a system overlay by the
 * accessibility service. Deliberately mirrors the desktop LLM Hover Tooltip
 * Anki add-on's look and motion:
 *
 *   - dark rounded card, header row with title + close (×)
 *   - fade-in-from-above entrance (~120ms, decelerate) matching the
 *     add-on's `@keyframes llmFadeIn`
 *   - three-dot loading pulse while the on-device model is generating,
 *     matching `@keyframes llmPulse` (scale + opacity, staggered 150ms)
 *   - result state: scrollable body, capped height, selectable text
 *   - error state: same card, red-tinted body text
 *
 * Unlike the desktop version this isn't injected into a WebView - it's a
 * real system window positioned near the long-press point, since AnkiCopy
 * is a separate app from AnkiDroid and can't reach into its WebView's DOM.
 */
class TooltipOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null
    private var pulseAnimators: List<ValueAnimator> = emptyList()
    private var attached = false

    private val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    else
        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

    /** Shows the loading (three-dot pulse) state anchored near (x, y) in screen coordinates. */
    fun showLoading(x: Int, y: Int) {
        ensureAttached()
        val root = rootView ?: return

        root.findViewById<View>(R.id.tooltipHeaderText).let {
            (it as TextView).text = "EXPLAINING"
        }
        root.findViewById<View>(R.id.tooltipLoadingRow).visibility = View.VISIBLE
        root.findViewById<View>(R.id.tooltipBodyScroll).visibility = View.GONE

        startPulse(root)
        positionNear(x, y)
        playFadeIn(root)
    }

    /** Swaps to the result state - stops the pulse, shows the explanation text. */
    fun showResult(text: String) {
        val root = rootView ?: return
        stopPulse()
        root.findViewById<View>(R.id.tooltipHeaderText).let {
            (it as TextView).text = "EXPLANATION"
        }
        root.findViewById<View>(R.id.tooltipLoadingRow).visibility = View.GONE
        val scroll = root.findViewById<ScrollView>(R.id.tooltipBodyScroll)
        val body = root.findViewById<TextView>(R.id.tooltipBodyText)
        body.setTextColor(0xFFf0f0f2.toInt())
        body.text = text
        scroll.visibility = View.VISIBLE
        // Re-run the position pass now that real content has a real height,
        // since the loading row and the result body rarely match heights -
        // without this the card can visually "jump" if it was near a screen
        // edge and the new size would overflow.
        repositionForCurrentSize()
    }

    /** Swaps to the error state - same layout, red-tinted body text. */
    fun showError(message: String) {
        val root = rootView ?: return
        stopPulse()
        root.findViewById<View>(R.id.tooltipHeaderText).let {
            (it as TextView).text = "ERROR"
        }
        root.findViewById<View>(R.id.tooltipLoadingRow).visibility = View.GONE
        val scroll = root.findViewById<ScrollView>(R.id.tooltipBodyScroll)
        val body = root.findViewById<TextView>(R.id.tooltipBodyText)
        body.setTextColor(0xFFff8686.toInt())
        body.text = message
        scroll.visibility = View.VISIBLE
        repositionForCurrentSize()
    }

    fun hide() {
        stopPulse()
        val root = rootView
        if (root != null && attached) {
            try {
                windowManager.removeView(root)
            } catch (e: Exception) {
                // Already detached (e.g. service torn down mid-animation) - ignore.
            }
        }
        attached = false
        rootView = null
    }

    val isShowing: Boolean get() = attached

    // ── internals ────────────────────────────────────────────────────────

    private fun ensureAttached() {
        if (attached) return
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.tooltip_overlay, null)

        view.findViewById<View>(R.id.tooltipCloseBtn).setOnClickListener { hide() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        windowManager.addView(view, params)
        rootView = view
        attached = true
    }

    /** Positions the card's top-left just below-right of the tap point, then
     *  nudges back on-screen if it would overflow - same logic as the
     *  desktop tooltip's `positionTooltip()` JS function. */
    private var lastAnchorX = 0
    private var lastAnchorY = 0

    private fun positionNear(x: Int, y: Int) {
        lastAnchorX = x
        lastAnchorY = y
        val root = rootView ?: return
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return

        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        // Card hasn't been measured yet on first layout pass; post so we
        // read real dimensions once the view has actually inflated content.
        root.post {
            val cardW = root.width.takeIf { it > 0 } ?: 260
            val cardH = root.height.takeIf { it > 0 } ?: 80

            var left = x + 12
            var top = y + 8

            if (left + cardW > screenW - 10) left = (screenW - cardW - 10).coerceAtLeast(10)
            if (left < 10) left = 10
            if (top + cardH > screenH - 10) {
                val above = y - cardH - 8
                top = if (above > 10) above else (screenH - cardH - 10).coerceAtLeast(10)
            }

            params.x = left
            params.y = top
            try {
                windowManager.updateViewLayout(root, params)
            } catch (e: Exception) {
                // View may have been removed concurrently (hide() raced this
                // callback) - safe to ignore, nothing left to reposition.
            }
        }
    }

    private fun repositionForCurrentSize() {
        positionNear(lastAnchorX, lastAnchorY)
    }

    private fun playFadeIn(root: View) {
        val anim = AnimationUtils.loadAnimation(context, R.anim.tooltip_fade_in)
        root.startAnimation(anim)
    }

    /** Three dots pulsing in sequence, matching the CSS keyframes:
     *  0%/80%/100% -> opacity .3, scale .8 ; 40% -> opacity 1, scale 1,
     *  each dot delayed by 150ms from the previous. Uses explicit Keyframes
     *  so the timing curve matches CSS's percentage-based keyframes rather
     *  than a naive linear ofFloat(0.8, 1, 0.8), which would peak at the
     *  midpoint (50%) instead of 40% and hold a different shape overall. */
    private fun startPulse(root: View) {
        stopPulse()
        val dotIds = listOf(R.id.dot1, R.id.dot2, R.id.dot3)
        val animators = dotIds.mapIndexed { index, id ->
            val dot = root.findViewById<View>(id)

            fun keyframeAnimator(property: android.util.Property<View, Float>, low: Float, high: Float): ObjectAnimator {
                val kf0 = android.animation.Keyframe.ofFloat(0f, low)
                val kf40 = android.animation.Keyframe.ofFloat(0.4f, high)
                val kf80 = android.animation.Keyframe.ofFloat(0.8f, low)
                val kf100 = android.animation.Keyframe.ofFloat(1f, low)
                val holder = android.animation.PropertyValuesHolder.ofKeyframe(property, kf0, kf40, kf80, kf100)
                return ObjectAnimator.ofPropertyValuesHolder(dot, holder)
            }

            val scaleX = keyframeAnimator(View.SCALE_X, 0.8f, 1f)
            val scaleY = keyframeAnimator(View.SCALE_Y, 0.8f, 1f)
            val alpha = keyframeAnimator(View.ALPHA, 0.3f, 1f)
            listOf(scaleX, scaleY, alpha).forEach {
                it.duration = 1000L
                it.repeatCount = ValueAnimator.INFINITE
                it.startDelay = index * 150L
                it.start()
            }
            listOf(scaleX, scaleY, alpha)
        }.flatten()
        pulseAnimators = animators
    }

    private fun stopPulse() {
        pulseAnimators.forEach { it.cancel() }
        pulseAnimators = emptyList()
    }
}
