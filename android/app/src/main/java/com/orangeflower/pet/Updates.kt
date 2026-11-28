package com.orangeflower.pet

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Looks for a newer Clau娘 on the project's GitHub releases page. It only reads the public
 * "latest release" record (no account, nothing about the phone is sent) and never installs
 * anything itself: the download opens in the browser and Android checks the signature.
 */
object Updates {
    private const val REPO = "fsynadxy/claude-"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    private const val PAGE_PREFIX = "https://github.com/$REPO/releases/"
    private const val AUTO_INTERVAL_MS = 20L * 60 * 60 * 1000

    data class Release(val version: String, val notes: String, val apkUrl: String, val pageUrl: String)

    private val pool by lazy { Executors.newSingleThreadExecutor() }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private fun prefs(c: Context) = c.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun autoCheck(c: Context) = prefs(c).getBoolean("auto", true)
    fun setAutoCheck(c: Context, on: Boolean) = prefs(c).edit().putBoolean("auto", on).apply()

    fun installedVersion(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /** Compares dotted versions numerically ("1.10.0" > "1.9.2"); a leading "v" is ignored. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").split('.', '-')
            .map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Reads the GitHub "latest release" JSON; links outside this project's release pages are ignored. */
    fun parse(json: String): Release? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val version = o.optString("tag_name").removePrefix("v").ifBlank { return null }
        val page = o.optString("html_url").takeIf { it.startsWith(PAGE_PREFIX) } ?: RELEASES_PAGE
        var apk = ""
        val assets = o.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val url = assets.getJSONObject(i).optString("browser_download_url")
                if (url.startsWith(DOWNLOAD_PREFIX) && url.endsWith(".apk")) {
                    apk = url
                    break
                }
            }
        }
        return Release(version, o.optString("body").trim(), apk, page)
    }

    /** The newer release found by the last check, if it is still newer than what is installed. */
    fun known(c: Context): Release? {
        val p = prefs(c)
        val v = p.getString("version", null) ?: return null
        if (!isNewer(v, installedVersion(c))) return null
        return Release(v, p.getString("notes", "") ?: "", p.getString("apk", "") ?: "", p.getString("page", RELEASES_PAGE) ?: RELEASES_PAGE)
    }

    /**
     * Checks GitHub. [force] checks now; otherwise at most about once a day and only when automatic
     * checks are on. [done] runs on the main thread with the newer release (null when up to date),
     * or a failure when the check could not be made.
     */
    fun check(c: Context, force: Boolean, done: (Result<Release?>) -> Unit) {
        val app = c.applicationContext
        val p = prefs(app)
        if (!force && (!autoCheck(app) || System.currentTimeMillis() - p.getLong("checkedAt", 0) < AUTO_INTERVAL_MS)) {
            done(Result.success(known(app)))
            return
        }
        val current = installedVersion(app)
        pool.execute {
            val result = runCatching {
                val conn = URL(API).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "ClauNiang-update-check")
                try {
                    val code = conn.responseCode
                    if (code == 404) return@runCatching null   // no release published yet
                    if (code !in 200..299) error("GitHub 返回 $code")
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    parse(body)
                } finally {
                    conn.disconnect()
                }
            }
            result.onSuccess { r ->
                val e = p.edit().putLong("checkedAt", System.currentTimeMillis())
                if (r != null) e.putString("version", r.version).putString("notes", r.notes).putString("apk", r.apkUrl).putString("page", r.pageUrl)
                e.apply()
            }
            main.post { done(result.map { r -> r?.takeIf { isNewer(it.version, current) } }) }
        }
    }

    /** True once per version: whether the pet should mention this update. */
    fun shouldAnnounce(c: Context, version: String): Boolean {
        val p = prefs(c)
        if (p.getString("announced", null) == version) return false
        p.edit().putString("announced", version).apply()
        return true
    }

    fun isIgnored(c: Context, version: String) = prefs(c).getString("ignored", null) == version
    fun ignore(c: Context, version: String) = prefs(c).edit().putString("ignored", version).apply()

    fun openDownload(c: Context, r: Release) = open(c, r.apkUrl.ifBlank { r.pageUrl })
    fun openPage(c: Context, r: Release) = open(c, r.pageUrl)

    private fun open(c: Context, url: String) {
        try {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}
