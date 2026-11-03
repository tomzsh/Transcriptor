#include <jni.h>
#include <whisper.h>
#include "inference_memory.h"
#include <sys/stat.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <exception>
#include <fstream>
#include <limits>
#include <new>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

constexpr int64_t kWindowSamples = WHISPER_SAMPLE_RATE * WHISPER_CHUNK_SIZE;
constexpr int64_t kOverlapSamples = WHISPER_SAMPLE_RATE * 2;

struct Segment {
    int64_t start_ms;
    int64_t end_ms;
    std::string text;
};

void append_window_segment(
    std::vector<Segment> & segments,
    int64_t window_start_ms,
    int64_t window_duration_ms,
    int64_t local_start_ms,
    int64_t local_end_ms,
    const char * text
) {
    local_start_ms = std::clamp<int64_t>(local_start_ms, 0, window_duration_ms);
    local_end_ms = std::clamp<int64_t>(local_end_ms, 0, window_duration_ms);
    int64_t start_ms = window_start_ms + local_start_ms;
    const int64_t end_ms = window_start_ms + local_end_ms;
    // Reconcile against emitted audio coverage, not the fixed lookback length.
    // A cue can start in the overlap and still contain speech beyond the last
    // emitted end. Keep its full text and clip only its timestamp; without word
    // alignment, trimming a text prefix could delete newly recognized speech.
    if (!segments.empty()) start_ms = std::max(start_ms, segments.back().end_ms);
    if (end_ms <= start_ms) return;
    if (text == nullptr || std::string(text).find_first_not_of(" \t\r\n") == std::string::npos) return;
    segments.push_back({start_ms, end_ms, text});
}

struct ProgressCallbackData {
    JavaVM * java_vm;
    jobject listener;
    jmethodID callback;
    int64_t total_samples;
    int64_t window_start;
    int64_t window_samples;
    std::atomic<int> last_percent{-1};
};

void throw_java(JNIEnv * env, const char * class_name, const char * message) {
    jclass exception_class = env->FindClass(class_name);
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message);
        env->DeleteLocalRef(exception_class);
    }
}

bool should_abort(void * user_data) {
    const auto * cancelled = static_cast<const std::atomic<bool> *>(user_data);
    return cancelled->load(std::memory_order_relaxed);
}

void notify_progress(JNIEnv * env, ProgressCallbackData * data, int percent) {
    const int clamped = std::clamp(percent, 0, 100);
    int previous = data->last_percent.load(std::memory_order_relaxed);
    while (clamped > previous) {
        if (data->last_percent.compare_exchange_weak(previous, clamped, std::memory_order_relaxed)) {
            env->CallVoidMethod(data->listener, data->callback, static_cast<jint>(clamped));
            if (env->ExceptionCheck()) env->ExceptionClear();
            return;
        }
    }
}

void notify_processed_samples(ProgressCallbackData * data, int64_t local_samples) {
    const int64_t processed = data->window_start + std::clamp<int64_t>(local_samples, 0, data->window_samples);
    // Whisper can report the final seek/timestamp before whisper_full returns.
    // Reserve 100% for a successful final window, including cancellation checks.
    const int percent = std::min(99, static_cast<int>(100.0 *
        static_cast<double>(processed) / static_cast<double>(data->total_samples)));
    JNIEnv * env = nullptr;
    bool attached = false;
    const jint status = data->java_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (data->java_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    } else if (status != JNI_OK) {
        return;
    }

    notify_progress(env, data, percent);
    if (attached) data->java_vm->DetachCurrentThread();
}

void on_whisper_progress(whisper_context *, whisper_state *, int window_percent, void * user_data) {
    auto * data = static_cast<ProgressCallbackData *>(user_data);
    notify_processed_samples(data, data->window_samples * std::clamp(window_percent, 0, 100) / 100);
}

void on_whisper_new_segment(whisper_context *, whisper_state * state, int n_new, void * user_data) {
    auto * data = static_cast<ProgressCallbackData *>(user_data);
    const int count = whisper_full_n_segments_from_state(state);
    for (int index = std::max(0, count - n_new); index < count; ++index) {
        // Public segment timestamps use 10 ms units, relative to this window.
        // Clamp before conversion: final segments can extend into padding.
        const int64_t end_cs = std::clamp<int64_t>(whisper_full_get_segment_t1_from_state(state, index),
            0, data->window_samples * 100 / WHISPER_SAMPLE_RATE);
        notify_processed_samples(data, end_cs * (WHISPER_SAMPLE_RATE / 100));
    }
}

