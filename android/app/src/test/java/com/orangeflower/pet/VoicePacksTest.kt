package com.orangeflower.pet

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest

class VoicePacksTest {

    private lateinit var server: ServerSocket
    private lateinit var files: File
    private var archive = ByteArray(0)
    private var requests = mutableListOf<String?>()   // Range header of each request

    @Before fun setUp() {
        files = Files.createTempDirectory("voices").toFile()
        // a minimal HTTP server that understands Range requests
        server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        Thread {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (e: Exception) { break }
                sock.use {
                    val reader = it.getInputStream().bufferedReader()
                    var range: String? = null
                    while (true) {
                        val l = reader.readLine() ?: break
                        if (l.isEmpty()) break
                        if (l.startsWith("Range:", ignoreCase = true)) range = l.substringAfter(':').trim()
                    }
                    requests += range
                    val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                    val body = archive.copyOfRange(from, archive.size)
                    val head = (if (from > 0) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-${archive.size - 1}/${archive.size}\r\n"
                        else "HTTP/1.1 200 OK\r\n") + "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    it.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    @After fun tearDown() {
        server.close()
        files.deleteRecursively()
    }

    private fun tarBz2(entries: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            for ((name, text) in entries) {
                val data = text.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun pack(sha: String = sha(archive)) = VoicePack(
        id = "test", name = "测试", blurb = "", note = "", kind = VoiceKind.KOKORO,
        downloads = listOf(VoiceDownload("http://127.0.0.1:${server.localPort}/test.tar.bz2", sha, archive.size.toLong())),
        diskMb = 1,
        keep = listOf("model.int8.onnx", "tokens.txt", "dict/", "espeak-ng-data/"),
        rename = mapOf("model.int8.onnx" to "model.onnx"),
    )

    @Test fun keepsOnlyListedFilesAndRenames() {
        val p = pack("x")
        assertEquals("model.onnx", VoicePacks.installedName(p, "kokoro/model.int8.onnx"))
        assertEquals("dict/jieba.dict.utf8", VoicePacks.installedName(p, "kokoro/dict/jieba.dict.utf8"))
        assertEquals("espeak-ng-data/cmn_dict", VoicePacks.installedName(p, "kokoro/espeak-ng-data/cmn_dict"))
        assertNull(VoicePacks.installedName(p, "kokoro/espeak-ng-data/ru_dict"))
        assertNull(VoicePacks.installedName(p, "kokoro/README.md"))
        assertNull(VoicePacks.installedName(p, "kokoro/"))
    }

    @Test fun downloadResumesVerifiesAndInstalls() {
        archive = tarBz2(mapOf(
            "kokoro/model.int8.onnx" to "MODEL".repeat(5000),
            "kokoro/tokens.txt" to "a 1\nb 2\n",
            "kokoro/README.md" to "skip me",
            "kokoro/espeak-ng-data/ru_dict" to "skip me too",
            "kokoro/dict/../../../escape.txt" to "must not leave the pack folder",
        ))
        val c = FakeContext(files)
        val p = pack()
        // pretend an earlier attempt stopped halfway
        val part = File(files, "voices/.part/test.tar.bz2.part")
        part.parentFile!!.mkdirs()
        part.writeBytes(archive.copyOfRange(0, archive.size / 2))

        var lastPhase = ""
        VoicePacks.install(c, p, progress = { _, _, phase -> lastPhase = phase }, cancelled = { false })

        assertEquals("bytes=${archive.size / 2}-", requests.single())
        assertEquals("安装中", lastPhase)
        val dir = VoicePacks.dir(c, "test")
        assertEquals("MODEL".repeat(5000), File(dir, "model.onnx").readText())
        assertTrue(File(dir, "tokens.txt").exists())
        assertFalse(File(dir, "README.md").exists())
        assertFalse(File(dir, "espeak-ng-data/ru_dict").exists())
        assertFalse(File(files, "escape.txt").exists())
        assertFalse(File(files.parentFile, "escape.txt").exists())
        assertTrue(VoicePacks.isInstalled(c, p))
        assertFalse(part.exists())
    }

    @Test fun wrongChecksumIsRejected() {
        archive = tarBz2(mapOf("kokoro/tokens.txt" to "x"))
        val c = FakeContext(files)
        try {
            VoicePacks.install(c, pack("0".repeat(64)), progress = { _, _, _ -> }, cancelled = { false })
            fail("should not install")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("校验"))
        }
        assertFalse(VoicePacks.isInstalled(c, pack("0".repeat(64))))
        assertFalse(File(files, "voices/.part/test.tar.bz2.part").exists())
    }
}

class ChineseReadingTest {
    @org.junit.Test fun namesAndAbbreviationsGetChineseReadings() {
        org.junit.Assert.assertEquals("我是 克劳娘，也叫克劳德。", Voice.chineseReading("我是 Clau娘，也叫Claude。"))
        org.junit.Assert.assertEquals("这个诶艾很聪明，欧开", Voice.chineseReading("这个AI很聪明，OK"))
        org.junit.Assert.assertEquals("hello 世界", Voice.chineseReading("hello 世界"))
    }
}
