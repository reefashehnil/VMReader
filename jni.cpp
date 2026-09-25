// Bridge between Kotlin and whisper.cpp: load model, transcribe audio, free model.
#include <jni.h>
#include <string>
#include "whisper.h"

// Load the .bin model file. Returns a pointer (0 = failed).
extern "C" JNIEXPORT jlong JNICALL
Java_com_asif_vmreader_WhisperLib_initModel(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

// Free the model.
extern "C" JNIEXPORT void JNICALL
Java_com_asif_vmreader_WhisperLib_freeModel(JNIEnv *, jobject, jlong ptr) {
    if (ptr) whisper_free(reinterpret_cast<whisper_context *>(ptr));
}

// Transcribe ONE audio part (16 kHz mono, <= 14 s). Returns UTF-8 bytes of the Bangla text.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_asif_vmreader_WhisperLib_transcribe(JNIEnv *env, jobject, jlong ptr,
                                             jfloatArray samples, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    std::string out;
    if (ctx) {
        jsize n = env->GetArrayLength(samples);
        jfloat *data = env->GetFloatArrayElements(samples, nullptr);

        // Same style as training: Bangla, transcribe, no timestamps, each part independent
        whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        p.n_threads        = threads;
        p.language         = "bn";
        p.translate        = false;
        p.no_context       = true;
        p.single_segment   = true;
        p.no_timestamps    = true;
        p.suppress_blank   = true;
        p.print_progress   = false;
        p.print_realtime   = false;
        p.print_special    = false;
        p.print_timestamps = false;

        if (whisper_full(ctx, p, data, n) == 0) {
            int ns = whisper_full_n_segments(ctx);
            for (int i = 0; i < ns; i++) {
                const char *t = whisper_full_get_segment_text(ctx, i);
                if (t) out += t;
            }
        }
        env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    }
    // Return raw bytes; Kotlin decodes them safely as UTF-8
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(out.size()),
                            reinterpret_cast<const jbyte *>(out.data()));
    return arr;
}