std::u16string utf8_to_utf16(const char * input) {
    std::u16string output;
    const auto * bytes = reinterpret_cast<const unsigned char *>(input);
    const size_t length = std::strlen(input);
    size_t index = 0;
    while (index < length) {
        uint32_t code_point = 0xFFFD;
        size_t count = 1;
        const unsigned char first = bytes[index];
        if (first < 0x80) {
            code_point = first;
        } else if (first >= 0xC2 && first <= 0xDF && length - index >= 2 &&
                   (bytes[index + 1] & 0xC0) == 0x80) {
            code_point = ((first & 0x1F) << 6) | (bytes[index + 1] & 0x3F);
            count = 2;
        } else if (first >= 0xE0 && first <= 0xEF && length - index >= 3 &&
                   (bytes[index + 1] & 0xC0) == 0x80 && (bytes[index + 2] & 0xC0) == 0x80) {
            const uint32_t decoded = ((first & 0x0F) << 12) | ((bytes[index + 1] & 0x3F) << 6) |
                (bytes[index + 2] & 0x3F);
            if (decoded >= 0x800 && !(decoded >= 0xD800 && decoded <= 0xDFFF)) {
                code_point = decoded;
                count = 3;
            }
        } else if (first >= 0xF0 && first <= 0xF4 && length - index >= 4 &&
                   (bytes[index + 1] & 0xC0) == 0x80 && (bytes[index + 2] & 0xC0) == 0x80 &&
                   (bytes[index + 3] & 0xC0) == 0x80) {
            const uint32_t decoded = ((first & 0x07) << 18) | ((bytes[index + 1] & 0x3F) << 12) |
                ((bytes[index + 2] & 0x3F) << 6) | (bytes[index + 3] & 0x3F);
            if (decoded >= 0x10000 && decoded <= 0x10FFFF) {
                code_point = decoded;
                count = 4;
            }
        }

        if (code_point <= 0xFFFF) {
            output.push_back(static_cast<char16_t>(code_point));
        } else {
            code_point -= 0x10000;
            output.push_back(static_cast<char16_t>(0xD800 + (code_point >> 10)));
            output.push_back(static_cast<char16_t>(0xDC00 + (code_point & 0x3FF)));
        }
        index += count;
    }
    return output;
}

