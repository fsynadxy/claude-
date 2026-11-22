package com.orangeflower.pet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Shared look for the speech bubble and the input bubble. */
object BubbleStyle {
    val fill = Color.parseColor("#F22B211D")
    val stroke = Color.parseColor("#F3E2D2")
    val text = Color.parseColor("#FFF8F0")
    val hint = Color.parseColor("#B8A79B")

    fun card(density: Float) = GradientDrawable().apply {
        cornerRadius = 16 * density
        setColor(fill)
        setStroke((1.5f * density).roundToInt(), stroke)
    }
}

/** A downward (or upward) pointing tail drawn under a bubble, aimed at [tipX]. */
class BubbleTail(context: Context) : View(context) {
    var tipX = 0f
        set(v) { field = v; invalidate() }
    var pointsUp = false
        set(v) { field = v; invalidate() }
    private val d = context.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BubbleStyle.fill }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BubbleStyle.stroke; style = Paint.Style.STROKE; strokeWidth = 1.5f * d; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val half = 9 * d
        val lo = half + 12 * d
        val hi = width - half - 12 * d
        val x = if (hi >= lo) tipX.coerceIn(lo, hi) else width / 2f   // very narrow bubble: centre the tail
        path.reset()
        if (pointsUp) {
            path.moveTo(x - half, h + 1); path.lineTo(x, 1f); path.lineTo(x + half, h + 1)
        } else {
            path.moveTo(x - half, -1f); path.lineTo(x, h - 1); path.lineTo(x + half, -1f)
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
    }
}

private class MaxHeightScrollView(context: Context) : ScrollView(context) {
    var maxHeightPx = Int.MAX_VALUE
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
    }
}

/**
 * Floating speech bubble above the pet. Shows short lines instantly, and streams chat replies
 * sentence by sentence with a typewriter effect (paced by the voice when it is on).
 */
@SuppressLint("ViewConstructor")
class SpeechBubble(context: Context, private val wm: WindowManager) {

