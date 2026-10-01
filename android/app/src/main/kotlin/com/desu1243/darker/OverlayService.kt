package com.desu1243.darker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
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
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class OverlayService : Service() {
    companion object {
        const val ACTION_START = "com.desu1243.darker.action.START"
        const val ACTION_UPDATE_OPACITY = "com.desu1243.darker.action.UPDATE_OPACITY"
        const val ACTION_STOP = "com.desu1243.darker.action.STOP"
        const val ACTION_START_CAPTURE = "com.desu1243.darker.action.START_CAPTURE"

        const val EXTRA_OPACITY = "opacity"
        const val EXTRA_TEXT_AWARE = "textAware"
        const val EXTRA_PROJECTION_RESULT_CODE = "projectionResultCode"
        const val EXTRA_PROJECTION_DATA = "projectionData"

        // Android rejects pass-through touches above 0.80 obscuring opacity.
        // 0.78 leaves a small safety margin for vendor-specific behaviour.
        const val MAX_SAFE_OPACITY = 0.78f

        @Volatile var isRunning = false
            private set
        @Volatile var isCaptureRunning = false
            private set
        @Volatile var currentOpacity = 0.45f
            private set
        @Volatile var isTextAwareEnabled = false
            private set

        private const val TAG = "DarkerOverlay"
        private const val CHANNEL_ID = "darker_overlay"
        private const val NOTIFICATION_ID = 41
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var lastAcceptedFrameNanos = 0L

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
            ACTION_START_CAPTURE -> startCapture(intent)
            ACTION_START, null -> {
                currentOpacity = intent?.getFloatExtra(EXTRA_OPACITY, currentOpacity)
                    ?.coerceIn(0f, MAX_SAFE_OPACITY) ?: currentOpacity
                isTextAwareEnabled = intent?.getBooleanExtra(EXTRA_TEXT_AWARE, false) ?: false
                startAsForeground(withMediaProjection = false)
                showOverlay()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseCapture()
        removeOverlay()
        isRunning = false
        isTextAwareEnabled = false
        super.onDestroy()
    }

    private fun showOverlay() {
        if (overlayView == null) {
            overlayView = View(this).apply { setBackgroundColor(Color.BLACK) }
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
                alpha = currentOpacity
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            windowManager.addView(overlayView, overlayParams)
        } else {
            updateOpacity(currentOpacity)
        }
        isRunning = true
    }

    private fun updateOpacity(requestedOpacity: Float) {
        currentOpacity = requestedOpacity.coerceIn(0f, MAX_SAFE_OPACITY)
        val view = overlayView ?: return
        val params = overlayParams ?: return
        params.alpha = currentOpacity
        runCatching { windowManager.updateViewLayout(view, params) }
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

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { reader ->
            reader.setOnImageAvailableListener({ availableReader ->
                availableReader.acquireLatestImage()?.use(::acceptCapturedFrame)
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

    /**
     * The capture pipeline is intentionally ready before OCR is introduced.
     * Frames are throttled here; the next milestone will turn accepted frames
     * into text bounds and update a cut-out mask in the overlay view.
     */
    private fun acceptCapturedFrame(image: Image) {
        val timestamp = image.timestamp
        if (timestamp - lastAcceptedFrameNanos < 300_000_000L) return
        lastAcceptedFrameNanos = timestamp
        Log.v(TAG, "Captured frame ${image.width}x${image.height}")
    }

    private fun releaseCapture() {
        isCaptureRunning = false
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
