package com.orangeflower.pet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

/** Draws the current frame plus an optional effect sticker and speech bubble. */
@SuppressLint("ViewConstructor")
class PetView(context: Context) : View(context) {

    var frame: Bitmap? = null
    var mirror = false
    var rotationDeg = 0f
    /** Rotation pivot as a fraction of the height (0.17 = near the head, 0.975 = the feet). */
    var pivotFrac = 0.17f
    var breath = 0f
    /** Vertical offset in px, for small procedural bounces. */
    var offsetY = 0f

    var effect: Bitmap? = null
    var effectAge = 0f          // 0..1 over the effect's lifetime
    var bubbleText: String? = null

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val effectPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val bubbleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFF7EE") }
    private val bubbleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5A3228"); style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3B2620") }
    private val dst = RectF()
    private val bubbleRect = RectF()
    private val tail = Path()
    private var cachedText: String? = null
    private var cachedLayout: StaticLayout? = null
    private var cachedWidth = 0

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        frame?.let { bmp ->
            canvas.save()
            if (offsetY != 0f) canvas.translate(0f, offsetY)
            if (rotationDeg != 0f) canvas.rotate(rotationDeg, w / 2f, h * pivotFrac)
            if (breath != 0f) canvas.scale(1f - breath * 0.5f, 1f + breath, w / 2f, h * FEET)
            if (mirror) canvas.scale(-1f, 1f, w / 2f, 0f)
            dst.set(0f, 0f, w, h)
            canvas.drawBitmap(bmp, null, dst, bitmapPaint)
            canvas.restore()
        }
        effect?.let { fx ->
            val rise = effectAge * h * 0.05f
            val pop = if (effectAge < 0.12f) 0.6f + effectAge / 0.12f * 0.4f else 1f
            effectPaint.alpha = if (effectAge > 0.75f) ((1f - effectAge) / 0.25f * 255).toInt().coerceIn(0, 255) else 255
            val fw = fx.width * pop
            val fh = fx.height * pop
            val cx = w * 0.72f
            val cy = h * 0.16f - rise
            dst.set(cx - fw / 2f, cy - fh / 2f, cx + fw / 2f, cy + fh / 2f)
            canvas.drawBitmap(fx, null, dst, effectPaint)
        }
        bubbleText?.let { drawBubble(canvas, it, w, h) }
    }

    private fun drawBubble(canvas: Canvas, text: String, w: Float, h: Float) {
        val maxW = (w * 0.9f).toInt()
        if (text != cachedText || maxW != cachedWidth) {
            textPaint.textSize = h * 0.052f
            val textW = minOf(textPaint.measureText(text).toInt() + 1, maxW - (h * 0.06f).toInt())
            cachedLayout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, textW.coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.05f)
                .build()
            cachedText = text
            cachedWidth = maxW
        }
        val layout = cachedLayout ?: return
        val padX = h * 0.03f
        val padY = h * 0.018f
        val bw = layout.width + padX * 2
        val bh = layout.height + padY * 2
        val left = (w - bw) / 2f
        val top = h * 0.012f
        bubbleRect.set(left, top, left + bw, top + bh)
        bubbleStroke.strokeWidth = h * 0.006f
        val r = h * 0.03f
        tail.reset()
        tail.moveTo(w / 2f - h * 0.02f, bubbleRect.bottom - 1f)
        tail.lineTo(w / 2f + h * 0.005f, bubbleRect.bottom + h * 0.03f)
        tail.lineTo(w / 2f + h * 0.025f, bubbleRect.bottom - 1f)
        canvas.drawRoundRect(bubbleRect, r, r, bubbleFill)
        canvas.drawRoundRect(bubbleRect, r, r, bubbleStroke)
        canvas.drawPath(tail, bubbleFill)
        canvas.drawLine(w / 2f - h * 0.02f, bubbleRect.bottom, w / 2f + h * 0.005f, bubbleRect.bottom + h * 0.03f, bubbleStroke)
        canvas.drawLine(w / 2f + h * 0.005f, bubbleRect.bottom + h * 0.03f, w / 2f + h * 0.025f, bubbleRect.bottom, bubbleStroke)
        canvas.save()
        canvas.translate(left + padX, top + padY)
        layout.draw(canvas)
        canvas.restore()
    }

    companion object {
        /** Feet baseline as a fraction of the frame height (780 / 800). */
        const val FEET = 0.975f
        /** Width / height of every frame (640 / 800). */
        const val ASPECT = 0.8f
    }
}
