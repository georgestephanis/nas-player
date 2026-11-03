# NAS Player

An Android TV / Google TV app for streaming video and music straight from a NAS: SMB (Windows / Samba / TrueNAS / Synology shares) and WebDAV. It has no cloud, no accounts, no media scanning and no metadata scraping. You browse folders and press play.

## Features

- **SMB 2/3 and WebDAV** sources, with shares listed automatically and SMB servers discovered over mDNS/Bonjour.
- **Two playback engines, chosen per file:**
  - **ExoPlayer (Media3)** first: hardware video decoding, HDR, and surround passthrough to an AV receiver. The [Jellyfin FFmpeg extension](https://github.com/jellyfin/jellyfin-androidx-media) decodes DTS, TrueHD, AC-3/E-AC-3, MP2 and other audio in software when the TV can't.
  - **libVLC** for containers ExoPlayer can't open at all (WMV/ASF, RealMedia, APE, ...). It also takes over **automatically** when ExoPlayer can't parse a file or decode a track (DivX 3, ProRes, VC-1, ...).
  - Any file can be forced to either engine from its long-press menu, or globally in Settings.
- Resume position per file, a "Continue watching" row, watched markers, and auto-play of the next file in the folder (with natural sort, so episode 2 comes before episode 10).
- Sidecar subtitles (`Movie.srt`, `Movie.en.srt`, `.ass`, `.vtt`) picked up next to the video.
- **Playback log** (Settings → Playback log): for every file played it records the container, each track's codec, which decoder handled it (hardware codec name, FFmpeg, passthrough, libVLC), and every ExoPlayer→VLC handoff or failure with the reason. "Problems only" groups the missing codec support by frequency. It records server display names and paths only, never hosts or credentials.

## Tested formats

On a Google TV Streamer (Android 14):

| File | Engine | Video | Audio |
|---|---|---|---|
| H.264 + AC-3 5.1 MKV | ExoPlayer | hardware | platform / passthrough |
| H.264 + DTS MKV | ExoPlayer | hardware | FFmpeg |
| HEVC + TrueHD MKV | ExoPlayer | hardware | FFmpeg |
| MPEG-2 + MP2 TS (broadcast) | ExoPlayer | hardware | FFmpeg |
| WMV8 + WMA2 | VLC (direct) | libVLC | libVLC |
| DivX 3 AVI | ExoPlayer → VLC fallback | libVLC | libVLC |
| ProRes MOV | ExoPlayer → VLC fallback | libVLC | libVLC |

## Devices

- **Google TV Streamer** and **Chromecast with Google TV** both run a 32-bit (`armeabi-v7a`) userspace, so install the `armeabi-v7a` APK on them.
- Most other current TVs and boxes (Shield, recent Sony/TCL sets): `arm64-v8a`.
- Emulator: `x86_64` (or `arm64-v8a` on Apple Silicon).
- Each build also produces a universal APK.

Requires Android 8.0 (API 26) or newer.

## Building

You need JDK 21 and the Android SDK (platform 37, build-tools).

```bash
./gradlew assembleDebug        # APKs in app/build/outputs/apk/debug/
```

### Installing over the network

Enable developer options and network debugging on the TV, then:

```bash
adb connect <tv-ip>:5555                       # accept the prompt on the TV
adb install -r app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk
```

To read the playback log from a debug build:

```bash
adb shell run-as dev.atv.nasplayer cat files/playback-log.jsonl
```

## NAS setup tips

- **SMB:** SMB 3 is used when the server offers it, and reads are pipelined, so high-bitrate remuxes stream at link speed. Leave the share field blank to pick from all shares, or pin a share/subfolder such as `Media/Movies`.
- **WebDAV:** enter the full base URL (e.g. `http://nas.local:8080/media`). Self-signed HTTPS can be allowed per source.
- Credentials are stored in app-private storage and encrypted with the Android Keystore when the device's keystore is responsive.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).

Bundled components: AndroidX Media3 / ExoPlayer (Apache-2.0), Jellyfin media3-ffmpeg-decoder (GPL-3.0; FFmpeg built with GPL components), libVLC (LGPL-2.1+), smbj and smbj-rpc (Apache-2.0), Bouncy Castle (MIT), OkHttp (Apache-2.0). The banner uses the Inter typeface (SIL OFL 1.1).
