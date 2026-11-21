
package com.wizzai.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_OCR_RESULT = "com.wizzai.app.OCR_RESULT"
        private const val CHANNEL_ID = "wizz_screen"
    }

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captured = false

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }

    override fun onCreate() {
        super.onCreate()

        handlerThread = HandlerThread("WizzOCR")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (projection != null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(
            "result_code",
            Activity.RESULT_CANCELED
        ) ?: Activity.RESULT_CANCELED

        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(
                    "result_data",
                    Intent::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("result_data")
            }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Wizz AI OCR")
            .setContentText("Sedang membaca teks layar")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                11,
                notification,
                android.content.pm.ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(11, notification)
        }

        try {
            val manager =
                getSystemService(MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

            projection = manager.getMediaProjection(
                resultCode,
                resultData
            )

            val currentProjection = projection ?: run {
                sendResult("Gagal mendapatkan izin layar.")
                stopSelf()
                return START_NOT_STICKY
            }

            currentProjection.registerCallback(
                projectionCallback,
                handler
            )

            startCapture(currentProjection)

        } catch (e: Exception) {
            sendResult("Gagal memulai OCR: ${e.message}")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startCapture(mp: MediaProjection) {
        val metrics = DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        imageReader?.setOnImageAvailableListener({ reader ->

            if (captured) return@setOnImageAvailableListener

            val image = reader.acquireLatestImage()
                ?: return@setOnImageAvailableListener

            captured = true

            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val fullBitmap = android.graphics.Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    android.graphics.Bitmap.Config.ARGB_8888
                )

                fullBitmap.copyPixelsFromBuffer(buffer)

                val bitmap = android.graphics.Bitmap.createBitmap(
                    fullBitmap,
                    0,
                    0,
                    width,
                    height
                )

                fullBitmap.recycle()
                image.close()

                recognizeText(bitmap)

            } catch (e: Exception) {
                image.close()
                sendResult("Gagal membaca gambar: ${e.message}")
                stopSelf()
            }

        }, handler)

        virtualDisplay = mp.createVirtualDisplay(
            "WizzAI-OCR",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )

        handler.postDelayed({
            if (!captured) {
                sendResult("Gagal mengambil layar. Coba lagi.")
                stopSelf()
            }
        }, 10000)
    }

    private fun recognizeText(bitmap: android.graphics.Bitmap) {
        val recognizer = TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                sendResult(result.text)
                bitmap.recycle()
                recognizer.close()
                stopSelf()
            }
            .addOnFailureListener { error ->
                sendResult("OCR gagal: ${error.message}")
                bitmap.recycle()
                recognizer.close()
                stopSelf()
            }
    }

    private fun sendResult(text: String) {
        val broadcast = Intent(ACTION_OCR_RESULT).apply {
            setPackage(packageName)
            putExtra("ocr_text", text)
        }

        sendBroadcast(broadcast)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wizz AI Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        try {
            virtualDisplay?.release()
            imageReader?.close()
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        } catch (_: Exception) {
        }

        virtualDisplay = null
        imageReader = null
        projection = null

        if (::handlerThread.isInitialized) {
            handlerThread.quitSafely()
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
