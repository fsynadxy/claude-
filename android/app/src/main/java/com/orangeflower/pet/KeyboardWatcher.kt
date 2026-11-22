package com.orangeflower.pet

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Detects the soft keyboard from an overlay. A 1 px wide, full-height, invisible probe window asks to sit
 * behind the input method (FLAG_ALT_FOCUSABLE_IM) with ADJUST_RESIZE, so it shrinks when a keyboard opens.
 * On Android 11+ the IME insets are used as well. Reports the keyboard's top edge in screen pixels, or null.
 */
class KeyboardWatcher(
    context: Context,
    private val wm: WindowManager,
    private val onChange: (Int?) -> Unit,
) {
    private val probe = View(context)
    private val minKeyboard = (120 * context.resources.displayMetrics.density).toInt()
    private var fullBottom = 0
    private var reported: Int? = null
    private var attached = false

    private val lp = WindowManager.LayoutParams(
        1, ViewGroup.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
        PixelFormat.TRANSPARENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        @Suppress("DEPRECATION")
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { evaluate(null) }

    fun start() {
        try {
            wm.addView(probe, lp)
            attached = true
        } catch (e: Exception) {
            return
        }
        probe.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            probe.setOnApplyWindowInsetsListener { _, insets ->
                val ime = if (insets.isVisible(WindowInsets.Type.ime())) insets.getInsets(WindowInsets.Type.ime()).bottom else 0
                evaluate(ime)
                insets
            }
        }
    }

    fun stop() {
        if (!attached) return
        probe.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        try {
            wm.removeView(probe)
        } catch (_: Exception) {
        }
        attached = false
    }

    private fun evaluate(imeInset: Int?) {
        if (!probe.isAttachedToWindow || probe.height == 0) return
        val loc = IntArray(2)
        probe.getLocationOnScreen(loc)
        val bottom = loc[1] + probe.height
        if (bottom > fullBottom) fullBottom = bottom
        val top: Int? = when {
            imeInset != null && imeInset >= minKeyboard -> bottom - imeInset
            fullBottom - bottom >= minKeyboard -> bottom
            else -> null
        }
        val changed = (top == null) != (reported == null) ||
            (top != null && reported != null && kotlin.math.abs(top - reported!!) > minKeyboard / 3)
        if (changed) {
            reported = top
            onChange(top)
        }
    }
}
