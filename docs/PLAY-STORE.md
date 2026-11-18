# Play Store release checklist

Status as of 2026-11-18. Items marked **Blocker** will get the app rejected or stop it shipping.

## Where things stand

- **Internal testing:** release `1.0 (1)` is live for the internal email list.
- **Closed testing (Alpha):** release `1.0 (2)` (versionCode 2) went to Google for review on 2026-11-18,
  along with the store listing and every App content declaration. Review usually takes up to 7 days.
  - Testers: anyone in the Google Group
    [nasplayer-testers@googlegroups.com](https://groups.google.com/g/nasplayer-testers), which anyone
    can join. Members then opt in at https://play.google.com/apps/testing/me.stephanis.nasplayer.
  - Feedback goes to GitHub issues.
  - Available in all 178 countries and regions.
- **GitHub:** the same code is release [v1.0.1](https://github.com/georgestephanis/nas-player/releases/tag/v1.0.1).
  The Play build says "1.0" because it was built before `versionName` was bumped. The next upload
  will match.
- **Next:**
  1. Once the review passes, recruit testers by posting on Reddit and similar places.
  2. Get 12 testers opted in, then wait 14 days.
  3. Apply for production access.

## Read first: timeline

- [ ] **Blocker — testing requirement for new personal accounts.** Personal developer accounts created
  after Nov 2023 must run a **closed test with at least 12 testers opted in for 14 days in a row**
  before they can apply for production. Production and open testing stay locked until then; check
  *Dashboard → Production access*.
- [ ] Start the closed test as soon as possible, because the 14 days don't begin until 12 testers have
  opted in. Testers need a Google account; they can be friends and family. *(In review; testers
  can't install it until the review passes.)*

## Decisions to make before the first upload (permanent)

- [x] **Package name.** `me.stephanis.nasplayer` for the Play build (see below).
- [x] **App name.** "NAS Player".
- [x] **TV only.** `android.software.leanback` is required and the phone launcher entry is gone, so Play
  only offers the app to TVs. Only the Android TV form factor is added in Play Console.

## Code changes

- [x] ~~**`MANAGE_EXTERNAL_STORAGE` (All files access).**~~ Replaced with `READ_MEDIA_VIDEO` / `_AUDIO` /
  `_IMAGES` (`READ_EXTERNAL_STORAGE` up to Android 12). Android 11+ serves media files by path through
  MediaStore, so `UsbBrowser` is unchanged. Tested on the API 34 emulator's removable volume: listing,
  thumbnails, playback and sidecar `.srt` all work. Still to test: a real USB drive on the Streamer, and
  sidecar subtitles on Android 11–12.
- [x] ~~**Trust-all TLS for WebDAV.**~~ "Accept self-signed HTTPS certificates" used to accept any
  certificate, which Play's security scan flags. It now pins the first certificate the server presents
  and refuses any other.
- [x] **Signing.** Play rejects APKs and bundles signed with a debug key, and new apps must
  use Play App Signing:
  - The upload key is `~/.android/nas-player-upload.jks` (alias `upload`), made with
    `keytool -genkeypair -v -keystore ~/.android/nas-player-upload.jks -alias upload -keyalg RSA
    -keysize 4096 -validity 10000`. It and its password are backed up in 1Password. Losing it means
    asking Google to reset it.
  - Its password is in the macOS Keychain as `nas-player-upload`
    (`security add-generic-password -a "$USER" -s nas-player-upload -w` adds it). Build the App
    Bundle with `./gradlew bundlePlay`.
    The output is `app/build/outputs/bundle/play/app-play.aab`. Play makes the per-ABI APKs itself;
    the `splits { abi }` block only affects sideload APKs.
- [x] **Sideloaded vs Play installs.** The Play build's app ID is `me.stephanis.nasplayer` (permanent
  once uploaded); sideload builds stay `dev.atv.nasplayer`. They are separate apps, so both can be
  installed on one TV, and sideloaded installs keep their sources and resume positions.
- [x] **versionCode / versionName.** `versionCode` goes up by one for every Play upload. `versionName`
  is semantic (`1.0.1`) and matches the GitHub tag. Currently 3 / 1.0.2.
- [x] **Target API.** `targetSdk = 36` was accepted.
- [x] **Landscape.** `screenOrientation="landscape"` implies a required
  `android.hardware.screen.landscape`, which Play's pre-review check flags because it excludes TVs. The
  manifest declares that feature as `required="false"`.
- [x] **Licences in the app.** The app is GPL-3.0 and bundles a GPL FFmpeg build (Jellyfin's
  media3-ffmpeg-decoder) and LGPL libVLC. *Settings → About & licences* shows the licence, the source
  link, the third-party list and the full licence texts (`res/raw/licenses.txt`). Update that file
  when a dependency is added.

## App content forms in Play Console (Policy → App content)

All of these are complete; App content shows nothing needing attention.

- [x] **Privacy policy URL:** `PRIVACY.md` in the repo.
- [x] **Data safety form.** No data collected or shared.
- [x] **App access.** Reviewers sign in to the public demo server, `https://nasplayer-demo.stephanis.me`
  (read-only WebDAV of generated media; see [TESTING.md](TESTING.md)). If its login changes, update
  *Sign in details* too.
- [x] **Photo and video permissions declaration.** Explains that the app browses USB drives by folder,
  which the photo picker can't do, and that the picker isn't available on Android TV.
- [x] **Content rating questionnaire.**
- [x] **Target audience.** Adults only; no children's age groups.
- [x] **Ads** (none), and the news, health, financial and government declarations.

## Store listing

- [x] Title, short description and full description.
- [x] App icon (512×512), feature graphic (1024×500) and TV banner (1280×720).
- [x] **TV screenshots, 1920×1080**, from `docs/screenshots/`.
- [x] Category and contact details.

## Android TV specifics

- [x] Android TV form factor added under *Test and release → Advanced settings → Form factors*. TV
  builds get an extra quality review that can take longer and is easy to fail.
- [x] The TV quality checklist, walked through on the emulator for 1.0.2:
  - LEANBACK_LAUNCHER and banner ✓
  - no required touchscreen ✓
  - landscape not required ✓
  - everything reachable by D-pad, with a visible focus everywhere ✓ (the text-field dialogs used to
    trap focus once the keyboard closed; fixed)
  - Back always works and exits from the home screen ✓
  - no text cut off at 1080p ✓ (layout is in dp, so 4K looks the same)
  - video plays fullscreen with no letterbox bugs ✓
- [ ] Test the Play-signed build from the internal testing track on the Streamer (see the signing note
  above about sideloaded installs).

## Release flow

1. [x] Internal testing: release `1.0 (1)` is live for the internal email list.
2. [ ] Closed testing: 12+ testers for 14 days, fixing whatever they report. *(In review since
   2026-11-18.)*
3. [ ] Apply for production access, answering the questions about the test.
4. [ ] Production: a staged rollout (e.g. 20%) is optional for a new app, but harmless.
5. [ ] Afterwards, watch *Android vitals* for crashes and ANRs.

## Cutting a release

1. Raise `versionCode` (by one) and `versionName` in `app/build.gradle.kts`, merge to `main`.
2. `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew bundlePlay assemblePlay` (plain `./gradlew` can
   fail with "Unable to locate a Java Runtime"). Upload `app/build/outputs/bundle/play/app-play.aab`
   to Play. In a saved release you can't swap the bundle: discard the release and create a new one.
3. Tag and publish the same commit on GitHub with the APKs (all signed with the upload key):
   ```bash
   v=1.0.1; d=$(mktemp -d)
   for a in arm64-v8a armeabi-v7a x86_64 universal; do
     cp app/build/outputs/apk/play/app-$a-play.apk "$d/NAS-Player-$v-$a.apk"; done
   gh release create v$v "$d"/*.apk --target main --title "NAS Player $v" --notes-file notes.md
   ```
