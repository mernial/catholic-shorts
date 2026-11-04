// Kotlin(WhisperEngine) ↔ whisper.cpp 연결
#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_kr_yunho_talkhelper_WhisperEngine_nativeInit(JNIEnv *env, jobject, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(jpath, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_kr_yunho_talkhelper_WhisperEngine_nativeFree(JNIEnv *, jobject, jlong ptr) {
    if (ptr != 0) whisper_free(reinterpret_cast<whisper_context *>(ptr));
}

/** 16kHz 모노 float 음성을 한국어로 받아써서 UTF-8 바이트로 돌려줌. 실패하면 null */
JNIEXPORT jbyteArray JNICALL
Java_kr_yunho_talkhelper_WhisperEngine_nativeTranscribe(JNIEnv *env, jobject, jlong ptr,
                                                        jfloatArray jsamples, jint threads,
                                                        jstring jprompt) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx == nullptr) return nullptr;

    std::string prompt;
    if (jprompt != nullptr) {
        const char *p = env->GetStringUTFChars(jprompt, nullptr);
        prompt = p;
        env->ReleaseStringUTFChars(jprompt, p);
    }

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.language = "ko";
    wp.translate = false;
    wp.n_threads = threads;
    wp.no_context = true;
    wp.no_timestamps = true;
    wp.suppress_blank = true;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_special = false;
    wp.print_timestamps = false;
    if (!prompt.empty()) wp.initial_prompt = prompt.c_str();

    const jsize n = env->GetArrayLength(jsamples);
    jfloat *samples = env->GetFloatArrayElements(jsamples, nullptr);
    const int rc = whisper_full(ctx, wp, samples, n);
    env->ReleaseFloatArrayElements(jsamples, samples, JNI_ABORT);
    if (rc != 0) return nullptr;

    std::string out;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) out += whisper_full_get_segment_text(ctx, i);

    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()),
                            reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

}
