# Testing

Pure logic has a few JVM tests (`./gradlew testDebugUnitTest`). Everything else is verified on a real device, because the emulator hid every serious bug so far (keystore hangs, slow SMB connects, VLC rendering black).

## Test share

Run a throwaway Samba server with Docker and point it at a folder of sample files:

```bash
docker run -d --name nasplayer-smb -p 1445:445 -v /path/to/media:/media:ro \
  dperson/samba -u "tester;secret" -s "Media;/media;yes;yes;no;tester" -p
```

Add it in the app with your computer's LAN IP, port `1445`, user `tester`, password `secret`. From the emulator the host is `10.0.2.2`.

Useful sample files, generated with `ffmpeg` (`-f lavfi -i testsrc2` for video, `-i sine` for audio):

| Purpose | Encoding |
|---|---|
| FFmpeg audio path | H.264 + DTS / TrueHD in MKV |
| Broadcast TS flags | MPEG-2 + MP2 in TS |
| Direct to VLC | WMV2 + WMA2 |
| Automatic fallback | MS-MPEG4v3 in AVI, ProRes in MOV |
| Throughput | 60–100 Mbit/s H.264 1080p with `noise` so it doesn't compress |
| Thumbnails | `poster.jpg` in folders, FLAC with an attached cover (`-disposition:v attached_pic`), JPEG photos |

## Simulating a USB drive

The emulator can fake a removable volume, which gets you the same `/storage/<uuid>` path a real drive has. It doesn't exercise the filesystem support of real TV firmware, so try a real drive as well.

```bash
adb shell sm set-virtual-disk true
adb shell sm list-disks                 # e.g. disk:7,288
adb shell sm partition disk:7,288 public
adb shell sm list-volumes               # e.g. public:7,289 mounted 57FB-1C1E
adb push /path/to/media /storage/57FB-1C1E/Media
adb shell sm unmount public:7,289       # "unplug"; sm mount to plug it back in
```

## Driving the TV over adb

```bash
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/release/app-armeabi-v7a-release.apk
adb shell input keyevent DPAD_DOWN      # DPAD_UP/LEFT/RIGHT/CENTER, BACK
adb shell input text "hello"            # into a focused text field
adb exec-out screencap -p > shot.png
```

- Long chains of `BACK` can leave the app and act on the launcher; relaunch with `adb shell am start -S -n dev.atv.nasplayer/.MainActivity` instead.
- Player overlays fade out after a few seconds; press `DPAD_DOWN` just before a screenshot.

## What to look at

- **Logs** (`adb logcat`): `NasSmb` (connect time, SMB throughput per read session), `NasThumb` (thumbnail failures), `NasExo` (Media3 `EventLogger`, debug builds only), `SecretBox` (keystore fallback).
- **ANRs:** `adb shell dumpsys dropbox --print data_app_anr`, then find the `Process: dev.atv.nasplayer` entry and its `"main"` thread stack.
- **Playback log:** Settings → Playback log, or `run-as dev.atv.nasplayer cat files/playback-log.jsonl` on a debug build.
- **Performance:** measure with a release build. Debug builds read SMB about three times slower.

## Screenshots

`docs/screenshots/` was captured at 1920×1080 against a demo library of generated, original artwork and media, then resized to 1280×720 and stripped of metadata. Some shots come from a Google TV Streamer and some from the emulator. Never capture screenshots against a real library: file names and artwork end up in a public repo.

The home screen lists servers found on the network, and the emulator sees your real ones. Before capturing it, run `adb shell cmd connectivity airplane-mode enable`, and check the image before committing it.
