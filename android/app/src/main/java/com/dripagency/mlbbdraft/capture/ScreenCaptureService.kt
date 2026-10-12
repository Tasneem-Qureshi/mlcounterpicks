package com.dripagency.mlbbdraft.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.dripagency.mlbbdraft.DraftCompanionApp
import com.dripagency.mlbbdraft.MainActivity
import com.dripagency.mlbbdraft.R
import com.dripagency.mlbbdraft.data.model.UserSettings
import com.dripagency.mlbbdraft.overlay.OverlayManager
import com.dripagency.mlbbdraft.vision.CalibrationDumper
import com.dripagency.mlbbdraft.vision.DraftPhase
import com.dripagency.mlbbdraft.vision.DraftStateMachine
import com.dripagency.mlbbdraft.vision.FrameReadings
import com.dripagency.mlbbdraft.vision.LayoutProfile
import com.dripagency.mlbbdraft.vision.AnchoredRect
import com.dripagency.mlbbdraft.vision.SlotLayouts
import com.dripagency.mlbbdraft.vision.SlotReading
import com.dripagency.mlbbdraft.vision.VisionDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One foreground service, two modes:
 *   OVERLAY_ONLY  – no screen capture.  Watches for MLBB in the foreground (Usage access) and shows
 *                   the card; enemies are entered with the in-card picker.  Zero consent prompts.
 *   CAPTURE       – adds MediaProjection screen detection (optional, enabled from the notification
 *                   or the app; Android asks for consent each time it is enabled).
 *
 * Power design for CAPTURE: one virtual display at ~960 px wide, surface detached whenever MLBB
 * is not in front or nothing has happened for a while, 1.5–5 s sampling, portrait frames skipped.
 */
class ScreenCaptureService : LifecycleService() {

    enum class Mode { OFF, OVERLAY_ONLY, CAPTURE }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var loopJob: Job? = null
    @Volatile private var surfaceAttached = false

    private lateinit var overlay: OverlayManager
    private var detector: VisionDetector? = null
    private lateinit var coordinator: DraftCoordinator
    private lateinit var watcher: ForegroundAppWatcher
    private val stateMachine = DraftStateMachine()
    private var layout: LayoutProfile = SlotLayouts.DEFAULT
    private var calibration: CalibrationDumper? = null

