# Play Store release checklist

Status as of 2026-11-11. Items marked **Blocker** will get the app rejected or stop it shipping.

## Read first: timeline

- [ ] **Blocker — testing requirement for new personal accounts.** Personal developer accounts created
  after Nov 2023 must run a **closed test with at least 12 testers opted in for 14 days in a row**
  before they can apply for production. "In the next couple of days" can get the app into internal
  testing and a closed test, not into production. Check *Dashboard → Production access* in Play
  Console to see whether this applies to your account (organisation accounts are exempt).
- [ ] Start the closed test as soon as the first build is uploaded, because the 14 days don't begin
  until 12 testers have opted in. Testers need a Google account; they can be friends and family.

## Decisions to make before the first upload (permanent)

- [ ] **Package name.** `dev.atv.nasplayer` can never change once uploaded. The reverse-domain
  convention suggests you own `atv.dev`. Play doesn't check that, but if you'd rather use your own
  domain (e.g. `com.stephanis.nasplayer`), change it now.
- [ ] **App name.** "NAS Player" is generic. Search Play for clashes. The title limit is 30 characters.
- [x] **TV only.** `android.software.leanback` is required and the phone launcher entry is gone, so Play
  only offers the app to TVs. Add only the Android TV form factor in Play Console.

## Code changes

- [x] ~~**`MANAGE_EXTERNAL_STORAGE` (All files access).**~~ Replaced with `READ_MEDIA_VIDEO` / `_AUDIO` /
  `_IMAGES` (`READ_EXTERNAL_STORAGE` up to Android 12). Android 11+ serves media files by path through
  MediaStore, so `UsbBrowser` is unchanged. Tested on the API 34 emulator's removable volume: listing,
  thumbnails, playback and sidecar `.srt` all work. Still to test: a real USB drive on the Streamer, and
  sidecar subtitles on Android 11–12.
- [ ] **Blocker — signing.** Play rejects APKs and bundles signed with a debug key, and new apps must
  use Play App Signing:
  - Generate an upload key: `keytool -genkeypair -v -keystore ~/.android/nas-player-upload.jks
    -alias upload -keyalg RSA -keysize 4096 -validity 10000`. Back it up somewhere safe, off this
    machine. Losing it means asking Google to reset it.
  - Read its passwords from an ignored `keystore.properties` (already in `.gitignore`), never from
    `build.gradle.kts`.
  - Build an App Bundle with `./gradlew bundleRelease`. Play makes the per-ABI APKs itself; the
    `splits { abi }` block only affects sideload APKs.
- [ ] **Sideloaded vs Play installs.** The Play-installed app will be signed with Google's key, not
  `nas-player.keystore`, so it can't update over the sideloaded app on your TV, or the other way
  round. Either uninstall once when switching (this loses saved sources and resume positions), or give
  sideload builds `applicationIdSuffix = ".dev"` so both can be installed side by side.
- [ ] **versionCode / versionName.** Each upload needs a higher `versionCode` (currently 1). Pick a
  scheme now, e.g. `versionCode` = build number and `versionName` = `1.0.0`.
- [ ] **Target API.** `targetSdk = 36`. Play requires new apps to target a recent API level (the
  bar rises every August). Confirm in Play Console that 36 is accepted.
- [ ] **Licences in the app.** The app is GPL-3.0 and bundles a GPL FFmpeg build (Jellyfin's
  media3-ffmpeg-decoder) and LGPL libVLC. Add an About screen with the licence, a link to the source
  (the repo is public: github.com/georgestephanis/nas-player) and the third-party notices from the
  README.

## App content forms in Play Console (Policy → App content)

- [ ] **Privacy policy URL** (required for every app). Host a short page, e.g. GitHub Pages or a file
  in the repo, saying: no accounts and no analytics; the app connects only to servers the user adds;
  passwords are stored on the device, encrypted with the Android Keystore; nothing is sent to the
  developer.
- [ ] **Data safety form.** Data collected: none. Data shared: none. Server credentials and the
  playback log stay on the device and are never sent to you, so they don't count as "collected", but
  read Google's definitions to be sure.
- [ ] **App access.** Reviewers can't reach your home network, so the app looks empty without a server.
  Give them instructions plus a public test source: for example a WebDAV server on a small VPS with a
  few freely licensed clips (Blender open movies such as *Big Buck Bunny* or *Sintel*), with test
  credentials. Without this, expect "unable to review" rejections.
- [ ] **Content rating questionnaire.** Answer as a general media player that plays the user's own
  files, with no user-to-user sharing.
- [ ] **Target audience.** 13+ or 18+. Don't select children's age groups, which bring in the
  Families policy.
- [ ] **Ads:** none. Also complete the news, health, financial and government declarations as "no".

## Store listing

- [ ] Title (≤30 chars), short description (≤80), full description (≤4000). The README intro is a
  good start.
- [ ] App icon, 512×512 PNG (the in-app icon is a vector drawable; export it).
- [ ] Feature graphic, 1024×500.
- [ ] **TV banner, 1280×720** (the in-app banner is 640×360; re-export it at the larger size).
- [ ] **TV screenshots, 1920×1080**, at least one; aim for 4–6: home, a folder, the player bar,
  music with cover art, photos, settings. Use only freely licensed sample media and neutral folder
  and server names in every screenshot.
- [ ] Category: Video Players & Editors. Contact email: required, and shown publicly.

## Android TV specifics

- [ ] In *Test and release → Advanced settings → Form factors*, add **Android TV** and opt in. TV
  builds get an extra quality review that can take longer and is easy to fail.
- [ ] The TV quality checklist that matters here:
  - LEANBACK_LAUNCHER and banner ✓
  - no required touchscreen ✓
  - everything reachable by D-pad, with a visible focus everywhere
  - Back always works and exits from the home screen
  - no text cut off at 1080p or 4K
  - video plays fullscreen with no letterbox bugs
- [ ] Test the Play-signed build from the internal testing track on the Streamer before the closed
  test starts (see the signing note above about sideloaded installs).

## Release flow

1. [ ] Internal testing: upload the AAB and install it on your own TV through the Play Store.
2. [ ] Closed testing: 12+ testers, run for 14 days, and fix whatever they report.
3. [ ] Apply for production access, answering the questions about the test.
4. [ ] Production: a staged rollout (e.g. 20%) is optional for a new app, but harmless.
5. [ ] Afterwards, watch *Android vitals* for crashes and ANRs.
