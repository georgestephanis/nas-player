# Architecture

A single-module Android app (`app/`), Kotlin + Jetpack Compose for TV. Package root: `dev.atv.nasplayer`.

```
MainActivity ──► HomeScreen ─► BrowserScreen ─► launchPlayback() ─► PlayerActivity
                  SettingsScreen, ServerEditScreen,                   ├─ ExoHost   (Media3 + FFmpeg)
                  PlaybackLogScreen                                   ├─ VlcHost   (libVLC)
                                                                      └─ ImageHost (Coil)
        MediaBrowser (Smb/WebDav/UsbBrowser)       ── lists folders
        NasDataSource (SmbDataSource / OkHttp)     ── streams to ExoPlayer
        RemoteFile                                 ── random access for thumbnails & photos
```

## Packages

| Package | Contents |
|---|---|
| `data/` | `ServerConfig` + `ServerRepository` (saved sources, JSON in SharedPreferences), `ServerSuggestions` (hosts, saved logins and known folders offered by the source form), `SecretBox` (Keystore password encryption with a timeout fallback), `ResumeStore` (positions, Continue watching), `Settings`. |
| `source/` | `MediaBrowser` interface with `SmbBrowser` (smbj, share enumeration over SRVSVC via smbj-rpc) `WebDavBrowser` (OkHttp `PROPFIND`) and `UsbBrowser` (`java.io.File` under `/storage/<uuid>`; `UsbDrives` lists mounted removable volumes as unsaved `ServerConfig`s). `SmbSessionPool` caches one authenticated session per server. `RemoteFile` gives block-cached ranged reads. `RemoteEntry`/`MediaTypes` classify files; `sortEntries` and `artworkFiles` live in `MediaBrowser.kt`. |
| `playback/` | `PlaybackRequest` (a folder queue held in memory for `PlayerActivity`, because a large folder won't fit in Intent extras), the three hosts, `EngineChooser` (which engine first, when to fall back), `ExoFactory` (extractor flags, buffers, FFmpeg renderers), `SmbDataSource` (pipelined SMB reads for Media3), `NasDataSource` (routes `smb://` / `dav://` URIs), `VlcMedia` (URLs for libVLC's own SMB/HTTP clients), `Subtitles` (sidecar matching), `PlaybackLog` + `PlaybackRecorder`. |
| `images/` | Coil fetchers: `ThumbnailFetcher` (frames via `MediaMetadataRetriever`, embedded art, folder/sibling art, disk cache in `cacheDir/thumbs`) and `FullImageFetcher` (photos). |
| `discovery/` | `NetworkDiscovery`: NSD browse for `_smb._tcp`. |
| `ui/` | Compose screens, theme, `Launch.kt` (builds the play queue and visible listing). |
| `com.hierynomus.smbj.share` | `SmbReadAhead.kt`: exposes smbj's package-private `File.readAsync` so reads can be pipelined. |

## Key decisions

- **Two engines.** ExoPlayer gives hardware decoding, HDR and passthrough; libVLC covers the long tail. `ExoHost` hands off to VLC on parse/decoder errors, on tracks ExoPlayer reports as unsupported, and when a video file has no recognised video track. The handoff keeps the playback position.
- **Media3 is pinned** to the line the Jellyfin FFmpeg decoder is built against (see `gradle/libs.versions.toml`). Bump both together.
- **URIs carry a server id, not credentials.** `smb://<serverId>/<share>/<path>` and `dav://<serverId>/<path>` are resolved against `ServerRepository` at open time. Only `VlcMedia` builds credentialed URLs, and only in memory for libVLC.
- **No main-thread I/O.** Keystore calls, SMB connects and listings all run on background dispatchers. On real TV hardware the keystore and network are far slower than on the emulator; both caused ANRs before this rule.
- **TV input.** Text entry uses a dialog per field so the on-screen keyboard can't trap D-pad focus. Focused buttons are never disabled, since that drops focus.
- **Thumbnails** are limited to two concurrent fetches and cached forever, keyed on path + size + mtime, with failures cached as empty files so they aren't retried while scrolling. Only files that finish without a thumbnail are cached this way: errors, timeouts and cancellations aren't, so they retry later.
- **Builds** use per-ABI APK splits; native libVLC and FFmpeg dominate the size.
