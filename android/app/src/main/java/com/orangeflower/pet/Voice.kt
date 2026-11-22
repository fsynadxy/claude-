package com.orangeflower.pet

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Offline text-to-speech with sherpa-onnx and the open piper zh_CN "huayan" voice.
 * Text is split into sentences; one thread synthesises, another plays, and [onSentence] fires on the
 * main thread as each sentence starts playing, so the bubble and mouth can follow the voice.
 */
object Voice {
    private const val ASSET_DIR = "tts"
    private const val VERSION = "huayan-medium-1"

    private val main = Handler(Looper.getMainLooper())
    private val synthThread = Executors.newSingleThreadExecutor()
    private val playThread = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)

    @Volatile private var tts: OfflineTts? = null
    @Volatile var error: String? = null
        private set
    @Volatile private var track: AudioTrack? = null

    private class Chunk(val gen: Int, val index: Int, val text: String, val samples: FloatArray?, val rate: Int)

    fun enabled(c: Context) = c.getSharedPreferences("pet", Context.MODE_PRIVATE).getBoolean("tts", true)
    fun setEnabled(c: Context, on: Boolean) = c.getSharedPreferences("pet", Context.MODE_PRIVATE).edit().putBoolean("tts", on).apply()

    /** Loads the engine in the background (copies the voice files on first use). */
    fun prepare(c: Context) {
        val app = c.applicationContext
        synthThread.execute { ensureEngine(app) }
    }

    private fun ensureEngine(app: Context): OfflineTts? {
        tts?.let { return it }
        return try {
            val dir = File(app.filesDir, ASSET_DIR)
            val marker = File(dir, ".version")
            if (!marker.exists() || marker.readText() != VERSION) {
                dir.deleteRecursively()
                copyAssets(app, ASSET_DIR, dir)
                marker.writeText(VERSION)
            }
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = File(dir, "model.onnx").absolutePath,
                        tokens = File(dir, "tokens.txt").absolutePath,
                        dataDir = File(dir, "espeak-ng-data").absolutePath,
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
                maxNumSentences = 1,
            )
            OfflineTts(null, config).also { tts = it; error = null }
        } catch (e: Throwable) {
            error = e.message ?: e.javaClass.simpleName
            null
        }
    }

    private fun copyAssets(app: Context, path: String, dest: File) {
        val children = app.assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            app.assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } else {
            dest.mkdirs()
            children.forEach { copyAssets(app, "$path/$it", File(dest, it)) }
        }
    }

    /** Splits into speakable sentences, merging very short pieces. */
    fun sentences(text: String): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (ch in "。！？!?；;\n" || (ch == '…' && sb.length > 8)) {
                val s = sb.toString().trim()
                if (s.isNotEmpty()) parts.add(s)
                sb.clear()
            }
        }
        if (sb.isNotBlank()) parts.add(sb.toString().trim())
        val merged = mutableListOf<String>()
        for (p in parts) {
            if (merged.isNotEmpty() && (merged.last().length < 6 || p.length < 3)) merged[merged.size - 1] = merged.last() + p
            else merged.add(p)
        }
        return merged
    }

    private fun speakable(s: String) = s
        .replace(Regex("[*#`>_~|]"), "")
        .replace(Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}]"), "")
        .replace("……", "，").replace("…", "，")
        .trim()

    /**
     * Speaks [parts] in order. [onSentence] gets (index, seconds) when sentence index starts playing;
     * [onDone] fires after the last one (or right away, with ok = false, if the voice is unavailable).
     */
    fun speak(c: Context, parts: List<String>, onSentence: (Int, Float) -> Unit, onDone: (Boolean) -> Unit) {
        val app = c.applicationContext
        val gen = generation.incrementAndGet()
        val queue = LinkedBlockingQueue<Chunk>()
        synthThread.execute {
            val engine = ensureEngine(app)
            if (engine == null) {
                queue.put(Chunk(gen, -1, "", null, 0))
                return@execute
            }
            for ((i, p) in parts.withIndex()) {
                if (generation.get() != gen) break
                val say = speakable(p)
                val audio = if (say.isEmpty()) null else try {
                    engine.generate(say, 0, 1.05f)
                } catch (e: Throwable) {
                    null
                }
                queue.put(Chunk(gen, i, p, audio?.samples, audio?.sampleRate ?: engine.sampleRate()))
            }
            queue.put(Chunk(gen, parts.size, "", null, 0))
        }
        playThread.execute {
            // drop whatever an interrupted sentence left in the buffer
            track?.let {
                try { it.pause(); it.flush(); it.play() } catch (_: Exception) {}
                framesWritten = 0
            }
            var ok = true
            while (true) {
                val chunk = queue.take()
                if (chunk.gen != generation.get()) { ok = false; break }
                if (chunk.index < 0) { ok = false; break }
                if (chunk.index >= parts.size) break
                val samples = chunk.samples
                val seconds = if (samples != null) samples.size / chunk.rate.toFloat() else 0.6f
                main.post { if (generation.get() == gen) onSentence(chunk.index, seconds) }
                if (samples == null) {
                    Thread.sleep((seconds * 1000).toLong())
                } else {
                    play(samples, chunk.rate, gen)
                }
            }
            main.post { if (generation.get() == gen) onDone(ok) }
        }
    }

    private var framesWritten = 0L

    private fun play(samples: FloatArray, rate: Int, gen: Int) {
        val t = obtainTrack(rate)
        var off = 0
        while (off < samples.size && generation.get() == gen) {
            val n = minOf(4096, samples.size - off)
            val w = t.write(samples, off, n, AudioTrack.WRITE_BLOCKING)
            if (w <= 0) break
            off += w
            framesWritten += w
        }
        // wait until this sentence has actually been heard before moving on
        val target = framesWritten - rate / 20
        while (generation.get() == gen && t.playbackHeadPosition.toLong() < target) Thread.sleep(15)
    }

    private fun obtainTrack(rate: Int): AudioTrack {
        track?.let { if (it.sampleRate == rate) return it }
        stopTrack()
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, rate * 4 / 5))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        framesWritten = 0
        return t
    }

    private fun stopTrack() {
        track?.let {
            try {
                it.pause(); it.flush(); it.stop(); it.release()
            } catch (_: Exception) {
            }
        }
        track = null
    }

    /** Stops speaking right away. */
    fun stop() {
        generation.incrementAndGet()
        playThread.execute { stopTrack() }
    }
}
