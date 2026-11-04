// 64비트 ARM 이 아닌 기기용 빈 껍데기 (앱이 이 기기에서는 고급 받아쓰기를 숨김)
#include <jni.h>

extern "C" {
JNIEXPORT jlong JNICALL
Java_kr_yunho_talkhelper_WhisperEngine_nativeInit(JNIEnv *, jobject, jstring) { return 0; }
JNIEXPORT void JNICALL
Java_kr_yunho_talkhelper_WhisperEngine_nativeFree(JNIEnv *, jobject, jlong) {}
JNIEXPORT jbyteArray JNICALL
Java_kr_yunho_talkhelper_WhisperEngine_nativeTranscribe(JNIEnv *, jobject, jlong, jfloatArray, jint, jstring) { return nullptr; }
}
