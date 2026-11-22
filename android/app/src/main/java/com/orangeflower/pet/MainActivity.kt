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
import android.widget.Button
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
            text = "橙花少女"
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

        root.addView(TextView(this).apply {
            text = listOf(
                "怎么和她相处",
                "· 单击：戳她一下；连着戳会生气",
                "· 双击：让她开心",
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

    private fun updateSizeLabel(v: Float) {
        sizeLabel.text = "大小：${(v * 100).roundToInt()}%"
    }

    private fun refresh() {
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
            Toast.makeText(this, "请在列表里允许“橙花桌宠”显示在其他应用上层", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startForegroundService(Intent(this, PetService::class.java))
        handler.postDelayed({ refresh() }, 300)
    }
}
