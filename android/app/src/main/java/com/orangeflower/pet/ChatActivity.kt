package com.orangeflower.pet

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Bottom chat panel. Translucent, so the pet stays visible above it. */
class ChatActivity : Activity() {

    private lateinit var panel: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var title: TextView
    private lateinit var clearButton: Button

    private lateinit var card: Card
    private var history = mutableListOf<Msg>()
    private var waiting = false
    private var pending: TextView? = null
    private var clearArmed = false

    private val ink = Color.parseColor("#3B2620")
    private val muted = Color.parseColor("#7A665B")
    private val accent = Color.parseColor("#C9561C")
    private val paper = Color.parseColor("#FFF9F3")
    private val herBubble = Color.parseColor("#FFFFFF")
    private val myBubble = Color.parseColor("#FBE2D1")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setDecorFitsSystemWindows(false)

        val root = FrameLayout(this).apply { setOnClickListener { finish() } }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                setColor(paper)
                val r = dp(20).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }
        val height = (resources.displayMetrics.heightPixels * 0.56f).roundToInt()
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, height, Gravity.BOTTOM))

        // header
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        title = TextView(this).apply { textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(ink) }
        header.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(smallButton("换角色") { startActivity(Intent(this, CardsActivity::class.java)) })
        clearButton = smallButton("清空") { onClear() }
        header.addView(clearButton)
        panel.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // messages
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // input row
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "和她说点什么……"
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(muted)
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.WHITE); setStroke(dp(1), Color.parseColor("#E6D8CC")) }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEND || (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) {
                    onSend(); true
                } else false
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        sendButton = Button(this).apply {
            text = "发送"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(accent) }
            setOnClickListener { onSend() }
        }
        row.addView(sendButton, LinearLayout.LayoutParams(dp(72), dp(40)).apply { leftMargin = dp(8) })
        panel.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Keyboard: use IME insets where available, and also measure the visible frame, because
        // translucent windows on some phones are neither resized nor given IME insets.
        var insetBottom = 0
        fun applyBottom() {
            val r = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(r)
            val covered = (root.rootView.height - r.bottom).coerceAtLeast(0)
            val bottom = maxOf(insetBottom, covered)
            if (panel.paddingBottom != dp(12) + bottom) panel.setPadding(dp(16), dp(14), dp(16), dp(12) + bottom)
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            insetBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.ime() or WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            applyBottom()
            insets
        }
        root.viewTreeObserver.addOnGlobalLayoutListener { applyBottom() }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        card = CardStore.active(this)
        history = ChatStore.load(this, card.id)
        render()
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(accent)
        background = null
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }

    private fun render() {
        title.text = card.name
        list.removeAllViews()
        pending = null
        if (!ChatConfig.isReady(this)) {
            addBubble("还没有填写聊天用的 API。在主界面的“聊天设置”里填好 API Key，她就能说话了。", mine = false, note = true)
            list.addView(Button(this).apply {
                text = "去填写"
                isAllCaps = false
                setTextColor(accent)
                setOnClickListener { startActivity(Intent(this@ChatActivity, MainActivity::class.java)); finish() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        if (history.isEmpty() && card.greeting.isNotBlank()) {
            addBubble(card.greeting.replace("{{user}}", ChatConfig.userName(this)), mine = false)
        }
        history.forEach {
            when {
                it.role == "user" && it.content.startsWith(ACTION_PREFIX) ->
                    addBubble("（你" + it.content.removePrefix(ACTION_PREFIX) + "）", mine = true, note = true)
                it.role == "user" -> addBubble(it.content, mine = true)
                else -> addBubble(Emotion.strip(it.content), mine = false)
            }
        }
        if (waiting) pending = addBubble("……", mine = false, note = true)
        scrollToEnd()
    }

    private fun addBubble(text: String, mine: Boolean, note: Boolean = false): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(if (note) muted else ink)
            setTextIsSelectable(true)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).roundToInt()
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (mine) myBubble else herBubble)
                if (!mine) setStroke(dp(1), Color.parseColor("#EADBCF"))
            }
        }
        list.addView(tv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            topMargin = dp(6)
        })
        return tv
    }

    private fun scrollToEnd() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    private fun onSend() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || waiting) return
        if (!ChatConfig.isReady(this)) {
            render()
            return
        }
        input.setText("")
        val cardNow = card
        history.add(Msg("user", text))
        ChatStore.save(this, cardNow.id, history)
        addBubble(text, mine = true)
        waiting = true
        sendButton.isEnabled = false
        pending = addBubble("……", mine = false, note = true)
        scrollToEnd()
        PetService.thinking()

        val app = applicationContext
        ChatClient.send(this, cardNow, history) { result ->
            waiting = false
            result.onSuccess { reply ->
                val h = ChatStore.load(app, cardNow.id)
                h.add(Msg("assistant", reply))
                ChatStore.save(app, cardNow.id, h)
                PetService.speak(reply)
                if (!isDestroyed && card.id == cardNow.id) {
                    history = h
                    pending?.let { it.text = Emotion.strip(reply); it.setTextColor(ink) }
                    pending = null
                }
            }.onFailure { e ->
                PetService.speak("……好像没连上。")
                if (!isDestroyed) {
                    pending?.text = "没连上：${e.message ?: e.javaClass.simpleName}\n可以检查一下聊天设置里的地址、模型和 Key。"
                    pending = null
                }
            }
            if (!isDestroyed) {
                sendButton.isEnabled = true
                scrollToEnd()
            }
        }
    }

    private fun onClear() {
        if (!clearArmed) {
            clearArmed = true
            clearButton.text = "确认清空？"
            clearButton.postDelayed({ clearArmed = false; clearButton.text = "清空" }, 3000)
            return
        }
        clearArmed = false
        clearButton.text = "清空"
        ChatStore.clear(this, card.id)
        history.clear()
        render()
    }
}
