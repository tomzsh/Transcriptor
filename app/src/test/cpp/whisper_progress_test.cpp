// Small host test of the real JNI progress adapter. Compile with the pinned
// Whisper headers, Android NDK's jni.h and -ffunction-sections -Wl,--gc-sections.
#include "../../main/cpp/whisper_jni.cpp"

#include <cassert>
#include <cstdarg>
#include <iostream>

// The adapter uses the public engine getters. Fixed committed timestamps isolate
// our progress mapping from model accuracy and do not allocate a model in tests.
struct whisper_state { std::vector<int64_t> end_centiseconds; };

extern "C" int whisper_full_n_segments_from_state(whisper_state * state) {
    return static_cast<int>(state->end_centiseconds.size());
}

extern "C" int64_t whisper_full_get_segment_t1_from_state(whisper_state * state, int index) {
    return state->end_centiseconds.at(index);
}

namespace {
std::vector<int> delivered;
JNIEnv * callback_env = nullptr;

void record_progress(JNIEnv *, jobject, jmethodID, va_list args) {
    delivered.push_back(va_arg(args, jint));
}

jboolean no_exception(JNIEnv *) { return JNI_FALSE; }

jint get_env(JavaVM *, void ** result, jint) {
    *result = callback_env;
    return JNI_OK;
}
} // namespace

int main() {
    JNINativeInterface functions{};
    functions.CallVoidMethodV = record_progress;
    functions.ExceptionCheck = no_exception;
    JNIEnv env{&functions};
    callback_env = &env;
    JNIInvokeInterface vm_functions{};
    vm_functions.GetEnv = get_env;
    JavaVM vm{&vm_functions};

    ProgressCallbackData progress{&vm, nullptr, nullptr, 16000, 0, 16000};
    on_whisper_progress(nullptr, nullptr, 100, &progress);
    assert((delivered == std::vector<int>{99}) && "Only a successful final window may report 100%");

    delivered.clear();
    ProgressCallbackData overlap{&vm, nullptr, nullptr, 160000, 0, 96000};
    on_whisper_progress(nullptr, nullptr, 100, &overlap);
    overlap.window_start = 64000;
    overlap.window_samples = 96000;
    on_whisper_progress(nullptr, nullptr, 0, &overlap);
    on_whisper_progress(nullptr, nullptr, 50, &overlap);
    on_whisper_progress(nullptr, nullptr, -20, &overlap);
    assert((delivered == std::vector<int>{60, 70}) && "Overlapping windows must never move progress backwards");

    delivered.clear();
    ProgressCallbackData segments{&vm, nullptr, nullptr, 160000, 0, 160000};
    whisper_state state{{125, 480}};
    on_whisper_new_segment(nullptr, &state, 2, &segments);
    assert((delivered == std::vector<int>{12, 48}) && "Committed segment ends must update progress before the window returns");
    segments.window_start = 64000;
    segments.window_samples = 96000;
    state.end_centiseconds = {10, 300};
    on_whisper_new_segment(nullptr, &state, 2, &segments);
    assert((delivered == std::vector<int>{12, 48, 70}) && "Segment progress includes the window offset without counting overlap twice");
    state.end_centiseconds = {-5, 999999};
    on_whisper_new_segment(nullptr, &state, 2, &segments);
    assert(delivered.back() == 99 && "Engine padding must not report beyond the audio or finish prematurely");
    notify_progress(&env, &segments, 100);
    assert(delivered.back() == 100);

    std::vector<Segment> boundary{{27000, 30000, "Already saved speech"}};
    append_window_segment(boundary, 28000, 30000, 0, 1000, "Fully covered speech");
    assert(boundary.size() == 1 && "A fully covered overlap cue must not be emitted again");
    append_window_segment(boundary, 28000, 30000, 1500, 4000, "Overlapping prefix and new speech");
    assert(boundary.size() == 2 && "A boundary-crossing cue must preserve its newly processed tail");
    assert(boundary.back().start_ms == 30000 && boundary.back().end_ms == 32000);
    assert(boundary.back().text == "Overlapping prefix and new speech" && "Do not guess which words belong to the clipped prefix");

    std::vector<Segment> recovered{{25000, 26000, "Earlier speech"}};
    append_window_segment(recovered, 28000, 30000, 500, 1500, "Previously missed lookback speech");
    assert(recovered.size() == 2 && recovered.back().start_ms == 28500 && recovered.back().end_ms == 29500);

    std::vector<Segment> overlapping;
    append_window_segment(overlapping, 0, 30000, 1000, 3500, "First cue");
    append_window_segment(overlapping, 0, 30000, 3000, 6000, "Next cue");
    assert(overlapping.size() == 2 && overlapping.back().start_ms == 3500 && overlapping.back().end_ms == 6000);
    append_window_segment(overlapping, 0, 30000, 4000, 5500, "Already covered inside a window");
    assert(overlapping.size() == 2);

    std::vector<Segment> clamped;
    append_window_segment(clamped, 28000, 4000, -100, 5000, "Padded cue");
    assert(clamped.size() == 1 && clamped[0].start_ms == 28000 && clamped[0].end_ms == 32000);
    append_window_segment(clamped, 28000, 4000, 5000, 6000, "Entirely in padding");
    assert(clamped.size() == 1);

    std::cout << "Whisper JNI progress and window-boundary checks passed\n";
}
