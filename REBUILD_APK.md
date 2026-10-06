# Rebuilding the RadioDroid APK

The timeshift implementation records progressive radio audio into rolling files under the app
cache directory (`radio-timeshift`). It retains a two-hour seek window plus a five-minute safety
margin and deletes the files when playback stops. Do not restore the old two-hour ExoPlayer
`setBackBuffer` configuration: that buffer is heap-backed, can stall/OOM, and does not make
progressive live streams seekable.

## Prerequisites

- JDK 11
- Android SDK Platform 33
- Android SDK Build Tools 30.0.3 and 33.0.2

Set the SDK path locally (do not commit `local.properties`):

```sh
printf 'sdk.dir=%s\n' "$HOME/Android/Sdk" > local.properties
export ANDROID_HOME="$HOME/Android/Sdk"
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

## Verification before packaging

```sh
./gradlew clean testFreeDebugUnitTest lintFreeDebug --no-daemon
./gradlew assembleFreeDebug assembleFreeRelease --no-daemon
```

Expected APK locations:

```text
app/build/outputs/apk/free/debug/RadioDroid-free-debug-DEV-*.apk
app/build/outputs/apk/free/release/RadioDroid-free-release-*.apk
```

The debug APK is signed with the Android debug key and can be installed directly. The repository
does not define a release signing configuration, so the release APK is unsigned until a signing
key is supplied outside the repository.

Verify packaging:

```sh
"$ANDROID_HOME/build-tools/33.0.2/apksigner" verify --verbose \
  app/build/outputs/apk/free/debug/*.apk
"$ANDROID_HOME/build-tools/33.0.2/aapt" dump badging \
  app/build/outputs/apk/free/debug/*.apk | grep -E "package:|sdkVersion|targetSdkVersion"
```

The minimum SDK should remain 16.

## Manual playback checks

Use a real progressive MP3/AAC station with ICY metadata:

1. Start playback and expand the full player.
2. Confirm the playhead appears after audio has buffered and advances toward `LIVE`.
3. Wait at least 30 seconds, drag backward, and confirm older audio plays while metadata continues
   to update from the live recorder.
4. Drag to the right edge and confirm playback returns to live.
5. Stop playback and confirm `cacheDir/radio-timeshift` contains no session files.
6. Also test an HLS station; its seek range must be limited to the server-provided live window.
7. Leave a progressive station running long enough to cross several 1 MiB cache segments and
   watch logcat for disk I/O, decoder, or reconnect errors.

Do not reuse APKs built from commits `8dc1e33` or `d0cde88`; those builds used an unsafe in-memory
back buffer and did not provide working progressive-radio timeshift.
