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
import com.dripagency.mlbbdraft.vision.NormRect
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
 * Foreground service (type mediaProjection) that samples the screen, runs the vision detector on
 * the draft slots and drives the overlay.  Reads pixels only — no game memory, no accessibility
 * service, no input injection.
 *
 * Power design:
 *  - The virtual display is created ONCE (Android 14+ allows one per consent) at a reduced
 *    resolution (~960 px wide).  Mirroring cost scales with surface size, so this alone cuts
 *    GPU/bandwidth work ~6x versus native resolution.
 *  - When MLBB is not in front (Usage access granted) or nothing is happening for a while, the
 *    surface is DETACHED (`setSurface(null)`): the compositor stops mirroring entirely.
 *  - Sampling is 1.5 s while drafting and 3–5 s otherwise; portrait frames are discarded early.
 */
class ScreenCaptureService : LifecycleService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureJob: Job? = null
    @Volatile private var surfaceAttached = false

    private lateinit var overlay: OverlayManager
    private lateinit var detector: VisionDetector
    private lateinit var coordinator: DraftCoordinator
    private lateinit var watcher: ForegroundAppWatcher
    private val stateMachine = DraftStateMachine()
    private var layouts: List<LayoutProfile> = SlotLayouts.DEFAULTS
    private var calibration: CalibrationDumper? = null

    @Volatile private var settings = UserSettings()
    @Volatile private var mastered: Set<String> = emptySet()
    @Volatile private var showRequested = false   // user asked for the overlay via notification
    @Volatile private var detectorReady = false

    private var frameBitmap: Bitmap? = null
    private var captureW = 0
    private var captureH = 0
    private var lastActivity = 0L
    private var mlbbWasFront: Boolean? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { Log.i(TAG, "projection stopped by system/user"); stopSelf() }
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
        coordinator = DraftCoordinator(lifecycleScope, graph.repository, overlay, stateMachine)
        watcher = ForegroundAppWatcher(this)
        layouts = SlotLayouts.load(this)
        lifecycleScope.launch { graph.prefs.settings.collectLatest { settings = it; overlay.setOpacity(it.overlayOpacity) } }
        lifecycleScope.launch { graph.repository.preferredIds.collectLatest { mastered = it } }
        lifecycleScope.launch {
            graph.repository.dataChanged.collectLatest {
                Log.i(TAG, "dataset changed → reloading templates + index")
                withContext(Dispatchers.Default) { detector = VisionDetector.load(this@ScreenCaptureService); coordinator.prepare() }
            }
        }
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, mainHandler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_SHOW_OVERLAY -> { showRequested = true; lastActivity = System.currentTimeMillis(); attachSurface(); coordinator.showStatus(settings, mastered, pinned = true); cancelAlert() }
            ACTION_HIDE_OVERLAY -> { showRequested = false; overlay.dismiss() }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                                    else intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (code == Int.MIN_VALUE || data == null) { Log.e(TAG, "missing projection result"); stopSelf(); return START_NOT_STICKY }
                // Android 14+: startForeground with the mediaProjection type MUST precede getMediaProjection().
                ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                startProjection(code, data)
            }
        }
        return START_NOT_STICKY // a projection token is single-use; never auto-restart with a stale one
    }

    private fun startProjection(code: Int, data: Intent) {
        if (projection != null) return
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p: MediaProjection = runCatching { mpm.getMediaProjection(code, data) }
            .onFailure { Log.e(TAG, "getMediaProjection failed", it) }
            .getOrNull() ?: run { Log.e(TAG, "no MediaProjection (consent revoked?)"); stopSelf(); return }
        p.registerCallback(projectionCallback, mainHandler)
        projection = p
        createCapture()
        _running.value = true
        lastActivity = System.currentTimeMillis()
        captureJob = lifecycleScope.launch(Dispatchers.Default) {
            detector = VisionDetector.load(this@ScreenCaptureService)
            coordinator.prepare()
            detectorReady = true
            calibration = CalibrationDumper(this@ScreenCaptureService)
            // Confirm the overlay works: show the status card for a few seconds right after Start.
            coordinator.showStatus(settings, mastered, pinned = false)
            lifecycleScope.launch { delay(STATUS_PREVIEW_MS); if (!showRequested && stateMachine.phase == DraftPhase.IDLE) overlay.dismiss() }
            captureLoop()
        }
    }

    // ---- virtual display lifecycle ---------------------------------------------------------
    private fun createCapture() {
        val p = projection ?: return
        val (w, h, dpi) = captureSize()
        captureW = w; captureH = h
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = p.createVirtualDisplay(
            "mlbb-draft-capture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, mainHandler,
        )
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
        imageReader?.let { vd.setSurface(it.surface); surfaceAttached = true; Log.d(TAG, "capture resumed") }
    }

    private fun detachSurface() {
        if (!surfaceAttached) return
        virtualDisplay?.setSurface(null)
        surfaceAttached = false
        Log.d(TAG, "capture paused (surface detached)")
    }

    // ---- main loop ---------------------------------------------------------------------------
    private suspend fun captureLoop() {
        var lastPhase: DraftPhase? = null
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            val mlbbFront = watcher.isMlbbInFront()
            handleMlbbTransition(mlbbFront)

            val mustCapture = when {
                mlbbFront == false -> false                            // another app in front: nothing to see
                stateMachine.phase != DraftPhase.IDLE -> true          // draft in progress
                showRequested -> true                                  // user is looking at the card
                mlbbFront == true -> true                              // MLBB in front, watch for a draft
                else -> now - lastActivity < IDLE_WATCH_MS             // unknown foreground: watch for a while, then nap
            }
            if (!mustCapture) {
                detachSurface()
                if (mlbbFront == false && overlay.isShowing && !showRequested) overlay.dismiss()
                delay(if (mlbbFront == false) 4_000L else 5_000L)
                continue
            }
            attachSurface()
            val frame = grabFrame()
            if (frame != null) runCatching { process(frame) }.onFailure { Log.w(TAG, "frame failed", it) }

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
            if (settings.autoShowOnMlbb) { showRequested = true; coordinator.showStatus(settings, mastered, pinned = true) } else postAlert()
        } else {
            cancelAlert()
        }
    }

    /** Copies the latest frame into a reusable Bitmap (handles row-stride padding). */
    private fun grabFrame(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val strideW = plane.rowStride / plane.pixelStride
            var bmp = frameBitmap
            if (bmp == null || bmp.width != strideW || bmp.height != image.height) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(strideW, image.height, Bitmap.Config.ARGB_8888)
                frameBitmap = bmp
            }
            bmp.copyPixelsFromBuffer(plane.buffer)
            if (strideW != image.width) Bitmap.createBitmap(bmp, 0, 0, image.width, image.height) else bmp
        } catch (t: Throwable) {
            Log.w(TAG, "grabFrame failed", t); null
        } finally {
            image.close()
        }
    }

    private fun process(frame: Bitmap) {
        if (!detectorReady) return
        if (frame.width < frame.height) {               // portrait = lobby / other app: nothing to match
            coordinator.onSnapshot(stateMachine.onFrame(FrameReadings(emptyList(), emptyList(), emptyList(), null)), settings, mastered, showRequested, null)
            return
        }
        val layout = SlotLayouts.forAspect(layouts, frame.width, frame.height)
        val gray = detector.toGray(frame)
        try {
            var bestId: String? = null; var bestScore = 0f
            fun read(rects: List<NormRect>) = rects.map { r ->
                val m = detector.identifyRegion(gray, r.toRect(frame.width, frame.height))
                val d = detector.lastCandidate
                if (d != null && d.second > bestScore) { bestScore = d.second; bestId = d.first }
                SlotReading(m?.heroId, m?.confidence ?: 0f)
            }
            val allies = read(layout.allySlots)
            val enemies = read(layout.enemySlots)
            val bans = read(layout.allyBans + layout.enemyBans)
            val lane = if (settings.autoDetectLane && detector.hasLaneTemplates)
                layout.laneBadges.firstNotNullOfOrNull { detector.detectLane(gray, it.toRect(frame.width, frame.height)) } else null
            val readings = FrameReadings(allies, enemies, bans, lane)
            if (settings.calibrationDump) calibration?.maybeDump(frame, layout, readings)
            val snap = stateMachine.onFrame(readings)
            val diag = "frame ${frame.width}×${frame.height} (${layout.name}) · best slot match ${bestId ?: "–"} ${"%.2f".format(bestScore)} / need ${"%.2f".format(detector.config.acceptScore)}"
            coordinator.onSnapshot(snap, settings, mastered, showRequested, diag)
        } finally {
            gray.release()
        }
    }

    private data class CaptureSize(val w: Int, val h: Int, val dpi: Int)

    /** Native screen size scaled so the long edge is ~[CAPTURE_LONG_EDGE] px (even numbers). */
    private fun captureSize(): CaptureSize {
        val (sw, sh, dpi) = screenSize()
        val scale = CAPTURE_LONG_EDGE.toFloat() / maxOf(sw, sh)
        if (scale >= 1f) return CaptureSize(sw, sh, dpi)
        val w = ((sw * scale).toInt() / 2) * 2
        val h = ((sh * scale).toInt() / 2) * 2
        return CaptureSize(w, h, (dpi * scale).toInt().coerceAtLeast(120))
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
        _running.value = false
        captureJob?.cancel()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop(); projection = null
        frameBitmap?.recycle(); frameBitmap = null
        coordinator.reset()
        overlay.destroy()
        cancelAlert()
        super.onDestroy()
    }

    // ---- notifications ------------------------------------------------------------------
    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_capture), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL_ID, getString(R.string.notif_channel_alert), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun servicePending(action: String, req: Int): PendingIntent = PendingIntent.getService(
        this, req, Intent(this, ScreenCaptureService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_overlay)
            .setContentTitle(getString(R.string.notif_capture_title))
            .setContentText(getString(R.string.notif_capture_text))
            .setContentIntent(servicePending(ACTION_SHOW_OVERLAY, 10))   // tapping the notification shows the card
            .addAction(0, getString(R.string.notif_show), servicePending(ACTION_SHOW_OVERLAY, 11))
            .addAction(0, getString(R.string.notif_hide), servicePending(ACTION_HIDE_OVERLAY, 12))
            .addAction(0, getString(R.string.notif_stop), servicePending(ACTION_STOP, 13))
            .addAction(0, getString(R.string.notif_open_app), openApp)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** "MLBB is open" heads-up when auto-show is off. */
    private fun postAlert() {
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_overlay)
            .setContentTitle(getString(R.string.notif_alert_title))
            .setContentText(getString(R.string.notif_alert_text))
            .setContentIntent(servicePending(ACTION_SHOW_OVERLAY, 20))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
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
        private const val CAPTURE_LONG_EDGE = 960
        private const val IDLE_WATCH_MS = 3 * 60_000L
        private const val STATUS_PREVIEW_MS = 10_000L
        const val ACTION_START = "com.dripagency.mlbbdraft.action.START"
        const val ACTION_STOP = "com.dripagency.mlbbdraft.action.STOP"
        const val ACTION_SHOW_OVERLAY = "com.dripagency.mlbbdraft.action.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.dripagency.mlbbdraft.action.HIDE_OVERLAY"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(i)
        }

        fun stop(context: Context) = context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP))
        fun showOverlay(context: Context) = context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_SHOW_OVERLAY))
    }
}
