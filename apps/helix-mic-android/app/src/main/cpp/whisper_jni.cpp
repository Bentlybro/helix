// JNI shim around whisper.cpp.
// Matches com.helix.mic.WhisperNative:
//   nativeInit(String modelPath) -> long handle
//   nativeTranscribe(long handle, float[] pcm) -> String
//   nativeFree(long handle)

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <string>
#include <vector>
#include <mutex>

#include "whisper.h"

#define LOG_TAG "whisper_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    std::mutex g_mutex;  // whisper contexts are not thread-safe for concurrent full() calls
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_helix_mic_WhisperNative_nativeInit(JNIEnv* env, jobject /*thiz*/, jstring jpath) {
    const char* path_c = env->GetStringUTFChars(jpath, nullptr);
    if (path_c == nullptr) return 0;
    std::string path(path_c);
    env->ReleaseStringUTFChars(jpath, path_c);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    whisper_context* ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) {
        LOGE("whisper_init_from_file failed for %s", path.c_str());
        return 0;
    }
    LOGI("whisper ctx created from %s", path.c_str());
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_helix_mic_WhisperNative_nativeTranscribe(JNIEnv* env, jobject /*thiz*/, jlong handle, jfloatArray jpcm) {
    if (handle == 0) return env->NewStringUTF("");
    whisper_context* ctx = reinterpret_cast<whisper_context*>(handle);

    jsize len = env->GetArrayLength(jpcm);
    if (len <= 0) return env->NewStringUTF("");

    std::vector<float> pcm(len);
    env->GetFloatArrayRegion(jpcm, 0, len, pcm.data());

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = false;
    // We feed pre-segmented chunks from the VAD — whisper doesn't need to split further.
    params.single_segment   = true;
    params.no_context       = true;
    params.suppress_blank   = true;
    params.language         = "en";
    params.n_threads        = 4;
    params.temperature      = 0.0f;
    // Cap the number of decoder steps. base.en shouldn't need more than this for short chunks.
    params.max_tokens       = 128;

    // KEY OPTIMIZATION: scale audio_ctx to match the actual chunk length.
    // Whisper's encoder otherwise processes a full 30-second context (1500 tokens)
    // regardless of input length. For ~3 s chunks this is ~10x wasted compute.
    //
    // Each encoder token ≈ 20 ms of input audio (after the 2x conv stride on the
    // 100 fps mel). Round up and add a small safety margin so the encoder has
    // room to resolve the last word.
    const int audio_ctx_estimate = (int)((pcm.size() / 320) + 32);
    const int audio_ctx_clamped = std::min(1500, std::max(32, audio_ctx_estimate));
    params.audio_ctx = audio_ctx_clamped;

    std::string out;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size())) != 0) {
            LOGE("whisper_full failed");
            return env->NewStringUTF("");
        }
        const int n_segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < n_segments; ++i) {
            const char* text = whisper_full_get_segment_text(ctx, i);
            if (text != nullptr) {
                if (!out.empty()) out.push_back(' ');
                out.append(text);
            }
        }
    }

    // Trim leading/trailing whitespace
    size_t a = out.find_first_not_of(" \t\n\r");
    size_t b = out.find_last_not_of(" \t\n\r");
    if (a == std::string::npos) return env->NewStringUTF("");
    return env->NewStringUTF(out.substr(a, b - a + 1).c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_helix_mic_WhisperNative_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    whisper_context* ctx = reinterpret_cast<whisper_context*>(handle);
    whisper_free(ctx);
    LOGI("whisper ctx freed");
}
