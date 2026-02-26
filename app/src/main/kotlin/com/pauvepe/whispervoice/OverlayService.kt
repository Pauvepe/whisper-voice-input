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
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.abs

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var micButton: ImageView? = null

    companion object {
        const val CHANNEL_ID = "whisper_overlay"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification())
            initSpeechRecognizer()
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
                .setContentText("Toca la burbuja para hablar")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Whisper Voice activo")
                .setContentText("Toca la burbuja para hablar")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Reconocimiento de voz no disponible", Toast.LENGTH_LONG).show()
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                micButton?.setColorFilter(0xFFFF0000.toInt())
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                micButton?.setColorFilter(0xFFFFAA00.toInt())
            }

            override fun onError(error: Int) {
                isListening = false
                micButton?.colorFilter = null
                micButton?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(150)?.start()

                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    Toast.makeText(this@OverlayService, "No se detectó voz", Toast.LENGTH_SHORT)
                        .show()
                } else if (error == SpeechRecognizer.ERROR_NETWORK ||
                    error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                ) {
                    Toast.makeText(this@OverlayService, "Error de red", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                micButton?.colorFilter = null
                micButton?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(150)?.start()

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim()

                if (!text.isNullOrBlank()) {
                    vibrate(20)
                    val acc = InputAccessibilityService.instance
                    if (acc != null) {
                        acc.insertText(text)
                    } else {
                        val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("voice", text))
                        Toast.makeText(
                            this@OverlayService,
                            "Copiado: $text",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Toast.makeText(
                        this@OverlayService,
                        "No se pudo transcribir",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        if (isListening) return

        if (speechRecognizer == null) {
            initSpeechRecognizer()
            if (speechRecognizer == null) {
                Toast.makeText(this, "No se puede iniciar el reconocimiento", Toast.LENGTH_SHORT)
                    .show()
                return
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        isListening = true
        vibrate(50)
        micButton?.setColorFilter(0xFF2196F3.toInt())
        micButton?.animate()?.scaleX(1.3f)?.scaleY(1.3f)?.setDuration(150)?.start()

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            micButton?.colorFilter = null
            micButton?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(150)?.start()
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopListening() {
        if (!isListening) return
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
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

        micButton = overlayView?.findViewById(R.id.mic_button) ?: return

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
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 30 || abs(dy) > 30) {
                        isDragging = true
                        params.x = initialX - dx
                        params.y = initialY + dy
                        try {
                            windowManager?.updateViewLayout(overlayView, params)
                        } catch (_: Exception) {}
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging) {
                        if (isListening) {
                            stopListening()
                        } else {
                            startListening()
                        }
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

    override fun onDestroy() {
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
