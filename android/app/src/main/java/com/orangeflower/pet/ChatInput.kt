package com.orangeflower.pet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The speech-bubble shaped input that pops up above the pet when she is tapped.
 * It lives in a transparent full-screen focusable overlay, which is what lets it raise the
 * keyboard and measure exactly where the keyboard is. A tap outside closes it; a tap on the
 * pet is passed back as a poke.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ChatInput(
    private val context: Context,
    private val wm: WindowManager,
    private val petRect: () -> Rect,
    private val onSend: (String) -> Unit,
    private val onExpand: () -> Unit,
    private val onPetTap: () -> Unit,
    private val onKeyboard: (Int?) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val d = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).roundToInt()

    private val edit = EditText(context).apply {
        hint = "输入想说的话…"
        setTextColor(BubbleStyle.text)
        setHintTextColor(BubbleStyle.hint)
        textSize = 16f
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), Color.parseColor("#806E62"))
        }
        setPadding(dp(12), dp(8), dp(12), dp(8))
        maxLines = 4
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_ACTION_SEND
        setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
    }

    /** Starts a new conversation; the earlier ones stay in the chat panel's list. */
    private val newTopic = TextView(context).apply {
        text = "＋"
        textSize = 20f
        setTextColor(BubbleStyle.hint)
        gravity = Gravity.CENTER
        contentDescription = "开新话题"
        setOnClickListener {
            val card = CardStore.active(context)
            val before = ChatStore.currentId(context, card.id)
            val now = ChatStore.startNew(context, card.id)
            edit.hint = if (now == before) "已经是新话题啦，说点什么…" else "新话题：想聊点什么…"
            setTextColor(BubbleStyle.text)
        }
    }

    private val expand = TextView(context).apply {
        text = "⤢"
        textSize = 20f
        setTextColor(BubbleStyle.hint)
        gravity = Gravity.CENTER
        contentDescription = "打开聊天记录"
        setOnClickListener { close(); onExpand() }
    }

    private val sendButton = TextView(context).apply {
        text = "发送"
        textSize = 15f
        setTextColor(Color.parseColor("#2B211D"))
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#FFF3E6")) }
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { send() }
    }

    private val closeButton = TextView(context).apply {
        text = "×"
        textSize = 18f
        setTextColor(BubbleStyle.text)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(BubbleStyle.fill)
            setStroke((1.5f * d).roundToInt(), BubbleStyle.stroke)
        }
        contentDescription = "关闭"
        setOnClickListener { close() }
    }

    private val card = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = BubbleStyle.card(d)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        addView(edit, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(newTopic, LinearLayout.LayoutParams(dp(34), dp(36)).apply { leftMargin = dp(2) })
        addView(expand, LinearLayout.LayoutParams(dp(34), dp(36)))
        addView(sendButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(4) })
    }

    private val tail = BubbleTail(context)

    private val bubble = FrameLayout(context).apply {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(tail, LinearLayout.LayoutParams(MATCH_PARENT, dp(12)))
        }
        addView(column, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10); rightMargin = dp(10) })
        addView(closeButton, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END))
        isClickable = true   // taps on the bubble itself don't close it
    }

    private val root = object : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                close(); return true
            }
            return super.dispatchKeyEvent(event)
        }
    }.apply {
        addView(bubble, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START))
        setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                if (petRect().contains(e.rawX.toInt(), e.rawY.toInt())) onPetTap() else close()
            }
            true
        }
    }

    private val lp = WindowManager.LayoutParams(
        MATCH_PARENT, MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        @Suppress("DEPRECATION")
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
    }

    private var open = false
    private var maxBottom = 0
    private var lastIme: Int? = -1
    private var lastInset = 0
    private var anchorX = 0
    private var headTop = 0
    private var screenW = 0

    val isOpen get() = open

    fun open(screenWidth: Int) {
        if (open) return
        screenW = screenWidth
        val width = minOf((screenWidth * 0.88f).roundToInt(), dp(380))
        bubble.layoutParams = FrameLayout.LayoutParams(width, WRAP_CONTENT, Gravity.TOP or Gravity.START)
        try {
            wm.addView(root, lp)
        } catch (e: Exception) {
            return
        }
        open = true
        root.viewTreeObserver.addOnGlobalLayoutListener { checkKeyboard(null); place() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { _, insets ->
                val ime = if (insets.isVisible(WindowInsets.Type.ime())) insets.getInsets(WindowInsets.Type.ime()).bottom else 0
                checkKeyboard(ime)
                insets
            }
        }
        edit.requestFocus()
        root.postDelayed({
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }, 120)
    }

    fun close() {
        if (!open) return
        open = false
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(edit.windowToken, 0)
        try {
            wm.removeView(root)
        } catch (_: Exception) {
        }
        onKeyboard(null)
        onClosed()
    }

    private fun send() {
        val text = edit.text.toString().trim()
        if (text.isEmpty()) return
        edit.setText("")
        close()
        onSend(text)
    }

    /** Points the bubble at the pet's head (screen px); called every frame while open. */
    fun setAnchor(centerX: Int, headTopY: Int) {
        anchorX = centerX
        headTop = headTopY
        if (open) place()
    }

    private fun place() {
        if (!open || bubble.width == 0) return
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val margin = dp(8)
        val x = (anchorX - bubble.width / 2).coerceIn(margin, (screenW - bubble.width - margin).coerceAtLeast(margin))
        val y = (headTop - bubble.height).coerceAtLeast(loc[1] + margin)
        bubble.translationX = x.toFloat()
        bubble.translationY = (y - loc[1]).toFloat()
        tail.tipX = (anchorX - x).toFloat() - 0f
    }

    private fun checkKeyboard(imeInset: Int?) {
        if (!open || root.height == 0) return
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val bottom = loc[1] + root.height
        if (bottom > maxBottom) maxBottom = bottom
        if (imeInset != null) lastInset = imeInset
        val inset = imeInset ?: lastInset
        val top: Int? = when {
            inset > dp(100) -> bottom - inset
            maxBottom - bottom > dp(100) -> bottom
            else -> null
        }
        if (top != lastIme) {
            lastIme = top
            onKeyboard(top)
        }
    }
}
