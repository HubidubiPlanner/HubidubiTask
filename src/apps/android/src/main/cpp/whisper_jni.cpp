// The bridge between capture/WhisperNative.kt and whisper.cpp: load the model
// from the APK's assets, write out 16 kHz mono samples, say how far it has
// got, and stop when asked.

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <atomic>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "whisper.h"

namespace {

constexpr const char * TAG = "Whisper";

struct Session {
    whisper_context * ctx;
    std::atomic<bool> abort{false};
};

void to_logcat(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_DEBUG || level == GGML_LOG_LEVEL_CONT) return;
    int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
             : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    __android_log_write(prio, TAG, text);
}

// whisper.cpp reads the model through these, straight from the asset.
size_t asset_read(void * ctx, void * out, size_t size) {
    auto * a = static_cast<AAsset *>(ctx);
    size_t done = 0;
    while (done < size) {
        int n = AAsset_read(a, static_cast<char *>(out) + done, size - done);
        if (n <= 0) break;
        done += static_cast<size_t>(n);
    }
    return done;
}
bool asset_eof(void * ctx) { return AAsset_getRemainingLength64(static_cast<AAsset *>(ctx)) <= 0; }
void asset_close(void * ctx) { AAsset_close(static_cast<AAsset *>(ctx)); }

struct Progress {
    JNIEnv * env;
    jobject listener;
    jmethodID method;
};

std::string utf8(JNIEnv * env, jstring s) {
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_hubidubitask_mobile_capture_WhisperNative_init(JNIEnv * env, jclass, jstring lib_dir) {
    whisper_log_set(to_logcat, nullptr);
    ggml_log_set(to_logcat, nullptr);
    ggml_backend_load_all_from_path(utf8(env, lib_dir).c_str());
    return env->NewStringUTF(whisper_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_hubidubitask_mobile_capture_WhisperNative_load(JNIEnv * env, jclass, jobject assets, jstring path) {
    AAssetManager * mgr = AAssetManager_fromJava(env, assets);
    AAsset * asset = AAssetManager_open(mgr, utf8(env, path).c_str(), AASSET_MODE_STREAMING);
    if (asset == nullptr) return 0;
    whisper_model_loader loader{asset, asset_read, asset_eof, asset_close};
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    // whisper.cpp closes the loader itself, whether it loads or not.
    whisper_context * ctx = whisper_init_with_params(&loader, params);
    if (ctx == nullptr) return 0;
    return reinterpret_cast<jlong>(new Session{ctx});
}

JNIEXPORT jbyteArray JNICALL
Java_com_hubidubitask_mobile_capture_WhisperNative_transcribe(JNIEnv * env, jclass, jlong handle, jfloatArray pcm,
                                                             jstring language, jint threads, jobject listener) {
    auto * s = reinterpret_cast<Session *>(handle);
    s->abort = false;

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    std::string lang = utf8(env, language);
    p.language = lang.c_str();
    p.n_threads = threads;
    p.translate = false;
    p.no_context = true;
    p.suppress_nst = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.print_special = false;

    Progress progress{env, listener, nullptr};
    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        progress.method = env->GetMethodID(cls, "onProgress", "(I)V");
        env->DeleteLocalRef(cls);
        if (progress.method != nullptr) {
            p.progress_callback = [](whisper_context *, whisper_state *, int percent, void * user) {
                auto * pr = static_cast<Progress *>(user);
                pr->env->CallVoidMethod(pr->listener, pr->method, percent);
                if (pr->env->ExceptionCheck()) pr->env->ExceptionClear();
            };
            p.progress_callback_user_data = &progress;
        } else if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
    p.abort_callback = [](void * user) { return static_cast<Session *>(user)->abort.load(); };
    p.abort_callback_user_data = s;

    jsize n = env->GetArrayLength(pcm);
    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());

    if (whisper_full(s->ctx, p, samples.data(), n) != 0 || s->abort) return nullptr;

    std::string text;
    for (int i = 0, segments = whisper_full_n_segments(s->ctx); i < segments; ++i) {
        text += whisper_full_get_segment_text(s->ctx, i);
    }
    // As bytes: NewStringUTF wants Java's own variant of UTF-8.
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

JNIEXPORT void JNICALL
Java_com_hubidubitask_mobile_capture_WhisperNative_cancel(JNIEnv *, jclass, jlong handle) {
    reinterpret_cast<Session *>(handle)->abort = true;
}

JNIEXPORT void JNICALL
Java_com_hubidubitask_mobile_capture_WhisperNative_free(JNIEnv *, jclass, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    whisper_free(s->ctx);
    delete s;
}

} // extern "C"
