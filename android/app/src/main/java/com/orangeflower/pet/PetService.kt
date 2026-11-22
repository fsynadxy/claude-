package com.orangeflower.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Display
import android.view.WindowManager

/** Foreground service that owns the floating pet window. */
class PetService : Service() {

    private var controller: PetController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESIZE -> {
                controller?.resize(Prefs.size(this))
                return START_STICKY
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (controller == null) {
            val ctx = overlayContext()
            val wm = ctx.getSystemService(WindowManager::class.java)
            controller = PetController(ctx, wm).also {
                it.onOpenChat = { openChat() }
                it.onOpenSettings = {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                it.start(Prefs.size(this))
            }
            running = true
            instance = this
        }
        return START_STICKY
    }

    /** Android 11+ wants overlay windows added through a window context tied to a display. */
    private fun overlayContext(): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return this
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return this
        return createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onScreenChanged()
    }

    private fun openChat() {
        startActivity(Intent(this, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onDestroy() {
        controller?.stop()
        controller = null
        running = false
        instance = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "桌宠常驻", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "桌宠显示在屏幕上时的常驻通知"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PetService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val chat = PendingIntent.getActivity(
            this, 2, Intent(this, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_flower)
            .setContentTitle("Clau娘在屏幕上陪着你")
            .setContentText("点她一下就能聊天，点“收起”让她回去休息")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "聊天", chat).build())
            .addAction(Notification.Action.Builder(null, "收起", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "com.orangeflower.pet.STOP"
        const val ACTION_RESIZE = "com.orangeflower.pet.RESIZE"
        private const val CHANNEL = "pet"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var running = false
            private set

        private var instance: PetService? = null

        /** Shows a chat reply above the pet (main thread). */
        fun speak(text: String) {
            instance?.controller?.speak(text)
        }

        /** Puts the pet into a thinking pose while a reply is on its way (main thread). */
        fun thinking() {
            instance?.controller?.thinking()
        }
    }
}

object Prefs {
    private const val FILE = "pet"
    fun size(context: Context): Float =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getFloat("size", 1f)

    fun modelReacts(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("model_reacts", true)

    fun setModelReacts(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("model_reacts", value).apply()
    }

    fun dodgeKeyboard(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("dodge_keyboard", true)

    fun setDodgeKeyboard(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("dodge_keyboard", value).apply()
    }

    fun setSize(context: Context, value: Float) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putFloat("size", value).apply()
    }
}
