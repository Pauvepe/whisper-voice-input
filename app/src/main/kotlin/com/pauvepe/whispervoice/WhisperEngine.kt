package com.pauvepe.whispervoice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class WhisperEngine(private val context: Context) {

    companion object {
        private const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
        private const val MODEL_FILENAME = "ggml-tiny.bin"

        init {
            System.loadLibrary("whisper_jni")
        }
    }

    private var contextPtr: Long = 0

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeFree(ptr: Long)
    private external fun nativeTranscribe(ptr: Long, audio: FloatArray): String

    val isLoaded: Boolean get() = contextPtr != 0L

    fun getModelFile(): File = File(context.filesDir, MODEL_FILENAME)

    fun isModelDownloaded(): Boolean = getModelFile().exists()

    suspend fun downloadModel(onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = getModelFile()
            val connection = URL(MODEL_URL).openConnection()
            connection.connect()
            val totalSize = connection.contentLength

            connection.getInputStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalSize > 0) {
                            onProgress(((downloaded * 100) / totalSize).toInt())
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            getModelFile().delete()
            false
        }
    }

    fun loadModel(): Boolean {
        val file = getModelFile()
        if (!file.exists()) return false
        contextPtr = nativeInit(file.absolutePath)
        return contextPtr != 0L
    }

    fun transcribe(audioSamples: FloatArray): String {
        if (contextPtr == 0L) return ""
        return nativeTranscribe(contextPtr, audioSamples)
    }

    fun release() {
        if (contextPtr != 0L) {
            nativeFree(contextPtr)
            contextPtr = 0
        }
    }
}
