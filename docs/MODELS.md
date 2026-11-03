# Models and hardware

Model files are optional, separate downloads. Choose according to the recording language and available memory.

## Speech recognition

For mobile use, **Small Q5** (190 MB) is a lighter multilingual option. It uses the same multilingual Small architecture with quantized weights: it saves storage/memory rather than improving accuracy over unquantized Small. Use **Balanced** decoding and select the known spoken language instead of automatic detection. **Large v3 Turbo Q5** (574 MB) is an optional quality candidate; its large encoder still needs more RAM and may be slower than Small on a phone. Neither model is English-only. Existing projects retain their chosen model.

Both downloads are pinned to [whisper.cpp model weights](https://huggingface.co/ggerganov/whisper.cpp/tree/5359861c739e955e79d9a303bcbc70fb988958b1), with exact sizes and SHA-256 verification. [OpenAI describes Turbo](https://github.com/openai/whisper/discussions/2363) as a multilingual large-v3 derivative with fewer decoder layers. No phone-specific accuracy or speed benchmark is claimed; noise, accents, mixed-language speech and the recording itself still matter.

Standard multilingual **Small** remains the original 487,601,967-byte file (about 488 MB / 465 MiB). The roughly 852 MB figure in the [whisper.cpp memory table](https://github.com/ggml-org/whisper.cpp#memory-usage) describes runtime memory, not the download size. Small Q5 is a separate catalog option.

### Cahya Indonesian model

**Cahya Medium · Indonesia · Q5** is an optional Indonesian-only transcription model based on [cahya/whisper-medium-id](https://huggingface.co/cahya/whisper-medium-id), using [rafdiraf's community GGML Q5 conversion](https://huggingface.co/rafdiraf/whisper-medium-id-ggml). The 539,212,467-byte download is pinned to revision `674030cf4477a23e279fbc95ffa993b08db02269` and SHA-256 `da09c51f05f78a727d37325e2d1ad7b78c20597593215276e820cd7fa1e92e0f`. Both source and conversion are identified in the app; the source is Apache-2.0 licensed.

Selecting it fixes the source language to Indonesian, as required by its fine-tune. It does not replace existing model selections or act as a general multilingual model. Medium's encoder requires more working memory and processing time than Small despite quantization. An Indonesian speech smoke test checks compatibility; it is not a comparative accuracy benchmark.

## AI translation

AI requests include complete cue groups, neighboring source text, preceding translations and optional user terminology guidance. Local decoding validates cue IDs, count and order. Generic chat models use a constrained JSON grammar; TranslateGemma uses its translation-specific prompt with exactly one complete source cue per request and separate reference context; its entire answer belongs to that cue, with no marker protocol. Imported TranslateGemma GGUF files are identified from bounded metadata reads. Incomplete or malformed translations are rejected without replacing saved text.

### Online

Use your own API key with an HTTPS OpenAI-compatible chat-completions endpoint that supports JSON-object responses. The default endpoint is OpenRouter with `qwen/qwen3.5-27b`; the endpoint and model are editable. Text is sent to the chosen provider, and its billing/retention terms apply. The API key is encrypted with Android Keystore AES/GCM in excluded-from-backup storage. Requests have size/time limits, never follow redirects and can be cancelled. No cloud key is embedded in the app.

### Offline on the phone

Whisper, offline translation and accelerator detection run in a separate worker process. If that process dies, the request is retried once on CPU with Vulkan registration disabled. A failure after that produces a project error while keeping the library and editor available. A Whisper retry restarts the transcription request. AI weights and graph/context allocations are reused between cue groups within a translation session. KV/dialogue state is reset for each request, and cancellation callbacks are detached before their flags are freed. The client explicitly ends the worker and waits up to five seconds for process death at session close before allowing the next session. Whisper memory checks include encoder architecture, because quantization reduces weight size without shrinking encoder working buffers. Memory checks reserve additional headroom for GPU or CPU inference; this is a guard, not a guarantee against operating-system memory pressure.

Download a catalog model under **Model → Penerjemah AI**, wait for size/SHA-256 verification, then activate it with **Gunakan**. Downloads use Android DownloadManager, continue through navigation and are recovered after app restart. Wi-Fi is required by default. The selected model persists; a missing model never silently switches to another file. Custom text-capable **instruct/chat GGUF** import is an additional option at the bottom of the same Model page. Models stay in app-specific storage, and translation runs on the phone through llama.cpp. There is no automatic cloud fallback. Model weights are downloaded separately and are not bundled into the APK.

| Model family | Practical starting point | Notes |
|---|---|---|
| [Qwen3.5](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF) | 0.8B or 2B, Q4_K_M | Available in the in-app catalog; 0.8B is lighter, 2B uses more memory. |
| [Llama 3.2 Instruct](https://huggingface.co/unsloth/Llama-3.2-1B-Instruct-GGUF) | 1B, Q4_K_M | Available in the in-app catalog. The official language list is limited; verify your language pair. |
| [Google TranslateGemma 4B](https://huggingface.co/google/translategemma-4b-it), [community GGUF quant](https://huggingface.co/mradermacher/translategemma-4b-it-GGUF) | Q4_K_M, 2.49 GB | Available in the in-app catalog under Gemma Terms. Purpose-built for translation and reported for 55 languages. The 4B weights need substantial free RAM and storage. |
| [Google Gemma 4 E2B instruction-tuned](https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf) | Q4_0, 3.35 GB | Available in the in-app catalog. Requires more free storage and RAM than smaller catalog models. The text-only GGUF is downloaded; the image projector is not needed for translation. |

Custom imports can also use compatible Qwen2.5 or Qwen3 instruct GGUF files. Thinking is disabled for Qwen translation JSON responses.

For Qwen3.5, use the text model file `Qwen3.5-2B-Q4_K_M.gguf` (about 1.28 GB), download it directly from **Model → Penerjemah AI**, or choose **Impor file GGUF** on that page. Image projection files are not needed for text translation.

Imports accept GGUF v2/v3 from 1 MiB to 4 GiB, check available disk space and replace the previous file atomically only after validation. Native loading checks RAM headroom; TranslateGemma's 2.49 GB and Gemma 4's 3.35 GB weights may be rejected on smaller phones. TranslateGemma uses its model-specific language prompt and one complete cue per generation; the source language must be selected. Missing/unsupported chat templates and overlong prompts produce explicit errors. Long cues are never silently truncated. Smaller models trade translation quality for memory use. The Qwen3.5 0.8B emulator checks established offline operation and cue alignment, but an ambiguous bank/river example still produced inaccurate wording and duplicated context (see the verification report). All translations should be reviewed, especially names and specialist terms. The selectable language catalog does not guarantee that every model supports every language.

## Acceleration and memory

Available Vulkan GPUs are preferred by the native engines, with CPU retry after accelerator failures. CPU thread count and batch size adapt to thermal conditions. OpenCL/NNAPI/NPU/APU capability reports do not imply an active inference backend. The current Whisper GGML and translator GGUF models execute through the implemented Vulkan/CPU paths; they are not automatically converted for a vendor NPU. Quantization reduces weight size but does not remove encoder or context working-memory requirements.

Larger models can be refused when RAM headroom is insufficient. Memory checks and process isolation reduce failure impact; they cannot guarantee that Android or a device driver will always keep an inference process alive. No universal phone speed or accuracy claim is made.