    private val d = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private val textView = TextView(context).apply {
        setTextColor(BubbleStyle.text)
        textSize = 15f
        setLineSpacing(3 * d, 1f)
    }
    private val scroll = MaxHeightScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(textView)
    }
    private val card = LinearLayout(context).apply {
        background = BubbleStyle.card(d)
        minimumWidth = (72 * d).roundToInt()
        setPadding((14 * d).roundToInt(), (10 * d).roundToInt(), (14 * d).roundToInt(), (10 * d).roundToInt())
        addView(scroll)
    }
    private val tailTop = BubbleTail(context).apply { pointsUp = true; visibility = View.GONE }
    private val tail = BubbleTail(context)
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(tailTop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (10 * d).roundToInt()))
        addView(card, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(tail, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (10 * d).roundToInt()))
        visibility = View.GONE
    }

    private val lp = WindowManager.LayoutParams(
        WRAP_CONTENT, WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private var attached = false
    private var target = ""
    private var shown = 0f
    private var rate = 14f              // characters per second while typing
    private var hideAt = Long.MAX_VALUE
    /** True while a chat reply is being streamed (voice or typing). */
    var speaking = false
        private set
    var onSkip: (() -> Unit)? = null

    private var anchorX = 0
    private var headTop = 0
    private var feetY = 0
    private var screenW = 0
    private var topInset = 0

    private var lastTick = SystemClock.uptimeMillis()
    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = ((now - lastTick) / 1000f).coerceIn(0f, 0.1f)
            lastTick = now
            if (shown < target.length) {
                shown = (shown + rate * dt).coerceAtMost(target.length.toFloat())
                setVisibleText()
                if (shown >= target.length && !speaking) scheduleHide()
            } else if (now >= hideAt) {
                hide()
            }
            if (root.visibility == View.VISIBLE) handler.postDelayed(this, 30)
        }
    }

    init {
        root.setOnClickListener {
            if (speaking) {
                // skip the voice; the reply's remaining text is filled in by the done callback
                onSkip?.invoke()
            }
            if (shown < target.length) {
                shown = target.length.toFloat()
                setVisibleText()
                if (!speaking) scheduleHide()
            } else if (!speaking) {
                hide()
            }
        }
    }

    fun attach() {
        if (attached) return
        try {
            wm.addView(root, lp)
            attached = true
        } catch (_: Exception) {
        }
    }

    fun detach() {
        handler.removeCallbacksAndMessages(null)
        if (!attached) return
        try {
            wm.removeView(root)
        } catch (_: Exception) {
        }
        attached = false
    }

    /** A short line, shown at once for [seconds]. Ignored while a reply is streaming. */
    fun showLine(text: String, seconds: Float) {
        if (speaking) return
        target = text
        shown = text.length.toFloat()
        setVisibleText()
        hideAt = SystemClock.uptimeMillis() + (seconds * 1000).toLong()
        show()
    }

    /** Starts streaming a reply; call [append] per sentence and [finish] at the end. */
    fun begin() {
        speaking = true
        target = ""
        shown = 0f
        hideAt = Long.MAX_VALUE
        setVisibleText()
        show()
    }

    /** Adds a sentence, typed out over about [seconds]. */
    fun append(sentence: String, seconds: Float) {
        if (shown < target.length) shown = target.length.toFloat()   // finish the previous sentence first
        target += sentence
        rate = (sentence.length / (seconds * 0.85f).coerceAtLeast(0.25f)).coerceIn(4f, 60f)
        show()
    }

    fun finish() {
        speaking = false
        if (shown >= target.length) scheduleHide()
    }

    fun hide() {
        speaking = false
        root.visibility = View.GONE
        hideAt = Long.MAX_VALUE
    }

    private fun scheduleHide() {
        val seconds = (target.length * 0.07f).coerceIn(3.5f, 12f)
        hideAt = SystemClock.uptimeMillis() + (seconds * 1000).toLong()
    }

    private fun show() {
        if (root.visibility != View.VISIBLE) {
            root.visibility = View.VISIBLE
            lastTick = SystemClock.uptimeMillis()
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }
        relayout()
    }

    private fun setVisibleText() {
        val n = shown.toInt().coerceIn(0, target.length)
        textView.text = if (target.isEmpty()) "……" else ActionText.dim(target.substring(0, n), BubbleStyle.hint)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        relayout()
    }

    /** Called every frame with the pet's position (screen px). */
    fun follow(centerX: Int, headTopY: Int, feetYpx: Int, screenWidth: Int, topInsetPx: Int) {
        anchorX = centerX
        headTop = headTopY
        feetY = feetYpx
        screenW = screenWidth
        topInset = topInsetPx
        if (root.visibility == View.VISIBLE) relayout()
    }

    private fun relayout() {
        if (!attached || screenW == 0) return
        val maxW = minOf((screenW * 0.82f).roundToInt(), (340 * d).roundToInt())
        textView.maxWidth = maxW - card.paddingLeft - card.paddingRight
        scroll.maxHeightPx = (260 * d).roundToInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = root.measuredWidth
        val h = root.measuredHeight
        val margin = (8 * d).roundToInt()
        val x = (anchorX - w / 2).coerceIn(margin, (screenW - w - margin).coerceAtLeast(margin))
        var y = headTop - h + (4 * d).roundToInt()
        val below = y < topInset + margin
        if (below) y = feetY + margin
        tail.visibility = if (below) View.GONE else View.VISIBLE
        tailTop.visibility = if (below) View.VISIBLE else View.GONE
        tail.tipX = (anchorX - x).toFloat()
        tailTop.tipX = (anchorX - x).toFloat()
        if (lp.x != x || lp.y != y) {
            lp.x = x
            lp.y = y
            try {
                wm.updateViewLayout(root, lp)
            } catch (_: Exception) {
            }
        }
    }
}
