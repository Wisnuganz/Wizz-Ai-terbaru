
package com.wizzai.app

import android.app.Activity
import android.content.*
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*

class MainActivity : Activity() {

    companion object {
        const val ACTION_START_OCR = "com.wizzai.app.START_OCR"
        private const val SCREEN_REQUEST = 1001
    }

    private lateinit var statusText: TextView
    private lateinit var apiInput: EditText

    private val ocrReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra("ocr_text").orEmpty()
            statusText.text = if (text.isNotBlank()) {
                "OCR berhasil membaca ${text.length} karakter."
            } else {
                "Tidak ditemukan teks pada layar."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(16, 17, 20)
        window.navigationBarColor = Color.rgb(16, 17, 20)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            setBackgroundColor(Color.rgb(16, 17, 20))
        }

        val title = TextView(this).apply {
            text = "Wizz AI"
            textSize = 30f
            setTextColor(Color.WHITE)
        }

        val subtitle = TextView(this).apply {
            text = "Your floating AI assistant"
            textSize = 14f
            setTextColor(Color.LTGRAY)
        }

        apiInput = EditText(this).apply {
            hint = "Masukkan Gemini API Key"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            inputType = 129
            setSingleLine(true)
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.rgb(35, 37, 43))
        }

        apiInput.setText(
            getSharedPreferences("wizz_settings", MODE_PRIVATE)
                .getString("gemini_key", "")
        )

        statusText = TextView(this).apply {
            text = "Status: Siap"
            textSize = 14f
            setTextColor(Color.LTGRAY)
        }

        val saveButton = makeButton("Simpan API Key")
        val overlayButton = makeButton("Aktifkan Floating AI")
        val ocrButton = makeButton("Aktifkan OCR Layar")
        val stopButton = makeButton("Matikan Floating AI")

        saveButton.setOnClickListener {
            val key = apiInput.text.toString().trim()

            if (key.isBlank()) {
                apiInput.error = "API Key belum diisi"
                return@setOnClickListener
            }

            getSharedPreferences("wizz_settings", MODE_PRIVATE)
                .edit()
                .putString("gemini_key", key)
                .apply()

            statusText.text = "API Key tersimpan."
        }

        overlayButton.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                startFloating()
            }
        }

        ocrButton.setOnClickListener {
            requestScreenCapture()
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            statusText.text = "Floating AI dimatikan."
        }

        root.addView(title)
        root.addView(subtitle)
        addSpace(root, 25)
        root.addView(apiInput)
        addSpace(root, 10)
        root.addView(saveButton)
        addSpace(root, 20)
        root.addView(overlayButton)
        addSpace(root, 10)
        root.addView(ocrButton)
        addSpace(root, 10)
        root.addView(stopButton)
        addSpace(root, 20)
        root.addView(statusText)

        setContentView(root)

        val filter = IntentFilter(ScreenCaptureService.ACTION_OCR_RESULT)

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(ocrReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(ocrReceiver, filter)
        }

        handleAction(intent)
    }

    private fun handleAction(intent: Intent?) {
        if (intent?.action == ACTION_START_OCR) {
            requestScreenCapture()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAction(intent)
    }

    private fun requestScreenCapture() {
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

            startActivityForResult(
                manager.createScreenCaptureIntent(),
                SCREEN_REQUEST
            )
        } catch (e: Exception) {
            statusText.text = "Gagal meminta izin layar: ${e.message}"
        }
    }

    private fun startFloating() {
        val serviceIntent = Intent(this, FloatingService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        statusText.text = "Floating AI aktif."
    }

    private fun makeButton(label: String): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(45, 105, 210))
        }
    }

    private fun addSpace(layout: LinearLayout, height: Int) {
        layout.addView(View(this), LinearLayout.LayoutParams(1, height))
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SCREEN_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                val serviceIntent = Intent(
                    this,
                    ScreenCaptureService::class.java
                ).apply {
                    putExtra("result_code", resultCode)
                    putExtra("result_data", data)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                statusText.text = "Sedang membaca layar..."
            } else {
                statusText.text = "Izin OCR dibatalkan."
            }
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(ocrReceiver)
        } catch (_: Exception) {
        }

        super.onDestroy()
    }
}
