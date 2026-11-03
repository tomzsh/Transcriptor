# Privacy and diagnostics

Audio/video transcription runs locally through whisper.cpp. Local AI translation processes transcript text on the device through llama.cpp. Models download from the sources identified in the catalog; weights are size/hash verified. Downloading models requires network access, but local inference does not upload the recording or silently fall back to a cloud service.

Online AI translation is an explicit option. It sends transcript text and context to the selected provider using your own API key. Its billing and retention terms apply. The key is encrypted using Android Keystore AES/GCM in excluded-from-backup storage. No provider key is bundled in the app.

## Local diagnostics

**Settings → Share diagnostics report** creates a ZIP for user-controlled sharing. Logs rotate locally (two 256 KiB files plus one bounded crash record per process; files inactive for seven days are removed during cleanup, while active bounded files may include older events). They include app/device version, stages, request duration, memory/thermal samples, sanitized Java exception classes/frames and Android 11+ process-exit metadata. Transcript/media content, API keys, exception messages and raw native/ANR traces are excluded. Nothing is uploaded automatically. **Clear diagnostics** removes logs and exported archives. A native process death can now be investigated from its OS exit reason instead of being labelled as insufficient RAM without evidence.

## Storage and sharing

Projects, imported media and downloaded models use app-specific storage. Copying/exporting or sharing diagnostics is user initiated. Uninstalling removes private app data. Retain original media and exported documents independently if you need them later. Local operation is a design choice, not a claim that the app has received an independent security audit.
