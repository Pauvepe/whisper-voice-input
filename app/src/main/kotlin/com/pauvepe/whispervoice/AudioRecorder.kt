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
    private var isRecording = false
    private val audioBuffer = mutableListOf<Short>()
    private var recordingThread: Thread? = null

    fun startRecording(): Boolean {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return false

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return false

        audioBuffer.clear()
        isRecording = true
        audioRecord?.startRecording()

        recordingThread = Thread {
            val buffer = ShortArray(bufferSize / 2)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    synchronized(audioBuffer) {
                        for (i in 0 until read) {
                            audioBuffer.add(buffer[i])
                        }
                    }
                }
            }
        }
        recordingThread?.start()
        return true
    }

    fun stopRecording(): FloatArray {
        isRecording = false
        recordingThread?.join(1000)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        synchronized(audioBuffer) {
            val floatArray = FloatArray(audioBuffer.size)
            for (i in audioBuffer.indices) {
                floatArray[i] = audioBuffer[i].toFloat() / Short.MAX_VALUE
            }
            audioBuffer.clear()
            return floatArray
        }
    }
}
