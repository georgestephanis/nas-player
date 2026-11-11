# AGENTS.md

Notes for agents working on this repo. See `README.md` and `docs/ARCHITECTURE.md` for how the app works.

## Building and installing on the Google TV Streamer

- **Build release APKs on `sardonyx`** (`ssh sardonyx`, checkout at `~/code/nas-player`). Its
  `~/.android/debug.keystore` signed the installed app; an APK from any other machine fails with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and uninstalling to get around that wipes saved sources and
  resume positions. Never do that.
- sardonyx build: `cd ~/code/nas-player && git fetch && git checkout <branch> && git pull &&
  JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew -q --no-daemon assembleRelease`
  (`local.properties` there points at `~/Library/Android/sdk`; it is gitignored).
- Local Mac build (compile check only): `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease`.
- **The Streamer is 32-bit userspace (`armeabi-v7a`)**. Install `app-armeabi-v7a-release.apk`;
  the arm64 one fails with `INSTALL_FAILED_NO_MATCHING_ABIS`.
- Device: `adb -s 192.168.0.140:5555`. The main Mac's adb is already authorised there, so copy the
  APK back (`scp sardonyx:code/nas-player/app/build/outputs/apk/release/app-armeabi-v7a-release.apk .`)
  and `adb install -r` it from the Mac.
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
  Resuming one (`setMediaItem(item, startMs)`) still starts at 0.