jstring new_java_string(JNIEnv * env, const char * text) {
    const std::u16string utf16 = utf8_to_utf16(text == nullptr ? "" : text);
    return env->NewString(reinterpret_cast<const jchar *>(utf16.data()), static_cast<jsize>(utf16.size()));
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_id_tomz_transcriptor_whisper_WhisperNative_createContext(
    JNIEnv * env,
    jobject,
    jstring model_path,
    jboolean use_gpu
) {
    if (model_path == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "Model path is required");
        return 0;
    }
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return 0;

    const std::string file_path(path);
    env->ReleaseStringUTFChars(model_path, path);
    try {
        auto load = [&](bool gpu) {
            struct stat file{};
            if (stat(file_path.c_str(), &file) != 0 || file.st_size <= 0) throw std::runtime_error("Whisper model is unavailable");
            const auto memory = transcriptor::read_memory_info();
            const auto reserve = transcriptor::whisper_working_reserve(transcriptor::whisper_audio_state(file_path), gpu);
            if (!transcriptor::model_fits_memory(static_cast<uint64_t>(file.st_size), memory.total, memory.available, gpu, reserve)) {
                throw std::runtime_error("RAM tersedia tidak cukup untuk Whisper; pilih model Tiny/Base atau tutup aplikasi lain");
            }
            whisper_context_params params = whisper_context_default_params();
            params.use_gpu = gpu;
            params.flash_attn = false;
            return whisper_init_from_file_with_params(file_path.c_str(), params);
        };
        whisper_context * context = nullptr;
        if (use_gpu) {
            try { context = load(true); } catch (const std::exception &) { /* Retry on CPU below. */ }
        }
        if (!context) context = load(false);
        if (!context) throw std::runtime_error("Whisper could not load the selected model");
        return reinterpret_cast<jlong>(context);
    } catch (const std::exception & error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
        return 0;
    } catch (...) {
        throw_java(env, "java/lang/IllegalStateException", "Whisper model loading failed");
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_id_tomz_transcriptor_whisper_WhisperNative_freeContext(JNIEnv *, jobject, jlong context_handle) {
    auto * context = reinterpret_cast<whisper_context *>(context_handle);
    if (context != nullptr) whisper_free(context);
}

extern "C" JNIEXPORT jlong JNICALL
Java_id_tomz_transcriptor_whisper_WhisperNative_createCancellationFlag(JNIEnv * env, jobject) {
    auto * cancelled = new (std::nothrow) std::atomic<bool>(false);
    if (cancelled == nullptr) {
        throw_java(env, "java/lang/OutOfMemoryError", "Could not create cancellation state");
        return 0;
    }
    return reinterpret_cast<jlong>(cancelled);
}

extern "C" JNIEXPORT void JNICALL
Java_id_tomz_transcriptor_whisper_WhisperNative_cancel(JNIEnv *, jobject, jlong cancellation_handle) {
    auto * cancelled = reinterpret_cast<std::atomic<bool> *>(cancellation_handle);
    if (cancelled != nullptr) cancelled->store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_id_tomz_transcriptor_whisper_WhisperNative_freeCancellationFlag(JNIEnv *, jobject, jlong cancellation_handle) {
    delete reinterpret_cast<std::atomic<bool> *>(cancellation_handle);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_id_tomz_transcriptor_whisper_WhisperNative_transcribe(
    JNIEnv * env,
    jobject,
    jlong context_handle,
    jstring audio_path,
    jstring language,
    jint requested_threads,
    jlong cancellation_handle,
    jboolean fast_decoding,
    jobject progress_listener
) {
    auto * context = reinterpret_cast<whisper_context *>(context_handle);
    auto * cancelled = reinterpret_cast<std::atomic<bool> *>(cancellation_handle);
    if (context == nullptr || cancelled == nullptr || audio_path == nullptr || language == nullptr || progress_listener == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "Whisper inference arguments are incomplete");
        return nullptr;
    }

    const char * path_chars = env->GetStringUTFChars(audio_path, nullptr);
    const char * language_chars = env->GetStringUTFChars(language, nullptr);
    if (path_chars == nullptr || language_chars == nullptr) {
        if (path_chars != nullptr) env->ReleaseStringUTFChars(audio_path, path_chars);
        if (language_chars != nullptr) env->ReleaseStringUTFChars(language, language_chars);
        return nullptr;
    }
    const std::string path(path_chars);
    const std::string language_value(language_chars);
    env->ReleaseStringUTFChars(audio_path, path_chars);
    env->ReleaseStringUTFChars(language, language_chars);

    std::ifstream input(path, std::ios::binary);
    if (!input) {
        throw_java(env, "java/io/IOException", "Normalized audio could not be opened");
        return nullptr;
    }
    input.seekg(0, std::ios::end);
    const std::streamoff file_bytes = input.tellg();
    if (file_bytes <= 0 || file_bytes % static_cast<std::streamoff>(sizeof(float)) != 0) {
        throw_java(env, "java/io/IOException", "Normalized audio is empty or truncated");
        return nullptr;
    }
    const int64_t total_samples = static_cast<int64_t>(file_bytes / static_cast<std::streamoff>(sizeof(float)));
    input.seekg(0, std::ios::beg);

    JavaVM * java_vm = nullptr;
    if (env->GetJavaVM(&java_vm) != JNI_OK) {
        throw_java(env, "java/lang/IllegalStateException", "Could not access the Java runtime");
        return nullptr;
    }
    jobject listener = env->NewGlobalRef(progress_listener);
    if (listener == nullptr) return nullptr;
    jclass listener_class = env->GetObjectClass(progress_listener);
    jmethodID progress_method = listener_class == nullptr ? nullptr : env->GetMethodID(listener_class, "onProgress", "(I)V");
    if (listener_class != nullptr) env->DeleteLocalRef(listener_class);
    if (progress_method == nullptr) {
        env->DeleteGlobalRef(listener);
        return nullptr;
    }

    std::vector<Segment> segments;
    ProgressCallbackData progress{java_vm, listener, progress_method, total_samples, 0, 0};
    try {
        std::vector<float> samples(static_cast<size_t>(kWindowSamples));
        int64_t window_start = 0;
        const int n_threads = std::clamp(static_cast<int>(requested_threads), 1, 4);

        while (window_start < total_samples) {
            if (cancelled->load(std::memory_order_relaxed)) {
                throw_java(env, "java/util/concurrent/CancellationException", "Transcription cancelled");
                env->DeleteGlobalRef(listener);
                return nullptr;
            }
            const int64_t window_samples = std::min(kWindowSamples, total_samples - window_start);
            const std::streamoff byte_offset = static_cast<std::streamoff>(window_start * static_cast<int64_t>(sizeof(float)));
            const std::streamsize byte_count = static_cast<std::streamsize>(window_samples * static_cast<int64_t>(sizeof(float)));
            input.clear();
            input.seekg(byte_offset, std::ios::beg);
            input.read(reinterpret_cast<char *>(samples.data()), byte_count);
            if (input.gcount() != byte_count) {
                throw_java(env, "java/io/IOException", "Normalized audio changed during transcription");
                env->DeleteGlobalRef(listener);
                return nullptr;
            }

            progress.window_start = window_start;
            progress.window_samples = window_samples;
            whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
            params.n_threads = n_threads;
            // Opt-in tradeoff: use one candidate instead of the default five
            // during temperature fallback. Ordinary temperature-zero decoding
            // already uses one candidate; preserve all other accuracy settings.
            if (fast_decoding == JNI_TRUE) params.greedy.best_of = 1;
            params.language = language_value.c_str();
            params.detect_language = false;
            params.no_context = false;
            params.print_progress = false;
            params.print_realtime = false;
            params.print_timestamps = false;
            params.print_special = false;
            params.translate = false;
            params.single_segment = false;
            params.progress_callback = on_whisper_progress;
            params.progress_callback_user_data = &progress;
            params.new_segment_callback = on_whisper_new_segment;
            params.new_segment_callback_user_data = &progress;
            params.abort_callback = should_abort;
            params.abort_callback_user_data = cancelled;

            const int result = whisper_full(context, params, samples.data(), static_cast<int>(window_samples));
            if (result != 0 || cancelled->load(std::memory_order_relaxed)) {
                if (cancelled->load(std::memory_order_relaxed)) {
                    throw_java(env, "java/util/concurrent/CancellationException", "Transcription cancelled");
                } else {
                    throw_java(env, "java/lang/IllegalStateException", "Whisper inference failed");
                }
                env->DeleteGlobalRef(listener);
                return nullptr;
            }

            const int64_t window_start_ms = window_start * 1000 / WHISPER_SAMPLE_RATE;
            const int64_t window_duration_ms = window_samples * 1000 / WHISPER_SAMPLE_RATE;
            for (int index = 0; index < whisper_full_n_segments(context); ++index) {
                append_window_segment(segments, window_start_ms, window_duration_ms,
                    whisper_full_get_segment_t0(context, index) * 10,
                    whisper_full_get_segment_t1(context, index) * 10,
                    whisper_full_get_segment_text(context, index));
            }

            const int completed_percent = static_cast<int>(100.0 *
                static_cast<double>(window_start + window_samples) / static_cast<double>(total_samples));
            notify_progress(env, &progress, completed_percent);
            if (window_start + window_samples >= total_samples) break;
            window_start += window_samples - kOverlapSamples;
        }
    } catch (const std::bad_alloc &) {
        env->DeleteGlobalRef(listener);
        throw_java(env, "java/lang/OutOfMemoryError", "Not enough memory to transcribe this audio");
        return nullptr;
    } catch (const std::exception & error) {
        env->DeleteGlobalRef(listener);
        throw_java(env, "java/lang/IllegalStateException", error.what());
        return nullptr;
    }
    env->DeleteGlobalRef(listener);

    jclass segment_class = env->FindClass("id/tomz/transcriptor/whisper/WhisperOutputSegment");
    if (segment_class == nullptr) return nullptr;
    jmethodID constructor = env->GetMethodID(segment_class, "<init>", "(JJLjava/lang/String;)V");
    if (constructor == nullptr) {
        env->DeleteLocalRef(segment_class);
        return nullptr;
    }
    if (segments.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        env->DeleteLocalRef(segment_class);
        throw_java(env, "java/lang/IllegalStateException", "Transcript contains too many segments");
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(segments.size()), segment_class, nullptr);
    if (result == nullptr) {
        env->DeleteLocalRef(segment_class);
        return nullptr;
    }
    for (size_t index = 0; index < segments.size(); ++index) {
        jstring text = new_java_string(env, segments[index].text.c_str());
        if (text == nullptr) break;
        jobject segment = env->NewObject(
            segment_class,
            constructor,
            static_cast<jlong>(segments[index].start_ms),
            static_cast<jlong>(segments[index].end_ms),
            text
        );
        env->DeleteLocalRef(text);
        if (segment == nullptr) break;
        env->SetObjectArrayElement(result, static_cast<jsize>(index), segment);
        env->DeleteLocalRef(segment);
        if (env->ExceptionCheck()) break;
    }
    env->DeleteLocalRef(segment_class);
    return result;
}
