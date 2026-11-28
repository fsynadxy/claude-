package com.orangeflower.pet

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class VoiceKind { VITS, MATCHA, KOKORO }

/** One file to fetch: a .tar.bz2 archive (extracted) or a single file saved as [saveAs]. */
class VoiceDownload(val url: String, val sha256: String, val bytes: Long, val saveAs: String? = null)

/**
 * A downloadable voice. Archives are fetched from the sherpa-onnx project's GitHub releases,
 * checked against a pinned SHA-256 and unpacked into the app's private files.
 */
class VoicePack(
    val id: String,
    val name: String,
    val blurb: String,
    /** Licence and other fine print. */
    val note: String,
    val kind: VoiceKind,
    val downloads: List<VoiceDownload>,
    /** Approximate size once installed, in MB. */
    val diskMb: Int,
    /** Archive entries to keep (paths below the archive's top folder); entries ending in "/" keep a folder. */
    val keep: List<String> = emptyList(),
    /** Archive file name -> installed name. */
    val rename: Map<String, String> = emptyMap(),
    val lexicons: List<String> = emptyList(),
    val fsts: List<String> = emptyList(),
    val speakers: List<Speaker> = emptyList(),
    val defaultSid: Int = 0,
    val threads: Int = 2,
    /** Multiplies the user's speed setting (some voices are naturally slow). */
    val baseSpeed: Float = 1f,
    /** Needs a fast phone. */
    val heavy: Boolean = false,
    /** False for Chinese-only voices, which skip Latin letters (see Voice.chineseReading). */
    val readsEnglish: Boolean = true,
) {
    val downloadMb get() = (downloads.sumOf { it.bytes } / 1_048_576.0).let { Math.round(it).toInt() }
    val builtin get() = downloads.isEmpty()
    val totalSpeakers get() = maxOf(1, speakers.size)
}

class Speaker(val sid: Int, val label: String)

object VoicePacks {
    const val BUILTIN = "huayan"
    private const val TTS = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
    private const val VOCODER = "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/"
    private val zhFsts = listOf("phone.fst", "date.fst", "number.fst")
    private const val BAKER_NOTE = "训练数据来自标贝（BZNSYP）开源中文语音库，仅限非商业使用。"

