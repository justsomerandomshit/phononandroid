<img src="assets/icon.svg" width="64" height="64" alt="">

# Phonon TTS for Android

A system text-to-speech engine for Android built on [Phonon](https://github.com/gradium-ai/xn-ptts),
Gradium's open-weight neural TTS. Install it, download a model in the app, and pick it as the
device's preferred engine. From then on every app that speaks through Android's `TextToSpeech`
uses it: TalkBack, Select to Speak, e-book readers, navigation, browsers' read-aloud, and so on.
Speech is generated on the phone's CPU. The network is only used to download models and voices.

## Features

- **Works as the system engine.** It appears under *Settings → Accessibility → Text-to-speech
  output → Preferred engine* (or *System → Languages*, depending on the phone).
- **One-tap model download** from Hugging Face. The default is Phonon 31m English (65 MB, Apache
  2.0). The gated Phonon 40m models (French, German, Spanish, Portuguese, English and
  multilingual; CC BY-NC 4.0) also work: accept their terms on Hugging Face and paste a token.
  You can also import a checkpoint folder from storage.
- **Voice catalog.** Browse the [102 catalog voices](https://huggingface.co/gradium/phonon-voices)
  and download the ones you want (about 250 KB each). Each shows up as an Android voice in its
  own locale (en-US, en-GB, fr-FR, …), so apps and the system settings can pick it by name.
- **Default voice per language**, used when an app asks only for a language.
- **Speech rate and pitch.** The sliders in the system settings, TalkBack and apps all work.
  Phonon has no rate control the C interface exposes, so the engine time-stretches its output
  (pitch-synchronous overlap-add, as in Sonic) and resamples for pitch.
- **Streaming.** Audio goes to Android as it is generated, and stopping cuts generation off
  right away (about 15 ms from a flush to the next utterance starting on the emulator).

## Install

Download the APK from [Releases](../../releases) (or the latest CI run's artifact) and install it.
Then:

1. Open **Phonon TTS** and tap **Get** next to Phonon 31m.
2. Tap **Open text-to-speech settings → Preferred engine → Phonon TTS**.
   Or from a computer: `adb shell settings put secure tts_default_synth dev.kanav.phonon`
3. Optionally, **Get more voices** and pick your default voice.

Requirements: Android 8+ on a 64-bit ARM phone with ARMv8.2 (dotprod and fp16, nearly every phone
since 2019), or an x86_64 emulator. For older ARM phones, build with
`ARM64_FEATURES= ./scripts/build-native.sh`, which is slower.

## Build

You need JDK 17, the Android SDK (platform 36) with an NDK, and Rust.

```sh
git clone --recursive https://github.com/justsomerandomshit/phononandroid
cd phononandroid
cargo install cargo-ndk
./scripts/build-native.sh        # libptts_ffi.so into app/src/main/jniLibs
./gradlew assembleDebug          # app/build/outputs/apk/debug/phonon-tts-debug.apk
./gradlew assembleRelease        # minified; signed with keystore.properties if present, else the debug key
```

`build-native.sh` finds the NDK from `ANDROID_NDK_HOME`, or the newest one in your SDK.
The Phonon runtime is the `third_party/xn-ptts` submodule, pinned to a release tag.

CI (`.github/workflows/build.yml`) builds the APK on every push and attaches it to a GitHub
release for `v*` tags. To sign CI builds with a stable key, so updates install over each other,
set the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` secrets.

## Test

`app/src/androidTest` drives the installed engine through `TextToSpeech`, as any app would. With a
model installed, it writes WAVs for each English voice, checks that a 2× speech rate halves the
length, and that `QUEUE_FLUSH` stops long speech.

```sh
./gradlew connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

Without that property Gradle uninstalls the app afterwards, deleting the downloaded model.

## Layout

| Path | What it is |
|---|---|
| `app/src/main/java/dev/kanav/phonon/engine/PhononService.kt` | The `TextToSpeechService` |
| `…/engine/Model.kt` | Installing, loading and swapping the checkpoint; Android voices |
| `…/engine/Voices.kt`, `Catalog.kt` | Model and voice catalogs, downloads |
| `…/engine/TimeStretch.kt` | Speech rate and pitch |
| `…/ffi/PhononTTS.kt` | JNA wrapper over `libptts_ffi.so`, vendored from upstream |
| `…/ui/` | The settings app (Jetpack Compose) |
| `scripts/build-native.sh` | Cross-compiles the Rust runtime for Android |

## Licenses

This app's code builds on the upstream engine scaffold in `xn-ptts/android/tts-engine`, which is
MIT OR Apache-2.0. Model weights are licensed separately: Phonon 31m is Apache 2.0; Phonon 40m and
Phonon 40m multilingual are CC BY-NC 4.0 (non-commercial). The catalog voices are Apache 2.0.
