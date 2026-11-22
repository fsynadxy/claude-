package com.orangeflower.pet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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

    private enum class State { IDLE, WALK, ACT, POKE, PAT, DRAG, FALL, LAND, SIT, SLEEP, CLIMB, HANG, FLY }

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
    private var running = false
    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = ((now - lastTick) / 1000f).coerceIn(0f, 0.1f)
            lastTick = now
            update(dt)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // keyboard dodging ("zero-gear takeoff")
    private var imeTop: Int? = null
    private var keyboardHold = false
    private val keyboard = KeyboardWatcher(context, wm) { top -> if (chatInput == null) onKeyboard(top) }

    // chat: input bubble, speech bubble, voice
    private val bubble = SpeechBubble(context, wm).also { b -> b.onSkip = { VoiceClient.stop() } }
    private var chatInput: ChatInput? = null
    private var flyHop = false

    // interactions answered by the model
    private val modelReacts get() = Prefs.modelReacts(context) && ChatConfig.isReady(context)
    private var reactBusy = false
    private var lastReactAt = -100f
    private var queuedReaction: Pair<String, List<String>>? = null
    private var pokeBurst = 0
    private var landEvent: String? = null
    private val pokeReaction = Runnable {
        val n = pokeBurst
        pokeBurst = 0
        if (n > 0) react(if (n == 1) "戳了你一下" else "连着戳了你 $n 下", if (n >= 4) Lines.angry else Lines.poke)
    }
    private var voiceErrorShown = false
    var onOpenSettings: (() -> Unit)? = null
    var onRequestCapture: (() -> Unit)? = null

    // multi-tap: 1 = chat input, 2 = poke, 3 = look at the screen
    private var tapSeq = 0
    private val tapResolve = Runnable {
        val n = tapSeq
        tapSeq = 0
        when {
            n >= 3 && Prefs.tripleTapLook(context) -> lookAtScreen()
            n >= 2 -> poke()
            else -> openChatInput()
        }
    }
    private val recentPetTaps = ArrayDeque<Float>()
    private var looking = false

    private fun dp(v: Float) = v * density
    private fun rnd(a: Float, b: Float) = a + Random.nextFloat() * (b - a)
    private val groundTop get() = minOf(screenH - bottomInset, imeTop ?: Int.MAX_VALUE) - winH * PetView.FEET
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
        act("curtsy", 2.2f, false, 2.6f)
        say(Lines.greeting(), 3.5f)
        lastTick = SystemClock.uptimeMillis()
        running = true
        Choreographer.getInstance().postFrameCallback(tick)
        keyboard.start()
        bubble.attach()
        if (Voice.enabled(context)) VoiceClient.prepare(context)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(tick)
        handler.removeCallbacksAndMessages(null)
        keyboard.stop()
        chatInput?.close()
        bubble.detach()
        VoiceClient.stop()
        VoiceClient.release()   // lets the system reclaim the speech process and its model
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
        if ((state == State.CLIMB || state == State.HANG) && !keyboardHold) startFall()
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
        x = x.safeCoerce(minX, maxX)
        y = y.safeCoerce(topInset - winH * 0.3f, groundTop)
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
        enter(State.WALK, "walk", 10f, true, rnd(3f, 8f))
    }

    private fun startSit() = enter(State.SIT, "sit", 0f, true, rnd(12f, 25f))

    private fun startSleep() {
        enter(State.SLEEP, "sleep", 0.5f, true, rnd(40f, 90f))
        effect("zzz", 3f)
    }

    private fun startClimb(left: Boolean) {
        climbLeft = left
        x = if (left) 0f else (screenW - winW).toFloat()
        enter(State.CLIMB, "climb", 4f, true, rnd(4f, 9f))
    }

    private fun startHang() {
        y = topInset - winH * 0.05f
        enter(State.HANG, "hang", 0f, true, rnd(3f, 6f))
    }

    private fun startFly() {
        enter(State.FLY, "fly", 8f)
        flyHop = false
        vx = 0f
        vy = 0f
        effect("exclaim", 1.0f)
        say(Lines.pick(Lines.takeoff), 2.2f)
    }

    /** Hops up onto a higher ground (the top of the keyboard while chatting). */
    private fun startHop() {
        enter(State.FLY, "fly", 8f)
        flyHop = true
        vx = 0f
        vy = 0f
    }

    /** Keyboard reported by the chat input: stand on top of it so the input bubble stays visible. */
    private fun onChatKeyboard(top: Int?) {
        imeTop = top
        if (top != null) {
            if (y > groundTop + 1f && state != State.DRAG && state != State.FLY) startHop()
        } else if (state != State.DRAG && state != State.FLY && state != State.HANG && state != State.CLIMB && y < groundTop - 1f) {
            startFall()
        }
    }

    private fun onKeyboard(top: Int?) {
        imeTop = top
        if (top != null) {
            val feet = y + winH * PetView.FEET
            if (Prefs.dodgeKeyboard(context) && feet > top - dp(8f) &&
                state != State.DRAG && state != State.FLY && state != State.HANG && state != State.CLIMB
            ) {
                keyboardHold = true
                startFly()
            }
        } else {
            if (keyboardHold && state == State.HANG) dur = t + 0.6f   // let go shortly after the keyboard closes
            keyboardHold = false
            // she may be standing on top of the keyboard: drop back to the real ground
            if (state != State.DRAG && state != State.FLY && state != State.HANG && state != State.CLIMB && y < groundTop - 1f) {
                startFall()
            }
        }
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
        if (!modelReacts && Random.nextFloat() < 0.6f) say(Lines.pick(Lines.pat))
    }

    private fun pickNext() {
        if (clock - lastRestReminder > 50 * 60f) {
            lastRestReminder = clock
            act("wave", 6f, true, 3f)
            say(Lines.pick(Lines.rest), 4f)
            return
        }
        val r = Random.nextFloat()
        when {
            r < 0.36f -> startWalk()
            r < 0.46f -> act("read", 0.9f, true, rnd(7f, 12f))
            r < 0.54f -> act("tea", 1.1f, true, rnd(6f, 9f))
            r < 0.60f -> act("hair", 0f, false, 3f)
            r < 0.65f -> { act("think", 0f, false, 3.5f); effect("question", 2f) }
            r < 0.72f -> act("yawn", 1.6f, false, 3f) { if (Random.nextBoolean()) startSit() else idle() }
            r < 0.84f -> startSit()
            r < 0.88f -> { act("bored", 0f, false, 5f); effect("dots", 2.5f) }
            r < 0.95f -> { say(Lines.pick(Lines.chatter)); idle() }
            else -> act("wave", 6f, true, 2.4f)
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
        var offY = 0f
        var rot: Float? = 0f          // null = DRAG/FALL manage rotation themselves
        var pivot = 0.975f

        when (state) {
            State.IDLE -> {
                breath = 0.008f * sin(clock * 2f * Math.PI.toFloat() / 3.2f)
                val blinkT = clock - nextBlink
                when {
                    blinkT in 0f..0.06f || blinkT in 0.16f..0.22f -> { frameAnim = "blink"; frameIndex = 0 }
                    blinkT in 0.06f..0.16f -> { frameAnim = "blink"; frameIndex = 1 }
                    clock < talkUntil -> {
                        // closed, half open, open, half open
                        when ((clock * 11f).toInt() % 4) {
                            1, 3 -> { frameAnim = "talk"; frameIndex = 1 }
                            2 -> { frameAnim = "talk"; frameIndex = 0 }
                        }
                    }
                }
                if (blinkT > 0.22f) nextBlink = clock + rnd(2.5f, 6f)
                if (t > idleFor && !touching && chatInput == null && !bubble.speaking && !looking) pickNext()
            }
            State.WALK -> {
                mirror = facingRight
                x += (if (facingRight) 1f else -1f) * dp(34f) * dt
                if (x <= minX || x >= maxX) {
                    val atLeft = x <= minX
                    x = x.safeCoerce(minX, maxX)
                    if (Random.nextFloat() < 0.3f) startClimb(atLeft) else idle()
                } else if (t > dur) {
                    idle()
                }
            }
            State.ACT -> {
                when (anim) {
                    "happy" -> frameIndex = (t * 8f).toInt()            // hop cycle drawn in the frames
                    "dizzy" -> {
                        // frames lean left, upright, right, upright; the small rotation follows them
                        val phase = t * 5f
                        frameIndex = phase.toInt()
                        rot = 3f * sin(phase * Math.PI.toFloat() / 2f)
                    }
                    "think" -> {
                        breath = 0.006f * sin(clock * 2f * Math.PI.toFloat() / 3f)
                        rot = 1.5f * sin(clock * 1.3f)
                    }
                    else -> breath = 0.006f * sin(clock * 2f * Math.PI.toFloat() / 3f)
                }
                if (t > dur) {
                    val next = afterAct
                    afterAct = null
                    if (next != null) next() else idle()
                }
            }
            State.POKE -> {
                // a quick startled shiver that settles
                if (t < 0.35f) offY = sin(t * 60f) * winH * 0.006f * (1f - t / 0.35f)
                if (t > dur) idle()
            }
            State.PAT -> {
                rot = 1.2f * sin(clock * Math.PI.toFloat() * 1.25f)
                frameIndex = (clock * 5f).toInt()
                if (clock - patStartedAt > 4f) {
                    frameAnim = "shy"; frameIndex = 0
                    if (!modelReacts && clock - patStartedAt < 4f + dt * 1.5f) say(Lines.pick(Lines.shy))
                }
                if (((clock - effectStart) > effectLen)) effect(if (Random.nextBoolean()) "flower" else "heart", 1.4f)
            }
            State.DRAG -> {
                rot = null
                val target = (-dragVx / dp(900f) * 16f).coerceIn(-16f, 16f) + 3f * sin(clock * 5f)
                view.rotationDeg += (target - view.rotationDeg) * (dt * 8f).coerceAtMost(1f)
                dragVx *= (1f - dt * 4f).coerceAtLeast(0f)
            }
            State.FALL -> {
                rot = null
                vy = (vy + dp(1900f) * dt).coerceAtMost(dp(2400f))
                vx *= (1f - dt * 0.8f)
                x += vx * dt
                y += vy * dt
                if (x < minX || x > maxX) {
                    x = x.safeCoerce(minX, maxX)
                    vx = -vx * 0.4f
                }
                view.rotationDeg *= (1f - dt * 6f).coerceAtLeast(0f)
                if (y >= groundTop) {
                    y = groundTop
                    startLand()
                }
            }
            State.LAND -> {
                frameIndex = when {
                    t < 0.12f -> 0
                    t < 0.30f -> 1
                    t < 0.50f -> 2
                    else -> 3
                }.coerceAtMost(sprites.count("land") - 1)
                if (t > dur) {
                    val event = landEvent
                    landEvent = null
                    if (dizzyPending) {
                        dizzyPending = false
                        act("dizzy", 3f, true, 2.6f)
                        effect("swirl", 2.4f)
                    } else {
                        idle()
                    }
                    if (event != null) react(event, if (event.contains("晃")) Lines.dizzy else Lines.drag)
                }
            }
            State.SIT -> {
                // mostly frame 1; a short page turn every few seconds
                frameIndex = if ((t % 7f) > 6.2f) 1 else 0
                breath = 0.005f * sin(clock * 2f * Math.PI.toFloat() / 3.6f)
                if (t > dur) if (Random.nextFloat() < 0.4f) startSleep() else act("yawn", 1.6f, false, 3f)
            }
            State.SLEEP -> {
                val phase = sin(clock * 2f * Math.PI.toFloat() / 4.2f)
                breath = 0.012f * phase
                frameIndex = if (phase < -0.2f) 1 else 0
                if (clock - effectStart > effectLen + 1.5f) effect("zzz", 3f)
                if (t > dur) act("yawn", 1.6f, false, 3f)
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
            State.HANG -> {
                val swing = sin(clock * 2.4f)
                rot = 6f * swing
                pivot = 0.05f
                frameIndex = if (swing < 0f) 0 else 1
                if (!keyboardHold && t > dur) {
                    effect("exclaim", 1.2f)
                    startFall()
                }
            }
            State.FLY -> {
                if (t < (if (flyHop) 0.08f else 0.14f)) {
                    // anticipation crouch, then full speed at once: zero-gear takeoff
                    frameAnim = "land"; frameIndex = 0
                } else {
                    if (flyHop) {
                        val target = groundTop
                        if (target > y + 1f) {
                            startFall()           // the keyboard closed mid-hop: drop down naturally
                        } else {
                            y -= dp(1800f) * dt
                            if (y <= target) {
                                y = target
                                startLand()
                            }
                        }
                    } else {
                        vy = -dp(2600f)
                        y += vy * dt
                        if (y <= topInset - winH * 0.05f) startHang()
                    }
                }
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
        val cx = (x + winW / 2f).roundToInt()
        val head = (y + offY + winH * 0.16f).roundToInt()
        bubble.follow(cx, head, (y + winH * PetView.FEET).roundToInt(), screenW, topInset)
        chatInput?.setAnchor(cx, head)

        val bmp = sprites.frame(frameAnim, frameIndex)
        if (bmp != null) view.frame = bmp
        view.mirror = mirror
        view.breath = breath
        view.offsetY = offY
        if (rot != null) {
            view.rotationDeg = rot
            view.pivotFrac = pivot
        } else {
            view.pivotFrac = 0.17f
        }
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
        if (!running || looking || bubble.speaking) return
        bubble.showLine(text, seconds)
        talkUntil = clock + minOf(text.length * 0.12f, 1.8f)
    }

    // ---------------------------------------------------------------- chat

    private val busy get() = state == State.DRAG || state == State.FALL || state == State.CLIMB || state == State.HANG

    /** Says a whole chat reply in the floating bubble, voiced sentence by sentence when TTS is on. */
    fun speak(text: String) {
        if (!running) return
        val (emotion, body) = Emotion.parse(text)
        val clean = body.ifBlank { "……" }
        val parts = Voice.sentences(clean).ifEmpty { listOf(clean) }
        if (!busy) showEmotion(emotion)
        bubble.begin()
        if (Voice.enabled(context)) {
            var appended = 0
            VoiceClient.speak(context, parts, onSentence = { i, seconds ->
                while (appended < i) { bubble.append(parts[appended], 0.3f); appended++ }
                bubble.append(parts[i], seconds)
                appended = i + 1
                talkUntil = clock + seconds
            }, onDone = { ok ->
                while (appended < parts.size) { bubble.append(parts[appended], parts[appended].length / 14f); appended++ }
                bubble.finish()
                if (!ok && VoiceClient.lastError != null && !voiceErrorShown) {
                    voiceErrorShown = true
                    effect("sweat", 1.5f)
                }
            })
        } else {
            var total = 0f
            parts.forEach { p -> val sec = p.length / 14f; bubble.append(p, sec); total += sec }
            talkUntil = clock + total
            bubble.finish()
        }
    }

    private fun showEmotion(emotion: String?) {
        when (emotion) {
            "开心" -> { act("happy", 4f, true, 2.4f); effect("heart", 1.6f) }
            "害羞" -> { act("shy", 0f, false, 2.8f); effect("heart", 1.4f) }
            "生气" -> { act("angry", 0f, false, 2.6f); effect("anger", 1.8f) }
            "委屈", "难过" -> { act("sad", 0f, false, 2.8f); effect("sweat", 1.8f) }
            "惊讶" -> { enter(State.POKE, "poke", 0f, false, 1.6f); effect("exclaim", 1.4f) }
            "思考" -> { act("think", 0f, false, 2.6f); effect("question", 1.6f) }
            else -> { idle(); effect("sparkle", 1.2f) }
        }
    }

    fun thinking() {
        if (busy) return
        act("think", 0f, false, 30f)
        effect("dots", 3f)
    }

    private fun petRect(): Rect = Rect(x.roundToInt(), y.roundToInt(), (x + winW).roundToInt(), (y + winH).roundToInt())

    private fun openChatInput() {
        if (chatInput != null) return
        if (state == State.SLEEP || state == State.SIT || state == State.ACT || state == State.WALK) idle()
        effect("notice", 0.8f)
        chatInput = ChatInput(
            context, wm,
            petRect = { petRect() },
            onSend = { sendChat(it) },
            onExpand = { onOpenChat?.invoke() },
            onPetTap = { onPetTapWhileChatting() },
            onKeyboard = { top -> onChatKeyboard(top) },
            onClosed = { chatInput = null },
        ).also { it.open(screenW) }
    }

    private fun sendChat(text: String) {
        if (!ChatConfig.isReady(context)) {
            say("先在设置里填好 API 吧，我才能回你。", 4f)
            onOpenSettings?.invoke()
            return
        }
        val card = CardStore.active(context)
        val history = ChatStore.load(context, card.id)
        history.add(Msg("user", text))
        ChatStore.save(context, card.id, history)
        thinking()
        ChatClient.send(context, card, history) { result ->
            result.onSuccess { reply ->
                val h = ChatStore.load(context, card.id)
                h.add(Msg("assistant", reply))
                ChatStore.save(context, card.id, h)
                speak(reply)
            }.onFailure { e ->
                speak("……好像没连上。" + (e.message?.let { "（${it.take(60)}）" } ?: ""))
            }
        }
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
                    if (!modelReacts && Random.nextFloat() < 0.5f) say(Lines.pick(Lines.drag), 2.5f)
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
                        val speed = hypot(dragVx, dragVy)
                        val height = groundTop - y
                        landEvent = when {
                            shakeCount >= 4 -> "把你拎起来使劲晃了好几下，晃得你头晕眼花"
                            speed > dp(1300f) -> "把你拎起来用力甩了出去"
                            height > screenH * 0.35f -> "把你拎到很高的地方，然后松手让你掉了下去"
                            else -> null
                        }
                        vx = dragVx.coerceIn(-dp(1500f), dp(1500f))
                        vy = (dragVy * 0.5f).coerceIn(-dp(700f), dp(900f))
                        startFall()
                    }
                    State.PAT -> {
                        val secs = (clock - patStartedAt).roundToInt().coerceAtLeast(1)
                        act("happy", 4f, true, 1.6f)
                        effect("heart", 1.5f)
                        react("摸了摸你的头，摸了大约 $secs 秒" + if (secs >= 5) "，摸了好久" else "", Lines.pat)
                    }
                    else -> if (e.actionMasked == MotionEvent.ACTION_UP) onTap()
                }
            }
        }
    }

    private fun onTap() {
        when (state) {
            State.CLIMB, State.HANG -> {
                landEvent = if (state == State.CLIMB) "把正在爬墙的你戳了下来" else "把挂在屏幕顶上的你戳了下来"
                effect("exclaim", 1.2f)
                startFall()
            }
            State.SLEEP -> {
                enter(State.POKE, "poke", 0f, false, 1.4f)
                effect("notice", 1.2f)
                react("在你打瞌睡的时候把你叫醒了", Lines.wake)
            }
            State.DRAG, State.FALL, State.FLY -> Unit
            else -> {
                tapSeq++
                handler.removeCallbacks(tapResolve)
                if (tapSeq >= 3) handler.post(tapResolve) else handler.postDelayed(tapResolve, 280)
            }
        }
    }

    /** Taps on her while the input bubble is open: pokes, and three quick ones look at the screen. */
    private fun onPetTapWhileChatting() {
        recentPetTaps.addLast(clock)
        while (recentPetTaps.isNotEmpty() && clock - recentPetTaps.first() > 0.8f) recentPetTaps.removeFirst()
        if (recentPetTaps.size >= 3 && Prefs.tripleTapLook(context)) {
            recentPetTaps.clear()
            chatInput?.close()
            lookAtScreen()
        } else {
            poke()
        }
    }

    fun screenSize(): Pair<Int, Int> = screenW to screenH

    /** Triple tap: hide, ask the system for a one-off screen capture, and let the model look at it. */
    private fun lookAtScreen() {
        if (looking) return
        if (!ChatConfig.isReady(context)) {
            say("先在设置里填好 API，我才能帮你看屏幕。", 3.5f)
            return
        }
        looking = true
        handler.removeCallbacks(pokeReaction)
        pokeBurst = 0
        chatInput?.close()
        bubble.hide()
        view.visibility = View.INVISIBLE
        handler.postDelayed(lookTimeout, 60_000)
        onRequestCapture?.invoke() ?: onScreenshot(null, cancelled = true)
    }

    // if the consent dialog is left without an answer, come back after a minute
    private val lookTimeout = Runnable { if (looking) onScreenshot(null, cancelled = true) }

    fun onScreenshot(bmp: Bitmap?, cancelled: Boolean) {
        handler.removeCallbacks(lookTimeout)
        if (!looking && bmp == null) return
        looking = false
        view.visibility = View.VISIBLE
        if (bmp == null) {
            say(if (cancelled) "那就不看啦。" else "……没看清，再试一次？", 2.6f)
            return
        }
        val image = try {
            ScreenGrabber.toJpegBase64(bmp)
        } catch (e: Exception) {
            null
        } finally {
            bmp.recycle()
        }
        if (image == null) {
            say("……没看清，再试一次？", 2.6f)
            return
        }
        effect("question", 1.4f)
        val card = CardStore.active(context)
        val history = ChatStore.load(context, card.id)
        history.add(Msg("user", "【识图】（给你看了一眼现在的屏幕）"))
        ChatStore.save(context, card.id, history)
        thinking()
        ChatClient.send(context, card, history, image) { result ->
            result.onSuccess { reply ->
                val h = ChatStore.load(context, card.id)
                h.add(Msg("assistant", reply))
                ChatStore.save(context, card.id, h)
                speak(reply)
            }.onFailure { e ->
                val m = e.message ?: ""
                speak(if (m.contains("image", true) || m.contains("vision", true) || m.contains("400"))
                    "这个模型好像看不了图片，换一个支持识图的模型试试？" else "……好像没连上。（${m.take(60)}）")
            }
        }
    }

    /** A tap on her while the input bubble is open: a poke; many pokes make her cross. */
    private fun poke() {
        if (clock - tapWindowStart > 2f) {
            tapWindowStart = clock
            tapCount = 0
        }
        tapCount++
        pokeBurst++
        when {
            tapCount >= 7 -> {
                act("sad", 0f, false, 3f)
                effect("sweat", 2f)
                tapCount = 0
            }
            tapCount >= 4 -> {
                act("angry", 0f, false, 2.4f)
                effect("anger", 2f)
            }
            else -> {
                enter(State.POKE, "poke", 0f, false, 1.0f)
                effect("notice", 1.0f)
            }
        }
        handler.removeCallbacks(pokeReaction)
        handler.postDelayed(pokeReaction, 1100)
    }

    /**
     * Something was done to her. With a chat API configured, the model answers in character
     * (the action goes into the chat history); otherwise a built-in line is used.
     */
    private fun react(event: String, fallback: List<String>) {
        if (!modelReacts) {
            say(Lines.pick(fallback), 2.6f)
            return
        }
        if (reactBusy || clock - lastReactAt < 2.5f) {
            queuedReaction = event to fallback
            return
        }
        reactBusy = true
        lastReactAt = clock
        val card = CardStore.active(context)
        val history = ChatStore.load(context, card.id)
        history.add(Msg("user", ACTION_PREFIX + event))
        ChatStore.save(context, card.id, history)
        ChatClient.send(context, card, history) { result ->
            reactBusy = false
            result.onSuccess { reply ->
                val h = ChatStore.load(context, card.id)
                h.add(Msg("assistant", reply))
                ChatStore.save(context, card.id, h)
                speak(reply)
            }.onFailure { say(Lines.pick(fallback), 2.6f) }
            queuedReaction?.let { (e, f) ->
                queuedReaction = null
                handler.postDelayed({ react(e, f) }, 2600)
            }
        }
    }

}

/** Like coerceIn, but never throws when the range is empty (tiny screens, landscape with a tall keyboard). */
private fun Float.safeCoerce(lo: Float, hi: Float): Float = if (hi < lo) hi else coerceIn(lo, hi)