    val all: List<VoicePack> = listOf(
        VoicePack(
            id = BUILTIN, name = "华燕",
            blurb = "内置的默认声音，温柔的中文女声，不用下载。",
            note = "piper 开源语音 zh_CN-huayan。",
            kind = VoiceKind.VITS, downloads = emptyList(), diskMb = 80, baseSpeed = 1.05f,
        ),
        VoicePack(
            id = "xiaoya", name = "小雅",
            blurb = "清亮一些的中文女声，字正腔圆，合成很快。只读中文，英文单词会跳过（常见名字和缩写会换成中文读法）。",
            note = "piper 开源语音 zh_CN-xiao_ya。$BAKER_NOTE",
            kind = VoiceKind.VITS,
            downloads = listOf(VoiceDownload(TTS + "vits-piper-zh_CN-xiao_ya-medium.tar.bz2",
                "9396a3dffbb95b037acaa18094500f58d0db9a7c4f2689554e2539717cf0db65", 60_462_944)),
            diskMb = 65,
            keep = listOf("zh_CN-xiao_ya-medium.onnx", "tokens.txt", "lexicon.txt") + zhFsts,
            rename = mapOf("zh_CN-xiao_ya-medium.onnx" to "model.onnx"),
            lexicons = listOf("lexicon.txt"), fsts = zhFsts, readsEnglish = false,
        ),
        VoicePack(
            id = "chaowen", name = "超文",
            blurb = "声线偏低、沉稳的中文声音，合成很快。只读中文，英文单词会跳过（常见名字和缩写会换成中文读法）。",
            note = "piper 开源语音 zh_CN-chaowen，在小雅的基础上微调。$BAKER_NOTE",
            kind = VoiceKind.VITS,
            downloads = listOf(VoiceDownload(TTS + "vits-piper-zh_CN-chaowen-medium.tar.bz2",
                "1b69bdcf10bb6331c7f8a7591940febeb175c6bb86caa3802d96531f8e4ad211", 60_443_846)),
            diskMb = 65,
            keep = listOf("zh_CN-chaowen-medium.onnx", "tokens.txt", "lexicon.txt") + zhFsts,
            rename = mapOf("zh_CN-chaowen-medium.onnx" to "model.onnx"),
            lexicons = listOf("lexicon.txt"), fsts = zhFsts, readsEnglish = false,
        ),
        VoicePack(
            id = "baker", name = "标贝",
            blurb = "播音腔的中文女声，吐字清楚、起伏自然，合成很快。只读中文，英文单词会跳过（常见名字和缩写会换成中文读法）。",
            note = "Matcha-TTS（icefall）+ Vocos 声码器。$BAKER_NOTE",
            kind = VoiceKind.MATCHA,
            downloads = listOf(
                VoiceDownload(TTS + "matcha-icefall-zh-baker.tar.bz2",
                    "ae70096b19c6e283ea351a26b8a718ce6427c8bde8c1da22eb46a5478d39a69b", 75_455_267),
                VoiceDownload(VOCODER + "vocos-22khz-univ.onnx",
                    "0574a135aa1db2de6e181050db2ec528496cacd4a4701fc5d7faf9f9804c0081", 53_884_024, saveAs = "vocoder.onnx"),
            ),
            diskMb = 145,
            keep = listOf("model-steps-3.onnx", "tokens.txt", "lexicon.txt", "dict/") + zhFsts,
            rename = mapOf("model-steps-3.onnx" to "model.onnx"),
            lexicons = listOf("lexicon.txt"), fsts = zhFsts, readsEnglish = false,
        ),
        VoicePack(
            id = "melo", name = "Melo 中英",
            blurb = "自然的中文女声，夹着英文单词也读得顺；音质高（44.1kHz），合成稍慢。",
            note = "MeloTTS（MyShell，MIT 许可）。",
            kind = VoiceKind.VITS,
            downloads = listOf(VoiceDownload(TTS + "vits-melo-tts-zh_en.tar.bz2",
                "e58351ed7149f290a54534538badd4077cdbe6fddc964b24d0bee870415d1514", 167_006_755)),
            diskMb = 185,
            keep = listOf("model.onnx", "tokens.txt", "lexicon.txt", "dict/") + zhFsts + "new_heteronym.fst",
            lexicons = listOf("lexicon.txt"), fsts = zhFsts + "new_heteronym.fst",
            threads = 4,
        ),
        VoicePack(
            id = "kokoro", name = "Kokoro 多声线",
            blurb = "最像真人的一档，有很多男女声线可以挑，中英文都能读。比较吃性能，开口前会多等一会儿。",
            note = "Kokoro-82M v1.1-zh（hexgrad，Apache-2.0）。",
            kind = VoiceKind.KOKORO,
            downloads = listOf(VoiceDownload(TTS + "kokoro-int8-multi-lang-v1_1.tar.bz2",
                "259c9c8bc906d6078e4e0570421885dabb68b539c2f970fcbb890a58eeaf3e9c", 147_052_523)),
            diskMb = 190,
            keep = listOf("model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-zh.txt",
                "date-zh.fst", "phone-zh.fst", "number-zh.fst", "dict/", "espeak-ng-data/"),
            rename = mapOf("model.int8.onnx" to "model.onnx"),
            lexicons = listOf("lexicon-us-en.txt", "lexicon-zh.txt"),
            fsts = listOf("date-zh.fst", "phone-zh.fst", "number-zh.fst"),
            speakers = KokoroVoices.list, defaultSid = KokoroVoices.DEFAULT,
            threads = 4, heavy = true,
        ),
    )

    fun find(id: String?) = all.firstOrNull { it.id == id }

    /** Where a downloaded pack lives (the built-in voice is copied to files/tts by [Voice]). */
    fun dir(c: Context, id: String) = File(c.filesDir, "voices/$id")
    private fun partDir(c: Context) = File(c.filesDir, "voices/.part")
    private fun marker(c: Context, id: String) = File(dir(c, id), ".installed")

    fun isInstalled(c: Context, pack: VoicePack) = pack.builtin || marker(c, pack.id).exists()

