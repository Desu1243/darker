package com.desu1243.darker

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    companion object {
        private const val CHANNEL = "com.desu1243.darker/overlay"
        private const val PROJECTION_REQUEST_CODE = 4510
    }

    private lateinit var projectionManager: MediaProjectionManager
    private var methodChannel: MethodChannel? = null
    private var pendingOpacity = 0.35f
    private var pendingTextDimAmount = 0.175f
    private var pendingTextMarginDp = 12f
    private var pendingCornerRadiusDp = 10f
    private var pendingFeatherDp = 12f
    private var pendingRefreshIntervalMs = 400L
    private var pendingJitterTolerancePx = 5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        methodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        ).also { channel ->
            channel.setMethodCallHandler { call, result ->
                when (call.method) {
                    "setKeepOverlayAfterClosing" -> {
                        getSharedPreferences(OverlayService.SETTINGS_NAME, MODE_PRIVATE)
                            .edit()
                            .putBoolean(OverlayService.KEEP_AFTER_CLOSE,
                                call.argument<Boolean>("enabled") ?: false)
                            .apply()
                        result.success(null)
                    }
                    "checkOverlayPermission" -> result.success(Settings.canDrawOverlays(this))
                    "requestOverlayPermission" -> {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                        result.success(null)
                    }
                    "getOverlayState" -> result.success(
                        mapOf(
                            "running" to OverlayService.isRunning,
                            "captureRunning" to OverlayService.isCaptureRunning,
                            "opacity" to OverlayService.currentOpacity.toDouble(),
                            "textDimAmount" to OverlayService.currentTextDimAmount.toDouble(),
                            "textAware" to OverlayService.isTextAwareEnabled
                        )
                    )
                    "startOverlay" -> {
                        if (!Settings.canDrawOverlays(this)) {
                            result.error(
                                "overlay_permission_missing",
                                "Allow Darker to display over other apps first.",
                                null
                            )
                            return@setMethodCallHandler
                        }
                        pendingOpacity = ((call.argument<Double>("opacity") ?: 0.35)
                            .toFloat())
                            .coerceIn(0f, OverlayService.MAX_DIM_AMOUNT)
                        pendingTextDimAmount =
                            ((call.argument<Double>("textDimAmount") ?: 0.175).toFloat())
                                .coerceIn(0f, pendingOpacity)
                        val textAware = call.argument<Boolean>("textAware") ?: false
                        pendingTextMarginDp =
                            (call.argument<Double>("textMarginDp") ?: 12.0).toFloat()
                        pendingCornerRadiusDp =
                            (call.argument<Double>("cornerRadiusDp") ?: 10.0).toFloat()
                        pendingFeatherDp =
                            (call.argument<Double>("featherDp") ?: 12.0).toFloat()
                        pendingRefreshIntervalMs =
                            (call.argument<Number>("refreshIntervalMs")?.toLong() ?: 400L)
                                .coerceIn(50L, 500L)
                        pendingJitterTolerancePx =
                            (call.argument<Number>("jitterTolerancePx")?.toFloat() ?: 5f)
                                .coerceIn(0f, 20f)
                        if (textAware) {
                            @Suppress("DEPRECATION")
                            startActivityForResult(
                                projectionManager.createScreenCaptureIntent(),
                                PROJECTION_REQUEST_CODE
                            )
                            result.success(mapOf("awaitingCapturePermission" to true))
                        } else {
                            startOverlayService(pendingOpacity, textAware = false)
                            result.success(mapOf("started" to true))
                        }
                    }
                    "updateJitterTolerance" -> {
                        if (OverlayService.isRunning) {
                            startService(
                                Intent(this, OverlayService::class.java)
                                    .setAction(OverlayService.ACTION_UPDATE_JITTER_TOLERANCE)
                                    .putExtra(OverlayService.EXTRA_JITTER_TOLERANCE_PX,
                                        (call.argument<Number>("jitterTolerancePx")?.toFloat() ?: 5f)
                                            .coerceIn(0f, 20f))
                            )
                        }
                        result.success(null)
                    }
                    "updateRefreshInterval" -> {
                        if (OverlayService.isRunning) {
                            startService(
                                Intent(this, OverlayService::class.java)
                                    .setAction(OverlayService.ACTION_UPDATE_REFRESH_INTERVAL)
                                    .putExtra(OverlayService.EXTRA_REFRESH_INTERVAL_MS,
                                        (call.argument<Number>("refreshIntervalMs")?.toLong() ?: 400L)
                                            .coerceIn(50L, 500L))
                            )
                        }
                        result.success(null)
                    }
                    "updateOpacity" -> {
                        val opacity = ((call.argument<Double>("opacity") ?: 0.35).toFloat())
                            .coerceIn(0f, OverlayService.MAX_DIM_AMOUNT)
                        startService(
                            Intent(this, OverlayService::class.java)
                                .setAction(OverlayService.ACTION_UPDATE_OPACITY)
                                .putExtra(OverlayService.EXTRA_OPACITY, opacity)
                        )
                        result.success(null)
                    }
                    "updateTextOpacity" -> {
                        val textDimAmount =
                            ((call.argument<Double>("textDimAmount") ?: 0.175).toFloat())
                                .coerceIn(0f, OverlayService.MAX_DIM_AMOUNT)
                        startService(
                            Intent(this, OverlayService::class.java)
                                .setAction(OverlayService.ACTION_UPDATE_TEXT_OPACITY)
                                .putExtra(OverlayService.EXTRA_TEXT_DIM_AMOUNT, textDimAmount)
                        )
                        result.success(null)
                    }
                    "stopOverlay" -> {
                        startService(
                            Intent(this, OverlayService::class.java)
                                .setAction(OverlayService.ACTION_STOP)
                        )
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PROJECTION_REQUEST_CODE) return

        if (resultCode != Activity.RESULT_OK || data == null) {
            methodChannel?.invokeMethod("onCapturePermissionDenied", null)
            return
        }

        startOverlayService(pendingOpacity, textAware = true)
        val captureIntent = Intent(this, OverlayService::class.java)
            .setAction(OverlayService.ACTION_START_CAPTURE)
            .putExtra(OverlayService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
            .putExtra(OverlayService.EXTRA_PROJECTION_DATA, data)
        ContextCompat.startForegroundService(this, captureIntent)
        methodChannel?.invokeMethod("onOverlayStarted", mapOf("textAware" to true))
    }

    private fun startOverlayService(opacity: Float, textAware: Boolean) {
        val intent = Intent(this, OverlayService::class.java)
            .setAction(OverlayService.ACTION_START)
            .putExtra(OverlayService.EXTRA_OPACITY, opacity)
            .putExtra(OverlayService.EXTRA_TEXT_DIM_AMOUNT, pendingTextDimAmount)
            .putExtra(OverlayService.EXTRA_TEXT_AWARE, textAware)
            .putExtra(OverlayService.EXTRA_TEXT_MARGIN_DP, pendingTextMarginDp)
            .putExtra(OverlayService.EXTRA_CORNER_RADIUS_DP, pendingCornerRadiusDp)
            .putExtra(OverlayService.EXTRA_FEATHER_DP, pendingFeatherDp)
            .putExtra(OverlayService.EXTRA_REFRESH_INTERVAL_MS, pendingRefreshIntervalMs)
            .putExtra(OverlayService.EXTRA_JITTER_TOLERANCE_PX, pendingJitterTolerancePx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }
}
