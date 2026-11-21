package com.wizzai.app

import android.app.Activity
import android.content.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.Color

class MainActivity : Activity() {

    private lateinit var apiInput: EditText
    private lateinit var statusText: TextView

    private val screenRequest = 1001

    private val ocrReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val result = intent?.getStringExtra("ocr_text").orEmpty()
            if (result.isNotBlank()) {
                statusText.text = "Teks layar berhasil dibaca."
            } else {
                statusText.text = "Tidak ditemukan teks pada layar."
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
            gravity = Gravity.CENTER_VERTICAL
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

        val savedKey = getSharedPreferences("wizz_settings", MODE_PRIVATE)
            .getString("gemini_key", "")

        apiInput.setText(savedKey)

        val saveButton = makeButton("Simpan API Key")
        val overlayButton = makeButton("Aktifkan Floating AI")
        val screenButton = makeButton("Aktifkan Live Screen + OCR")
        val stopButton = makeButton("Matikan Floating AI")

        statusText = TextView(this).apply {
            text = "Status: Siap"
            textSize = 14f
            setTextColor(Color.LTGRAY)
        }

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

            statusText.text = "API Key tersimpan di perangkat."
            Toast.makeText(this, "API Key disimpan", Toast.LENGTH_SHORT).show()
        }

        overlayButton.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                startFloating()
            }
        }

        screenButton.setOnClickListener {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

            startActivityForResult(
                manager.createScreenCaptureIntent(),
                screenRequest
            )
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            statusText.text = "Floating AI dimatikan."
        }

        root.addView(title)
        root.addView(subtitle)
        addSpace(root, 30)
        root.addView(apiInput)
        addSpace(root, 12)
        root.addView(saveButton)
        addSpace(root, 24)
        root.addView(overlayButton)
        addSpace(root, 12)
        root.addView(screenButton)
        addSpace(root, 12)
        root.addView(stopButton)
        addSpace(root, 24)
        root.addView(statusText)

        setContentView(root)

        val filter = IntentFilter(ScreenCaptureService.ACTION_OCR_RESULT)

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(ocrReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(ocrReceiver, filter)
        }
    }

    private fun startFloating() {
        val intent = Intent(this, FloatingService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
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

        if (requestCode == screenRequest && resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("result_code", resultCode)
                putExtra("result_data", data)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            statusText.text = "Izin layar diberikan. Mengambil layar..."
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
