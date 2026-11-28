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
import android.widget.HorizontalScrollView
import android.widget.Switch
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
    private lateinit var diagText: TextView
    private lateinit var updateBox: LinearLayout
    private lateinit var updateTitle: TextView
    private lateinit var updateNotes: TextView
    private lateinit var updateStatus: TextView
    private var shownRelease: Updates.Release? = null
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

        // ---- new version banner (hidden until a newer release is known)
        updateBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.parseColor("#FBE2D1")) }
        }
        updateTitle = TextView(this).apply { textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(ink) }
        updateNotes = TextView(this).apply { textSize = 13f; setTextColor(muted); maxLines = 6; setLineSpacing(dp(2).toFloat(), 1f) }
        updateBox.addView(updateTitle)
        updateBox.addView(updateNotes, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        val updateButtons = LinearLayout(this)
        updateButtons.addView(outlineButton("下载新版") { shownRelease?.let { Updates.openDownload(this, it) } },
            LinearLayout.LayoutParams(0, dp(40), 1.2f).apply { rightMargin = dp(6) })
        updateButtons.addView(outlineButton("更新说明") { shownRelease?.let { Updates.openPage(this, it) } },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) })
        updateButtons.addView(outlineButton("先不了") {
            shownRelease?.let { Updates.ignore(this, it.version) }
            updateBox.visibility = View.GONE
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
        updateBox.addView(updateButtons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        updateBox.addView(TextView(this).apply {
            text = "会在浏览器里下载安装包，下载后点开安装即可，直接覆盖旧版，聊天记录和设置都会保留。“先不了”之后这个版本不再提醒。"
            textSize = 11f
            setTextColor(muted)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        root.addView(updateBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(20) })

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
        root.addView(TextView(this).apply { text = "服务商"; textSize = 13f; setTextColor(muted) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        val providerRow = LinearLayout(this)
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(providerRow) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        val keyHint = TextView(this).apply { textSize = 12f; setTextColor(muted) }
        root.addView(keyHint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })

        // custom provider only: request format + endpoint
        val customBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val formatGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbOpenAi = RadioButton(this).apply { id = View.generateViewId(); text = "OpenAI 兼容格式"; setTextColor(ink) }
        val rbAnthropic = RadioButton(this).apply { id = View.generateViewId(); text = "Anthropic 格式"; setTextColor(ink) }
        formatGroup.addView(rbOpenAi)
        formatGroup.addView(rbAnthropic)
        if (ChatConfig.customFormat(this) == ChatConfig.FORMAT_ANTHROPIC) rbAnthropic.isChecked = true else rbOpenAi.isChecked = true
        val baseField = input("接口地址，例如 https://example.com/v1", ChatConfig.customBase(this), password = false)
        customBox.addView(formatGroup)
        customBox.addView(baseField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        root.addView(customBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

        val modelField = input("模型", "", password = false)
        root.addView(modelField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        val modelRow = LinearLayout(this)
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(modelRow) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2) })
        val keyField = input("API Key", "", password = true)
        val userField = input("她怎么称呼你（可空，默认“你”）", ChatConfig.rawUserName(this), password = false)
        listOf(keyField, userField).forEach {
            root.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        }

        // unsaved edits per provider, so switching back and forth doesn't lose typing
        val editedKeys = ChatConfig.providers.associate { it.id to ChatConfig.keyFor(this, it.id) }.toMutableMap()
        val editedModels = ChatConfig.providers.associate { it.id to ChatConfig.modelFor(this, it) }.toMutableMap()
        var selected = ChatConfig.provider(this)
        val chips = mutableMapOf<String, Button>()

        fun showProvider(p: Provider) {
            editedKeys[selected.id] = keyField.text.toString()
            editedModels[selected.id] = modelField.text.toString()
            selected = p
            chips.forEach { (id, b) -> styleChip(b, id == p.id) }
            keyHint.text = p.keyHint
            customBox.visibility = if (p.id == ChatConfig.CUSTOM) View.VISIBLE else View.GONE
            modelField.setText(editedModels[p.id] ?: "")
            keyField.setText(editedKeys[p.id] ?: "")
            modelRow.removeAllViews()
            p.models.forEach { m ->
                modelRow.addView(Button(this).apply {
                    text = m
                    isAllCaps = false
                    textSize = 12f
                    setTextColor(accent)
                    background = null
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = dp(32)
                    minimumHeight = dp(32)
                    setPadding(dp(8), 0, dp(8), 0)
                    setOnClickListener { modelField.setText(m) }
                })
            }
        }
        ChatConfig.providers.forEach { p ->
            val chip = Button(this).apply {
                text = p.label
                isAllCaps = false
                textSize = 13f
                minWidth = 0
                minimumWidth = 0
                minHeight = dp(34)
                minimumHeight = dp(34)
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener { showProvider(p) }
            }
            chips[p.id] = chip
            providerRow.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, dp(34)).apply { rightMargin = dp(6) })
        }
        // first render without overwriting the stored values
        editedKeys[selected.id]?.let { keyField.setText(it) }
        editedModels[selected.id]?.let { modelField.setText(it) }
        showProvider(selected)

        val testResult = TextView(this).apply { textSize = 13f; setTextColor(muted) }
        fun saveChat() = ChatConfig.save(
            this, selected.id, modelField.text.toString(), keyField.text.toString(), userField.text.toString(),
            baseField.text.toString(), if (rbAnthropic.isChecked) ChatConfig.FORMAT_ANTHROPIC else ChatConfig.FORMAT_OPENAI,
        )
        val chatButtons = LinearLayout(this)
        chatButtons.addView(outlineButton("保存") {
            saveChat()
            testResult.text = "已保存，现在使用 ${selected.label}"
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) })
        chatButtons.addView(outlineButton("保存并测试") {
            saveChat()
            if (!ChatConfig.isReady(this)) {
                testResult.text = if (selected.id == ChatConfig.CUSTOM) "接口地址、模型和 Key 都要填写" else "模型和 Key 都要填写"
                return@outlineButton
            }
            testResult.text = "正在问她……"
            val card = CardStore.active(this)
            ChatClient.send(this, card, listOf(Msg("user", "用一句话跟我打个招呼吧。"))) { r ->
                testResult.text = r.fold({ "连上了。「${card.name}」说：${Emotion.strip(it)}" }, { "没连上：${it.message}" })
            }
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(chatButtons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        root.addView(testResult, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        root.addView(TextView(this).apply {
            text = "选好服务商后只需填 Key；模型可以点下面的推荐，也可以自己填（以服务商控制台里的名字为准）。每家的 Key 分开保存，只存在这台手机的应用数据里。"
            textSize = 12f
            setTextColor(muted)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

        // ---- desktop behaviour
        root.addView(sectionTitle("桌面行为"), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(28) })
        root.addView(Switch(this).apply {
            text = "键盘弹出时自动起飞（避开键盘）"
            textSize = 14f
            setTextColor(ink)
            isChecked = Prefs.dodgeKeyboard(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setDodgeKeyboard(this@MainActivity, on) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        root.addView(Switch(this).apply {
            text = "摸头、戳、拎起来甩等互动交给模型回应"
            textSize = 14f
            setTextColor(ink)
            isChecked = Prefs.modelReacts(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setModelReacts(this@MainActivity, on) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        root.addView(TextView(this).apply {
            text = "开启后，你对她做的事会作为一条“互动”发给当前角色卡，由模型按角色性格回应（每次约一句话的额度）；没填 API 时用内置台词。"
            textSize = 12f
            setTextColor(muted)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2) })
        root.addView(Switch(this).apply {
            text = "连点她三下：让她看看现在的屏幕"
            textSize = 14f
            setTextColor(ink)
            isChecked = Prefs.tripleTapLook(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setTripleTapLook(this@MainActivity, on) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        root.addView(TextView(this).apply {
            text = "系统每次都会弹出截屏确认，点“立即开始”后截一张图（她自己会先藏起来），发给当前聊天设置里的模型，需要模型支持识图。截图不会保存在手机上。"
            textSize = 12f
            setTextColor(muted)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2) })
        root.addView(Switch(this).apply {
            text = "朗读她的回复（离线语音）"
            textSize = 14f
            setTextColor(ink)
            isChecked = Voice.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Voice.setEnabled(this@MainActivity, on)
                if (on) VoiceClient.prepare(this@MainActivity) else VoiceClient.stop()
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        root.addView(TextView(this).apply {
            text = "语音在手机上离线合成（开源 sherpa-onnx + piper 中文女声 huayan），不联网、不收费。第一次朗读前会把约 65 MB 的语音模型解压到应用目录，需要几秒钟。"
            textSize = 12f
            setTextColor(muted)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2) })
        val voiceStatus = TextView(this).apply { textSize = 13f; setTextColor(muted) }
        root.addView(outlineButton("试听语音") {
            voiceStatus.text = "正在准备语音……（第一次需要几秒）"
            val started = System.currentTimeMillis()
            VoiceClient.speak(this, listOf("你好，我是 Clau娘。", "这样说话，听得清楚吗？"), onSentence = { i, sec ->
                if (i == 0) voiceStatus.text = "正在朗读（准备用了 ${(System.currentTimeMillis() - started) / 100 / 10.0} 秒）"
            }, onDone = { ok ->
                voiceStatus.text = if (ok) "语音正常" else "语音没能播放：${VoiceClient.lastError ?: "未知原因"}\n诊断信息里可能有更多细节。"
            })
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(42)).apply { topMargin = dp(8) })
        root.addView(voiceStatus, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

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
                "· 单击：她头顶会弹出输入框，和她聊天；双击：戳她，连着戳会生气",
                "· 连点三下：让她看看现在的屏幕（识图）",
                "· 输入框右边的 ⤢ 可以打开完整的聊天记录",
                "· 长按：摸摸头，摸久了会害羞；摸头、戳、甩她都会由模型按角色性格回应",
                "· 她的表情会跟着回复内容变化（开心、害羞、生气、委屈、惊讶、思考）",
                "· 拖动：把她拎起来，松手会掉下去；使劲晃会晕",
                "· 不理她时：她会散步、看书、喝茶、坐下打瞌睡，偶尔爬上屏幕边缘",
                "· 键盘弹出时：她会零档起飞，挂到屏幕顶上让开，键盘收起后再落回来",
                "· 通知栏里点“收起”就能让她回去",
            ).joinToString("\n")
            textSize = 14f
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextColor(muted)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(28) })

        // ---- version and updates
        root.addView(sectionTitle("版本"), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(28) })
        root.addView(TextView(this).apply {
            text = "当前版本：${Updates.installedVersion(this@MainActivity)}"
            textSize = 14f
            setTextColor(ink)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        root.addView(Switch(this).apply {
            text = "自动检查新版本"
            textSize = 14f
            setTextColor(ink)
            isChecked = Updates.autoCheck(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Updates.setAutoCheck(this@MainActivity, on) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        root.addView(TextView(this).apply {
            text = "每天最多看一次 GitHub 上的最新发布，不上传任何信息；有新版本时主界面会提示，她也会在桌面上提一句。"
            textSize = 12f
            setTextColor(muted)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2) })
        updateStatus = TextView(this).apply { textSize = 13f; setTextColor(muted) }
        root.addView(outlineButton("检查更新") {
            updateStatus.text = "正在检查……"
            Updates.check(this, force = true) { r ->
                if (isDestroyed) return@check
                r.onSuccess { rel ->
                    if (rel == null) {
                        updateStatus.text = "已经是最新版本"
                    } else {
                        updateStatus.text = "发现新版本 ${rel.version}，在页面顶部"
                        showUpdate(rel)
                    }
                }.onFailure { e ->
                    updateStatus.text = "没检查成功：${e.message ?: e.javaClass.simpleName}\n国内访问 GitHub 有时不稳定，可以稍后再试，或直接打开发布页。"
                }
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(42)).apply { topMargin = dp(8) })
        root.addView(updateStatus, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

        // ---- diagnostics
        root.addView(sectionTitle("诊断信息"), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(28) })
        diagText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(muted)
            setTextIsSelectable(true)
        }
        root.addView(diagText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        val diagButtons = LinearLayout(this)
        diagButtons.addView(outlineButton("复制诊断信息") {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Clau娘诊断", Diagnostics.report(this)))
            Toast.makeText(this, "已复制，可以直接粘贴发给我", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) })
        diagButtons.addView(outlineButton("清除记录") {
            Diagnostics.clear(this)
            refresh()
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(diagButtons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })

        setContentView(ScrollView(this).apply { setBackgroundColor(paper); fitsSystemWindows = true; addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        Updates.check(this, force = false) { r ->
            val rel = r.getOrNull() ?: return@check
            if (!isDestroyed && !Updates.isIgnored(this, rel.version)) showUpdate(rel)
        }
    }

    private fun showUpdate(rel: Updates.Release) {
        shownRelease = rel
        updateTitle.text = "发现新版本 ${rel.version}（当前 ${Updates.installedVersion(this)}）"
        val notes = rel.notes.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("提交：") }
        updateNotes.text = notes.joinToString("\n")
        updateNotes.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
        updateBox.visibility = View.VISIBLE
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

    private fun styleChip(b: Button, on: Boolean) {
        b.setTextColor(if (on) Color.WHITE else ink)
        b.background = GradientDrawable().apply {
            cornerRadius = dp(17).toFloat()
            setColor(if (on) accent else Color.WHITE)
            setStroke(dp(1), if (on) accent else Color.parseColor("#E6D8CC"))
        }
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
        val report = Diagnostics.report(this)
        diagText.text = if (report.lines().size > 1) report.takeLast(3000) else report + "\n暂无异常记录"
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
