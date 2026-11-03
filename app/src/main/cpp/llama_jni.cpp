#include <jni.h>
#include "llama.h"
#include "ggml-backend.h"
#include "inference_memory.h"
#include "translation_prompt.h"
#include <android/NeuralNetworks.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <dlfcn.h>
#include <fstream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <thread>
#include <vector>

namespace {
constexpr int kContextTokens = 4096;
constexpr int kGenerationTokens = 1536;
constexpr int kBatchTokens = 128;
constexpr size_t kMaxPromptBytes = 131072;

using Cancellation = std::atomic<bool>;
std::mutex inference_mutex;

void throw_java(JNIEnv * env, const char * type, const std::string & message) {
    if (!env->ExceptionCheck()) {
        jclass exception = env->FindClass(type);
        if (exception) env->ThrowNew(exception, message.c_str());
    }
}

std::string read_utf8(JNIEnv * env, jbyteArray value) {
    if (!value) throw std::invalid_argument("Prompt AI tidak valid");
    const jsize length = env->GetArrayLength(value);
    if (length < 1 || static_cast<size_t>(length) > kMaxPromptBytes) {
        throw std::invalid_argument("Teks terlalu panjang untuk model AI offline");
    }
    std::string result(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte *>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Gagal membaca prompt AI");
    if (result.find('\0') != std::string::npos) throw std::invalid_argument("Teks AI mengandung karakter tidak valid");
    return result;
}

std::string read_optional_utf8(JNIEnv * env, jbyteArray value) {
    if (!value || env->GetArrayLength(value) == 0) return {};
    return read_utf8(env, value);
}

jobjectArray make_string_array(JNIEnv * env, const std::vector<std::string> & values) {
    jclass string_class = env->FindClass("java/lang/String");
    if (!string_class) return nullptr;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(values.size()), string_class, nullptr);
    if (result) {
        for (jsize index = 0; index < static_cast<jsize>(values.size()); ++index) {
            jstring value = env->NewStringUTF(values[static_cast<size_t>(index)].c_str());
            if (!value) break;
            env->SetObjectArrayElement(result, index, value);
            env->DeleteLocalRef(value);
        }
    }
    env->DeleteLocalRef(string_class);
    return result;
}

void initialize_backend() {
    static std::once_flag initialized;
    std::call_once(initialized, [] { llama_backend_init(); });
}

std::vector<std::string> registered_accelerators() {
    std::vector<std::string> result;
    initialize_backend();
    for (size_t index = 0; index < ggml_backend_dev_count(); ++index) {
        ggml_backend_dev_t device = ggml_backend_dev_get(index);
        if (!device || ggml_backend_dev_type(device) == GGML_BACKEND_DEVICE_TYPE_CPU) continue;
        auto * registration = ggml_backend_dev_backend_reg(device);
        const char * backend = registration ? ggml_backend_reg_name(registration) : "unknown";
        const char * name = ggml_backend_dev_name(device);
        const char * description = ggml_backend_dev_description(device);
        // Tab is the JNI transport delimiter. Avoid ambiguous records if a
        // vendor driver includes it in its device metadata.
        auto clean = [](const char * value) {
            std::string output = value ? value : "";
            std::replace(output.begin(), output.end(), '\t', ' ');
            std::replace(output.begin(), output.end(), '\n', ' ');
            std::replace(output.begin(), output.end(), '\r', ' ');
            return output;
        };
        std::string entry = clean(backend);
        entry += '\t';
        entry += clean(name ? name : "accelerator");
        entry += '\t';
        entry += clean(description);
        result.push_back(std::move(entry));
    }
    return result;
}

std::vector<std::string> nnapi_accelerators() {
    std::vector<std::string> result;
    // NNAPI is optional on older Android versions, so resolve its API at
    // runtime and never add a hard dependency to the APK.
    void * library = dlopen("libneuralnetworks.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) return result;

    using GetDeviceCount = int (*)(uint32_t *);
    using GetDevice = int (*)(uint32_t, ANeuralNetworksDevice **);
    using GetDeviceType = int (*)(const ANeuralNetworksDevice *, int32_t *);
    using GetDeviceName = int (*)(const ANeuralNetworksDevice *, const char **);
    const auto get_count = reinterpret_cast<GetDeviceCount>(dlsym(library, "ANeuralNetworks_getDeviceCount"));
    const auto get_device = reinterpret_cast<GetDevice>(dlsym(library, "ANeuralNetworks_getDevice"));
    const auto get_type = reinterpret_cast<GetDeviceType>(dlsym(library, "ANeuralNetworksDevice_getType"));
    const auto get_name = reinterpret_cast<GetDeviceName>(dlsym(library, "ANeuralNetworksDevice_getName"));
    if (get_count && get_device && get_type && get_name) {
        uint32_t count = 0;
        if (get_count(&count) == ANEURALNETWORKS_NO_ERROR) {
            for (uint32_t index = 0; index < count; ++index) {
                ANeuralNetworksDevice * device = nullptr;
                int32_t type = ANEURALNETWORKS_DEVICE_UNKNOWN;
                const char * name = nullptr;
                if (get_device(index, &device) == ANEURALNETWORKS_NO_ERROR && device &&
                    get_type(device, &type) == ANEURALNETWORKS_NO_ERROR &&
                    type == ANEURALNETWORKS_DEVICE_ACCELERATOR &&
                    get_name(device, &name) == ANEURALNETWORKS_NO_ERROR && name && *name) {
                    result.emplace_back(name);
                }
            }
        }
    }
    dlclose(library);
    return result;
}

bool opencl_driver_available() {
    void * library = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) return false;
    using GetPlatformIds = int32_t (*)(uint32_t, void **, uint32_t *);
    const auto get_platform_ids = reinterpret_cast<GetPlatformIds>(dlsym(library, "clGetPlatformIDs"));
    uint32_t count = 0;
    const bool available = get_platform_ids && get_platform_ids(0, nullptr, &count) == 0 && count > 0;
    dlclose(library);
    return available;
}

ggml_backend_dev_t find_registered_device(const std::string & backend, const std::string & name) {
    if (backend.empty() || name.empty()) return nullptr;
    for (size_t index = 0; index < ggml_backend_dev_count(); ++index) {
        ggml_backend_dev_t device = ggml_backend_dev_get(index);
        if (!device || ggml_backend_dev_type(device) == GGML_BACKEND_DEVICE_TYPE_CPU) continue;
        auto * registration = ggml_backend_dev_backend_reg(device);
        const char * backend_name = registration ? ggml_backend_reg_name(registration) : nullptr;
        const char * device_name = ggml_backend_dev_name(device);
        if (backend_name && device_name && backend == backend_name && name == device_name) return device;
    }
    return nullptr;
}

void check_cancelled(const Cancellation & flag) {
    if (flag.load(std::memory_order_relaxed)) throw std::runtime_error("AI offline dibatalkan");
}

bool abort_inference(void * data) {
    return static_cast<Cancellation *>(data)->load(std::memory_order_relaxed);
}

bool continue_loading(float, void * data) { return !abort_inference(data); }

void check_memory_budget(const std::string & path, bool gpu) {
    struct stat info{};
    if (stat(path.c_str(), &info) != 0 || info.st_size <= 0) throw std::runtime_error("Berkas model AI tidak tersedia");
    const auto memory = transcriptor::read_memory_info();
    if (!transcriptor::model_fits_memory(static_cast<uint64_t>(info.st_size), memory.total, memory.available, gpu)) {
        throw std::runtime_error("RAM tersedia tidak cukup untuk model AI ini. Tutup aplikasi lain atau gunakan Qwen3.5 0.8B GGUF");
    }
}

struct ModelDeleter { void operator()(llama_model * value) const { llama_model_free(value); } };
struct ContextDeleter { void operator()(llama_context * value) const { llama_free(value); } };
struct SamplerDeleter { void operator()(llama_sampler * value) const { llama_sampler_free(value); } };
// One translation session owns one worker process. Keep weights mapped between
// cue groups; unbinding the worker releases the entire model and driver memory.
struct ModelCache {
    std::unique_ptr<llama_model, ModelDeleter> model;
    std::unique_ptr<llama_context, ContextDeleter> context;
    int batch_tokens = 0;
    std::string path;
    ggml_backend_dev_t device = nullptr;
    bool gpu = false;
    int64_t size = 0;
    int64_t modified = 0;
};
ModelCache & cached_model() {
    static ModelCache value; // Constructed after the backend; destroyed before it on normal exit.
    return value;
}
bool gpu_failed_in_session = false; // Access is serialized by inference_mutex.
struct Batch {
    llama_batch value = llama_batch_init(kBatchTokens, 0, 1);
    ~Batch() { llama_batch_free(value); }
    void set(llama_token * tokens, int count, int position, bool output) {
        if (!value.token || !value.pos || !value.n_seq_id || !value.seq_id || !value.logits) throw std::bad_alloc();
        value.n_tokens = count;
        for (int index = 0; index < count; ++index) {
            value.token[index] = tokens[index];
            value.pos[index] = position + index;
            value.n_seq_id[index] = 1;
            value.seq_id[index][0] = 0;
            value.logits[index] = output && index == count - 1;
        }
    }
};

std::string format_prompt(const llama_model * model, const std::string & system, const std::string & user) {
    if (transcriptor::is_translation_prompt(system)) {
        return transcriptor::translation_prompt(system, user);
    }
    const char * chat_template = llama_model_chat_template(model, nullptr);
    if (!chat_template || !*chat_template) {
        throw std::runtime_error("Model tidak memiliki chat template. Gunakan model GGUF instruct/chat yang didukung");
    }
    const std::string template_text(chat_template);
    // Qwen3 supports /no_think. Its GGUF Jinja template additionally uses an empty
    // thinking prefix for enable_thinking=false; the lightweight C template API
    // does not evaluate Jinja kwargs, so supply that prefix explicitly.
    const bool qwen_thinking = template_text.find("<|im_start|>") != std::string::npos &&
                               template_text.find("enable_thinking") != std::string::npos;
    const std::string instruction = user + (qwen_thinking ? "\n/no_think" : "");
    const llama_chat_message messages[] = {{"system", system.c_str()}, {"user", instruction.c_str()}};
    int length = llama_chat_apply_template(chat_template, messages, 2, true, nullptr, 0);
    if (length < 1 || static_cast<size_t>(length) > kMaxPromptBytes) {
        throw std::runtime_error("Chat template model tidak didukung. Gunakan model GGUF instruct/chat yang didukung");
    }
    std::vector<char> buffer(static_cast<size_t>(length) + 1);
    length = llama_chat_apply_template(chat_template, messages, 2, true, buffer.data(), static_cast<int32_t>(buffer.size()));
    if (length < 1 || static_cast<size_t>(length) >= buffer.size()) throw std::runtime_error("Gagal memformat prompt AI");
    std::string result(buffer.data(), static_cast<size_t>(length));
    if (qwen_thinking) result += "<think>\n\n</think>\n\n";
    return result;
}

std::vector<llama_token> tokenize(const llama_vocab * vocabulary, const std::string & prompt) {
    const int required = llama_tokenize(vocabulary, prompt.data(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (required >= 0 || required == INT32_MIN) throw std::runtime_error("Gagal menghitung token prompt AI");
    const int count = -required;
    if (count > kContextTokens) throw std::runtime_error("Konteks AI offline terlalu panjang; kurangi ukuran teks");
    std::vector<llama_token> result(static_cast<size_t>(count));
    const int written = llama_tokenize(vocabulary, prompt.data(), static_cast<int32_t>(prompt.size()), result.data(), count, true, true);
    if (written < 1 || written > count) throw std::runtime_error("Gagal membaca token prompt AI");
    result.resize(static_cast<size_t>(written));
    return result;
}

std::string token_piece(const llama_vocab * vocabulary, llama_token token) {
    std::vector<char> buffer(256);
    int count = llama_token_to_piece(vocabulary, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, false);
    if (count < 0 && count != INT32_MIN && -count <= 65536) {
        buffer.resize(static_cast<size_t>(-count));
        count = llama_token_to_piece(vocabulary, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, false);
    }
    if (count < 0 || static_cast<size_t>(count) > buffer.size()) throw std::runtime_error("Gagal membaca keluaran model AI");
    return {buffer.data(), static_cast<size_t>(count)};
}

std::string generate_attempt(JNIEnv * env, const std::string & path, const std::string & system,
                     const std::string & user, const std::string & grammar,
                     ggml_backend_dev_t selected_device, bool use_gpu,
                     Cancellation & flag, int threads, int batch_tokens, jobject listener) {
    check_cancelled(flag);
    // Prompts and generated transcript text are never written to logcat.
    llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
    auto load_model = [&](bool offload) {
        auto model_params = llama_model_default_params();
        ggml_backend_dev_t selected_devices[] = {selected_device, nullptr};
        model_params.n_gpu_layers = offload ? -1 : 0;
        model_params.devices = offload ? selected_devices : nullptr;
        model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
        model_params.progress_callback = continue_loading;
        model_params.progress_callback_user_data = &flag;
        return std::unique_ptr<llama_model, ModelDeleter>(llama_model_load_from_file(path.c_str(), model_params));
    };
    struct stat info{};
    if (stat(path.c_str(), &info) != 0) throw std::runtime_error("Berkas model AI tidak tersedia");
    auto & model_cache = cached_model();
    if (!model_cache.model || model_cache.path != path || model_cache.device != selected_device ||
        model_cache.gpu != use_gpu || model_cache.size != info.st_size || model_cache.modified != info.st_mtime) {
        model_cache.context.reset();
        model_cache.model.reset();
        check_memory_budget(path, use_gpu);
        model_cache.model = load_model(use_gpu);
        model_cache.path = path;
        model_cache.device = selected_device;
        model_cache.gpu = use_gpu;
        model_cache.size = info.st_size;
        model_cache.modified = info.st_mtime;
    }
    auto & model = model_cache.model;
    check_cancelled(flag);
    if (!model) throw std::runtime_error("Model AI tidak dapat dimuat. Pastikan berkas GGUF instruct didukung dan RAM cukup");
    auto create_context = [&](llama_model * loaded_model, bool offload) {
        const int context_size = std::min(kContextTokens, llama_model_n_ctx_train(loaded_model));
        if (context_size < 2048) throw std::runtime_error("Model AI membutuhkan dukungan konteks minimal 2048 token");
        auto context_params = llama_context_default_params();
        context_params.n_ctx = static_cast<uint32_t>(context_size);
        context_params.n_batch = batch_tokens;
        context_params.n_ubatch = batch_tokens;
        context_params.n_seq_max = 1;
        context_params.n_threads = threads;
        context_params.n_threads_batch = context_params.n_threads;
        context_params.offload_kqv = offload;
        context_params.op_offload = offload;
        context_params.no_perf = true;
        context_params.abort_callback = abort_inference;
        context_params.abort_callback_data = &flag;
        return std::unique_ptr<llama_context, ContextDeleter>(llama_init_from_model(loaded_model, context_params));
    };
    if (!model_cache.context || model_cache.batch_tokens != batch_tokens) {
        model_cache.context.reset();
        model_cache.context = create_context(model.get(), use_gpu);
        model_cache.batch_tokens = batch_tokens;
    }
    auto & context = model_cache.context;
    if (!context) throw std::runtime_error("RAM tidak cukup untuk konteks AI offline; gunakan model lebih kecil");
    // Reuse graph allocations within one session, but clear dialogue/KV state for each cue.
    // Cancellation flags have request lifetime; detach them on every exit path.
    struct DetachAbort {
        llama_context * value;
        ~DetachAbort() { llama_set_abort_callback(value, nullptr, nullptr); }
    } detach{context.get()};
    check_cancelled(flag);
    llama_memory_clear(llama_get_memory(context.get()), false);
    llama_set_n_threads(context.get(), threads, threads);
    llama_set_abort_callback(context.get(), abort_inference, &flag);
    const auto * vocabulary = llama_model_get_vocab(model.get());
    if (!vocabulary) throw std::runtime_error("Model AI tidak memiliki tokenizer");
    const auto prompt = format_prompt(model.get(), system, user);
    auto prompt_tokens = tokenize(vocabulary, prompt);
    const int context_size = std::min(kContextTokens, llama_model_n_ctx_train(model.get()));
    const int output_limit = std::min(kGenerationTokens, context_size / 2);
    if (static_cast<int>(prompt_tokens.size()) + output_limit > context_size) {
        throw std::runtime_error("Konteks AI offline terlalu panjang; kurangi ukuran teks atau konteks percakapan");
    }
    jclass listener_class = env->GetObjectClass(listener);
    if (!listener_class) throw std::runtime_error("Progress AI tidak tersedia");
    jmethodID on_token = env->GetMethodID(listener_class, "onToken", "(I)V");
    env->DeleteLocalRef(listener_class);
    if (!on_token) throw std::runtime_error("Progress AI tidak didukung");
    env->CallVoidMethod(listener, on_token, static_cast<jint>(0));
    if (env->ExceptionCheck()) throw std::runtime_error("Progress AI dibatalkan");
    std::unique_ptr<llama_sampler, SamplerDeleter> sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()));
    if (!sampler) throw std::bad_alloc();
    if (!grammar.empty()) {
        std::unique_ptr<llama_sampler, SamplerDeleter> format(llama_sampler_init_grammar(vocabulary, grammar.c_str(), "root"));
        if (!format) throw std::runtime_error("Format keluaran AI tidak dapat disiapkan");
        llama_sampler_chain_add(sampler.get(), format.release());
    }
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
    Batch batch;
    for (int offset = 0; offset < static_cast<int>(prompt_tokens.size()); offset += batch_tokens) {
        check_cancelled(flag);
        const int count = std::min(batch_tokens, static_cast<int>(prompt_tokens.size()) - offset);
        batch.set(prompt_tokens.data() + offset, count, offset, offset + count == static_cast<int>(prompt_tokens.size()));
        const int status = llama_decode(context.get(), batch.value);
        check_cancelled(flag);
        if (status != 0) throw std::runtime_error("Gagal memproses konteks AI offline; RAM mungkin tidak cukup");
    }
    std::string result;
    for (int generated = 0; generated < output_limit; ++generated) {
        check_cancelled(flag);
        llama_token token = llama_sampler_sample(sampler.get(), context.get(), -1);
        if (llama_vocab_is_eog(vocabulary, token)) return result;
        result += token_piece(vocabulary, token);
        env->CallVoidMethod(listener, on_token, static_cast<jint>(generated + 1));
        if (env->ExceptionCheck()) throw std::runtime_error("Progress AI dibatalkan");
        // A response that reaches the limit is deliberately rejected instead of
        // returning a silently cut sentence or incomplete JSON translation batch.
        if (generated + 1 == output_limit) break;
        batch.set(&token, 1, static_cast<int>(prompt_tokens.size()) + generated, true);
        const int status = llama_decode(context.get(), batch.value);
        check_cancelled(flag);
        if (status != 0) throw std::runtime_error("Gagal menghasilkan teks AI offline");
    }
    throw std::runtime_error("Jawaban AI melebihi batas 1536 token; gunakan potongan teks lebih pendek");
}

std::string generate(JNIEnv * env, const std::string & path, const std::string & system,
                     const std::string & user, const std::string & grammar,
                     const std::string & preferred_backend, const std::string & preferred_device,
                     Cancellation & flag, int threads, int batch_tokens, jobject listener) {
    std::lock_guard<std::mutex> guard(inference_mutex);
    check_cancelled(flag);
    initialize_backend();
    const auto selected_device = gpu_failed_in_session ? nullptr : find_registered_device(preferred_backend, preferred_device);
    if (!selected_device) {
        return generate_attempt(env, path, system, user, grammar, nullptr, false, flag, threads, batch_tokens, listener);
    }
    try {
        return generate_attempt(env, path, system, user, grammar, selected_device, true, flag, threads, batch_tokens, listener);
    } catch (...) {
        // Restart the whole request on CPU if Vulkan model loading, context
        // allocation, or decode fails. A cancellation or Java callback failure
        // must remain visible to the caller and must never trigger a retry.
        check_cancelled(flag);
        if (env->ExceptionCheck()) throw;
        gpu_failed_in_session = true;
        return generate_attempt(env, path, system, user, grammar, nullptr, false, flag, threads, batch_tokens, listener);
    }
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_id_tomz_transcriptor_translation_LlamaNative_createCancellationFlag(JNIEnv * env, jobject) {
    try { return reinterpret_cast<jlong>(new Cancellation(false)); }
    catch (...) { throw_java(env, "java/lang/OutOfMemoryError", "Memori tidak cukup untuk AI offline"); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_id_tomz_transcriptor_translation_LlamaNative_cancel(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Cancellation *>(handle)->store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_id_tomz_transcriptor_translation_LlamaNative_freeCancellationFlag(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Cancellation *>(handle);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_id_tomz_transcriptor_translation_LlamaNative_getAvailableBackends(JNIEnv * env, jobject) {
    try {
        std::lock_guard<std::mutex> guard(inference_mutex);
        return make_string_array(env, registered_accelerators());
    } catch (...) {
        return make_string_array(env, {});
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_id_tomz_transcriptor_translation_LlamaNative_getNnapiAccelerators(JNIEnv * env, jobject) {
    try { return make_string_array(env, nnapi_accelerators()); }
    catch (...) { return make_string_array(env, {}); }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_id_tomz_transcriptor_translation_LlamaNative_isOpenClAvailable(JNIEnv *, jobject) {
    return opencl_driver_available() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_id_tomz_transcriptor_translation_LlamaNative_generate(JNIEnv * env, jobject, jbyteArray path,
        jbyteArray system, jbyteArray user, jbyteArray grammar, jbyteArray preferred_backend,
        jbyteArray preferred_device, jlong cancellation_handle, jint threads, jint batch_tokens, jobject listener) {
    try {
        if (!cancellation_handle || !listener) throw std::invalid_argument("Permintaan AI offline tidak valid");
        const auto constraint = grammar && env->GetArrayLength(grammar) > 0 ? read_utf8(env, grammar) : std::string();
        const auto output = generate(env, read_utf8(env, path), read_utf8(env, system), read_utf8(env, user), constraint,
                                    read_optional_utf8(env, preferred_backend), read_optional_utf8(env, preferred_device),
                                    *reinterpret_cast<Cancellation *>(cancellation_handle), std::clamp(static_cast<int>(threads), 1, 4),
                                    std::clamp(static_cast<int>(batch_tokens), 16, kBatchTokens), listener);
        jbyteArray result = env->NewByteArray(static_cast<jsize>(output.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const std::bad_alloc &) {
        throw_java(env, "java/lang/IllegalStateException", "RAM tidak cukup untuk AI offline; gunakan model GGUF lebih kecil");
    } catch (const std::exception & error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
    } catch (...) {
        throw_java(env, "java/lang/IllegalStateException", "AI offline gagal memproses teks");
    }
    return nullptr;
}
