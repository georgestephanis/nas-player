# AGENTS.md

Notes for agents working on this repo. See `README.md` and `docs/ARCHITECTURE.md` for how the app works.

## Building and installing on the Google TV Streamer

- **Signing:** release builds use `~/.android/nas-player.keystore` when it exists (the debug key from
  `sardonyx` that signed the installed app; standard debug passwords), else the machine's own debug
  key. That file is on the main Mac and on sardonyx. Without it, installs fail with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and uninstalling to get around that wipes saved sources and
  resume positions. Never do that. Key files are gitignored and must never be committed.
- Main Mac: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease`.
- sardonyx (`ssh sardonyx`, checkout at `~/code/nas-player`, `local.properties` points at
  `~/Library/Android/sdk`): `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew -q --no-daemon assembleRelease`.
- **The Streamer is 32-bit userspace (`armeabi-v7a`)**. Install `app-armeabi-v7a-release.apk`;
  the arm64 one fails with `INSTALL_FAILED_NO_MATCHING_ABIS`.
- Device: `adb -s 192.168.0.140:5555` (the main Mac's adb is authorised there):
  `adb install -r app/build/outputs/apk/release/app-armeabi-v7a-release.apk`.
- Check the keys match before installing: `apksigner verify --print-certs` on the built APK vs the
  installed one (`adb shell pm path dev.atv.nasplayer`, then `adb pull`). Expected SHA-256:
  `5187ed066e99cf289ab8f4c5626f8e2ef229bba64d36bd47888160c4bebd2425`.
- Installing kills playback; check with the user if they're watching something.

## Debugging playback on the device

- Release builds aren't debuggable, so `run-as` (and the `files/playback-log.jsonl` file) isn't
  reachable. Use `adb logcat -d -v time --pid=$(adb shell pidof dev.atv.nasplayer)`. ExoPlayer stack
  traces name the extractor in use (e.g. `FragmentedMp4Extractor`), which tells you the container.
- `EventLogger("NasExo")` is attached only in debug builds.

## ExoPlayer gotchas

- Index-less files (fragmented MP4 with no `sidx`, etc.) are unseekable in ExoPlayer, and it turns
  every seek into a seek to 0. `ExoHost.seekBy` hands these to VLC at the target position instead.
  Resuming one would start at 0, so `ExoHost` also hands off on the first `STATE_READY` when
  `startMs > 0`.

## libVLC gotchas

- On the Streamer (MediaTek `c2.mtk.avc.decoder`), VLC's hardware decoding wedges when a seek flushes
  the decoder. The picture freezes while audio carries on, and logcat shows `client does not own the
  buffer`, then repeated `buffer deadlock prevented`. Sessions handed to VLC because of a seek use
  `PlaybackSession.softwareDecode`. Other VLC sessions still decode in hardware and may freeze the
  same way when seeking.
- Software (avcodec) seeks in index-less files land between keyframes and draw grey smears from missing
  references, so those sessions add `:no-avcodec-corrupted`.
