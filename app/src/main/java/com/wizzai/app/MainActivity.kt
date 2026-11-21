
package com.wizzai.app

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_OCR_RESULT = "com.wizzai.app.OCR_RESULT"
    }

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var captured = false
    private var recognizerClosed = false

    private val handlerThread = HandlerThread("WizzScreenCapture")

    private lateinit var handler: Handler

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }

    override fun onCreate() {
        super.onCreate()
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val resultCode = intent?.getIntExtra(
            "result_code",
            Activity.RESULT_CANCELED
        ) ?: Activity.RESULT_CANCELED

        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(
                "result_data",
                Intent::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("result_data")
        }

        if (resultData == null || resultCode != Activity.RESULT_OK) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = Notification.Builder(this, "wizz_screen")
            .setContentTitle("Wizz AI OCR")
            .setContentText("Sedang membaca teks layar.")
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
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

            projection = manager.getMediaProjection(resultCode, resultData)

            val currentProjection = projection

            if (currentProjection == null) {
                sendResult("Gagal mendapatkan izin layar.")
                stopSelf()
                return START_NOT_STICKY
            }

            // Wajib didaftarkan sebelum createVirtualDisplay.
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

    private fun startCapture(currentProjection: MediaProjection) {
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

            var bitmap: Bitmap? = null

            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val fullBitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )

                fullBitmap.copyPixelsFromBuffer(buffer)

                bitmap = Bitmap.createBitmap(
                    fullBitmap,
                    0,
                    0,
                    width,
                    height
                )

                fullBitmap.recycle()

            } catch (e: Exception) {
                sendResult("Gagal memproses gambar: ${e.message}")
                stopSelf()
            } finally {
                image.close()
            }

            bitmap?.let { recognizeText(it) }

        }, handler)

        virtualDisplay = currentProjection.createVirtualDisplay(
            "WizzAI-OCR",
            width,
            height,
            density,
            android.hardware.display.DisplayManager
                .VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )

        handler.postDelayed({
            if (!captured) {
                sendResult("Tidak berhasil mengambil layar. Silakan coba lagi.")
                stopSelf()
            }
        }, 10000)
    }

    private fun recognizeText(bitmap: Bitmap) {
        val recognizer = TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

        recognizerClosed = false

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val text = result.text.trim()

                if (text.isBlank()) {
                    sendResult("")
                } else {
                    sendResult(text)
                }

                bitmap.recycle()
                recognizer.close()
                recognizerClosed = true
                stopSelf()
            }
            .addOnFailureListener { error ->
                sendResult("OCR gagal: ${error.message}")
                bitmap.recycle()
                recognizer.close()
                recognizerClosed = true
                stopSelf()
            }
    }

    private fun sendResult(text: String) {
        val intent = Intent(ACTION_OCR_RESULT).apply {
            setPackage(packageName)
            putExtra("ocr_text", text)
        }

        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                "wizz_screen",
                "Wizz AI Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun cleanup() {
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
    }

    override fun onDestroy() {
        cleanup()
        handlerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
