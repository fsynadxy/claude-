package com.orangeflower.pet

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Lists character cards: pick one, create, edit, delete, or import a SillyTavern card file. */
class CardsActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private val ink = Color.parseColor("#3B2620")
    private val muted = Color.parseColor("#7A665B")
    private val accent = Color.parseColor("#C9561C")
    private val paper = Color.parseColor("#FFF9F3")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(paper)
        }
        root.addView(TextView(this).apply {
            text = "角色卡"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ink)
        })
        root.addView(TextView(this).apply {
            text = "点一张卡让她换成那个角色。每张卡的聊天记录分开保存。"
            textSize = 13f
            setTextColor(muted)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(pillButton("新建角色卡") { edit(null) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        actions.addView(pillButton("导入角色卡文件") { pickFile() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        root.addView(TextView(this).apply {
            text = "支持 SillyTavern / TavernAI 格式的角色卡：.json 文件，或内嵌角色数据的 .png 图片。"
            textSize = 12f
            setTextColor(muted)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        setContentView(ScrollView(this).apply { setBackgroundColor(paper); fitsSystemWindows = true; addView(root) })
    }

    override fun onResume() {
        super.onResume()
        renderList()
    }

    private fun pillButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(accent) }
        setOnClickListener { onClick() }
    }

    private fun renderList() {
        listBox.removeAllViews()
        val active = CardStore.active(this).id
        for (card in CardStore.all(this)) {
            val isActive = card.id == active
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.WHITE)
                    setStroke(dp(if (isActive) 2 else 1), if (isActive) accent else Color.parseColor("#E6D8CC"))
                }
                setOnClickListener {
                    CardStore.setActive(this@CardsActivity, card.id)
                    Toast.makeText(this@CardsActivity, "现在是「${card.name}」", Toast.LENGTH_SHORT).show()
                    renderList()
                }
            }
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(this).apply {
                text = card.name + if (card.builtin) "（内置）" else ""
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ink)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (isActive) top.addView(TextView(this).apply { text = "使用中"; textSize = 12f; setTextColor(accent) })
            row.addView(top)
            row.addView(TextView(this).apply {
                text = card.persona.replace("\n", " ").take(70) + if (card.persona.length > 70) "…" else ""
                textSize = 13f
                setTextColor(muted)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
            val btns = LinearLayout(this).apply { gravity = Gravity.END }
            btns.addView(textButton(if (card.builtin) "复制并编辑" else "编辑") { edit(card) })
            if (!card.builtin) btns.addView(textButton("删除") { confirmDelete(card) })
            row.addView(btns, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            listBox.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        }
    }

    private fun textButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(accent)
        background = null
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { onClick() }
    }

    private fun field(hint: String, value: String, lines: Int): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        textSize = 14f
        if (lines > 1) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = lines
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
        } else {
            inputType = InputType.TYPE_CLASS_TEXT
        }
    }

    private fun edit(card: Card?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val nameField = field("名字", card?.let { if (it.builtin) it.name + "（自定义）" else it.name } ?: "", 1)
        val personaField = field("人设：她是谁、怎么说话。可以用 {{user}} 指代你，{{char}} 指代她", card?.persona ?: "", 6)
        val greetingField = field("开场白（可选）", card?.greeting ?: "", 2)
        listOf(
            TextView(this).apply { text = "名字"; setTextColor(muted) } to nameField,
            TextView(this).apply { text = "人设"; setTextColor(muted) } to personaField,
            TextView(this).apply { text = "开场白"; setTextColor(muted) } to greetingField,
        ).forEach { (label, f) -> box.addView(label); box.addView(f) }
        AlertDialog.Builder(this)
            .setTitle(if (card == null) "新建角色卡" else "编辑角色卡")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                val name = nameField.text.toString().trim()
                val persona = personaField.text.toString().trim()
                if (name.isEmpty() || persona.isEmpty()) {
                    Toast.makeText(this, "名字和人设都要填写", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val saved = CardStore.save(
                    this,
                    Card(id = if (card == null || card.builtin) "" else card.id, name = name, persona = persona,
                        greeting = greetingField.text.toString().trim())
                )
                CardStore.setActive(this, saved.id)
                renderList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(card: Card) {
        AlertDialog.Builder(this)
            .setTitle("删除「${card.name}」？")
            .setMessage("这张卡和它的聊天记录都会被删除。")
            .setPositiveButton("删除") { _, _ -> CardStore.delete(this, card.id); renderList() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "image/png", "text/plain", "application/octet-stream"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || uri == null) return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("读不到文件")
            if (bytes.size > 20 * 1024 * 1024) throw IllegalStateException("文件太大了")
            val saved = CardStore.save(this, CardImport.parse(bytes))
            CardStore.setActive(this, saved.id)
            Toast.makeText(this, "已导入「${saved.name}」", Toast.LENGTH_SHORT).show()
            renderList()
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败：${e.message ?: "格式不认识"}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQ_IMPORT = 7
    }
}
