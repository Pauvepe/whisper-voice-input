package com.pauvepe.whispervoice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.app.ActivityCompat

class AudioRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isRecording = false
    private val audioBuffer = ArrayList<Short>(SAMPLE_RATE * 30)
    private var recordingThread: Thread? = null

    fun startRecording(): Boolean {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return false

        try {
            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING),
                SAMPLE_RATE * 2
            )

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                return false
            }

            audioBuffer.clear()
            isRecording = true
            audioRecord?.startRecording()

            recordingThread = Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                val buffer = ShortArray(1024)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        synchronized(audioBuffer) {
                            for (i in 0 until read) {
                                audioBuffer.add(buffer[i])
                            }
                        }
                    } else if (read < 0) {
                        break
                    }
                }
            }, "AudioRecorder")
            recordingThread?.start()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
            return false
        }
    }

    fun stopRecording(): FloatArray {
        isRecording = false
        try { recordingThread?.join(2000) } catch (_: Exception) {}
        cleanup()

        synchronized(audioBuffer) {
            val floatArray = FloatArray(audioBuffer.size)
            for (i in audioBuffer.indices) {
                floatArray[i] = audioBuffer[i].toFloat() / Short.MAX_VALUE
            }
            audioBuffer.clear()
            return floatArray
        }
    }

    private fun cleanup() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        recordingThread = null
    }
}
