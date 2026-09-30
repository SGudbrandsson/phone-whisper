package com.kafkasl.phonewhisper

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs

class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhisperAccessibilityService? = null
        private const val TAG = "PhoneWhisper"
        private const val SAMPLE_RATE = 16000
        private const val BTN_DP = 44
        private const val PAD_DP = 10
        private const val MARGIN_DP = 8
        private const val TAP_THRESHOLD_DP = 10
        private const val RING_DP = 56
        private const val FEEDBACK_OFFSET_DP = 64

        private const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        private const val COLOR_RECORDING = 0xDDEF4444.toInt()
        private const val COLOR_BUSY = 0xDD6B6B6B.toInt()
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        private const val COLOR_RING = 0xFFE8EAED.toInt()
        private const val COLOR_PILL_BG = 0xEE1C1C1E.toInt()
        private const val COLOR_CANCEL = 0xFF3A3A3C.toInt()
        private const val COLOR_STOP = 0xFFEF4444.toInt()
        /** Hairline around the bubble and pill so they stand out on dark screens. */
        private const val COLOR_OUTLINE = 0x33FFFFFF
        private const val PILL_W_DP = 216
        private const val PILL_GAP_DP = 6
        private const val QUICK_HISTORY_COUNT = 10
        private const val VISIBILITY_DEBOUNCE_MS = 120L
        private const val HIDE_DELAY_MS = 600L
        /** 10 minutes of 16 kHz 16-bit mono; keeps uploads under the common 25 MB API limit. */
        private const val MAX_RECORDING_BYTES = 10 * 60 * 16000 * 2
    }

    @VisibleForTesting internal enum class State { IDLE, RECORDING, TRANSCRIBING }

    @VisibleForTesting @Volatile internal var state = State.IDLE
        private set
    @VisibleForTesting internal var overlayView: FrameLayout? = null
        private set
    private var button: ImageView? = null
    private var spinner: ProgressBar? = null
    @VisibleForTesting internal var feedbackView: TextView? = null
        private set
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null

    // Recording pill: [✕] waveform/status timer [✓], shown beside the bubble while busy.
    @VisibleForTesting internal var pillView: android.widget.LinearLayout? = null
        private set
    private var pillParams: WindowManager.LayoutParams? = null
    private var pillShown = false
    private var waveform: WaveformView? = null
    @VisibleForTesting internal val waveformForTest get() = waveform
    private var pillStatus: TextView? = null
    private var pillTimer: TextView? = null
    private var pillStop: View? = null
    private var pillBusy: View? = null
    private var recordStartMs = 0L
    private val tickTimer = object : Runnable {
        override fun run() {
            if (state != State.RECORDING) return
            val secs = (System.currentTimeMillis() - recordStartMs) / 1000
            pillTimer?.text = String.format(java.util.Locale.US, "%d:%02d", secs / 60, secs % 60)
            handler.postDelayed(this, 500)
        }
    }
    private var audioRecord: AudioRecord? = null
    private var pcmStream: ByteArrayOutputStream? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable {
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            feedbackView?.visibility = View.GONE
        }?.start()
    }

    private val settings by lazy { AppSettings(this) }
    /** Shared so the history screen can retry with the already-loaded local model. */
    val engine by lazy { TranscriptionEngine(this) }
    private val history by lazy { HistoryStore.get(this) }

    // Increments on every new recording and on cancel, so late results from an
    // abandoned session are dropped instead of being typed into the wrong place.
    @Volatile private var session = 0
    private var currentJob: TranscriptionEngine.Job? = null
    private var targetPackage: String? = null

    private val dp get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    public override fun onServiceConnected() { // public so tests can drive it
        instance = this
        Diagnostics.info(TAG, "Accessibility service connected")
        showOverlay()
        refreshVisibility()
        // The keyboard/focus events around unlocking can arrive while still "locked"; re-check after unlock.
        androidx.core.content.ContextCompat.registerReceiver(
            this, unlockReceiver, android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        HistoryCleanupWorker.schedule(this)
        thread {
            history.prune(settings.retentionDays)
            // In local-only mode, load the model now so the first dictation is fast.
            // Otherwise it is loaded only when a fallback actually needs it.
            if (settings.mode == TranscriptionMode.LOCAL_ONLY) engine.obtainLocalModel()
        }
    }

    // --- Show only when typing ---

    private var bubbleVisible = true
    private val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: android.content.Intent?) {
            if (overlayView != null) handler.postDelayed(evaluateVisibility, 300)
        }
    }
    private val evaluateVisibility = Runnable { applyVisibility(computeTypingState()) }
    private val hideBubbleNow = Runnable {
        if (state == State.IDLE && sheetView == null && !computeTypingState()) setBubbleVisible(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!settings.showOnlyWhenTyping) return
        // Coalesce bursts of focus/window events into one check.
        handler.removeCallbacks(evaluateVisibility)
        handler.postDelayed(evaluateVisibility, VISIBILITY_DEBOUNCE_MS)
    }

    /** True when the keyboard is on screen or an editable field has input focus. */
    private fun computeTypingState(): Boolean {
        if (isLocked()) return false // never offer dictation on the lock screen (PIN/password fields)
        val imeVisible = try {
            windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (_: Exception) { false }
        if (imeVisible) return true
        val root = rootInActiveWindow ?: return false
        return try {
            if (root.packageName == packageName) return false // our own settings screen
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focus ->
                (focus.isEditable || focus.className?.toString()?.contains("EditText") == true).also { focus.recycle() }
            } ?: false
        } finally { root.recycle() }
    }

    private fun applyVisibility(typing: Boolean) {
        val keepVisible = !settings.showOnlyWhenTyping || typing ||
            state != State.IDLE || sheetView != null
        if (keepVisible) {
            handler.removeCallbacks(hideBubbleNow)
            setBubbleVisible(true)
        } else if (bubbleVisible) {
            // Short grace period so moving between fields doesn't flicker the bubble.
            handler.removeCallbacks(hideBubbleNow)
            handler.postDelayed(hideBubbleNow, HIDE_DELAY_MS)
        }
    }

    private fun setBubbleVisible(visible: Boolean) {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        if (visible == bubbleVisible) return
        bubbleVisible = visible
        view.visibility = if (visible) View.VISIBLE else View.GONE
        // A hidden overlay must not swallow touches meant for the app underneath.
        params.flags = if (visible) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(view, params)
    }

    /** Called from settings when the show-only-when-typing option changes. */
    fun refreshVisibility() = handler.post { applyVisibility(computeTypingState()) }
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(unlockReceiver) } catch (_: IllegalArgumentException) {}
        session++
        state = State.IDLE
        currentJob?.cancel()
        currentJob = null
        releaseRecorder()
        pcmStream = null
        removeOverlay()
        thread { engine.unloadLocalModel() }
        super.onDestroy()
    }

    /** Called from MainActivity when the model or mode changes. */
    fun reloadModel() {
        thread {
            engine.unloadLocalModel()
            if (settings.mode == TranscriptionMode.LOCAL_ONLY) engine.obtainLocalModel()
        }
    }

    // --- Overlay ---

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val buttonSize = (BTN_DP * dp).toInt()
        val ringSize = (RING_DP * dp).toInt()
        val pad = (PAD_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()

        val ring = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
            visibility = View.GONE
        }

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            background = circle(COLOR_IDLE)
        }

        val overlay = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
        }

        val params = WindowManager.LayoutParams(
            ringSize, ringSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - ringSize - margin
            y = screenH / 2 - ringSize / 2
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var longPressed = false
        val longPress = Runnable {
            if (state == State.IDLE) {
                longPressed = true
                overlay.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                showQuickHistory()
            }
        }

        overlay.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    longPressed = false
                    handler.postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(ev.rawX - touchX) + abs(ev.rawY - touchY) >= TAP_THRESHOLD_DP * dp) {
                        handler.removeCallbacks(longPress)
                    }
                    if (longPressed) return@setOnTouchListener true
                    params.x = startX + (ev.rawX - touchX).toInt()
                    params.y = startY + (ev.rawY - touchY).toInt()
                    wm.updateViewLayout(v, params)
                    moveFeedbackWithBubble()
                    updatePillPosition()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (longPressed) return@setOnTouchListener true
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (moved < TAP_THRESHOLD_DP * dp) {
                        onTap()
                    } else {
                        params.x = if (params.x + ringSize / 2 > screenW / 2)
                            screenW - ringSize - margin else margin
                        wm.updateViewLayout(v, params)
                        moveFeedbackWithBubble()
                        updatePillPosition()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(longPress); true }
                else -> false
            }
        }

        val feedback = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = pill(COLOR_FEEDBACK_BG)
            alpha = 0f
            visibility = View.GONE
        }

        val feedbackParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        positionFeedback(feedbackParams, params)

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
        overlayView = overlay
        button = img
        spinner = ring
        feedbackView = feedback
        layoutParams = params
        feedbackLayoutParams = feedbackParams
        buildPill(ringSize)
    }

    /** Keeps the feedback toast next to the bubble. Both are added in [showOverlay]. */
    private fun moveFeedbackWithBubble() {
        val view = feedbackView ?: return
        val fp = feedbackLayoutParams ?: return
        val bp = layoutParams ?: return
        positionFeedback(fp, bp)
        (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(view, fp)
    }

    private fun buildPill(height: Int) {
        val btn = (34 * dp).toInt()
        val iconPad = (8 * dp).toInt()
        fun roundButton(icon: Int, color: Int, desc: String, onClick: () -> Unit) = ImageView(this).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(iconPad, iconPad, iconPad, iconPad)
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), circle(color, outline = false), null)
            contentDescription = desc
            setOnClickListener { onClick() }
            layoutParams = android.widget.LinearLayout.LayoutParams(btn, btn)
        }

        val cancel = roundButton(R.drawable.ic_close, COLOR_CANCEL, "Cancel dictation") { cancelDictation() }
        val stop = roundButton(R.drawable.ic_check, COLOR_STOP, "Stop and transcribe") { if (state == State.RECORDING) stopAndTranscribe() }
        val busy = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(0xCCFFFFFF.toInt())
            visibility = View.GONE
            layoutParams = android.widget.LinearLayout.LayoutParams((16 * dp).toInt(), (16 * dp).toInt()).apply {
                marginStart = (12 * dp).toInt()
            }
        }

        val wave = WaveformView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, (22 * dp).toInt(), 1f).apply {
                marginStart = (8 * dp).toInt(); marginEnd = (6 * dp).toInt()
            }
        }
        val status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (8 * dp).toInt(); marginEnd = (6 * dp).toInt()
            }
        }
        val timer = TextView(this).apply {
            textSize = 12f
            setTextColor(0xBBFFFFFF.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * dp).toInt() }
        }

        val pad = ((height - btn) / 2)
        val pill = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = height / 2f
                setColor(COLOR_PILL_BG)
                setStroke(maxOf(1, dp.toInt()), COLOR_OUTLINE) // keeps the pill visible on dark apps
            }
            addView(cancel); addView(wave); addView(busy); addView(status); addView(timer); addView(stop)
        }

        pillParams = WindowManager.LayoutParams(
            (PILL_W_DP * dp).toInt(), height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        pillView = pill; waveform = wave; pillStatus = status; pillTimer = timer; pillStop = stop; pillBusy = busy
    }

    /** Places the pill on whichever side of the bubble has room. */
    private fun updatePillPosition() {
        val pill = pillView ?: return
        val pp = pillParams ?: return
        val bp = layoutParams ?: return
        val gap = (PILL_GAP_DP * dp).toInt()
        val bubbleW = bp.width
        val onRight = bp.x + bubbleW / 2 > screenW / 2
        pp.x = if (onRight) bp.x - pp.width - gap else bp.x + bubbleW + gap
        pp.x = pp.x.coerceIn(0, maxOf(0, screenW - pp.width))
        pp.y = bp.y
        if (pillShown) (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(pill, pp)
    }

    private fun showPillRecording() = handler.post {
        waveform?.clear()
        waveform?.visibility = View.VISIBLE
        pillStatus?.visibility = View.GONE
        pillBusy?.visibility = View.GONE
        pillStop?.visibility = View.VISIBLE
        pillTimer?.visibility = View.VISIBLE
        pillTimer?.text = "0:00"
        attachPill()
        handler.removeCallbacks(tickTimer)
        handler.post(tickTimer)
    }

    private fun showPillStatus(text: String) = handler.post {
        waveform?.visibility = View.GONE
        pillStatus?.text = text
        pillStatus?.visibility = View.VISIBLE
        pillBusy?.visibility = View.VISIBLE
        pillStop?.visibility = View.GONE
        pillTimer?.visibility = View.GONE
        attachPill()
    }

    private fun attachPill() {
        val pill = pillView ?: return
        if (!pillShown) {
            // Position first: with pillShown still false this only computes the params.
            // updateViewLayout on a view that isn't added yet throws IllegalArgumentException.
            updatePillPosition()
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(pill, pillParams)
            pillShown = true
        }
    }

    private fun hidePill() = handler.post {
        handler.removeCallbacks(tickTimer)
        val pill = pillView ?: return@post
        if (pillShown) {
            pillShown = false
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(pill)
        }
    }

    // --- Quick history (long-press the bubble) ---

    @VisibleForTesting internal var sheetView: View? = null
        private set

    private fun isLocked() = (getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked

    private fun showQuickHistory() {
        if (!settings.historyEnabled) { showFeedback("History is turned off", 1500); return }
        if (isLocked()) { showFeedback("Unlock to see history", 1500); return }
        thread {
            val entries = history.recent(QUICK_HISTORY_COUNT)
            handler.post { buildQuickHistory(entries) }
        }
    }

    private fun dismissQuickHistory() {
        val sheet = sheetView ?: return
        (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(sheet)
        sheetView = null
        handler.post { if (overlayView != null) applyVisibility(computeTypingState()) }
    }

    private fun buildQuickHistory(entries: List<HistoryEntry>) {
        if (overlayView == null || isLocked()) return // service stopped or device locked meanwhile
        dismissQuickHistory()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val pad = (12 * dp).toInt()
        val white = 0xFFFFFFFF.toInt()
        val dim = 0x99FFFFFF.toInt()

        val list = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }

        val header = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad / 2, pad / 2)
            addView(TextView(context).apply {
                text = "Recent dictations"
                textSize = 14f
                setTextColor(white)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = "All"
                textSize = 14f
                setTextColor(0xFF8AB4F8.toInt())
                setPadding(pad, pad / 2, pad, pad / 2)
                setOnClickListener {
                    dismissQuickHistory()
                    startActivity(android.content.Intent(context, HistoryActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            })
            addView(TextView(context).apply {
                text = "✕"
                textSize = 16f
                setTextColor(white)
                setPadding(pad, pad / 2, pad / 2, pad / 2)
                contentDescription = "Close"
                setOnClickListener { dismissQuickHistory() }
            })
        }

        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Nothing yet. Your dictations will show up here."
                setTextColor(dim)
                textSize = 13f
                setPadding(pad, pad, pad, pad * 2)
            })
        }
        for (e in entries) {
            val failed = e.status == HistoryEntry.Status.FAILED
            list.addView(android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(pad, pad / 2 + pad / 4, pad, pad / 2 + pad / 4)
                val outValue = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                addView(TextView(context).apply {
                    text = if (failed) "Failed: ${e.error ?: "unknown error"}" else e.text
                    textSize = 14f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(if (failed) 0xFFFF8A80.toInt() else white)
                })
                addView(TextView(context).apply {
                    val action = if (failed) (if (e.canRetry) "Tap to retry" else "Audio not saved") else "Tap to insert"
                    text = "${HistoryActions.relativeTime(e.createdAt)} · $action"
                    textSize = 11f
                    setTextColor(dim)
                })
                setOnClickListener { onQuickHistoryTap(e) }
            })
        }

        val scroll = android.widget.ScrollView(this).apply { addView(list) }
        val sheet = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = pill(COLOR_PILL_BG).apply { setStroke(maxOf(1, dp.toInt()), COLOR_OUTLINE) }
            addView(header)
            addView(scroll)
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_OUTSIDE) { dismissQuickHistory(); true } else false
            }
        }

        val width = minOf((320 * dp).toInt(), screenW - (2 * MARGIN_DP * dp).toInt())
        sheet.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val height = minOf(sheet.measuredHeight, (380 * dp).toInt())
        val bp = layoutParams
        val params = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - width) / 2
            y = ((bp?.y ?: screenH / 2) - height / 2).coerceIn((24 * dp).toInt(), maxOf(0, screenH - height - (24 * dp).toInt()))
        }
        wm.addView(sheet, params)
        sheetView = sheet
    }

    private fun onQuickHistoryTap(e: HistoryEntry) {
        dismissQuickHistory()
        when {
            isLocked() -> Unit
            e.status == HistoryEntry.Status.OK && e.text != null -> injectText(e.text, feedback = "Inserted from history")
            e.canRetry && state == State.IDLE -> retryFromHistory(e)
            else -> showFeedback("This recording's audio wasn't kept", 2000)
        }
    }

    /** Retries a failed entry and types the result into the focused field, like a normal dictation. */
    private fun retryFromHistory(e: HistoryEntry) {
        session++
        val id = session
        state = State.TRANSCRIBING
        setAppearance(COLOR_BUSY)
        setBusy(true)
        showPillStatus("Retrying…")
        thread {
            val job = HistoryActions.retry(this, engine, e) { outcome ->
                if (id != session) return@retry
                when (outcome) {
                    is TranscriptionEngine.Outcome.Success -> finish(id) { injectText(outcome.text, feedback = outcome.note ?: "Retry succeeded") }
                    is TranscriptionEngine.Outcome.Failure -> finish(id) { showFeedback("Retry failed: ${outcome.error}", 3500) }
                }
            }
            handler.post {
                if (id != session) { job?.cancel(); return@post }
                if (job == null) reset("Saved audio is missing")
                else if (state != State.IDLE) currentJob = job // may already have finished
            }
        }
    }

    private fun removeOverlay() {
        dismissQuickHistory()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView?.let {
            wm.removeView(it)
            overlayView = null
        }
        feedbackView?.let {
            wm.removeView(it)
            feedbackView = null
        }
        handler.removeCallbacks(tickTimer)
        handler.removeCallbacks(evaluateVisibility)
        handler.removeCallbacks(hideBubbleNow)
        if (pillShown) pillView?.let { wm.removeView(it) }
        pillShown = false
        pillView = null
        button = null
        spinner = null
        layoutParams = null
        feedbackLayoutParams = null
    }

    private fun circle(color: Int, outline: Boolean = true) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
        if (outline) setStroke(maxOf(1, dp.toInt()), COLOR_OUTLINE)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16 * dp
        setColor(color)
    }

    private fun setAppearance(color: Int) {
        handler.post { button?.background = circle(color) }
    }

    private fun setBusy(visible: Boolean) {
        handler.post {
            spinner?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun positionFeedback(
        feedbackParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams
    ) {
        val margin = (MARGIN_DP * dp).toInt()
        val offset = (FEEDBACK_OFFSET_DP * dp).toInt()
        feedbackParams.x = maxOf(margin, bubbleParams.x - offset)
        feedbackParams.y = maxOf(margin, bubbleParams.y - margin)
    }

    private fun showFeedback(text: String, durationMs: Long = 2000) {
        handler.post {
            val view = feedbackView ?: return@post
            val bubbleParams = layoutParams ?: return@post
            val feedbackParams = feedbackLayoutParams ?: return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager

            view.text = text
            positionFeedback(feedbackParams, bubbleParams)
            wm.updateViewLayout(view, feedbackParams)

            handler.removeCallbacks(hideFeedback)
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(120).start()
            handler.postDelayed(hideFeedback, durationMs)
        }
    }

    private var pulse: android.animation.ObjectAnimator? = null

    private fun startPulse() {
        stopPulse()
        // One repeating animator. Chaining end actions would spin the main thread when the
        // user has turned animations off, since each animation then ends immediately.
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return
        val b = button ?: return
        pulse = android.animation.ObjectAnimator.ofFloat(b, View.ALPHA, 1f, 0.4f).apply {
            duration = 500
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        button?.alpha = 1f
    }

    // --- State machine ---

    private fun onTap() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopAndTranscribe()
            State.TRANSCRIBING -> {}
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("Grant audio permission in Phone Whisper app"); return
        }

        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufSize <= 0) { toast("Microphone unavailable"); return }
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (_: SecurityException) { toast("Audio permission denied"); return }
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release(); audioRecord = null
            toast("Microphone unavailable"); return
        }

        session++
        targetPackage = rootInActiveWindow?.let { root -> root.packageName?.toString().also { root.recycle() } }
        val stream = ByteArrayOutputStream()
        pcmStream = stream
        val recorder = audioRecord!!
        try { recorder.startRecording() } catch (e: IllegalStateException) {
            releaseRecorder(); pcmStream = null
            toast("Microphone busy"); return
        }
        handler.removeCallbacks(hideBubbleNow)
        state = State.RECORDING
        recordStartMs = System.currentTimeMillis()
        setBusy(false)
        setAppearance(COLOR_RECORDING)
        startPulse()
        showPillRecording()

        thread {
            val buf = ByteArray(bufSize)
            while (state == State.RECORDING) {
                val n = try { recorder.read(buf, 0, buf.size) } catch (_: IllegalStateException) { break }
                if (n < 0) break // recorder stopped/released or errored
                if (n == 0) continue
                stream.write(buf, 0, n)
                if (stream.size() >= MAX_RECORDING_BYTES) {
                    handler.post { if (state == State.RECORDING && pcmStream === stream) { showFeedback("Max length reached", 1500); stopAndTranscribe() } }
                    break
                }
                val level = AudioLevel.levelOf(buf, n)
                handler.post { waveform?.push(level) }
            }
        }
    }

    private fun stopAndTranscribe() {
        state = State.TRANSCRIBING
        stopPulse()
        setAppearance(COLOR_BUSY)
        setBusy(true)

        releaseRecorder()

        val pcm = pcmStream?.toByteArray() ?: ByteArray(0)
        pcmStream = null

        if (pcm.isEmpty()) { reset("No audio captured"); return }
        showPillStatus("Transcribing…")

        val id = session
        val target = targetPackage
        currentJob = engine.run(pcm, onStatus = { if (id == session) showPillStatus(it) }) { outcome ->
            if (id != session) return@run
            when (outcome) {
                is TranscriptionEngine.Outcome.Success -> {
                    if (settings.historyEnabled && id == session) safely("save history") {
                        history.addSuccess(outcome.text, outcome.rawText, outcome.source.label, target, HistoryPolicy.durationMs(pcm.size))
                    }
                    val prefix = if (outcome.source == TranscriptionEngine.Source.LOCAL_FALLBACK) "Offline — used local model. " else ""
                    val msg = prefix + (outcome.note ?: "Copied to clipboard")
                    finish(id) { injectText(outcome.text, feedback = msg, feedbackDurationMs = if (outcome.note != null) 3000 else 2000) }
                }
                is TranscriptionEngine.Outcome.Failure -> {
                    // Keep the audio so the dictation isn't lost; it can be retried from history.
                    val saved = settings.historyEnabled && id == session &&
                        HistoryPolicy.durationMs(pcm.size) >= HistoryPolicy.MIN_FAILED_AUDIO_MS &&
                        safely("save failed recording") { history.addFailure(outcome.error, pcm, target) }
                    val msg = if (saved) "${outcome.error} — saved to history, retry from there" else outcome.error
                    finish(id) { showFeedback(msg, 3500) }
                }
            }
        }
    }

    /** Runs [action] on the main thread and returns to idle, unless the session was cancelled. */
    private fun finish(id: Int, action: () -> Unit) {
        handler.post {
            if (id != session) return@post
            currentJob = null
            action()
            state = State.IDLE
            setBusy(false)
            setAppearance(COLOR_IDLE)
            hidePill()
            applyVisibility(computeTypingState())
        }
    }

    /** Discards the current recording or transcription. Nothing is typed or copied. */
    private fun cancelDictation() {
        if (state == State.IDLE) return
        session++ // late results from the cancelled session are ignored
        currentJob?.cancel()
        currentJob = null
        state = State.IDLE
        releaseRecorder()
        pcmStream = null
        stopPulse()
        setBusy(false)
        setAppearance(COLOR_IDLE)
        hidePill()
        showFeedback("Cancelled", 1200)
        applyVisibility(computeTypingState())
    }

    /** Runs a history write; a full disk or DB error must never take down dictation. */
    private inline fun safely(what: String, block: () -> Unit): Boolean = try {
        block(); true
    } catch (e: Exception) {
        Diagnostics.error(TAG, "Failed to $what", e); false
    }

    private fun releaseRecorder() {
        val r = audioRecord ?: return
        audioRecord = null
        try { r.stop() } catch (_: IllegalStateException) {}
        r.release()
    }


    private fun reset(msg: String) {
        toast(msg)
        state = State.IDLE
        setBusy(false)
        setAppearance(COLOR_IDLE)
        hidePill()
        handler.post { applyVisibility(computeTypingState()) }
    }

    // --- Text injection ---

    private fun injectText(
        text: String,
        feedback: String? = "Copied to clipboard",
        feedbackDurationMs: Long = 2000
    ) {
        val clip = ClipData.newPlainText("phonewhisper", text)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        feedback?.let { showFeedback(it, feedbackDurationMs) }

        val candidates = findInjectionCandidates()
        Log.i(TAG, "Injecting text into ${candidates.size} candidate node(s)")

        var injected = false
        try {
            for (candidate in candidates) {
                if (tryInjectIntoNode(candidate, text)) {
                    injected = true
                    break
                }
            }
        } finally {
            candidates.forEach { it.recycle() }
        }

        Log.i(TAG, if (injected) "Text injection action reported success" else "No injection action succeeded; clipboard fallback only")
    }

    private fun findInjectionCandidates(): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        rootInActiveWindow?.let { root ->
            Log.i(TAG, "Active root: package=${root.packageName} class=${root.className}")
            collectInjectionCandidates(root, candidates)
            root.recycle()
        }

        windows
            ?.filter { it.isActive || it.isFocused }
            ?.forEach { window ->
                val root = window.root ?: return@forEach
                Log.i(
                    TAG,
                    "Window root: type=${window.type} active=${window.isActive} focused=${window.isFocused} package=${root.packageName} class=${root.className}"
                )
                collectInjectionCandidates(root, candidates)
                root.recycle()
            }

        return candidates.sortedByDescending(::candidateScore)
    }

    private fun collectInjectionCandidates(
        root: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { out += it }
        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { out += it }
        collectPotentialTargets(root, out)
    }

    private fun collectPotentialTargets(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (isPotentialInjectionTarget(node)) {
            out += AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectPotentialTargets(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun isPotentialInjectionTarget(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isFocused ||
            node.isEditable ||
            className.contains("EditText") ||
            className.contains("TerminalView") ||
            findCustomPasteAction(node) != null
    }

    private fun candidateScore(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString().orEmpty()
        var score = 0
        if (findCustomPasteAction(node) != null) score += 100
        if (className.contains("TerminalView")) score += 80
        if (node.isEditable) score += 60
        if (node.isFocused) score += 40
        if (className.contains("EditText")) score += 20
        return score
    }

    private fun tryInjectIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        logNode("Trying node", node)

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        findCustomPasteAction(node)?.let { action ->
            val ok = node.performAction(action.id)
            Log.i(TAG, "Custom action '${action.label}' (${action.id}) => $ok")
            if (ok) return true
        }

        val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "ACTION_PASTE => $pasteOk")
        if (pasteOk) return true

        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            val current = node.text?.toString().orEmpty()
            val start = if (node.textSelectionStart >= 0) node.textSelectionStart else current.length
            val end = if (node.textSelectionEnd >= 0) node.textSelectionEnd else start
            val replacementStart = minOf(start, end)
            val replacementEnd = maxOf(start, end)
            val updated = current.replaceRange(replacementStart, replacementEnd, text)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    updated
                )
            }
            val setTextOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "ACTION_SET_TEXT => $setTextOk")
            if (setTextOk) return true
        }

        return false
    }

    private fun findCustomPasteAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo.AccessibilityAction? =
        node.actionList.firstOrNull { action ->
            action.label?.toString()?.contains("paste", ignoreCase = true) == true
        }

    private fun logNode(prefix: String, node: AccessibilityNodeInfo) {
        val actions = node.actionList.joinToString { action ->
            action.label?.toString() ?: action.id.toString()
        }
        Log.i(
            TAG,
            "$prefix package=${node.packageName} class=${node.className} focused=${node.isFocused} editable=${node.isEditable} textLen=${node.text?.length ?: 0} actions=[$actions]"
        )
    }

    private fun toast(msg: String) {
        Diagnostics.warn(TAG, msg) // every toast here reports a problem
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
