package com.desu1243.darker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

class OverlayService : Service() {
    companion object {
        const val ACTION_START = "com.desu1243.darker.action.START"
        const val ACTION_UPDATE_OPACITY = "com.desu1243.darker.action.UPDATE_OPACITY"
        const val ACTION_UPDATE_TEXT_OPACITY =
            "com.desu1243.darker.action.UPDATE_TEXT_OPACITY"
        const val ACTION_STOP = "com.desu1243.darker.action.STOP"
        const val ACTION_START_CAPTURE = "com.desu1243.darker.action.START_CAPTURE"
        const val ACTION_UPDATE_REFRESH_INTERVAL = "com.desu1243.darker.action.UPDATE_REFRESH_INTERVAL"
        const val ACTION_UPDATE_JITTER_TOLERANCE = "com.desu1243.darker.action.UPDATE_JITTER_TOLERANCE"

        const val EXTRA_OPACITY = "opacity"
        const val EXTRA_TEXT_DIM_AMOUNT = "textDimAmount"
        const val EXTRA_TEXT_AWARE = "textAware"
        const val EXTRA_PROJECTION_RESULT_CODE = "projectionResultCode"
        const val EXTRA_PROJECTION_DATA = "projectionData"
        const val EXTRA_TEXT_MARGIN_DP = "textMarginDp"
        const val EXTRA_CORNER_RADIUS_DP = "cornerRadiusDp"
        const val EXTRA_FEATHER_DP = "featherDp"
        const val EXTRA_REFRESH_INTERVAL_MS = "refreshIntervalMs"
        const val EXTRA_JITTER_TOLERANCE_PX = "jitterTolerancePx"
        const val SETTINGS_NAME = "overlay_settings"
        const val KEEP_AFTER_CLOSE = "keepOverlayAfterClosing"

        // Keep the overlay window slightly below Android's 0.80 maximum
        // obscuring opacity so touches can pass to applications underneath.
        const val MAX_DIM_AMOUNT = 0.70f

        @Volatile var isRunning = false
            private set
        @Volatile var isCaptureRunning = false
            private set
        @Volatile var currentOpacity = 0.35f
            private set
        @Volatile var currentTextDimAmount = 0.175f
            private set
        @Volatile var isTextAwareEnabled = false
            private set

        private const val TAG = "DarkerOverlay"
        private const val CHANNEL_ID = "darker_overlay"
        private const val NOTIFICATION_ID = 41
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: DimmingOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var textMarginDp = 12f
    private var cornerRadiusDp = 10f
    private var featherDp = 12f
    private var refreshIntervalMs = 400L
    private var jitterTolerancePx = 5f

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var textRecognizer: TextRecognizer? = null
    private var lastOcrAtMs = 0L
    private var ignoreMotionUntilMs = 0L
    private var lastFrameSignature: IntArray? = null
    private var motionActive = false
    private var ocrInFlight = false
    private var captureGeneration = 0
    private var pendingFrameAvailable = false

    private val pendingFrameDrain = Runnable {
        processLatestPendingFrame()
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopEverything()
            ACTION_UPDATE_OPACITY -> updateOpacity(
                intent.getFloatExtra(EXTRA_OPACITY, currentOpacity)
            )
            ACTION_UPDATE_TEXT_OPACITY -> updateTextDimAmount(
                intent.getFloatExtra(EXTRA_TEXT_DIM_AMOUNT, currentTextDimAmount)
            )
            ACTION_START_CAPTURE -> startCapture(intent)
            ACTION_UPDATE_JITTER_TOLERANCE -> {
                jitterTolerancePx = intent.getFloatExtra(EXTRA_JITTER_TOLERANCE_PX, 5f)
                    .coerceIn(0f, 20f)
                overlayView?.setJitterTolerance(jitterTolerancePx)
            }
            ACTION_UPDATE_REFRESH_INTERVAL -> {
                refreshIntervalMs = intent.getLongExtra(EXTRA_REFRESH_INTERVAL_MS, 400L)
                    .coerceIn(50L, 500L)
                overlayView?.setRefreshInterval(refreshIntervalMs)
                schedulePendingFrameDrain()
            }
            ACTION_START, null -> {
                currentOpacity = intent?.getFloatExtra(EXTRA_OPACITY, currentOpacity)
                    ?.coerceIn(0f, MAX_DIM_AMOUNT) ?: currentOpacity
                currentTextDimAmount = intent
                    ?.getFloatExtra(EXTRA_TEXT_DIM_AMOUNT, currentTextDimAmount)
                    ?.coerceIn(0f, currentOpacity) ?: currentTextDimAmount
                isTextAwareEnabled = intent?.getBooleanExtra(EXTRA_TEXT_AWARE, false) ?: false
                textMarginDp = intent?.getFloatExtra(EXTRA_TEXT_MARGIN_DP, 12f) ?: 12f
                cornerRadiusDp = intent?.getFloatExtra(EXTRA_CORNER_RADIUS_DP, 10f) ?: 10f
                featherDp = intent?.getFloatExtra(EXTRA_FEATHER_DP, 12f) ?: 12f
                refreshIntervalMs = intent?.getLongExtra(EXTRA_REFRESH_INTERVAL_MS, 400L)
                    ?.coerceIn(50L, 500L) ?: 400L
                jitterTolerancePx = intent?.getFloatExtra(EXTRA_JITTER_TOLERANCE_PX, 5f)
                    ?.coerceIn(0f, 20f) ?: 5f
                startAsForeground(withMediaProjection = false)
                showOverlay()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE)
                .getBoolean(KEEP_AFTER_CLOSE, false)) {
            stopEverything()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseCapture()
        textRecognizer?.close()
        textRecognizer = null
        removeOverlay()
        isRunning = false
        isTextAwareEnabled = false
        super.onDestroy()
    }

    private fun showOverlay() {
        if (overlayView == null) {
            // The window alpha remains constant for the lifetime of the
            // overlay. Changing it from exactly zero to a non-zero value can
            // switch the HWC/GPU composition path and cause a large brightness
            // jump on some physical displays. Dimming is therefore controlled
            // by the pixel alpha of this background instead.
            overlayView = DimmingOverlayView(this).apply {
                setDimAmount(currentOpacity)
                setTextDimAmount(currentTextDimAmount)
                setTextStyle(textMarginDp, cornerRadiusDp, featherDp)
                setRefreshInterval(refreshIntervalMs)
                setJitterTolerance(jitterTolerancePx)
            }
            overlayParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // This value is used by Android's touch-obscuring check. It is
                // deliberately constant and remains below the 0.80 limit.
                alpha = MAX_DIM_AMOUNT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            windowManager.addView(overlayView, overlayParams)
        } else {
            updateOpacity(currentOpacity)
            overlayView?.setTextDimAmount(currentTextDimAmount)
            overlayView?.setTextStyle(textMarginDp, cornerRadiusDp, featherDp)
            overlayView?.setRefreshInterval(refreshIntervalMs)
            overlayView?.setJitterTolerance(jitterTolerancePx)
        }
        isRunning = true
    }

    private fun updateOpacity(requestedOpacity: Float) {
        currentOpacity = requestedOpacity.coerceIn(0f, MAX_DIM_AMOUNT)
        currentTextDimAmount = currentTextDimAmount.coerceAtMost(currentOpacity)
        val view = overlayView ?: return
        view.setDimAmount(currentOpacity)
        view.setTextDimAmount(currentTextDimAmount)
    }

    private fun updateTextDimAmount(requestedAmount: Float) {
        currentTextDimAmount = requestedAmount.coerceIn(0f, currentOpacity)
        overlayView?.setTextDimAmount(currentTextDimAmount)
    }

    private fun removeOverlay() {
        overlayView?.let { view -> runCatching { windowManager.removeView(view) } }
        overlayView = null
        overlayParams = null
    }

    private fun startCapture(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0)
        val projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        } ?: return

