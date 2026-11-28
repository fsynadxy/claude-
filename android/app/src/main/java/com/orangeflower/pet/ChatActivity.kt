package com.orangeflower.pet

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
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
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Bottom chat panel. Translucent, so the pet stays visible above it. */
class ChatActivity : Activity() {

    private lateinit var panel: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var title: TextView
    private lateinit var subtitle: TextView

    private lateinit var card: Card
    private var sessionId = ""
    private var history = mutableListOf<Msg>()
    /** Conversation a reply is on its way for (only one at a time). */
    private var waitingSid: String? = null
    private val waiting get() = waitingSid != null
    private var pending: TextView? = null

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
        subtitle = TextView(this).apply { textSize = 12f; setTextColor(muted); setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(subtitle)
            setOnClickListener { showSessions() }
        }
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(smallButton("新话题") { onNewTopic() })
        header.addView(smallButton("对话") { showSessions() })
        header.addView(smallButton("换角色") { startActivity(Intent(this, CardsActivity::class.java)) })
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
        reload()
    }

    private fun reload() {
        card = CardStore.active(this)
        sessionId = ChatStore.currentId(this, card.id)
        history = ChatStore.load(this, card.id, sessionId)
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
        subtitle.text = ChatStore.current(this, card.id).shownTitle() + "  ▾"
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
                else -> addBubble(Emotion.strip(it.content), mine = false).also { tv -> tv.text = ActionText.dim(tv.text, muted) }
            }
        }
        if (waitingSid == sessionId) pending = addBubble("……", mine = false, note = true)
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
        addBubble(text, mine = true)
        sendButton.isEnabled = false
        pending = addBubble("……", mine = false, note = true)
        scrollToEnd()
        PetService.thinking()

        val app = applicationContext
        val sid = ChatClient.ask(this, cardNow, text) { result ->
            val sameView = !isDestroyed && card.id == cardNow.id && sessionId == waitingSid
            waitingSid = null
            result.onSuccess { reply ->
                PetService.speak(reply)
                if (sameView) {
                    history = ChatStore.load(app, cardNow.id, sessionId)
                    pending?.let { it.text = ActionText.dim(Emotion.strip(reply), muted); it.setTextColor(ink) }
                    pending = null
                }
            }.onFailure { e ->
                PetService.speak("……好像没连上。")
                if (sameView) {
                    pending?.text = "没连上：${e.message ?: e.javaClass.simpleName}\n可以检查一下聊天设置里的地址、模型和 Key。"
                    pending = null
                }
            }
            if (!isDestroyed) {
                sendButton.isEnabled = true
                scrollToEnd()
            }
        }
        waitingSid = sid
        history = ChatStore.load(this, cardNow.id, sid)
        subtitle.text = ChatStore.current(this, cardNow.id).shownTitle() + "  ▾"
    }

    /** Starts a fresh conversation; the old ones stay in the list. */
    private fun onNewTopic() {
        val before = sessionId
        ChatStore.startNew(this, card.id)
        reload()
        Toast.makeText(this, if (before == sessionId) "已经是新话题啦" else "开始新话题，之前的对话还在“对话”里", Toast.LENGTH_SHORT).show()
    }

    private fun showSessions() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(4)) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("${card.name} 的对话")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("开新话题") { _, _ -> onNewTopic() }
            .setNegativeButton("关闭", null)
            .create()

        fun fill() {
            box.removeAllViews()
            val now = System.currentTimeMillis()
            val cur = ChatStore.currentId(this, card.id)
            ChatStore.sessions(this, card.id).forEach { session ->
                val isCur = session.id == cur
                val row = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(4), dp(10))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(if (isCur) myBubble else Color.TRANSPARENT)
                    }
                }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this).apply {
                    text = session.shownTitle()
                    textSize = 15f
                    setTextColor(ink)
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    if (isCur) setTypeface(typeface, Typeface.BOLD)
                })
                val n = ChatStore.userCount(this, card.id, session.id)
                val time = DateUtils.getRelativeTimeSpanString(session.updated, now, DateUtils.MINUTE_IN_MILLIS)
                col.addView(TextView(this).apply {
                    text = listOfNotNull(time, if (n > 0) "$n 句" else "还没开始", if (isCur) "正在聊" else null).joinToString(" · ")
                    textSize = 12f
                    setTextColor(muted)
                })
                row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                val more = TextView(this).apply {
                    text = "⋯"
                    textSize = 20f
                    setTextColor(muted)
                    gravity = Gravity.CENTER
                    contentDescription = "改名或删除"
                }
                more.setOnClickListener {
                    PopupMenu(this, more).apply {
                        menu.add(0, 1, 0, "改名")
                        menu.add(0, 2, 1, "删除")
                        setOnMenuItemClickListener { item ->
                            if (item.itemId == 1) renameSession(session) { fill(); render() }
                            else deleteSession(session) { fill(); reload() }
                            true
                        }
                    }.show()
                }
                row.addView(more, LinearLayout.LayoutParams(dp(44), dp(44)))
                row.setOnClickListener {
                    ChatStore.select(this, card.id, session.id)
                    dialog.dismiss()
                    reload()
                }
                box.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
            }
            box.addView(TextView(this).apply {
                text = "点一下切换到那段对话；⋯ 可以改名或删除。桌面上的她和头顶输入框都接着“正在聊”的那段说。"
                textSize = 12f
                setTextColor(muted)
                setPadding(dp(4), dp(10), dp(4), 0)
            })
        }
        fill()
        dialog.show()
    }

    private fun renameSession(session: Session, after: () -> Unit) {
        val field = EditText(this).apply {
            setText(if (session.named) session.title else "")
            hint = session.title.ifBlank { "新对话" }
            setSingleLine(true)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("改个名字")
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field) })
            .setPositiveButton("好") { _, _ -> ChatStore.rename(this, card.id, session.id, field.text.toString()); after() }
            .setNeutralButton("用自动标题") { _, _ -> ChatStore.rename(this, card.id, session.id, ""); after() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSession(session: Session, after: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("删除「${session.shownTitle()}」？")
            .setMessage("这段对话会被删除，不能恢复。其他对话不受影响。")
            .setPositiveButton("删除") { _, _ -> ChatStore.delete(this, card.id, session.id); after() }
            .setNegativeButton("取消", null)
            .show()
    }
}
