# Release signing

The release APK uses a separate local signing key. The build sets `debuggable=false` and `jniDebuggable=false`. Code shrinking stays disabled so JNI class and method names remain stable.

The private files are `.signing/transcriptor-release.p12` and `keystore.properties`. Both are excluded from Git; the directory is restricted to its owner and the files are owner-readable/writable only. Back up **both files together** in a private, secure location. Future release updates need the same signing key. Do not upload either file with source code or APK artifacts.

To build with the existing local key:

```sh
./gradlew :app:assembleRelease -Ptranscriptor.abis=arm64-v8a
```

The phone APK is generated under `app/build/outputs/apk/release/`. Omitting `transcriptor.abis` builds ARM64 and x86_64. Distributed phone APKs use ARM64. Verified distributables belong in ignored `artifacts/v<version>/` and GitHub Release assets, outside `docs`.

On another checkout, restore the private signing files separately. `keystore.properties.example` documents the required field names without including credentials. Without local signing properties, Gradle produces an unsigned release APK that must be signed before installation. Never regenerate or overwrite an existing key just to rebuild.

For a completely new application identity, create a keystore interactively before filling in local properties:

```sh
umask 077
mkdir -p .signing
chmod 700 .signing
keytool -genkeypair -keystore .signing/transcriptor-release.p12 \
  -storetype PKCS12 -alias transcriptor -keyalg RSA -keysize 3072 \
  -sigalg SHA256withRSA -validity 10000 -dname 'CN=Transcriptor, O=qodequ.com'
chmod 600 .signing/transcriptor-release.p12
```

After creating local `keystore.properties`, set its permissions with `chmod 600 keystore.properties`.

## Moving from the debug APK

The debug and release certificates differ. Android cannot update the existing debug installation with this release APK. Export transcripts and retain original media before removing the debug app. Uninstalling removes its private projects and downloaded models; models may need to be downloaded again. An APK alone does not migrate that data. This build process does not uninstall or change any phone installation.

Relevant Android documentation: [signing configurations](https://developer.android.com/build/build-variants#signing) and [keeping signing information out of build scripts](https://developer.android.com/build/gradle-tips#remove-private-signing-information-from-your-project).
