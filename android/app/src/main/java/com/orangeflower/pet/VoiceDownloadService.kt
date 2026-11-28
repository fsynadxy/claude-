package com.orangeflower.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.LinkedBlockingDeque
import kotlin.concurrent.thread

/**
 * Downloads voice packs one at a time in the foreground, so a download keeps going (with a
 * progress notification) after leaving the voice screen.
 */
class VoiceDownloadService : Service() {

    private val queue = LinkedBlockingDeque<String>()
    @Volatile private var current: String? = null
    @Volatile private var cancelCurrent = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_CANCEL -> {
                if (id == null || id == current) cancelCurrent = true
                if (id == null) queue.clear() else queue.remove(id)
                if (id != null && id != current) VoiceDownloads.set(id, VoiceDownloads.State.Idle)
                if (current == null && queue.isEmpty()) stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startInForeground("准备下载语音包…", 0, 0)
                if (id != null && id != current && !queue.contains(id) && VoicePacks.find(id) != null) {
                    queue.add(id)
                    VoiceDownloads.set(id, VoiceDownloads.State.Queued)
                }
                if (worker?.isAlive != true) worker = thread(name = "voice-download") { work() }
            }
        }
        return START_NOT_STICKY
    }

    private fun work() {
        while (true) {
            val id = queue.poll() ?: break
            val pack = VoicePacks.find(id) ?: continue
            current = id
            cancelCurrent = false
            var lastNotify = 0L
            try {
                VoicePacks.install(applicationContext, pack, progress = { done, total, phase ->
                    VoiceDownloads.set(id, VoiceDownloads.State.Running(done, total, phase))
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 700 || phase != "下载中") {
                        lastNotify = now
                        startInForeground("${pack.name}：$phase", done, total)
                    }
                }, cancelled = { cancelCurrent })
                VoiceDownloads.set(id, VoiceDownloads.State.Idle)
                notifyDone("「${pack.name}」装好了", "回到语音包页面就能选用")
            } catch (e: VoicePacks.Cancelled) {
                VoiceDownloads.set(id, VoiceDownloads.State.Idle)
            } catch (e: Throwable) {
                val msg = e.message ?: e.javaClass.simpleName
                VoiceDownloads.set(id, VoiceDownloads.State.Failed(msg))
                Diagnostics.note(this, "语音包 ${pack.id} 下载失败：$msg")
                notifyDone("「${pack.name}」没下载成功", msg)
            }
            current = null
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun channel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "语音包下载", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            })
        }
    }

    private fun openScreen() = PendingIntent.getActivity(
        this, 10, Intent(this, VoicePacksActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun startInForeground(text: String, done: Long, total: Long) {
        val nm = getSystemService(NotificationManager::class.java)
        channel(nm)
        val cancel = PendingIntent.getService(
            this, 11, Intent(this, VoiceDownloadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE
        )
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_flower)
            .setContentTitle("正在下载语音包")
            .setContentText(if (total > 0) "$text  ${done / 1_048_576} / ${total / 1_048_576} MB" else text)
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openScreen())
            .addAction(Notification.Action.Builder(null, "取消", cancel).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun notifyDone(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        channel(nm)
        nm.notify(DONE_ID, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_flower)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openScreen())
            .build())
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 limits background data transfers; stop cleanly, the partial file is kept for resuming
        cancelCurrent = true
        queue.clear()
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "voice_download"
        private const val NOTIFICATION_ID = 7
        private const val DONE_ID = 8
        const val ACTION_CANCEL = "cancel"
        const val EXTRA_ID = "id"

        fun start(c: Context, id: String) {
            c.startForegroundService(Intent(c, VoiceDownloadService::class.java).putExtra(EXTRA_ID, id))
        }

        fun cancel(c: Context, id: String) {
            c.startService(Intent(c, VoiceDownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id))
        }
    }
}