    /** Bytes already downloaded towards [pack] (for resuming). */
    fun partialBytes(c: Context, pack: VoicePack) =
        pack.downloads.sumOf { d -> partFile(c, d).let { if (it.exists()) it.length() else 0L } }

    private fun partFile(c: Context, d: VoiceDownload) = File(partDir(c), d.url.substringAfterLast('/') + ".part")

    fun delete(c: Context, pack: VoicePack) {
        if (pack.builtin) return
        dir(c, pack.id).deleteRecursively()
        File(c.filesDir, "voices/${pack.id}.tmp").deleteRecursively()
        pack.downloads.forEach { partFile(c, it).delete() }
        if (VoiceSettings.packId(c) == pack.id) VoiceSettings.setPack(c, BUILTIN)
    }

    /** Whether an archive entry is kept, and under which name. */
    fun installedName(pack: VoicePack, entry: String): String? {
        val path = entry.substringAfter('/', "")   // drop the archive's top folder
        if (path.isEmpty() || path.endsWith("/")) return null
        val kept = pack.keep.any { k -> if (k.endsWith("/")) path.startsWith(k) else path == k }
        if (!kept) return null
        // espeak-ng: only the Chinese and English dictionaries
        val name = path.substringAfterLast('/')
        if (path.startsWith("espeak-ng-data/") && name.endsWith("_dict") && name != "cmn_dict" && name != "en_dict") return null
        return pack.rename[path] ?: path
    }

    // ------------------------------------------------------------------ downloading

    /** Download routes: GitHub, then two public mirrors that pass GitHub files through unchanged. */
    val routes = listOf("GitHub 直连" to "", "镜像 gh-proxy.com" to "https://gh-proxy.com/", "镜像 ghproxy.net" to "https://ghproxy.net/")

    class Cancelled : IOException("已取消")

    /**
     * Downloads (resuming partial files), verifies and installs [pack]. Blocking; call off the main thread.
     * [progress] gets (bytes done, bytes total, phase). [cancelled] is polled.
     */
    fun install(c: Context, pack: VoicePack, progress: (Long, Long, String) -> Unit, cancelled: () -> Boolean) {
        val total = pack.downloads.sumOf { it.bytes }
        val need = total + pack.diskMb * 1_048_576L - partialBytes(c, pack)
        val free = c.filesDir.usableSpace
        if (free < need + 50L * 1_048_576) {
            throw IOException("手机空间不够：需要约 ${need / 1_048_576 + 50} MB，现在只剩 ${free / 1_048_576} MB")
        }
        partDir(c).mkdirs()
        var before = 0L
        for (d in pack.downloads) {
            val part = partFile(c, d)
            val base = before
            fetch(c, d, part, cancelled) { done -> progress(base + done, total, "下载中") }
            progress(base + d.bytes, total, "校验中")
            if (sha256(part) != d.sha256) {
                part.delete()
                throw IOException("下载的文件校验不通过（可能被网络改动过），请重试")
            }
            before += d.bytes
        }
        progress(total, total, "安装中")
        val staging = File(c.filesDir, "voices/${pack.id}.tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        for (d in pack.downloads) {
            if (cancelled()) throw Cancelled()
            val part = partFile(c, d)
            if (d.saveAs != null) {
                part.copyTo(File(staging, d.saveAs), overwrite = true)
            } else {
                extract(pack, part, staging, cancelled)
            }
        }
        val target = dir(c, pack.id)
        target.deleteRecursively()
        if (!staging.renameTo(target)) throw IOException("安装失败：无法移动文件")
        File(target, ".installed").writeText(pack.downloads.joinToString(",") { it.sha256.take(12) })
        pack.downloads.forEach { partFile(c, it).delete() }
    }

