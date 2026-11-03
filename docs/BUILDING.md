# Building and testing

Requires a full JDK 25 LTS, Android SDK 37.2, NDK 28.2.13676358, CMake 3.22.1 and the Gradle wrapper. Set `JAVA_HOME` to the full JDK (including `javac`), and configure `ANDROID_HOME` or `sdk.dir` in an ignored `local.properties`. Gradle 9.8.2, AGP 9.5.0 and built-in Kotlin are used; Java/Kotlin bytecode targets Java 17, with min SDK 26 and target SDK 36. Dependency versions are pinned in `gradle/libs.versions.toml`.

Build defaults limit Gradle to one worker, two logical processors and a 1 GiB heap, and stop the daemon after completion. Run the build and lint separately, then start the emulator after Gradle exits. Model test fixtures should live on disk rather than RAM-backed `/tmp`.

Initial builds require network access for Maven dependencies and pinned native source archives. whisper.cpp and llama.cpp use isolated static builds with hidden symbols, preventing incompatible ggml revisions from mixing. Vulkan is enabled for both engines; an available Vulkan GPU is preferred, with CPU fallback after device or inference errors. NNAPI accelerator/APU detection is informational: current Whisper GGML and translator GGUF models do not execute on the APU path. The POCO X6 Pro's Dimensity 8300/MT6897 is listed by [LiteRT's generic MediaTek support](https://developers.google.com/edge/litert/next/mediatek), but the public [LiteRT-LM NPU model table](https://developers.google.com/edge/litert/next/litert_lm_npu#mediatek-neuropilot) currently provides MediaTek LLM artifacts for MT6989/MT6991, not MT6897. GGML/GGUF files cannot be routed directly through that runtime; an appropriate converted model and tested integration are still needed. llama.cpp is pinned to upstream commit `79bce0c` for Gemma 4 support.

`:app:assembleDebug` produces separate `app-arm64-v8a-debug.apk` and `app-x86_64-debug.apk` files under `app/build/outputs/apk/debug/`. The ARM64 APK is the phone build; the x86_64 APK is for emulators. A universal APK is disabled to avoid making phone downloads include both native runtimes.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```



## Update artifacts

Every update must build both ARM64 variants. Set `versionName` to the public version and keep `versionCode` increasing. Public v1.0 uses internal code 11 so earlier development installations can update without a version downgrade.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease -Ptranscriptor.abis=arm64-v8a
```

Restore the existing ignored signing files before producing the distributed release; see [signing](../SIGNING.md). Copy the verified APKs to `artifacts/v<version>/Transcriptor-v<version>-debug.apk` and `Transcriptor-v<version>-release.apk`, and generate `SHA256SUMS.txt`. Publish them as GitHub Release assets. Keep binaries out of Git and out of `docs`.

Build emulator APKs and the test APK with `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -Ptranscriptor.abis=x86_64`, then stop Gradle before starting an emulator on a small laptop. `connectedDebugAndroidTest` also needs an available device. Real-model tests are opt-in via `whisperSmoke=true`, `translationSmoke=true` or `mobileWhisperSmoke=true` and require the corresponding verified local fixtures. The default UI/data suite skips those inference checks.

Database schema v5 uses the additive v4→v5 migration. Presentation preferences and AI settings are separate from stored source cues. Keep tests for migrations, cancellation, worker isolation, draft preservation, export lifecycle and failed writes when changing those workflows.
