package com.orangeflower.pet

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/** Pick, preview, download and delete voices. */
class VoicePacksActivity : Activity() {

    private val ink = Color.parseColor("#3B2620")
    private val muted = Color.parseColor("#7A665B")
    private val accent = Color.parseColor("#C9561C")
    private val paper = Color.parseColor("#FFF9F3")
    private val line = Color.parseColor("#E6D8CC")

    private lateinit var list: LinearLayout
    private lateinit var speakerBox: LinearLayout
    private lateinit var speakerRow: LinearLayout
    private lateinit var tryStatus: TextView
    private var player: MediaPlayer? = null
    private val onChange: () -> Unit = { renderPacks() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = paper
        window.navigationBarColor = paper
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
            setBackgroundColor(paper)
        }
        root.addView(TextView(this).apply {
            text = "语音包"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ink)
        })
        root.addView(small("挑一个你喜欢的声音。除了内置的华燕，其他声音都要先下载一次，之后完全离线、不联网也能说话。点“试听”可以先听听样音再决定。"),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })

        // ---- voice settings
        root.addView(title("声音设置"), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(24) })
        val speedLabel = TextView(this).apply { textSize = 14f; setTextColor(ink) }
        fun showSpeed(v: Float) { speedLabel.text = "语速：${"%.2f".format(java.util.Locale.US, v)}×" }
        showSpeed(VoiceSettings.speed(this))
        root.addView(speedLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        root.addView(SeekBar(this).apply {
            max = 70   // 0.70 .. 1.40
            progress = ((VoiceSettings.speed(this@VoicePacksActivity) - 0.7f) * 100).roundToInt().coerceIn(0, 70)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = 0.7f + p / 100f
                    showSpeed(v)
                    if (fromUser) VoiceSettings.setSpeed(this@VoicePacksActivity, v)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        speakerBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        speakerBox.addView(TextView(this).apply { text = "声线"; textSize = 14f; setTextColor(ink) })
        speakerRow = LinearLayout(this)
        speakerBox.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(speakerRow) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        speakerBox.addView(small("换了声线后点下面的“试读”听听。第一次用这个声音时要先加载几秒。"))
        root.addView(speakerBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        tryStatus = small("")
        root.addView(outlineButton("用现在的声音试读") { tryVoice() },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(42)).apply { topMargin = dp(12) })
        root.addView(tryStatus, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

        // ---- download route
        root.addView(title("下载线路"), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(24) })
        val group = RadioGroup(this)
        VoicePacks.routes.forEachIndexed { i, (label, _) ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                setTextColor(ink)
                isChecked = i == VoiceSettings.route(this@VoicePacksActivity)
                setOnCheckedChangeListener { _, on -> if (on) VoiceSettings.setRoute(this@VoicePacksActivity, i) }
            })
        }
        root.addView(group)
        root.addView(small("GitHub 在国内有时很慢，可以换镜像。一条线路失败时会自动试其他线路；下载的文件都会核对校验码，镜像改不了内容。中断后再点下载会接着下。"))

        setContentView(ScrollView(this).apply { setBackgroundColor(paper); fitsSystemWindows = true; addView(root) })
    }

    override fun onResume() {
        super.onResume()
        VoiceDownloads.listen(onChange)
        renderPacks()
    }

    override fun onPause() {
        super.onPause()
        VoiceDownloads.unlisten(onChange)
        stopSample()
    }

    // ------------------------------------------------------------------ pack cards

    private fun renderPacks() {
        list.removeAllViews()
        val selected = VoiceSettings.packId(this)
        for (pack in VoicePacks.all) {
            val installed = VoicePacks.isInstalled(this, pack)
            val inUse = pack.id == selected
            val state = VoiceDownloads.state(pack.id)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.WHITE)
                    setStroke(dp(if (inUse) 2 else 1), if (inUse) accent else line)
                }
            }
            val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(TextView(this).apply {
                text = pack.name
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ink)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            head.addView(TextView(this).apply {
                text = when {
                    inUse -> "正在使用"
                    pack.builtin -> "内置"
                    installed -> "已下载"
                    else -> "约 ${pack.downloadMb} MB"
                }
                textSize = 12f
                setTextColor(if (inUse) accent else muted)
            })
            card.addView(head)
            card.addView(TextView(this).apply {
                text = pack.blurb
                textSize = 13f
                setTextColor(ink)
                setLineSpacing(dp(2).toFloat(), 1f)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

            when (state) {
                is VoiceDownloads.State.Running -> {
                    card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 1000
                        progress = if (state.total > 0) (state.done * 1000 / state.total).toInt() else 0
                        isIndeterminate = state.phase != "下载中"
                    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
                    card.addView(small("${state.phase}  ${state.done / 1_048_576} / ${state.total / 1_048_576} MB"))
                }
                is VoiceDownloads.State.Queued -> card.addView(small("排队中，前一个下载完就开始"))
                is VoiceDownloads.State.Failed -> card.addView(small("没下载成功：${state.message}").apply { setTextColor(accent) })
                else -> {
                    val part = if (!installed && !pack.builtin) VoicePacks.partialBytes(this, pack) else 0L
                    if (part > 0) card.addView(small("已下载 ${part / 1_048_576} MB，点“继续下载”接着下"))
                }
            }

            val buttons = LinearLayout(this)
            buttons.addView(outlineButton("试听") { playSample(pack) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(6) })
            val busy = state is VoiceDownloads.State.Running || state is VoiceDownloads.State.Queued
            when {
                busy -> buttons.addView(outlineButton("取消") { VoiceDownloadService.cancel(this, pack.id) },
                    LinearLayout.LayoutParams(0, dp(38), 1f))
                !installed -> {
                    val part = VoicePacks.partialBytes(this, pack)
                    buttons.addView(filledButton(if (part > 0) "继续下载" else "下载") { VoiceDownloadService.start(this, pack.id) },
                        LinearLayout.LayoutParams(0, dp(38), 1f))
                }
                else -> {
                    buttons.addView(filledButton(if (inUse) "使用中" else "使用") { use(pack) }.apply { isEnabled = !inUse; alpha = if (inUse) 0.55f else 1f },
                        LinearLayout.LayoutParams(0, dp(38), 1f))
                }
            }
            if (!pack.builtin && (installed || (!busy && VoicePacks.partialBytes(this, pack) > 0))) {
                buttons.addView(outlineButton("删除") { confirmDelete(pack) },
                    LinearLayout.LayoutParams(0, dp(38), 0.8f).apply { leftMargin = dp(6) })
            }
            card.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
            val fine = buildString {
                if (pack.heavy) append("建议在性能较好的手机上使用。")
                if (!pack.builtin) append("安装后占用约 ${pack.diskMb} MB。")
                append(pack.note)
            }
            card.addView(small(fine), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
            list.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        renderSpeakers()
    }

    private fun renderSpeakers() {
        val pack = VoicePacks.find(VoiceSettings.packId(this))
        speakerRow.removeAllViews()
        if (pack == null || pack.speakers.isEmpty()) {
            speakerBox.visibility = View.GONE
            return
        }
        speakerBox.visibility = View.VISIBLE
        val current = VoiceSettings.sid(this, pack)
        for (s in pack.speakers) {
            val on = s.sid == current
            speakerRow.addView(Button(this).apply {
                text = s.label
                isAllCaps = false
                textSize = 13f
                minWidth = 0
                minimumWidth = 0
                minHeight = dp(34)
                minimumHeight = dp(34)
                setPadding(dp(12), 0, dp(12), 0)
                setTextColor(if (on) Color.WHITE else ink)
                background = GradientDrawable().apply {
                    cornerRadius = dp(17).toFloat()
                    setColor(if (on) accent else Color.WHITE)
                    setStroke(dp(1), if (on) accent else line)
                }
                setOnClickListener {
                    VoiceSettings.setSid(this@VoicePacksActivity, pack, s.sid)
                    renderSpeakers()
                }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(34)).apply { rightMargin = dp(6) })
        }
    }

    private fun use(pack: VoicePack) {
        VoiceSettings.setPack(this, pack.id)
        VoiceClient.prepare(this)   // load it now, so her next sentence starts quickly
        tryStatus.text = "已换成「${pack.name}」" + if (pack.heavy) "，第一次加载要多等几秒" else ""
        renderPacks()
    }

    private fun confirmDelete(pack: VoicePack) {
        AlertDialog.Builder(this)
            .setTitle("删除「${pack.name}」？")
            .setMessage("会释放约 ${pack.diskMb} MB 空间，以后想用可以再下载。" +
                if (VoiceSettings.packId(this) == pack.id) "\n删除后改用内置的华燕。" else "")
            .setPositiveButton("删除") { _, _ ->
                VoicePacks.delete(this, pack)
                VoiceDownloads.set(pack.id, VoiceDownloads.State.Idle)
                renderPacks()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------ listening

    /** Plays the bundled sample, so a voice can be heard before downloading it. */
    private fun playSample(pack: VoicePack) {
        stopSample()
        VoiceClient.stop()
        try {
            val fd = assets.openFd("voice-samples/${pack.id}.m4a")
            player = MediaPlayer().apply {
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                fd.close()
                setOnCompletionListener { stopSample() }
                prepare()
                start()
            }
        } catch (e: Exception) {
            tryStatus.text = "样音播放失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun stopSample() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
    }

    private fun tryVoice() {
        stopSample()
        val pack = VoicePacks.find(VoiceSettings.packId(this)) ?: return
        tryStatus.text = "正在准备「${pack.name}」……"
        val started = System.currentTimeMillis()
        VoiceClient.speak(this, listOf("你好呀，我是 Clau娘。", "这个声音，你喜欢吗？"), onSentence = { i, _ ->
            if (i == 0) tryStatus.text = "正在朗读（准备用了 ${(System.currentTimeMillis() - started) / 100 / 10.0} 秒）"
        }, onDone = { ok ->
            if (!isDestroyed) tryStatus.text = if (ok) "读完了" else "没能播放：${VoiceClient.lastError ?: "未知原因"}"
        })
    }

    // ------------------------------------------------------------------ small views

    private fun title(t: String) = TextView(this).apply {
        text = t
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ink)
    }

    private fun small(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(muted)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun outlineButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(accent)
        minHeight = 0
        minimumHeight = 0
        background = GradientDrawable().apply { cornerRadius = dp(19).toFloat(); setStroke(dp(1), accent); setColor(Color.TRANSPARENT) }
        setOnClickListener { onClick() }
    }

    private fun filledButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        minHeight = 0
        minimumHeight = 0
        background = GradientDrawable().apply { cornerRadius = dp(19).toFloat(); setColor(accent) }
        setOnClickListener { onClick() }
    }
}