        isTextAwareEnabled = true
        startAsForeground(withMediaProjection = true)
        releaseCapture()

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, projectionData) ?: return
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { releaseCapture() }
            }
        }, mainHandler)

        setupVirtualDisplay(projection)
    }

    private fun setupVirtualDisplay(projection: MediaProjection) {
        val metrics = resources.displayMetrics
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            null
        }
        val width = bounds?.width() ?: metrics.widthPixels
        val height = bounds?.height() ?: metrics.heightPixels

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).also { reader ->
            reader.setOnImageAvailableListener({ _ ->
                // Retain the latest frame until the next OCR slot, including
                // the final frame of a scroll when no more touches arrive.
                pendingFrameAvailable = true
                schedulePendingFrameDrain()
            }, mainHandler)
        }

        virtualDisplay = projection.createVirtualDisplay(
            "DarkerTextAwareCapture",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            mainHandler
        )
        isCaptureRunning = true
        Log.i(TAG, "Screen capture started: ${width}x$height")
    }

    private fun acceptCapturedFrame(image: Image) {
        val now = SystemClock.uptimeMillis()

        val signature = createFrameSignature(image)
        val previous = lastFrameSignature
        lastFrameSignature = signature

        // A full-display projection may include our own overlay. Ignore the
        // short visual change caused by revealing new cut-outs so it cannot be
        // mistaken for user-driven scrolling.
        val suppressMotionDetection = now < ignoreMotionUntilMs

        if (previous == null) {
            startOcr(image, now)
            return
        }

        val frameDifference = signatureDifference(previous, signature)
        val frameMoved = frameDifference >= 5.0
        if (frameMoved && !suppressMotionDetection) {
            if (!motionActive) {
                motionActive = true
                overlayView?.hideTextRegions(animated = true)
            }
        }

        startOcr(image, now)
    }

    private fun createFrameSignature(image: Image): IntArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val columns = 18
        val rows = 32
        val signature = IntArray(columns * rows)

        for (row in 0 until rows) {
            val y = ((row + 0.5f) * image.height / rows).toInt()
                .coerceIn(0, image.height - 1)
            for (column in 0 until columns) {
                val x = ((column + 0.5f) * image.width / columns).toInt()
                    .coerceIn(0, image.width - 1)
                val offset = y * plane.rowStride + x * plane.pixelStride
                val red = buffer.get(offset).toInt() and 0xFF
                val green = buffer.get(offset + 1).toInt() and 0xFF
                val blue = buffer.get(offset + 2).toInt() and 0xFF
                signature[row * columns + column] =
                    (red * 30 + green * 59 + blue * 11) / 100
            }
        }
        return signature
    }

    private fun signatureDifference(first: IntArray, second: IntArray): Double {
        if (first.size != second.size || first.isEmpty()) return Double.MAX_VALUE
        var difference = 0L
        first.indices.forEach { index -> difference += abs(first[index] - second[index]) }
        return difference.toDouble() / first.size
    }

    private fun startOcr(image: Image, now: Long) {
        if (ocrInFlight) return
        ocrInFlight = true
        lastOcrAtMs = now
        val generation = captureGeneration

        val bitmap = imageToBitmap(image)
        val recognizer = textRecognizer ?: TextRecognition
            .getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .also { textRecognizer = it }
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                if (!isCaptureRunning || generation != captureGeneration) {
                    return@addOnSuccessListener
                }
                val boxes = mutableListOf<Rect>()
                result.textBlocks.forEach { block ->
                    block.lines.forEach { line -> line.boundingBox?.let { boxes.add(Rect(it)) } }
                }
                overlayView?.setTextRegions(
                    boxes,
                    bitmap.width,
                    bitmap.height,
                    animated = true,
                )
                ignoreMotionUntilMs = SystemClock.uptimeMillis() + 360L
                Log.v(TAG, "OCR found ${boxes.size} text lines")
            }
            .addOnFailureListener { error -> Log.w(TAG, "OCR failed", error) }
            .addOnCompleteListener {
                bitmap.recycle()
                ocrInFlight = false
                if (pendingFrameAvailable) schedulePendingFrameDrain()
            }
    }

    private fun schedulePendingFrameDrain() {
        if (!pendingFrameAvailable || ocrInFlight || !isCaptureRunning) return
        mainHandler.removeCallbacks(pendingFrameDrain)
        // Motion suppression protects against feedback from our fade; it must
        // not impose a second, fixed OCR interval on the user's setting.
        val nextOcrAt = if (lastOcrAtMs == 0L) 0L else lastOcrAtMs + refreshIntervalMs
        val delay = (nextOcrAt - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        mainHandler.postDelayed(pendingFrameDrain, delay)
    }

    private fun processLatestPendingFrame() {
        if (ocrInFlight) return
        val reader = imageReader ?: return
        val image = reader.acquireLatestImage()
        if (image == null) {
            pendingFrameAvailable = false
            return
        }
        pendingFrameAvailable = false
        image.use { acceptCapturedFrame(it) }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val paddedWidth = image.width +
            (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth == image.width) return padded

        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun releaseCapture() {
        captureGeneration++
        isCaptureRunning = false
        ocrInFlight = false
        pendingFrameAvailable = false
        mainHandler.removeCallbacks(pendingFrameDrain)
        lastFrameSignature = null
        lastOcrAtMs = 0L
        motionActive = false
        ignoreMotionUntilMs = 0L
        overlayView?.clearTextRegions()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        val projection = mediaProjection
        mediaProjection = null
        projection?.stop()
    }

    private fun stopEverything() {
        releaseCapture()
        removeOverlay()
        isRunning = false
        isTextAwareEnabled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startAsForeground(withMediaProjection: Boolean) {
        val notification = buildNotification(withMediaProjection)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (withMediaProjection) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                startForeground(NOTIFICATION_ID, notification, type)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && withMediaProjection -> {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            }
            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Darker overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the screen dimming overlay active"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(withMediaProjection: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, OverlayService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Darker is active")
            .setContentText(
                if (withMediaProjection) "Text Aware capture is active"
                else "Screen dimming is active"
            )
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }
}
