package com.orangeflower.pet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Invisible activity that only asks for the system's screen-capture consent and hands the result over. */
class CaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), 1)
        } catch (e: Exception) {
            PetService.onCaptureResult(RESULT_CANCELED, null)
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        PetService.onCaptureResult(resultCode, data)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

/**
 * Grabs a single frame of the screen through MediaProjection, at half resolution, then stops.
 * Must be called after the service is running in the foreground with the media projection type.
 */
object ScreenGrabber {
    private val main = Handler(Looper.getMainLooper())

    fun grab(c: Context, resultCode: Int, data: Intent, screenW: Int, screenH: Int, dpi: Int, done: (Bitmap?) -> Unit) {
        val mpm = c.getSystemService(MediaProjectionManager::class.java)
        val projection: MediaProjection = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            null
        } ?: return done(null)
        val w = (screenW / 2).coerceAtLeast(1)
        val h = (screenH / 2).coerceAtLeast(1)
        var display: VirtualDisplay? = null
        var reader: ImageReader? = null
        var finished = false
        fun finish(bmp: Bitmap?) {
            if (finished) return
            finished = true
            try { display?.release() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            try { projection.stop() } catch (_: Exception) {}
            done(bmp)
        }
        try {
            // Android 14 requires a callback before creating the virtual display
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { finish(null) } }
            }, main)
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            display = projection.createVirtualDisplay(
                "clau-look", w, h, (dpi / 2).coerceAtLeast(1),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, main,
            )
        } catch (e: Exception) {
            finish(null)
            return
        }
        // let the consent dialog fade out, then take the newest frame
        val start = SystemClock.uptimeMillis()
        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                val r = reader ?: return finish(null)
                if (SystemClock.uptimeMillis() - start < 450) { main.postDelayed(this, 60); return }
                val image = try { r.acquireLatestImage() } catch (e: Exception) { null }
                if (image == null) {
                    if (SystemClock.uptimeMillis() - start > 3000) finish(null) else main.postDelayed(this, 80)
                    return
                }
                val bmp = try {
                    // copy row by row: the last row of the buffer is often shorter than rowStride
                    val plane = image.planes[0]
                    val buf = plane.buffer
                    val stride = plane.rowStride
                    val ps = plane.pixelStride
                    val iw = minOf(w, image.width)
                    val ih = minOf(h, image.height)
                    val px = IntArray(iw * ih)
                    val row = ByteArray(stride)
                    for (y in 0 until ih) {
                        buf.position(y * stride)
                        val n = minOf(stride, buf.remaining())
                        buf.get(row, 0, n)
                        var x = 0
                        while (x < iw && x * ps + 2 < n) {
                            val i = x * ps
                            px[y * iw + x] = (0xff shl 24) or ((row[i].toInt() and 0xff) shl 16) or
                                ((row[i + 1].toInt() and 0xff) shl 8) or (row[i + 2].toInt() and 0xff)
                            x++
                        }
                    }
                    Bitmap.createBitmap(px, iw, ih, Bitmap.Config.ARGB_8888)
                } catch (e: Exception) {
                    null
                } finally {
                    image.close()
                }
                finish(bmp)
            }
        }
        main.postDelayed(poll, 60)
    }

    /** JPEG, longest side at most [maxSide] px, as base64. */
    fun toJpegBase64(bmp: Bitmap, maxSide: Int = 1280): String {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (scaled !== bmp) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
