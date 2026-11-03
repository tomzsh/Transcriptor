# v1.0 verification

Public version **1.0**, Android `versionCode` **11**, application ID `id.tomz.transcriptor`, min SDK 26, target SDK 36. The increasing internal code allows updates from earlier development builds despite the public version reset. This release changes version metadata and repository packaging; application behavior is unchanged from the tested UI/model baseline.

## Current build

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease -Ptranscriptor.abis=arm64-v8a
```

Completed successfully on 3 November 2026 in 3m 57s, including `lintVitalRelease`, using JDK 25, one Gradle worker and a 1 GiB heap with the emulator stopped.

- **120 JVM tests passed**, zero failures/errors/skips.
- Both APKs passed signature verification for min SDK 26 and retain their existing signing identities.
- Manifest/generated config checks confirm debug is debuggable and release is non-debuggable and not test-only. Only ARM64 native code is packaged.
- `zipalign -c -P 16 4` passed for both. All eight native ELF libraries have 16 KiB LOAD alignment.
- Signing files are excluded. A release archive scan also found no local signing password in packaged contents. Model weights are separate downloads.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `Transcriptor-v1.0-debug.apk` | 112,403,681 | `38eb9d7449e93cebac659181933512b009b2a734d6b3487ae593b0224899eab9` |
| `Transcriptor-v1.0-release.apk` | 92,563,165 | `e048518d545aa7dbe7c2bb579a944378660601dc3339d75311ebc39904f99d1f` |

Local files and `SHA256SUMS.txt` live in ignored `artifacts/v1.0/`. Public downloads are [GitHub Release assets](https://github.com/tomzsh/Transcriptor/releases/tag/v1.0), outside Git source history.

## Runtime baseline

The preceding internal UI/model build ran the full API 35 x86_64 emulator suite: **32 passed, 7 assumption-skipped, zero failures** (39 discovered). The skipped checks require explicitly enabled real-model fixtures. The v1.0 metadata-only repackaging reran JVM/build checks; it did not repeat emulator or phone inference tests.

Coverage includes language recreation/navigation, catalog discovery, speaker accessibility, clean paragraphs, export lifecycle, editor write failures, Room migrations/persistence, processing constraints, worker isolation, Cahya language validation and unsaved drafts across tabs. Draft regressions were observed failing, then passed after the outgoing lazy list captured an immutable tab value and used its own saved-state scope. API keys remain outside saved-instance state.

The UI baseline passed full debug lint with zero errors; remaining warnings were existing API, unused-resource and style notices. Light/dark appearance, 360dp enlarged text and wide layouts were inspected. Screenshots use synthetic text: [reader](screenshots/reader-large.png), [settings](screenshots/settings-large.png), [tablet](screenshots/tablet.png).

The optional Cahya Medium Indonesian Q5 download matched its pinned size/hash and loaded in a host whisper.cpp CPU smoke test with public Indonesian speech. The output contained recognition errors. That test establishes format/load compatibility, not improved WER or phone speed. No universal chipset, NPU/APU execution, physical-phone performance or accuracy benchmark is claimed.
