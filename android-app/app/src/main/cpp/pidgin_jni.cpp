// ViNT JNI bridge for llama.cpp.
// Verified against llama.cpp commit 52ffa027455662ea59f78d0e4e5a8f38ee63a5fe by compiling on a Linux x86_64 host
// (see tools/host-verify and docs/BUILD_ANDROID.md). It has NOT yet been compiled with the Android NDK or run on a phone.
// All text crosses the JNI boundary as UTF-8 byte arrays: JNI's NewStringUTF/GetStringUTFChars use *modified* UTF-8,
// which corrupts emoji and half-received multibyte characters.
#include <jni.h>
#include <atomic>
#include <string>
#include <vector>
#include "llama.h"

struct Ctx {
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
    std::atomic<bool> cancel{false};
};

// Result codes of nativeGenerate (>= 0 means "number of generated tokens").
static const jint ERR_DECODE = -1, ERR_PROMPT_TOO_LONG = -2, ERR_TOKENIZE = -3, ERR_CALLBACK = -4, ERR_BAD_HANDLE = -5;

static std::string to_std(JNIEnv* env, jbyteArray a) {
    if (!a) return {};
    jsize n = env->GetArrayLength(a);
    std::string s(n, '\0');
    if (n) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte*>(&s[0]));
    return s;
}

static jbyteArray to_java(JNIEnv* env, const char* p, size_t n) {
    jbyteArray out = env->NewByteArray((jsize) n);
    if (out && n) env->SetByteArrayRegion(out, 0, (jsize) n, reinterpret_cast<const jbyte*>(p));
    return out;
}

// Length of the longest prefix of s that does not end in the middle of a UTF-8 sequence.
static size_t utf8_complete_prefix(const std::string& s) {
    size_t n = s.size(), i = n; int cont = 0;
    while (i > 0 && cont < 4 && (((unsigned char) s[i - 1]) & 0xC0) == 0x80) { i--; cont++; }
    if (i == 0) return n;                       // only continuation bytes: nothing sensible to wait for
    unsigned char lead = (unsigned char) s[i - 1];
    int need = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
    return (cont + 1 >= need) ? n : i - 1;
}

extern "C" JNIEXPORT jlong JNICALL Java_ng_pidgingpt_inference_LlamaEngine_nativeLoad(
        JNIEnv* env, jobject, jstring jpath, jint nCtx, jint nThreads) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    std::string p(path); env->ReleaseStringUTFChars(jpath, path);
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(p.c_str(), mp);
    if (!model) return 0;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nCtx; cp.n_batch = (uint32_t) nCtx;       // whole (windowed) prompt is decoded in one call
    cp.n_threads = nThreads; cp.n_threads_batch = nThreads;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); return 0; }
    auto* c = new Ctx(); c->model = model; c->ctx = ctx;
    return reinterpret_cast<jlong>(c);
}

// roles: Java Strings (ASCII: system/user/assistant); contents: byte[][] (UTF-8). Returns UTF-8 prompt bytes, or null on failure.
extern "C" JNIEXPORT jbyteArray JNICALL Java_ng_pidgingpt_inference_LlamaEngine_nativeApplyTemplate(
        JNIEnv* env, jobject, jlong h, jobjectArray roles, jobjectArray contents) {
    auto* c = reinterpret_cast<Ctx*>(h); if (!c) return nullptr;
    jsize n = env->GetArrayLength(roles);
    std::vector<std::string> r((size_t) n), t((size_t) n);
    std::vector<llama_chat_message> msgs((size_t) n);
    for (jsize i = 0; i < n; i++) {
        auto jr = (jstring) env->GetObjectArrayElement(roles, i);
        const char* a = env->GetStringUTFChars(jr, nullptr); r[i] = a; env->ReleaseStringUTFChars(jr, a); env->DeleteLocalRef(jr);
        auto jc = (jbyteArray) env->GetObjectArrayElement(contents, i); t[i] = to_std(env, jc); env->DeleteLocalRef(jc);
        msgs[i].role = r[i].c_str(); msgs[i].content = t[i].c_str();
    }
    const char* tmpl = llama_model_chat_template(c->model, nullptr);   // may be null -> llama.cpp falls back to its default
    int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, nullptr, 0);
    if (len < 0) return nullptr;
    std::string buf((size_t) len, '\0');
    len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, &buf[0], len);
    if (len < 0) return nullptr;
    return to_java(env, buf.data(), (size_t) len);
}

extern "C" JNIEXPORT jint JNICALL Java_ng_pidgingpt_inference_LlamaEngine_nativeGenerate(
        JNIEnv* env, jobject, jlong h, jbyteArray jprompt, jint maxNew, jfloat temp, jobject cb) {
    auto* c = reinterpret_cast<Ctx*>(h); if (!c) return ERR_BAD_HANDLE;
    c->cancel = false;
    const llama_vocab* vocab = llama_model_get_vocab(c->model);
    std::string prompt = to_std(env, jprompt);

    llama_memory_clear(llama_get_memory(c->ctx), true);             // each call re-sends the full windowed history
    int32_t nTok = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    if (nTok <= 0) return ERR_TOKENIZE;
    std::vector<llama_token> toks((size_t) nTok);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), nTok, true, true) < 0) return ERR_TOKENIZE;
    if (nTok + 1 >= (int32_t) llama_n_ctx(c->ctx)) return ERR_PROMPT_TOO_LONG;   // caller must trim history

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass cls = env->GetObjectClass(cb);
    jmethodID mid = env->GetMethodID(cls, "onBytes", "([B)V");
    if (!mid) { llama_sampler_free(smpl); return ERR_CALLBACK; }

    llama_batch batch = llama_batch_get_one(toks.data(), (int32_t) toks.size());
    std::string pending; jint produced = 0, rc = 0;
    while (produced < maxNew && !c->cancel) {
        if (llama_decode(c->ctx, batch) != 0) { rc = ERR_DECODE; break; }
        llama_token id = llama_sampler_sample(smpl, c->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;
        char piece[256];
        int32_t n = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, true);
        if (n > 0) pending.append(piece, (size_t) n);
        produced++;
        size_t ok = utf8_complete_prefix(pending);
        if (ok > 0) {
            jbyteArray arr = to_java(env, pending.data(), ok);
            env->CallVoidMethod(cb, mid, arr); env->DeleteLocalRef(arr);
            if (env->ExceptionCheck()) { rc = ERR_CALLBACK; break; }
            pending.erase(0, ok);
        }
        batch = llama_batch_get_one(&id, 1);
    }
    if (!pending.empty() && rc == 0) {                               // flush a trailing partial sequence (invalid UTF-8 is replaced in Kotlin)
        jbyteArray arr = to_java(env, pending.data(), pending.size());
        env->CallVoidMethod(cb, mid, arr); env->DeleteLocalRef(arr);
    }
    llama_sampler_free(smpl);
    return rc != 0 ? rc : produced;
}

extern "C" JNIEXPORT void JNICALL Java_ng_pidgingpt_inference_LlamaEngine_nativeCancel(JNIEnv*, jobject, jlong h) {
    if (h) reinterpret_cast<Ctx*>(h)->cancel = true;
}

extern "C" JNIEXPORT void JNICALL Java_ng_pidgingpt_inference_LlamaEngine_nativeFree(JNIEnv*, jobject, jlong h) {
    if (!h) return;
    auto* c = reinterpret_cast<Ctx*>(h);
    llama_free(c->ctx); llama_model_free(c->model); delete c;
}