    private fun fetch(c: Context, d: VoiceDownload, part: File, cancelled: () -> Boolean, progress: (Long) -> Unit) {
        if (part.exists() && part.length() == d.bytes) return
        if (part.exists() && part.length() > d.bytes) part.delete()
        val first = VoiceSettings.route(c)
        val order = listOf(first) + routes.indices.filter { it != first }
        var last: Exception? = null
        for (r in order) {
            if (cancelled()) throw Cancelled()
            try {
                fetchFrom(routes[r].second + d.url, d, part, cancelled, progress)
                return
            } catch (e: Cancelled) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("下载失败：${last?.message ?: "网络不通"}", last)
    }

    private fun fetchFrom(url: String, d: VoiceDownload, part: File, cancelled: () -> Boolean, progress: (Long) -> Unit) {
        var have = if (part.exists()) part.length() else 0L
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "ClauNiang-voice-download")
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = conn.responseCode
            val append = code == 206 && have > 0
            if (code != 200 && code != 206) throw IOException("服务器返回 $code")
            if (!append) have = 0
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        if (cancelled()) throw Cancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        if (have - lastReport > 256 * 1024) {
                            lastReport = have
                            progress(have)
                        }
                    }
                }
            }
            if (part.length() != d.bytes) throw IOException("下载不完整（${part.length()}/${d.bytes}）")
            progress(have)
        } finally {
            conn.disconnect()
        }
    }

    private fun extract(pack: VoicePack, archive: File, into: File, cancelled: () -> Boolean) {
        val root = into.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(FileInputStream(archive).buffered(1 shl 16))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (cancelled()) throw Cancelled()
                if (!entry.isFile) continue
                val name = installedName(pack, entry.name) ?: continue
                val out = File(into, name)
                if (!out.canonicalPath.startsWith(root)) continue   // never write outside the pack folder
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { tar.copyTo(it, 1 shl 16) }
            }
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** What the user picked; sent to the speech process with each request. */
data class VoiceChoice(val pack: String, val sid: Int, val speed: Float)

object VoiceSettings {
    private fun prefs(c: Context) = c.getSharedPreferences("pet", Context.MODE_PRIVATE)

    /** The selected pack, falling back to the built-in voice if it is not installed. */
    fun packId(c: Context): String {
        val id = prefs(c).getString("voice_pack", VoicePacks.BUILTIN) ?: VoicePacks.BUILTIN
        val pack = VoicePacks.find(id) ?: return VoicePacks.BUILTIN
        return if (VoicePacks.isInstalled(c, pack)) id else VoicePacks.BUILTIN
    }

    fun setPack(c: Context, id: String) = prefs(c).edit().putString("voice_pack", id).apply()

    fun sid(c: Context, pack: VoicePack): Int =
        prefs(c).getInt("voice_sid_${pack.id}", pack.defaultSid).let { s ->
            if (pack.speakers.isEmpty()) 0 else if (pack.speakers.any { it.sid == s }) s else pack.defaultSid
        }

    fun setSid(c: Context, pack: VoicePack, sid: Int) = prefs(c).edit().putInt("voice_sid_${pack.id}", sid).apply()

    fun speed(c: Context) = prefs(c).getFloat("voice_speed", 1f)
    fun setSpeed(c: Context, v: Float) = prefs(c).edit().putFloat("voice_speed", v).apply()

    fun route(c: Context) = prefs(c).getInt("voice_route", 0).coerceIn(0, VoicePacks.routes.size - 1)
    fun setRoute(c: Context, r: Int) = prefs(c).edit().putInt("voice_route", r).apply()

    fun choice(c: Context): VoiceChoice {
        val pack = VoicePacks.find(packId(c)) ?: VoicePacks.all[0]
        return VoiceChoice(pack.id, sid(c, pack), speed(c) * pack.baseSpeed)
    }
}

/**
 * Download state shared between the download service and the voice screen (main process only).
 * Listeners are called on the main thread.
 */
object VoiceDownloads {
    sealed class State {
        object Idle : State()
        object Queued : State()
        class Running(val done: Long, val total: Long, val phase: String) : State()
        class Failed(val message: String) : State()
    }

    private val main = Handler(Looper.getMainLooper())
    private val states = HashMap<String, State>()
    private val listeners = mutableListOf<() -> Unit>()

    fun state(id: String): State = synchronized(states) { states[id] ?: State.Idle }

    @Volatile private var lastPost = 0L

    fun set(id: String, s: State) {
        val previous = synchronized(states) { states.put(id, s) }
        // progress ticks arrive often; redraw at most a few times a second unless the phase changed
        val now = System.currentTimeMillis()
        val tick = s is State.Running && previous is State.Running && previous.phase == s.phase
        if (tick && now - lastPost < 300) return
        lastPost = now
        main.post { listeners.toList().forEach { it() } }
    }

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
}
