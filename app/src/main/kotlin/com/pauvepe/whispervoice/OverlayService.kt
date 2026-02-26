package com.pauvepe.whispervoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var whisperEngine: WhisperEngine? = null
    private var audioRecorder: AudioRecorder? = null
    private var isRecording = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "whisper_overlay"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        whisperEngine = (application as App).whisperEngine
        audioRecorder = AudioRecorder(this)

        setupOverlay()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Input Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Floating voice input button"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Whisper Voice Input")
                .setContentText("Hold the mic button to speak")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Whisper Voice Input")
                .setContentText("Hold the mic button to speak")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
        }
    }

    @Suppress("ClickableViewAccessibility")
    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 0
            y = 0
        }

        val micButton = overlayView?.findViewById<ImageView>(R.id.mic_button)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        micButton?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    startVoiceRecording(micButton)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 20 || abs(dy) > 20) {
                        isDragging = true
                        params.x = initialX - dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    stopVoiceRecording(micButton)
                    true
                }

                else -> false
            }
        }

        windowManager?.addView(overlayView, params)
    }

    private fun startVoiceRecording(micButton: ImageView) {
        if (isRecording) return
        isRecording = true
        micButton.setColorFilter(0xFFFF0000.toInt())
        audioRecorder?.startRecording()
    }

    private fun stopVoiceRecording(micButton: ImageView) {
        if (!isRecording) return
        isRecording = false
        micButton.colorFilter = null

        val audioData = audioRecorder?.stopRecording() ?: return

        // Ignore very short recordings (< 0.3s)
        if (audioData.size < AudioRecorder.SAMPLE_RATE * 0.3) return

        scope.launch {
            micButton.alpha = 0.5f

            val text = withContext(Dispatchers.IO) {
                whisperEngine?.transcribe(audioData) ?: ""
            }

            micButton.alpha = 1.0f

            if (text.isNotBlank()) {
                val trimmed = text.trim()
                val accessibility = InputAccessibilityService.instance
                if (accessibility != null) {
                    accessibility.insertText(trimmed)
                } else {
                    // Fallback: copy to clipboard
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("voice", trimmed))
                    Toast.makeText(this@OverlayService, "Copied: $trimmed", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        overlayView?.let { windowManager?.removeView(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
