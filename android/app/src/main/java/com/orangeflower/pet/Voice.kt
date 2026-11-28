package com.orangeflower.pet

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

private object VoiceSettingsDefaults {
    const val BUILTIN_SPEED = 1.05f
}

/**
 * Offline text-to-speech with sherpa-onnx: the built-in piper zh_CN "huayan" voice, or a downloaded
 * voice pack (see [VoicePacks]). Runs in the ":tts" process; the choice arrives with each request.
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
    /** The pack asked for when the engine was loaded, and the one actually running (built-in on fallback). */
    @Volatile private var requestedPack: String? = null
    @Volatile private var actualPack: String? = null
    @Volatile private var fallbackAt = 0L
    @Volatile var error: String? = null
        private set
    @Volatile private var track: AudioTrack? = null

    private class Chunk(val gen: Int, val index: Int, val text: String, val samples: FloatArray?, val rate: Int)

    fun enabled(c: Context) = c.getSharedPreferences("pet", Context.MODE_PRIVATE).getBoolean("tts", true)
    fun setEnabled(c: Context, on: Boolean) = c.getSharedPreferences("pet", Context.MODE_PRIVATE).edit().putBoolean("tts", on).apply()

    /** Loads the engine in the background (copies the built-in voice files on first use). */
    fun prepare(c: Context, choice: VoiceChoice) {
        val app = c.applicationContext
        synthThread.execute { ensureEngine(app, choice.pack) }
    }

    /** The engine for [packId], loading it (and dropping another one) if needed; null if unavailable. */
    private fun ensureEngine(app: Context, packId: String): OfflineTts? {
        val settled = actualPack == packId || System.currentTimeMillis() - fallbackAt < 20_000
        if (requestedPack == packId && settled) tts?.let { return it }
        tts?.let {
            try { it.release() } catch (_: Throwable) {}
        }
        tts = null
        requestedPack = null
        actualPack = null
        val pack = VoicePacks.find(packId)
        val fromPack = if (pack != null && !pack.builtin) {
            try {
                loadPack(app, pack)
            } catch (e: Throwable) {
                error = "语音包「${pack.name}」加载失败，先用内置声音：" + (e.message ?: e.javaClass.simpleName)
                null
            }
        } else null
        if (fromPack != null) {
            tts = fromPack
            requestedPack = packId
            actualPack = packId
            error = null
            return fromPack
        }
        return try {
            loadBuiltin(app).also {
                tts = it
                // remember what was asked for, so a broken pack doesn't make every sentence retry it
                requestedPack = packId
                actualPack = VoicePacks.BUILTIN
                fallbackAt = System.currentTimeMillis()
                if (pack == null || pack.builtin) error = null
            }
        } catch (e: Throwable) {
            error = e.message ?: e.javaClass.simpleName
            null
        }
    }

    private fun loadBuiltin(app: Context): OfflineTts {
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
        return OfflineTts(null, config)
    }

    private fun loadPack(app: Context, pack: VoicePack): OfflineTts {
        val dir = VoicePacks.dir(app, pack.id)
        if (!VoicePacks.isInstalled(app, pack)) error("还没有下载")
        fun f(name: String) = File(dir, name).absolutePath
        fun optional(name: String) = File(dir, name).let { if (it.exists()) it.absolutePath else "" }
        val lexicon = pack.lexicons.joinToString(",") { f(it) }
        val model = when (pack.kind) {
            VoiceKind.VITS -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = f("model.onnx"), lexicon = lexicon, tokens = f("tokens.txt"),
                    dataDir = optional("espeak-ng-data"), dictDir = optional("dict"),
                ),
                numThreads = pack.threads, debug = false, provider = "cpu",
            )
            VoiceKind.MATCHA -> OfflineTtsModelConfig(
                matcha = OfflineTtsMatchaModelConfig(
                    acousticModel = f("model.onnx"), vocoder = f("vocoder.onnx"), lexicon = lexicon,
                    tokens = f("tokens.txt"), dictDir = optional("dict"),
                ),
                numThreads = pack.threads, debug = false, provider = "cpu",
            )
            VoiceKind.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = f("model.onnx"), voices = f("voices.bin"), tokens = f("tokens.txt"),
                    dataDir = f("espeak-ng-data"), lexicon = lexicon, dictDir = optional("dict"),
                ),
                numThreads = pack.threads, debug = false, provider = "cpu",
            )
        }
        val config = OfflineTtsConfig(
            model = model,
            ruleFsts = pack.fsts.joinToString(",") { f(it) },
            maxNumSentences = 1,
        )
        return OfflineTts(null, config)
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

    /**
     * For voices that only know Chinese (they silently skip Latin letters): give common names a Chinese
     * reading and spell short all-caps abbreviations letter by letter. Other English words are left as they are.
     */
    fun chineseReading(text: String): String {
        var t = text
        for ((word, reading) in NAMES) t = t.replace(Regex("\\b$word", RegexOption.IGNORE_CASE), reading)
        return t.replace(Regex("\\b[A-Z]{1,4}\\b")) { m -> m.value.map { LETTERS[it - 'A'] }.joinToString("") }
    }

    private val NAMES = listOf("Claude" to "克劳德", "Clau" to "克劳", "Anthropic" to "安索匹克", "OK" to "欧开")
    private val LETTERS = listOf("诶", "比", "西", "迪", "伊", "艾弗", "吉", "艾尺", "艾", "杰", "开", "艾勒", "艾姆",
        "恩", "欧", "屁", "克优", "阿", "艾斯", "提", "优", "维", "达不溜", "艾克斯", "歪", "贼")

    private fun speakable(s: String) = ActionText.strip(s)
        .replace(Regex("[*#`>_~|]"), "")
        .replace(Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}]"), "")
        .replace("……", "，").replace("…", "，")
        .trim()

    /**
     * Speaks [parts] in order. [onSentence] gets (index, seconds) when sentence index starts playing;
     * [onDone] fires after the last one (or right away, with ok = false, if the voice is unavailable).
     */
    fun speak(c: Context, parts: List<String>, choice: VoiceChoice, onSentence: (Int, Float) -> Unit, onDone: (Boolean) -> Unit) {
        val app = c.applicationContext
        val gen = generation.incrementAndGet()
        val queue = LinkedBlockingQueue<Chunk>()
        synthThread.execute {
          try {
            val engine = ensureEngine(app, choice.pack)
            val asked = actualPack == choice.pack
            val readsEnglish = VoicePacks.find(actualPack)?.readsEnglish ?: true
            val sid = if (asked) choice.sid.coerceIn(0, maxOf(1, engine?.numSpeakers() ?: 1) - 1) else 0
            val speed = (if (asked) choice.speed else VoiceSettingsDefaults.BUILTIN_SPEED).coerceIn(0.5f, 2f)
            if (engine == null) {
                queue.put(Chunk(gen, -1, "", null, 0))
                return@execute
            }
            for ((i, p) in parts.withIndex()) {
                if (generation.get() != gen) break
                val say = speakable(p).let { if (readsEnglish) it else chineseReading(it) }
                val audio = if (say.isEmpty()) null else try {
                    engine.generate(say, sid, speed)
                } catch (e: Throwable) {
                    null
                }
                queue.put(Chunk(gen, i, p, audio?.samples, audio?.sampleRate ?: engine.sampleRate()))
            }
            queue.put(Chunk(gen, parts.size, "", null, 0))
          } catch (e: Throwable) {
            error = "合成失败：" + (e.message ?: e.javaClass.simpleName)
            queue.put(Chunk(gen, -1, "", null, 0))
          }
        }
        playThread.execute {
            // drop whatever an interrupted sentence left in the buffer
            track?.let {
                try { it.pause(); it.flush(); it.play() } catch (_: Exception) {}
                framesWritten = 0
            }
            var ok = true
            try {
            while (true) {
                val chunk = queue.take()
                if (chunk.gen != generation.get()) { ok = false; break }
                if (chunk.index < 0) { ok = false; break }   // engine unavailable: error is set
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
            } catch (e: Throwable) {
                ok = false
                error = "播放失败：" + (e.message ?: e.javaClass.simpleName)
                try { stopTrack() } catch (_: Throwable) {}
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
