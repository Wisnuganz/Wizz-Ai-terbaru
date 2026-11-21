
package com.wizzai.app

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import kotlinx.coroutines.*

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private var panel: View? = null
    private var bubble: View? = null
    private var answerView: TextView? = null
    private var questionInput: EditText? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val ocrReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra("ocr_text").orEmpty()

            if (text.isNotBlank()) {
                questionInput?.setText(
                    "Tolong baca dan jelaskan teks layar ini:\n\n$text"
                )
                answerView?.text =
                    "OCR berhasil. Tekan Tanya AI untuk menganalisis teks."
            } else {
                answerView?.text = "Tidak ditemukan teks pada layar."
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startAsForeground()

        val filter = IntentFilter(ScreenCaptureService.ACTION_OCR_RESULT)

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(ocrReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(ocrReceiver, filter)
        }

        showPanel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (panel == null && bubble == null) showPanel()
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = Notification.Builder(this, "wizz_overlay")
            .setContentTitle("Wizz AI aktif")
            .setContentText("Floating assistant sedang berjalan.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                10,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(10, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                "wizz_overlay",
                "Wizz AI Floating",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun params(width: Int, height: Int) =
        WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 150
            softInputMode =
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

    private fun showPanel() {
        removeBubble()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 14)
            setBackgroundColor(Color.rgb(24, 26, 32))
            elevation = 12f
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "Wizz AI"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }

        val minimize = smallButton("—")
        val close = smallButton("×")

        header.addView(title, LinearLayout.LayoutParams(0, 45, 1f))
        header.addView(minimize)
        header.addView(close)
        header.setOnTouchListener(makeDragListener())

        questionInput = EditText(this).apply {
            hint = "Tulis pertanyaan..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            textSize = 14f
            minLines = 2
            maxLines = 5
            setPadding(12, 10, 12, 10)
            setBackgroundColor(Color.rgb(42, 45, 53))
        }

        answerView = TextView(this).apply {
            text = "Halo! Aku Wizz AI. Ada yang bisa dibantu?"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(8, 12, 8, 12)
        }

        val askButton = actionButton("Tanya AI")
        val ocrButton = actionButton("OCR Layar")
        val clearButton = actionButton("Bersihkan")

        askButton.setOnClickListener {
            askGemini()
        }

        ocrButton.setOnClickListener {
            openOcrPermission()
        }

        clearButton.setOnClickListener {
            questionInput?.setText("")
            answerView?.text = "Percakapan dibersihkan."
        }

        minimize.setOnClickListener {
            showBubble()
        }

        close.setOnClickListener {
            stopSelf()
        }

        container.addView(header)
        addGap(container, 8)
        container.addView(questionInput)
        addGap(container, 8)
        container.addView(askButton)
        addGap(container, 5)
        container.addView(ocrButton)
        addGap(container, 5)
        container.addView(clearButton)
        addGap(container, 8)

        val scroll = ScrollView(this).apply {
            addView(answerView)
        }

        container.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                220
            )
        )

        val windowParams = params(
            dp(310),
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        panelParams = windowParams
        panel = container

        try {
            windowManager.addView(container, windowParams)
        } catch (_: Exception) {
            panel = null
            stopSelf()
        }
    }

    private fun openOcrPermission() {
        if (!Settings.canDrawOverlays(this)) {
            answerView?.text =
                "Aktifkan izin tampil di atas aplikasi lain terlebih dahulu."
            return
        }

        answerView?.text =
            "Silakan setujui izin tangkapan layar pada halaman berikutnya."

        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_START_OCR
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        startActivity(intent)
    }

    private fun askGemini() {
        val prompt = questionInput?.text?.toString()?.trim().orEmpty()

        if (prompt.isBlank()) {
            questionInput?.error = "Tulis pertanyaan terlebih dahulu"
            return
        }

        answerView?.text = "Wizz AI sedang berpikir..."

        serviceScope.launch {
            try {
                GeminiClient.ask(this@FloatingService, prompt) { partial ->
                    answerView?.text = partial
                }
            } catch (e: Exception) {
                answerView?.text =
                    "Gagal: ${e.message ?: "Terjadi kesalahan"}"
            }
        }
    }

    private fun showBubble() {
        removePanel()

        val bubbleView = TextView(this).apply {
            text = "W"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(45, 105, 210))
            elevation = 15f
            setOnTouchListener(makeBubbleDragListener())
        }

        bubble = bubbleView

        val bubbleParams = params(dp(58), dp(58)).apply {
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        try {
            windowManager.addView(bubbleView, bubbleParams)
        } catch (_: Exception) {
            bubble = null
            stopSelf()
        }
    }

    private fun makeDragListener(): View.OnTouchListener {
        return object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val p = panelParams ?: return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = p.x
                        startY = p.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        p.x = startX + (event.rawX - touchX).toInt()
                        p.y = startY + (event.rawY - touchY).toInt()

                        try {
                            panel?.let {
                                windowManager.updateViewLayout(it, p)
                            }
                        } catch (_: Exception) {
                        }
                        return true
                    }
                }
                return false
            }
        }
    }

    private fun makeBubbleDragListener(): View.OnTouchListener {
        return object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val p = view.layoutParams as WindowManager.LayoutParams

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = p.x
                        startY = p.y
                        touchX = event.rawX
                        touchY = event.rawY
                        moved = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()

                        if (kotlin.math.abs(dx) > 8 ||
                            kotlin.math.abs(dy) > 8) moved = true

                        p.x = startX + dx
                        p.y = startY + dy

                        try {
                            windowManager.updateViewLayout(view, p)
                        } catch (_: Exception) {
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!moved) showPanel()
                        return true
                    }
                }
                return false
            }
        }
    }

    private fun actionButton(text: String) =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(45, 105, 210))
        }

    private fun smallButton(text: String) =
        TextView(this).apply {
            this.text = text
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(12, 4, 12, 4)
        }

    private fun addGap(layout: LinearLayout, height: Int) {
        layout.addView(View(this), LinearLayout.LayoutParams(1, height))
    }

    private fun removePanel() {
        panel?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        panel = null
    }

    private fun removeBubble() {
        bubble?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubble = null
    }

    override fun onDestroy() {
        serviceScope.cancel()

        try {
            unregisterReceiver(ocrReceiver)
        } catch (_: Exception) {
        }

        removePanel()
        removeBubble()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
