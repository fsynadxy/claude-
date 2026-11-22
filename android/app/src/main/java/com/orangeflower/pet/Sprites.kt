package com.orangeflower.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Loads pet frames from assets/pet. Every character frame shares one 640x800 canvas with the
 * feet on a common baseline, so frames are simply scaled to the window height.
 */
class Sprites(private val context: Context) {

    private val counts: Map<String, Int>
    private var frameHeight = 0

    private val cache = object : LruCache<String, Bitmap>(56 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    init {
        val text = context.assets.open("pet/manifest.json").bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        counts = json.keys().asSequence().associateWith { json.getInt(it) }
    }

    fun count(anim: String): Int = counts[anim] ?: 0

    fun setFrameHeight(height: Int) {
        if (height != frameHeight) {
            frameHeight = height
            cache.evictAll()
        }
    }

    /** Frame [index] (0-based, wraps around) of [anim], scaled to the current window height. */
    fun frame(anim: String, index: Int): Bitmap? {
        val n = count(anim)
        if (n == 0 || frameHeight <= 0) return null
        val name = String.format("%s_%02d", anim, Math.floorMod(index, n) + 1)
        return load("pet/$name.webp", frameHeight, fitLongestSide = false)
    }

    /** Effect sticker scaled so its longest side is [size] px. */
    fun effect(name: String, size: Int): Bitmap? = load("pet/fx_$name.webp", size, fitLongestSide = true)

    private fun load(path: String, size: Int, fitLongestSide: Boolean): Bitmap? {
        val key = "$path@$size"
        cache.get(key)?.let { return it }
        val src = try {
            context.assets.open(path).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        } ?: return null
        val scale = if (fitLongestSide) size.toFloat() / maxOf(src.width, src.height) else size.toFloat() / src.height
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        val bmp = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true).also {
            if (it !== src) src.recycle()
        }
        cache.put(key, bmp)
        return bmp
    }
}
