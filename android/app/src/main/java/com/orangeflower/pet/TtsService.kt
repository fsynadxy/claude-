package com.orangeflower.pet

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException

private const val MSG_PREPARE = 1
private const val MSG_SPEAK = 2
private const val MSG_STOP = 3
private const val MSG_SENTENCE = 10
private const val MSG_DONE = 11

/**
 * Runs the speech engine in its own process (":tts"), so a crash inside the native TTS library
 * only ends that process — the pet keeps running and simply shows the text without voice.
 */
class TtsService : Service() {

    private val handler = Handler(Looper.getMainLooper()) { msg -> handle(msg); true }
    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun handle(msg: Message) {
        when (msg.what) {
            MSG_PREPARE -> Voice.prepare(this)
            MSG_STOP -> Voice.stop()
            MSG_SPEAK -> {
                val parts = msg.data.getStringArrayList("parts") ?: arrayListOf()
                val gen = msg.arg1
                val reply = msg.replyTo
                Voice.speak(this, parts, onSentence = { i, seconds ->
                    send(reply, Message.obtain(null, MSG_SENTENCE, gen, i).apply { data = Bundle().apply { putFloat("s", seconds) } })
                }, onDone = { ok ->
                    send(reply, Message.obtain(null, MSG_DONE, gen, if (ok) 1 else 0).apply {
                        data = Bundle().apply { putString("error", Voice.error) }
                    })
                })
            }
        }
    }

    private fun send(to: Messenger?, m: Message) {
        try {
            to?.send(m)
        } catch (_: RemoteException) {
        }
    }
}

/** Main-process side: talks to [TtsService] and survives it crashing. */
object VoiceClient {
    private val main = Handler(Looper.getMainLooper()) { msg -> onReply(msg); true }
    private val replyTo = Messenger(main)
    private var service: Messenger? = null
    private var binding = false
    private val queue = mutableListOf<Message>()
    private var app: Context? = null

    private var gen = 0
    private var onSentence: ((Int, Float) -> Unit)? = null
    private var onDone: ((Boolean) -> Unit)? = null
    var lastError: String? = null
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            service = Messenger(binder)
            try {
                binder.linkToDeath({ main.post { onDied() } }, 0)
            } catch (_: RemoteException) {
                onDied()
                return
            }
            queue.forEach { send(it) }
            queue.clear()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            onDied()
        }
    }

    private fun ensureBound(c: Context) {
        if (binding) return
        val ctx = c.applicationContext
        app = ctx
        binding = ctx.bindService(Intent(ctx, TtsService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun send(m: Message) {
        val s = service
        if (s == null) {
            queue.add(m)
            return
        }
        try {
            s.send(m)
        } catch (_: RemoteException) {
            onDied()
        }
    }

    fun prepare(c: Context) {
        ensureBound(c)
        send(Message.obtain(null, MSG_PREPARE))
    }

    fun speak(c: Context, parts: List<String>, onSentence: (Int, Float) -> Unit, onDone: (Boolean) -> Unit) {
        ensureBound(c)
        finishPending(false)
        gen++
        this.onSentence = onSentence
        this.onDone = onDone
        send(Message.obtain(null, MSG_SPEAK, gen, 0).apply {
            data = Bundle().apply { putStringArrayList("parts", ArrayList(parts)) }
            replyTo = this@VoiceClient.replyTo
        })
    }

    /** Stops speaking; the pending callback still gets onDone(false) so the bubble can finish. */
    fun stop() {
        gen++
        if (service != null) send(Message.obtain(null, MSG_STOP))
        finishPending(false)
    }

    private fun finishPending(ok: Boolean) {
        val done = onDone
        onDone = null
        onSentence = null
        done?.invoke(ok)
    }

    private fun onReply(msg: Message) {
        if (msg.arg1 != gen) return
        when (msg.what) {
            MSG_SENTENCE -> onSentence?.invoke(msg.arg2, msg.data.getFloat("s"))
            MSG_DONE -> {
                val ok = msg.arg2 == 1
                if (!ok) lastError = msg.data.getString("error") ?: lastError
                finishPending(ok)
            }
        }
    }

    private fun onDied() {
        service = null
        if (onDone != null) {
            lastError = "语音进程意外退出"
            app?.let { Diagnostics.note(it, "语音进程意外退出（主程序不受影响）") }
            finishPending(false)
        }
        // the binding stays registered; the system reconnects when the service is started again
    }
}
