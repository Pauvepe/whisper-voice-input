package com.pauvepe.whispervoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
    private var isTranscribing = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "whisper_overlay"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification())

            whisperEngine = (application as App).whisperEngine
            audioRecorder = AudioRecorder(this)

            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhisperVoice::Overlay")
            wakeLock?.acquire(60 * 60 * 1000L)

            setupOverlay()
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Voice Input Overlay", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Floating voice input button"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Whisper Voice activo")
                .setContentText("Mantén la burbuja para hablar")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Whisper Voice activo")
                .setContentText("Mantén la burbuja para hablar")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
    }

    private fun vibrate(ms: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(ms)
                }
            }
        } catch (_: Exception) {}
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 16
            y = 0
        }

        val micButton = overlayView?.findViewById<ImageView>(R.id.mic_button) ?: return

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        micButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    if (!isTranscribing) {
                        startVoiceRecording(micButton)
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 60 || abs(dy) > 60) {
                        params.x = initialX - dx
                        params.y = initialY + dy
                        try {
                            windowManager?.updateViewLayout(overlayView, params)
                        } catch (_: Exception) {}
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isRecording) {
                        stopVoiceRecording(micButton)
                    }
                    true
                }

                else -> false
            }
        }

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun startVoiceRecording(micButton: ImageView) {
        if (isRecording || isTranscribing) return

        if (whisperEngine == null || !whisperEngine!!.isLoaded) {
            Toast.makeText(this, "Abre la app primero para cargar el modelo", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val started = audioRecorder?.startRecording() ?: false
            if (!started) {
                Toast.makeText(this, "No se puede acceder al micrófono", Toast.LENGTH_SHORT).show()
                return
            }
            isRecording = true
            vibrate(50)
            micButton.setColorFilter(0xFFFF0000.toInt())
            micButton.animate().scaleX(1.3f).scaleY(1.3f).setDuration(150).start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopVoiceRecording(micButton: ImageView) {
        if (!isRecording) return
        isRecording = false
        vibrate(30)
        micButton.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()

        val audioData: FloatArray
        try {
            audioData = audioRecorder?.stopRecording() ?: return
        } catch (e: Exception) {
            micButton.colorFilter = null
            return
        }

        if (audioData.size < AudioRecorder.SAMPLE_RATE / 2) {
            micButton.colorFilter = null
            return
        }

        isTranscribing = true
        micButton.setColorFilter(0xFFFFAA00.toInt())

        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    whisperEngine?.transcribe(audioData) ?: ""
                }

                micButton.colorFilter = null
                isTranscribing = false

                if (text.isNotBlank()) {
                    val trimmed = text.trim()
                    val acc = InputAccessibilityService.instance
                    if (acc != null) {
                        acc.insertText(trimmed)
                        vibrate(20)
                    } else {
                        val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("voice", trimmed))
                        Toast.makeText(this@OverlayService, "Copiado: $trimmed", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@OverlayService, "No se pudo transcribir", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                micButton.colorFilter = null
                isTranscribing = false
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
