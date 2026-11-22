package com.orangeflower.pet

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var toggle: Button
    private lateinit var status: TextView
    private lateinit var sizeLabel: TextView
    private lateinit var cardLabel: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val ink = Color.parseColor("#3B2620")
    private val muted = Color.parseColor("#7A665B")
    private val accent = Color.parseColor("#C9561C")
    private val paper = Color.parseColor("#FFF9F3")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = paper
        window.navigationBarColor = paper
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(32))
            setBackgroundColor(paper)
        }

        val portrait = ImageView(this).apply {
            assets.open("pet/idle_01.webp").use { setImageBitmap(BitmapFactory.decodeStream(it)) }
            adjustViewBounds = true
        }
        root.addView(portrait, LinearLayout.LayoutParams(dp(200), dp(250)))

        root.addView(TextView(this).apply {
            text = "Clau娘"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ink)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

        toggle = Button(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(accent) }
            setOnClickListener { onToggle() }
        }
        root.addView(toggle, LinearLayout.LayoutParams(dp(220), dp(48)).apply { topMargin = dp(20) })

        sizeLabel = TextView(this).apply { textSize = 14f; setTextColor(ink) }
        root.addView(sizeLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(28) })
        val seek = SeekBar(this).apply {
            max = 100
            progress = ((Prefs.size(this@MainActivity) - 0.6f) / 1.0f * 100).roundToInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = 0.6f + p / 100f
                    updateSizeLabel(v)
                    if (fromUser) {
                        Prefs.setSize(this@MainActivity, v)
                        if (PetService.running) {
                            startService(Intent(this@MainActivity, PetService::class.java).setAction(PetService.ACTION_RESIZE))
                        }
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        updateSizeLabel(Prefs.size(this))
        root.addView(seek, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

        // ---- chat settings
        root.addView(sectionTitle("聊天设置"), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(28) })
        val formatGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbAnthropic = RadioButton(this).apply { id = View.generateViewId(); text = "Anthropic 格式"; setTextColor(ink) }
        val rbOpenAi = RadioButton(this).apply { id = View.generateViewId(); text = "OpenAI 兼容格式"; setTextColor(ink) }
        formatGroup.addView(rbAnthropic)
        formatGroup.addView(rbOpenAi)
        root.addView(formatGroup)
        val baseField = input("接口地址", ChatConfig.base(this), password = false)
        val modelField = input("模型", ChatConfig.model(this), password = false)
        val keyField = input("API Key", ChatConfig.key(this), password = true)
        val userField = input("她怎么称呼你（可空，默认“你”）", ChatConfig.rawUserName(this), password = false)
        if (ChatConfig.format(this) == ChatConfig.FORMAT_OPENAI) rbOpenAi.isChecked = true else rbAnthropic.isChecked = true
        formatGroup.setOnCheckedChangeListener { _, checked ->
            // swap the built-in OpenCode defaults when switching formats, keep anything custom
            val toOpenAi = checked == rbOpenAi.id
            val base = baseField.text.toString().trim()
            val model = modelField.text.toString().trim()
            if (toOpenAi) {
                if (base == ChatConfig.DEFAULT_ANTHROPIC_BASE || base.isEmpty()) baseField.setText(ChatConfig.DEFAULT_OPENAI_BASE)
                if (model == ChatConfig.DEFAULT_ANTHROPIC_MODEL || model.isEmpty()) modelField.setText(ChatConfig.DEFAULT_OPENAI_MODEL)
            } else {
                if (base == ChatConfig.DEFAULT_OPENAI_BASE || base.isEmpty()) baseField.setText(ChatConfig.DEFAULT_ANTHROPIC_BASE)
                if (model == ChatConfig.DEFAULT_OPENAI_MODEL || model.isEmpty()) modelField.setText(ChatConfig.DEFAULT_ANTHROPIC_MODEL)
            }
        }
        listOf(baseField, modelField, keyField, userField).forEach {
            root.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        val testResult = TextView(this).apply { textSize = 13f; setTextColor(muted) }
        fun saveChat() = ChatConfig.save(
            this,
            if (rbOpenAi.isChecked) ChatConfig.FORMAT_OPENAI else ChatConfig.FORMAT_ANTHROPIC,
            baseField.text.toString(), modelField.text.toString(), keyField.text.toString(), userField.text.toString(),
        )
        val chatButtons = LinearLayout(this)
        chatButtons.addView(outlineButton("保存") {
            saveChat()
            testResult.text = "已保存"
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) })
        chatButtons.addView(outlineButton("保存并测试") {
            saveChat()
            if (!ChatConfig.isReady(this)) {
                testResult.text = "地址、模型和 Key 都要填写"
                return@outlineButton
            }
            testResult.text = "正在问她……"
            val card = CardStore.active(this)
            ChatClient.send(this, card, listOf(Msg("user", "用一句话跟我打个招呼吧。"))) { r ->
                testResult.text = r.fold({ "连上了。「${card.name}」说：$it" }, { "没连上：${it.message}" })
            }
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(chatButtons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        root.addView(testResult, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        root.addView(TextView(this).apply {
            text = "用 OpenCode 的 Key 时，保持默认的地址和模型即可。Key 只保存在这台手机的应用数据里。"
            textSize = 12f
            setTextColor(muted)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

        // ---- character cards
        root.addView(sectionTitle("角色卡"), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(28) })
        cardLabel = TextView(this).apply { textSize = 14f; setTextColor(ink) }
        root.addView(cardLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        val cardButtons = LinearLayout(this)
        cardButtons.addView(outlineButton("管理角色卡") {
            startActivity(Intent(this, CardsActivity::class.java))
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) })
        cardButtons.addView(outlineButton("打开聊天") {
            startActivity(Intent(this, ChatActivity::class.java))
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(cardButtons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        root.addView(TextView(this).apply {
            text = listOf(
                "怎么和她相处",
                "· 单击：戳她一下；连着戳会生气",
                "· 双击：和她聊天（先在下面填好聊天设置）",
                "· 长按：摸摸头，摸久了会害羞",
                "· 拖动：把她拎起来，松手会掉下去；使劲晃会晕",
                "· 不理她时：她会散步、看书、喝茶、坐下打瞌睡，偶尔爬上屏幕边缘",
                "· 通知栏里点“收起”就能让她回去",
            ).joinToString("\n")
            textSize = 14f
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextColor(muted)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(28) })

        setContentView(ScrollView(this).apply { setBackgroundColor(paper); fitsSystemWindows = true; addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ink)
    }

    private fun input(hint: String, value: String, password: Boolean) = EditText(this).apply {
        this.hint = hint
        setText(value)
        textSize = 14f
        setSingleLine(true)
        setTextColor(ink)
        setHintTextColor(muted)
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }

    private fun outlineButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(accent)
        background = GradientDrawable().apply { cornerRadius = dp(21).toFloat(); setStroke(dp(1), accent); setColor(Color.TRANSPARENT) }
        setOnClickListener { onClick() }
    }

    private fun updateSizeLabel(v: Float) {
        sizeLabel.text = "大小：${(v * 100).roundToInt()}%"
    }

    private fun refresh() {
        cardLabel.text = "当前角色：${CardStore.active(this).name}"
        val canOverlay = Settings.canDrawOverlays(this)
        toggle.text = if (PetService.running) "让她回去休息" else "请她出来"
        status.text = when {
            !canOverlay -> "需要“显示在其他应用上层”权限，她才能出现在桌面上"
            PetService.running -> "她正在屏幕上陪着你"
            else -> "她在这里安静地看书"
        }
    }

    private fun onToggle() {
        if (PetService.running) {
            startService(Intent(this, PetService::class.java).setAction(PetService.ACTION_STOP))
            handler.postDelayed({ refresh() }, 300)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请在列表里允许“Clau娘”显示在其他应用上层", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startForegroundService(Intent(this, PetService::class.java))
        handler.postDelayed({ refresh() }, 300)
    }
}
