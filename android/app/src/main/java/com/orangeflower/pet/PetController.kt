package com.orangeflower.pet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The pet's brain: a small state machine plus simple physics, driving a floating overlay window.
 * All character frames face left; the view mirrors them when she walks right or climbs the right edge.
 */
class PetController(private val context: Context, private val wm: WindowManager) {

    private enum class State { IDLE, WALK, ACT, POKE, PAT, DRAG, FALL, LAND, SIT, SLEEP, CLIMB, HANG }

    private val sprites = Sprites(context)
    var onOpenChat: (() -> Unit)? = null
    val view = PetView(context)
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val lp = WindowManager.LayoutParams(
        1, 1,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    // window and screen geometry (px)
    private var winW = 0
    private var winH = 0
    private var screenW = 0
    private var screenH = 0
    private var topInset = 0
    private var bottomInset = 0
    private var x = 0f
    private var y = 0f
    private var vx = 0f
    private var vy = 0f

    // state
    private var state = State.IDLE
    private var t = 0f
    private var dur = 0f
    private var anim = "idle"
    private var fps = 0f
    private var loop = true
    private var facingRight = false
    private var climbLeft = true
    private var afterAct: (() -> Unit)? = null
    private var idleFor = 3f
    private var dizzyPending = false

    // overlays
    private var clock = 0f
    private var nextBlink = 3f
    private var effectName: String? = null
    private var effectStart = 0f
    private var effectLen = 0f
    private var bubbleUntil = 0f
    private var talkUntil = 0f
    private var lastRestReminder = 0f

    // touch
    private var downX = 0f
    private var downY = 0f
    private var grabDX = 0f
    private var grabDY = 0f
    private var lastMoveX = 0f
    private var lastMoveY = 0f
    private var lastMoveTime = 0L
    private var dragVx = 0f
    private var dragVy = 0f
    private var shakeCount = 0
    private var lastShakeSign = 0
    private var touching = false
    private var lastTapAt = -10f
    private var tapCount = 0
    private var tapWindowStart = -10f
    private var patStartedAt = 0f
    private var lastTick = 0L

    private val longPress = Runnable { if (touching && state != State.DRAG) startPat() }
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = ((now - lastTick) / 1000f).coerceIn(0f, 0.1f)
            lastTick = now
            update(dt)
            handler.postDelayed(this, 33)
        }
    }

    private fun dp(v: Float) = v * density
    private fun rnd(a: Float, b: Float) = a + Random.nextFloat() * (b - a)
    private val groundTop get() = screenH - bottomInset - winH * PetView.FEET
    private val minX get() = -winW * 0.18f
    private val maxX get() = screenW - winW * 0.82f

    // ---------------------------------------------------------------- lifecycle

    @SuppressLint("ClickableViewAccessibility")
    fun start(sizeFactor: Float) {
        refreshScreen()
        applySize(sizeFactor)
        x = rnd(screenW * 0.2f, screenW * 0.6f)
        y = groundTop
        pushLayout(force = true)
        wm.addView(view, lp)
        view.setOnTouchListener { _, e -> onTouch(e); true }
        act("curtsy", 1.1f, false, 2.4f)
        say(Lines.greeting(), 3.5f)
        lastTick = SystemClock.uptimeMillis()
        handler.post(tick)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        try {
            wm.removeView(view)
        } catch (_: Exception) {
        }
    }

    fun resize(sizeFactor: Float) {
        val feet = y + winH * PetView.FEET
        applySize(sizeFactor)
        y = feet - winH * PetView.FEET
        clampToScreen()
        pushLayout(force = true)
    }

    fun onScreenChanged() {
        refreshScreen()
        clampToScreen()
        if (state in setOf(State.IDLE, State.WALK, State.SIT, State.SLEEP, State.ACT, State.POKE, State.LAND)) y = groundTop
        if (state == State.CLIMB || state == State.HANG) startFall()
        pushLayout(force = true)
    }

    private fun applySize(factor: Float) {
        winH = dp(190f * factor).roundToInt()
        winW = (winH * PetView.ASPECT).roundToInt()
        lp.width = winW
        lp.height = winH
        sprites.setFrameHeight(winH)
    }

    @Suppress("DEPRECATION")
    private fun refreshScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = wm.currentWindowMetrics
            screenW = m.bounds.width()
            screenH = m.bounds.height()
            val ins = m.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.navigationBars() or WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
            )
            topInset = ins.top
            bottomInset = ins.bottom
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels
            screenH = dm.heightPixels
            val res = context.resources
            val nav = res.getIdentifier("navigation_bar_height", "dimen", "android")
            val status = res.getIdentifier("status_bar_height", "dimen", "android")
            bottomInset = if (nav > 0) res.getDimensionPixelSize(nav) else 0
            topInset = if (status > 0) res.getDimensionPixelSize(status) else 0
        }
    }

    private fun clampToScreen() {
        x = x.coerceIn(minX, maxX)
        y = y.coerceIn(topInset - winH * 0.3f, groundTop)
    }

    private fun pushLayout(force: Boolean = false) {
        val nx = x.roundToInt()
        val ny = y.roundToInt()
        if (force || nx != lp.x || ny != lp.y) {
            lp.x = nx
            lp.y = ny
            if (view.isAttachedToWindow) wm.updateViewLayout(view, lp)
        }
    }

    // ---------------------------------------------------------------- states

    private fun enter(s: State, animName: String, frameRate: Float, looping: Boolean = true, duration: Float = 0f) {
        state = s
        t = 0f
        anim = animName
        fps = frameRate
        loop = looping
        dur = duration
    }

    private fun idle() {
        enter(State.IDLE, "idle", 0f)
        idleFor = rnd(3f, 8f)
        view.rotationDeg = 0f
    }

    private fun act(name: String, frameRate: Float, looping: Boolean, duration: Float, then: (() -> Unit)? = null) {
        enter(State.ACT, name, frameRate, looping, duration)
        afterAct = then
        view.rotationDeg = 0f
    }

    private fun startWalk() {
        facingRight = when {
            x < screenW * 0.15f -> true
            x > screenW * 0.65f -> false
            else -> Random.nextBoolean()
        }
        enter(State.WALK, "walk", 6f, true, rnd(3f, 8f))
    }

    private fun startSit() = enter(State.SIT, "sit", 0f, true, rnd(12f, 25f))

    private fun startSleep() {
        enter(State.SLEEP, "sleep", 0.5f, true, rnd(40f, 90f))
        effect("zzz", 3f)
    }

    private fun startClimb(left: Boolean) {
        climbLeft = left
        x = if (left) 0f else (screenW - winW).toFloat()
        enter(State.CLIMB, "climb", 3f, true, rnd(4f, 9f))
    }

    private fun startHang() {
        y = topInset - winH * 0.05f
        enter(State.HANG, "hang", 1.2f, true, rnd(3f, 6f))
    }

    private fun startFall() {
        if (state != State.DRAG) {
            vx = 0f
            vy = 0f
        }
        enter(State.FALL, "fall", 0f)
    }

    private fun startLand() {
        enter(State.LAND, "land", 0f, false, 0.85f)
        vx = 0f
        vy = 0f
    }

    private fun startPat() {
        enter(State.PAT, "pat", 1.5f)
        patStartedAt = clock
        effect("flower", 1.4f)
        if (Random.nextFloat() < 0.6f) say(Lines.pick(Lines.pat))
    }

    private fun pickNext() {
        if (clock - lastRestReminder > 50 * 60f) {
            lastRestReminder = clock
            act("wave", 3f, true, 3f)
            say(Lines.pick(Lines.rest), 4f)
            return
        }
        val r = Random.nextFloat()
        when {
            r < 0.36f -> startWalk()
            r < 0.46f -> act("read", 0.45f, true, rnd(7f, 12f))
            r < 0.54f -> act("tea", 0.45f, true, rnd(6f, 9f))
            r < 0.60f -> act("hair", 0f, false, 3f)
            r < 0.65f -> { act("think", 0f, false, 3.5f); effect("question", 2f) }
            r < 0.72f -> act("yawn", 0.8f, false, 2.6f) { if (Random.nextBoolean()) startSit() else idle() }
            r < 0.84f -> startSit()
            r < 0.88f -> { act("bored", 0f, false, 5f); effect("dots", 2.5f) }
            r < 0.95f -> { say(Lines.pick(Lines.chatter)); idle() }
            else -> act("wave", 3f, true, 2.4f)
        }
    }

    // ---------------------------------------------------------------- per-frame update

    private fun update(dt: Float) {
        clock += dt
        t += dt
        var frameAnim = anim
        var frameIndex = if (fps > 0f) (t * fps).toInt() else 0
        if (!loop) frameIndex = frameIndex.coerceAtMost(sprites.count(anim) - 1)
        var breath = 0f
        var mirror = false

        when (state) {
            State.IDLE -> {
                breath = 0.008f * sin(clock * 2f * Math.PI.toFloat() / 3.2f)
                val blinkT = clock - nextBlink
                when {
                    blinkT in 0f..0.06f || blinkT in 0.16f..0.22f -> { frameAnim = "blink"; frameIndex = 0 }
                    blinkT in 0.06f..0.16f -> { frameAnim = "blink"; frameIndex = 1 }
                    clock < talkUntil && (clock * 7f).toInt() % 2 == 1 -> { frameAnim = "talk"; frameIndex = 0 }
                }
                if (blinkT > 0.22f) nextBlink = clock + rnd(2.5f, 6f)
                if (t > idleFor && !touching) pickNext()
            }
            State.WALK -> {
                mirror = facingRight
                x += (if (facingRight) 1f else -1f) * dp(34f) * dt
                if (x <= minX || x >= maxX) {
                    val atLeft = x <= minX
                    x = x.coerceIn(minX, maxX)
                    if (Random.nextFloat() < 0.3f) startClimb(atLeft) else idle()
                } else if (t > dur) {
                    idle()
                }
            }
            State.ACT -> {
                if (t > dur) {
                    val next = afterAct
                    afterAct = null
                    if (next != null) next() else idle()
                }
            }
            State.POKE -> if (t > dur) idle()
            State.PAT -> {
                if (clock - patStartedAt > 4f) {
                    frameAnim = "shy"; frameIndex = 0
                    if (clock - patStartedAt < 4f + dt * 1.5f) say(Lines.pick(Lines.shy))
                }
                if (((clock - effectStart) > effectLen)) effect(if (Random.nextBoolean()) "flower" else "heart", 1.4f)
            }
            State.DRAG -> {
                val target = (-dragVx / dp(900f) * 16f).coerceIn(-16f, 16f) + 3f * sin(clock * 5f)
                view.rotationDeg += (target - view.rotationDeg) * (dt * 8f).coerceAtMost(1f)
                dragVx *= (1f - dt * 4f).coerceAtLeast(0f)
            }
            State.FALL -> {
                vy = (vy + dp(1900f) * dt).coerceAtMost(dp(2400f))
                vx *= (1f - dt * 0.8f)
                x += vx * dt
                y += vy * dt
                if (x < minX || x > maxX) {
                    x = x.coerceIn(minX, maxX)
                    vx = -vx * 0.4f
                }
                view.rotationDeg *= (1f - dt * 6f).coerceAtLeast(0f)
                if (y >= groundTop) {
                    y = groundTop
                    startLand()
                }
            }
            State.LAND -> {
                frameIndex = if (t < 0.25f) 0 else 1
                view.rotationDeg = 0f
                if (t > dur) {
                    if (dizzyPending) {
                        dizzyPending = false
                        act("dizzy", 3f, true, 2.6f)
                        effect("swirl", 2.4f)
                        say(Lines.pick(Lines.dizzy))
                    } else {
                        idle()
                    }
                }
            }
            State.SIT -> {
                // mostly frame 1; a short page turn every few seconds
                frameIndex = if ((t % 7f) > 6.2f) 1 else 0
                if (t > dur) if (Random.nextFloat() < 0.4f) startSleep() else act("yawn", 0.8f, false, 2.6f)
            }
            State.SLEEP -> {
                if (clock - effectStart > effectLen + 1.5f) effect("zzz", 3f)
                if (t > dur) act("yawn", 0.8f, false, 2.6f)
            }
            State.CLIMB -> {
                mirror = !climbLeft
                y -= dp(28f) * dt
                if (y <= topInset - winH * 0.05f) {
                    startHang()
                } else if (t > dur) {
                    effect("notice", 1.2f)
                    startFall()
                }
            }
            State.HANG -> if (t > dur) {
                effect("exclaim", 1.2f)
                startFall()
            }
        }

        // overlays
        if (effectName != null) {
            val age = (clock - effectStart) / effectLen
            if (age >= 1f) {
                effectName = null
                view.effect = null
            } else {
                view.effectAge = age
            }
        }
        if (view.bubbleText != null && clock > bubbleUntil) view.bubbleText = null

        val bmp = sprites.frame(frameAnim, frameIndex)
        if (bmp != null) view.frame = bmp
        view.mirror = mirror
        view.breath = breath
        view.invalidate()
        pushLayout()
    }

    private fun effect(name: String, seconds: Float) {
        effectName = name
        effectStart = clock
        effectLen = seconds
        view.effect = sprites.effect(name, (winH * 0.13f).roundToInt())
        view.effectAge = 0f
    }

    private fun say(text: String, seconds: Float = 3.2f) {
        view.bubbleText = text
        bubbleUntil = clock + seconds
        talkUntil = clock + minOf(text.length * 0.12f, 1.8f)
    }

    // ---------------------------------------------------------------- chat

    private val busy get() = state == State.DRAG || state == State.FALL || state == State.CLIMB || state == State.HANG

    /** Shows the first sentence of a chat reply in the bubble; the full text stays in the chat panel. */
    fun speak(text: String) {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        val end = Regex("[。！？!?~～]|…+").findAll(clean).firstOrNull { it.range.last >= 6 }
        val cut = end?.let { clean.substring(0, it.range.last + 1) } ?: clean
        val excerpt = if (cut.length > 26) cut.take(24) + "…" else cut
        if (!busy) idle()
        effect("sparkle", 1.2f)
        say(excerpt, (excerpt.length * 0.25f).coerceIn(3f, 8f))
    }

    fun thinking() {
        if (busy) return
        act("think", 0f, false, 30f)
        effect("dots", 3f)
    }

    // ---------------------------------------------------------------- touch

    private fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                downX = e.rawX
                downY = e.rawY
                grabDX = e.rawX - x
                grabDY = e.rawY - y
                lastMoveX = e.rawX
                lastMoveY = e.rawY
                lastMoveTime = e.eventTime
                dragVx = 0f
                dragVy = 0f
                shakeCount = 0
                lastShakeSign = 0
                handler.postDelayed(longPress, 450)
            }
            MotionEvent.ACTION_MOVE -> {
                if (state != State.DRAG && state != State.PAT &&
                    hypot(e.rawX - downX, e.rawY - downY) > touchSlop
                ) {
                    handler.removeCallbacks(longPress)
                    enter(State.DRAG, "drag", 0f)
                    if (Random.nextFloat() < 0.5f) say(Lines.pick(Lines.drag), 2.5f)
                    effect("sweat", 1.5f)
                }
                if (state == State.DRAG) {
                    val dtMs = (e.eventTime - lastMoveTime).coerceAtLeast(1L)
                    val ivx = (e.rawX - lastMoveX) * 1000f / dtMs
                    val ivy = (e.rawY - lastMoveY) * 1000f / dtMs
                    dragVx = dragVx * 0.6f + ivx * 0.4f
                    dragVy = dragVy * 0.6f + ivy * 0.4f
                    if (abs(ivx) > dp(450f)) {
                        val sign = if (ivx > 0) 1 else -1
                        if (sign != lastShakeSign) {
                            if (lastShakeSign != 0) shakeCount++
                            lastShakeSign = sign
                        }
                    }
                    lastMoveX = e.rawX
                    lastMoveY = e.rawY
                    lastMoveTime = e.eventTime
                    x = e.rawX - grabDX
                    y = (e.rawY - grabDY).coerceAtMost(groundTop)
                    pushLayout()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                handler.removeCallbacks(longPress)
                when (state) {
                    State.DRAG -> {
                        if (shakeCount >= 4) dizzyPending = true
                        vx = dragVx.coerceIn(-dp(1500f), dp(1500f))
                        vy = (dragVy * 0.5f).coerceIn(-dp(700f), dp(900f))
                        startFall()
                    }
                    State.PAT -> {
                        act("happy", 4f, true, 1.6f)
                        effect("heart", 1.5f)
                    }
                    else -> if (e.actionMasked == MotionEvent.ACTION_UP) onTap()
                }
            }
        }
    }

    private fun onTap() {
        val doubleTap = clock - lastTapAt < 0.3f
        lastTapAt = clock
        if (clock - tapWindowStart > 2f) {
            tapWindowStart = clock
            tapCount = 0
        }
        tapCount++
        when {
            state == State.CLIMB || state == State.HANG -> {
                effect("exclaim", 1.2f)
                startFall()
            }
            state == State.SLEEP -> {
                enter(State.POKE, "poke", 0f, false, 1.4f)
                effect("notice", 1.2f)
                say(Lines.pick(Lines.wake))
            }
            tapCount >= 7 -> {
                act("sad", 0f, false, 3f)
                effect("sweat", 2f)
                say(Lines.pick(Lines.sad))
                tapCount = 0
            }
            tapCount >= 4 -> {
                act("angry", 0f, false, 2.4f)
                effect("anger", 2f)
                say(Lines.pick(Lines.angry))
            }
            doubleTap -> {
                act("happy", 4f, true, 1.8f)
                effect(if (Random.nextBoolean()) "heart" else "music", 1.6f)
                say(Lines.pick(Lines.chatOpen), 2f)
                onOpenChat?.invoke()
            }
            else -> {
                enter(State.POKE, "poke", 0f, false, 1.0f)
                effect("notice", 1.0f)
                if (Random.nextFloat() < 0.5f) say(Lines.pick(Lines.poke), 2.2f)
            }
        }
    }
}
