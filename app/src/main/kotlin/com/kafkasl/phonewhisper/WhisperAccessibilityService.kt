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
        private const val PILL_W_DP = 216
        private const val PILL_GAP_DP = 6
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    private var state = State.IDLE
    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
    private var spinner: ProgressBar? = null
    private var feedbackView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null

    // Recording pill: [✕] waveform/status timer [✓], shown beside the bubble while busy.
    private var pillView: android.widget.LinearLayout? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var pillShown = false
    private var waveform: WaveformView? = null
    private var pillStatus: TextView? = null
    private var pillTimer: TextView? = null
    private var pillStop: TextView? = null
    private var recordStartMs = 0L
    private val tickTimer = object : Runnable {
        override fun run() {
            if (state != State.RECORDING) return
            val secs = (System.currentTimeMillis() - recordStartMs) / 1000
            pillTimer?.text = String.format("%d:%02d", secs / 60, secs % 60)
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

    // Local transcription engine. Loaded eagerly in local-only mode, otherwise on first use.
    @Volatile private var localTranscriber: LocalTranscriber? = null
    private val modelLock = Any()

    private val settings by lazy { AppSettings(this) }

    // Increments on every new recording and on cancel, so late results from an
    // abandoned session are dropped instead of being typed into the wrong place.
    @Volatile private var session = 0
    private var currentCall: okhttp3.Call? = null

    private val dp get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        instance = this
        showOverlay()
        // In local-only mode, load the model now so the first dictation is fast.
        // Otherwise it is loaded only when a fallback actually needs it.
        if (settings.mode == TranscriptionMode.LOCAL_ONLY) thread { obtainLocalModel() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        currentCall?.cancel()
        removeOverlay()
        thread { unloadLocalModel() }
        super.onDestroy()
    }

    /** Returns the loaded local model, loading it if needed. Blocking; call off the main thread. */
    private fun obtainLocalModel(): LocalTranscriber? = synchronized(modelLock) {
        val available = LocalTranscriber.availableModels(this)
        val wanted = settings.modelName.takeIf { it in available } ?: available.firstOrNull() ?: return null
        localTranscriber?.let { if (it.modelName == wanted) return it; it.release() }
        localTranscriber = null
        val t0 = System.currentTimeMillis()
        localTranscriber = LocalTranscriber.create(this, wanted)
        Log.i(TAG, "Loaded local model $wanted in ${System.currentTimeMillis() - t0}ms")
        localTranscriber
    }

    private fun unloadLocalModel() = synchronized(modelLock) {
        localTranscriber?.release()
        localTranscriber = null
    }

    private fun hasLocalModel() = LocalTranscriber.availableModels(this).isNotEmpty()

    /** Called from MainActivity when the model or mode changes. */
    fun reloadModel() {
        thread {
            unloadLocalModel()
            if (settings.mode == TranscriptionMode.LOCAL_ONLY) obtainLocalModel()
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

        overlay.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (ev.rawX - touchX).toInt()
                    params.y = startY + (ev.rawY - touchY).toInt()
                    wm.updateViewLayout(v, params)
                    feedbackLayoutParams?.let {
                        positionFeedback(it, params)
                        wm.updateViewLayout(feedbackView, it)
                    }
                    updatePillPosition()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (moved < TAP_THRESHOLD_DP * dp) {
                        onTap()
                    } else {
                        params.x = if (params.x + ringSize / 2 > screenW / 2)
                            screenW - ringSize - margin else margin
                        wm.updateViewLayout(v, params)
                        feedbackLayoutParams?.let {
                            positionFeedback(it, params)
                            wm.updateViewLayout(feedbackView, it)
                        }
                        updatePillPosition()
                    }
                    true
                }
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

    private fun buildPill(height: Int) {
        val btn = (34 * dp).toInt()
        fun roundButton(label: String, color: Int, desc: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = circle(color)
            contentDescription = desc
            setOnClickListener { onClick() }
            layoutParams = android.widget.LinearLayout.LayoutParams(btn, btn)
        }

        val cancel = roundButton("✕", COLOR_CANCEL, "Cancel dictation") { cancelDictation() }
        val stop = roundButton("✓", COLOR_STOP, "Stop and transcribe") { if (state == State.RECORDING) stopAndTranscribe() }

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
                marginStart = (10 * dp).toInt(); marginEnd = (6 * dp).toInt()
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
            }
            addView(cancel); addView(wave); addView(status); addView(timer); addView(stop)
        }

        pillParams = WindowManager.LayoutParams(
            (PILL_W_DP * dp).toInt(), height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        pillView = pill; waveform = wave; pillStatus = status; pillTimer = timer; pillStop = stop
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
        pillStop?.visibility = View.GONE
        pillTimer?.visibility = View.GONE
        attachPill()
    }

    private fun attachPill() {
        val pill = pillView ?: return
        if (!pillShown) {
            pillShown = true
            updatePillPosition()
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(pill, pillParams)
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

    private fun removeOverlay() {
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
        if (pillShown) pillView?.let { wm.removeView(it) }
        pillShown = false
        pillView = null
        button = null
        spinner = null
        layoutParams = null
        feedbackLayoutParams = null
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
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

    private fun startPulse() {
        button?.let {
            it.animate().alpha(0.4f).setDuration(500).withEndAction {
                it.animate().alpha(1f).setDuration(500).withEndAction {
                    if (state == State.RECORDING) startPulse()
                }.start()
            }.start()
        }
    }

    private fun stopPulse() {
        button?.animate()?.cancel()
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
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (_: SecurityException) { toast("Audio permission denied"); return }

        session++
        val stream = ByteArrayOutputStream()
        pcmStream = stream
        val recorder = audioRecord!!
        recorder.startRecording()
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
                if (n <= 0) continue
                stream.write(buf, 0, n)
                val level = AudioLevel.levelOf(buf, n)
                handler.post { waveform?.push(level) }
            }
        }
    }

    private enum class Source { CLOUD, LOCAL, LOCAL_FALLBACK }

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

        val plan = TranscriptionRouter.plan(
            mode = settings.mode,
            online = Connectivity.isOnline(this),
            hasApiKey = settings.hasApiKey,
            hasLocalModel = hasLocalModel(),
            baseUrl = settings.baseUrl,
        )
        Log.i(TAG, "Transcription plan: $plan")
        val id = session
        when (plan) {
            is TranscriptionRouter.Plan.Cloud -> transcribeCloud(pcm, plan.fallbackToLocal, id)
            TranscriptionRouter.Plan.Local -> transcribeLocal(pcm, Source.LOCAL, id)
            is TranscriptionRouter.Plan.Unavailable -> reset(plan.reason)
        }
    }

    private fun transcribeCloud(pcm: ByteArray, fallbackToLocal: Boolean, id: Int) {
        val wav = WavWriter.encode(pcm)
        currentCall = TranscriberClient.transcribe(wav, settings.cloudConfig()) { result ->
            if (id != session) return@transcribe
            currentCall = null
            when {
                !result.text.isNullOrBlank() -> handleTranscriptionResult(result.text, Source.CLOUD, id)
                result.networkFailure && fallbackToLocal -> {
                    Log.i(TAG, "Cloud unreachable (${result.error}); falling back to local")
                    showPillStatus("Offline — local model…")
                    transcribeLocal(pcm, Source.LOCAL_FALLBACK, id)
                }
                else -> finishWithError("Error: ${result.error ?: "empty transcript"}", id)
            }
        }
    }

    private fun transcribeLocal(pcm: ByteArray, source: Source, id: Int) {
        thread {
            try {
                val transcriber = obtainLocalModel()
                if (transcriber == null) { finishWithError("Local model could not be loaded", id); return@thread }

                val samples = pcmToFloat(pcm)
                val t0 = System.currentTimeMillis()
                val text = transcriber.transcribe(samples, SAMPLE_RATE)
                Log.i(TAG, "Local transcription: ${System.currentTimeMillis() - t0}ms, ${samples.size / SAMPLE_RATE}s audio")

                if (id == session) handleTranscriptionResult(text, source, id)
            } catch (e: Exception) {
                Log.e(TAG, "Local transcription failed", e)
                finishWithError("Local error: ${e.message}", id)
            }
        }
    }

    private fun pcmToFloat(pcm: ByteArray): FloatArray {
        val samples = FloatArray(pcm.size / 2)
        for (i in samples.indices) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
        }
        return samples
    }

    private fun handleTranscriptionResult(text: String?, source: Source, id: Int) {
        if (text.isNullOrBlank()) { finishWithError("No speech detected", id); return }

        val localNote = if (source == Source.LOCAL_FALLBACK) "Offline — used local model. " else ""

        if (!settings.usePostProcessing) {
            finish(id) { injectText(text, feedback = "${localNote}Copied to clipboard") }
            return
        }

        // Cleanup needs the network; skip it rather than fail when we are offline.
        val online = source != Source.LOCAL_FALLBACK && Connectivity.isOnline(this)
        val keyMissing = !settings.hasApiKey &&
            Endpoints.normalizeBase(settings.baseUrl) == Endpoints.DEFAULT_BASE_URL
        if (!online || keyMissing) {
            val why = if (!online) "cleanup skipped (offline)" else "cleanup needs an API key"
            finish(id) { injectText(text, feedback = "$localNote${why.replaceFirstChar { it.uppercase() }}", feedbackDurationMs = 3000) }
            return
        }

        currentCall = PostProcessor.process(
            text, settings.postProcessingPrompt, settings.baseUrl, settings.apiKey, settings.chatModel
        ) { result ->
            if (id != session) return@process
            currentCall = null
            finish(id) {
                if (!result.text.isNullOrBlank()) {
                    injectText(result.text, feedback = "${localNote}Copied to clipboard")
                } else {
                    injectText(text, feedback = "Cleanup failed — raw copied to clipboard", feedbackDurationMs = 3000)
                }
            }
        }
    }

    /** Runs [action] on the main thread and returns to idle, unless the session was cancelled. */
    private fun finish(id: Int, action: () -> Unit) {
        handler.post {
            if (id != session) return@post
            action()
            state = State.IDLE
            setBusy(false)
            setAppearance(COLOR_IDLE)
            hidePill()
        }
    }

    /** Discards the current recording or transcription. Nothing is typed or copied. */
    private fun cancelDictation() {
        if (state == State.IDLE) return
        session++ // late results from the cancelled session are ignored
        currentCall?.cancel()
        currentCall = null
        state = State.IDLE
        releaseRecorder()
        pcmStream = null
        stopPulse()
        setBusy(false)
        setAppearance(COLOR_IDLE)
        hidePill()
        showFeedback("Cancelled", 1200)
    }

    private fun releaseRecorder() {
        val r = audioRecord ?: return
        audioRecord = null
        try { r.stop() } catch (_: IllegalStateException) {}
        r.release()
    }

    private fun finishWithError(msg: String, id: Int) = finish(id) { toast(msg) }

    private fun reset(msg: String) {
        toast(msg)
        state = State.IDLE
        setBusy(false)
        setAppearance(COLOR_IDLE)
        hidePill()
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
            "$prefix package=${node.packageName} class=${node.className} focused=${node.isFocused} editable=${node.isEditable} text=${node.text} desc=${node.contentDescription} actions=[$actions]"
        )
    }

    private fun toast(msg: String) { handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}
