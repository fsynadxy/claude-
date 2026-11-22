package com.orangeflower.pet

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
    }
}

/** Keeps a small crash log so problems on the phone can be copied and reported. */
object Diagnostics {
    private fun file(c: Context) = File(c.filesDir, "crash.txt")
    private val fmt get() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val proc = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else "?"
                val f = file(app)
                if (f.length() > 64_000) f.delete()
                f.appendText("[${fmt.format(Date())}] $proc / ${thread.name}\n${sw.toString().take(4000)}\n")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun note(c: Context, text: String) {
        try {
            file(c).appendText("[${fmt.format(Date())}] $text\n")
        } catch (_: Throwable) {
        }
    }

    fun report(c: Context): String {
        val sb = StringBuilder()
        sb.append("Clau娘 ${appVersion(c)} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n")
        if (Build.VERSION.SDK_INT >= 30) {
            val am = c.getSystemService(ActivityManager::class.java)
            val exits = try {
                am.getHistoricalProcessExitReasons(c.packageName, 0, 6)
            } catch (_: Throwable) {
                emptyList()
            }
            val interesting = exits.filter {
                it.reason in setOf(
                    ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY,
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                )
            }
            for (e in interesting) {
                sb.append("\n[${fmt.format(Date(e.timestamp))}] ${e.processName} 退出：${reasonName(e.reason)}")
                e.description?.let { sb.append(" — ").append(it.take(300)) }
                sb.append(" · 内存 ${e.pss / 1024} MB\n")
                if (e.reason == ApplicationExitInfo.REASON_ANR) {
                    try {
                        e.traceInputStream?.bufferedReader()?.use { r -> sb.append(r.readLines().take(30).joinToString("\n")).append('\n') }
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        val crash = file(c)
        if (crash.exists()) sb.append("\n").append(crash.readText().takeLast(6000))
        return sb.toString().trim()
    }

    fun hasProblems(c: Context): Boolean = report(c).lines().size > 1

    fun clear(c: Context) {
        file(c).delete()
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "Java 崩溃"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生库崩溃"
        ApplicationExitInfo.REASON_ANR -> "无响应 (ANR)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过高"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初始化失败"
        else -> "原因 $r"
    }

    private fun appVersion(c: Context) = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName
    } catch (_: Throwable) {
        "?"
    }
}