    @Volatile private var settings = UserSettings()
    @Volatile private var mastered: Set<String> = emptySet()
    @Volatile private var showRequested = false
    private var frameBitmap: Bitmap? = null
    private var captureW = 0
    private var captureH = 0
    private var lastActivity = 0L
    private var mlbbWasFront: Boolean? = null
    private var started = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { Log.i(TAG, "projection stopped by system/user"); mainHandler.post { stopCapture() } }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || virtualDisplay == null) return
            val (w, h, _) = captureSize()
            if (w != captureW || h != captureH) { Log.i(TAG, "display changed → capture $w x $h"); resizeCapture() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val graph = DraftCompanionApp.graph(this)
        overlay = OverlayManager(this, lifecycleScope).also { it.onClosedByUser = { showRequested = false } }
        coordinator = DraftCoordinator(lifecycleScope, graph.repository, overlay, stateMachine).also {
            it.settings = { settings }; it.mastered = { mastered }; it.pinned = { showRequested }; it.captureActive = { projection != null }
            it.onDraftFinished = { mainHandler.post { Log.i(TAG, "draft finished → screen detection off"); stopCapture() } }
        }
        watcher = ForegroundAppWatcher(this)
        layout = SlotLayouts.load(this)
        lifecycleScope.launch { graph.prefs.settings.collectLatest { settings = it; overlay.setOpacity(it.overlayOpacity) } }
        lifecycleScope.launch { graph.repository.preferredIds.collectLatest { mastered = it } }
        lifecycleScope.launch {
            graph.repository.dataChanged.collectLatest {
                withContext(Dispatchers.Default) { if (projection != null) detector = VisionDetector.load(this@ScreenCaptureService); coordinator.prepare() }
            }
        }
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, mainHandler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START_OVERLAY -> ensureStarted()
            ACTION_SHOW_OVERLAY -> { ensureStarted(); showRequested = true; lastActivity = System.currentTimeMillis(); attachSurface(); coordinator.render(); cancelAlert() }
            ACTION_HIDE_OVERLAY -> { showRequested = false; overlay.dismiss() }
            ACTION_STOP_CAPTURE -> stopCapture()
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                                    else intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (code == Int.MIN_VALUE || data == null) { Log.e(TAG, "missing projection result"); ensureStarted(); return START_NOT_STICKY }
                ensureStarted()
                // Android 14+: startForeground with the mediaProjection type MUST precede getMediaProjection().
                foreground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                startCapture(code, data)
            }
        }
        return START_NOT_STICKY
    }

    /** Overlay-only baseline: foreground as special-use, engine ready, watcher loop running. */
    private fun ensureStarted() {
        if (started) return
        started = true
        foreground(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        _mode.value = Mode.OVERLAY_ONLY
        lastActivity = System.currentTimeMillis()
        loopJob = lifecycleScope.launch(Dispatchers.Default) {
            coordinator.prepare()
            coordinator.render()                       // confirm the overlay works right after Start
            lifecycleScope.launch { delay(STATUS_PREVIEW_MS); if (!showRequested && stateMachine.phase == DraftPhase.IDLE && mlbbWasFront != true) overlay.dismiss() }
            loop()
        }
    }

    private fun foreground(type: Int) = ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)

    // ---- capture (optional) --------------------------------------------------------------
    private fun startCapture(code: Int, data: Intent) {
        if (projection != null) return
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p: MediaProjection = runCatching { mpm.getMediaProjection(code, data) }
            .onFailure { Log.e(TAG, "getMediaProjection failed", it) }
            .getOrNull() ?: run { foreground(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE); return }
        p.registerCallback(projectionCallback, mainHandler)
        projection = p
        createCapture()
        _mode.value = Mode.CAPTURE
        lastActivity = System.currentTimeMillis()
        foreground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) // refresh actions
        lifecycleScope.launch(Dispatchers.Default) {
            detector = VisionDetector.load(this@ScreenCaptureService)
            calibration = CalibrationDumper(this@ScreenCaptureService)
            coordinator.render()
        }
    }

    private fun stopCapture() {
        if (projection == null) return
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop(); projection = null
        surfaceAttached = false
        detector = null
        stateMachine.reset()
        _mode.value = if (started) Mode.OVERLAY_ONLY else Mode.OFF
        if (started) { foreground(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE); if (overlay.isShowing) coordinator.render() }
    }

    private fun createCapture() {
        val p = projection ?: return
        val (w, h, dpi) = captureSize()
        captureW = w; captureH = h
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = p.createVirtualDisplay("mlbb-draft-capture", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, mainHandler)
        surfaceAttached = true
    }

    private fun resizeCapture() {
        val vd = virtualDisplay ?: return
        val (w, h, dpi) = captureSize()
        captureW = w; captureH = h
        val old = imageReader
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        vd.resize(w, h, dpi)
        if (surfaceAttached) vd.setSurface(reader.surface)
        old?.close()
    }

    private fun attachSurface() {
        if (surfaceAttached) return
        val vd = virtualDisplay ?: return
        imageReader?.let { vd.setSurface(it.surface); surfaceAttached = true }
    }

    private fun detachSurface() {
        if (!surfaceAttached) return
        virtualDisplay?.setSurface(null)
        surfaceAttached = false
    }

    // ---- main loop -----------------------------------------------------------------------
    private suspend fun loop() {
        var lastPhase: DraftPhase? = null
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            val mlbbFront = if (settings.detectAnywhere) null else watcher.isMlbbInFront()
            handleMlbbTransition(mlbbFront)

            if (projection == null) {                       // overlay-only: just watch the foreground app
                if (mlbbFront == false && overlay.isShowing && !showRequested) overlay.dismiss()
                delay(if (mlbbFront == true) 3_000L else 4_000L)
                continue
            }
            if (stateMachine.phase == DraftPhase.IDLE && now - lastActivity > CAPTURE_IDLE_OFF_MS) {
                Log.i(TAG, "no draft for ${CAPTURE_IDLE_OFF_MS / 60_000} min → screen detection off")
                withContext(Dispatchers.Main) { stopCapture() }
                continue
            }
            val mustCapture = when {
                mlbbFront == false -> false
                stateMachine.phase != DraftPhase.IDLE -> true
                showRequested -> true
                mlbbFront == true -> true
                else -> now - lastActivity < IDLE_WATCH_MS
            }
            if (!mustCapture) {
                detachSurface()
                if (mlbbFront == false && overlay.isShowing && !showRequested) overlay.dismiss()
                delay(if (mlbbFront == false) 4_000L else 5_000L)
                continue
            }
            attachSurface()
            grabFrame()?.let { f -> runCatching { process(f) }.onFailure { Log.w(TAG, "frame failed", it) } }

            val phase = stateMachine.phase
            if (phase != lastPhase) { Log.i(TAG, "phase → $phase"); lastPhase = phase; if (phase != DraftPhase.IDLE) lastActivity = now }
            val interval = when (phase) {
                DraftPhase.DRAFTING -> settings.samplingMillis
                DraftPhase.LOCKED -> settings.samplingMillis + 500
                DraftPhase.IN_GAME, DraftPhase.COOLDOWN -> 4_000L
                DraftPhase.IDLE -> if (showRequested) 2_000L else 3_000L
            }
            delay(interval.coerceIn(800L, 6000L))
        }
    }

    private fun handleMlbbTransition(front: Boolean?) {
        if (front == null || front == mlbbWasFront) return
        mlbbWasFront = front
        if (front) {
            lastActivity = System.currentTimeMillis()
            if (settings.autoShowOnMlbb) { showRequested = true; coordinator.render() } else postAlert()
        } else cancelAlert()
    }

    private fun grabFrame(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val strideW = plane.rowStride / plane.pixelStride
            var bmp = frameBitmap
            if (bmp == null || bmp.width != strideW || bmp.height != image.height) {
                bmp?.recycle(); bmp = Bitmap.createBitmap(strideW, image.height, Bitmap.Config.ARGB_8888); frameBitmap = bmp
            }
            bmp.copyPixelsFromBuffer(plane.buffer)
            if (strideW != image.width) Bitmap.createBitmap(bmp, 0, 0, image.width, image.height) else bmp
        } catch (t: Throwable) { Log.w(TAG, "grabFrame failed", t); null } finally { image.close() }
    }

    private fun process(frame: Bitmap) {
        val det = detector ?: return
        if (frame.width < frame.height) {
            coordinator.onSnapshot(stateMachine.onFrame(FrameReadings(emptyList(), emptyList(), emptyList(), null)), null)
            return
        }
        val gray = det.toGray(frame)
        try {
            val content = det.contentRect(gray)
            var bestId: String? = null; var bestScore = 0f
            var key = 0
            fun read(rects: List<AnchoredRect>, tryMirror: Boolean) = rects.map { r ->
                val m = det.identifyRegion(gray, r.toRect(content), key++, tryMirror)
                det.lastCandidate?.let { if (it.second > bestScore) { bestScore = it.second; bestId = it.first } }
                SlotReading(m?.heroId, m?.confidence ?: 0f)
            }
            val allies = read(layout.allySlots, tryMirror = true)
            val enemies = read(layout.enemySlots, tryMirror = true)
            val bans = read(layout.allyBans + layout.enemyBans, tryMirror = false)
            val lane = if (settings.autoDetectLane && det.hasLaneTemplates)
                layout.laneBadges.firstNotNullOfOrNull { det.detectLane(gray, it.toRect(content)) } else null
            val readings = FrameReadings(allies, enemies, bans, lane)
            val diag = "${frame.width}×${frame.height} · content ${content.width()}×${content.height()} · best ${bestId ?: "–"} ${"%.2f".format(bestScore)}/${"%.2f".format(det.config.acceptScore)}"
            if (settings.calibrationDump) calibration?.maybeDump(frame, layout, content, readings, diag)
            coordinator.onSnapshot(stateMachine.onFrame(readings), diag)
        } finally { gray.release() }
    }

    private data class CaptureSize(val w: Int, val h: Int, val dpi: Int)

    private fun captureSize(): CaptureSize {
        val (sw, sh, dpi) = screenSize()
        val scale = CAPTURE_LONG_EDGE.toFloat() / maxOf(sw, sh)
        if (scale >= 1f) return CaptureSize(sw, sh, dpi)
        return CaptureSize(((sw * scale).toInt() / 2) * 2, ((sh * scale).toInt() / 2) * 2, (dpi * scale).toInt().coerceAtLeast(120))
    }

    private fun screenSize(): CaptureSize {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
            CaptureSize(b.width(), b.height(), resources.configuration.densityDpi)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
            CaptureSize(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    override fun onDestroy() {
        started = false
        _mode.value = Mode.OFF
        loopJob?.cancel()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        stopCapture()
        frameBitmap?.recycle(); frameBitmap = null
        coordinator.reset()
        overlay.destroy()
        cancelAlert()
        super.onDestroy()
    }

    // ---- notifications -------------------------------------------------------------------
    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_capture), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL_ID, getString(R.string.notif_channel_alert), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun servicePending(action: String, req: Int): PendingIntent = PendingIntent.getService(
        this, req, Intent(this, ScreenCaptureService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(): Notification {
        val capture = projection != null
        val consent = PendingIntent.getActivity(
            this, 30, Intent(this, ProjectionConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_overlay)
            .setContentTitle(getString(R.string.notif_capture_title))
            .setContentText(if (capture) getString(R.string.notif_capture_text) else getString(R.string.notif_overlay_only_text))
            .setContentIntent(servicePending(ACTION_SHOW_OVERLAY, 10))
            .addAction(0, getString(R.string.notif_show), servicePending(ACTION_SHOW_OVERLAY, 11))
            .let {
                if (capture) it.addAction(0, getString(R.string.notif_disable_detection), servicePending(ACTION_STOP_CAPTURE, 14))
                else it.addAction(0, getString(R.string.notif_enable_detection), consent)
            }
            .addAction(0, getString(R.string.notif_stop), servicePending(ACTION_STOP, 13))
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun postAlert() {
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_overlay)
            .setContentTitle(getString(R.string.notif_alert_title))
            .setContentText(getString(R.string.notif_alert_text))
            .setContentIntent(servicePending(ACTION_SHOW_OVERLAY, 20))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ALERT_ID, n)
    }

    private fun cancelAlert() = getSystemService(NotificationManager::class.java).cancel(ALERT_ID)

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "capture"
        private const val ALERT_CHANNEL_ID = "alerts"
        private const val NOTIF_ID = 1001
        private const val ALERT_ID = 1002
        private const val CAPTURE_LONG_EDGE = 1280
        private const val IDLE_WATCH_MS = 3 * 60_000L
        private const val CAPTURE_IDLE_OFF_MS = 10 * 60_000L
        private const val STATUS_PREVIEW_MS = 10_000L
        const val ACTION_START = "com.dripagency.mlbbdraft.action.START"
        const val ACTION_START_OVERLAY = "com.dripagency.mlbbdraft.action.START_OVERLAY"
        const val ACTION_STOP = "com.dripagency.mlbbdraft.action.STOP"
        const val ACTION_STOP_CAPTURE = "com.dripagency.mlbbdraft.action.STOP_CAPTURE"
        const val ACTION_SHOW_OVERLAY = "com.dripagency.mlbbdraft.action.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.dripagency.mlbbdraft.action.HIDE_OVERLAY"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private val _mode = MutableStateFlow(Mode.OFF)
        val mode: StateFlow<Mode> = _mode

        fun startOverlayOnly(context: Context) =
            context.startForegroundService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_START_OVERLAY))

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(i)
        }

        fun stop(context: Context) = context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP))
        fun stopCapture(context: Context) = context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP_CAPTURE))
        fun showOverlay(context: Context) = context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_SHOW_OVERLAY))
    }
}
