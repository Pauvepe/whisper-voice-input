#include <jni.h>
#include <string>
#include "whisper.h"
#include <android/log.h>

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_pauvepe_whispervoice_WhisperEngine_nativeInit(
        JNIEnv *env, jobject, jstring modelPath) {

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model from: %s", path);

    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);

    env->ReleaseStringUTFChars(modelPath, path);

    if (!ctx) {
        LOGE("Failed to load model");
        return 0;
    }

    LOGI("Model loaded successfully");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_pauvepe_whispervoice_WhisperEngine_nativeFree(
        JNIEnv *, jobject, jlong ptr) {

    if (ptr != 0) {
        whisper_free(reinterpret_cast<struct whisper_context *>(ptr));
    }
}

JNIEXPORT jstring JNICALL
Java_com_pauvepe_whispervoice_WhisperEngine_nativeTranscribe(
        JNIEnv *env, jobject, jlong ptr, jfloatArray audio) {

    auto *ctx = reinterpret_cast<struct whisper_context *>(ptr);
    if (!ctx) return env->NewStringUTF("");

    jsize n_samples = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.n_threads        = 4;
    params.language         = "auto";
    params.translate        = false;

    LOGI("Transcribing %d samples...", n_samples);
    int rc = whisper_full(ctx, params, samples, n_samples);
    env->ReleaseFloatArrayElements(audio, samples, 0);

    if (rc != 0) {
        LOGE("Transcription failed: %d", rc);
        return env->NewStringUTF("");
    }

    std::string text;
    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; i++) {
        text += whisper_full_get_segment_text(ctx, i);
    }

    LOGI("Result: %s", text.c_str());
    return env->NewStringUTF(text.c_str());
}

} // extern "C"
