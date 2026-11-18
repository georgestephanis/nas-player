# NAS Player privacy policy

*Last updated: 18 November 2026*

NAS Player (`me.stephanis.nasplayer`) is a media player for Android TV, made by George Stephanis. It plays video, music and photos from file servers and USB drives that you choose.

**The developer collects no data.** The app has no accounts, analytics, advertising, crash reporting or tracking, and it never sends anything to the developer or to any third party.

## What stays on your device

- **The sources you add:** server addresses, share names, usernames and passwords. Passwords are encrypted with a key held in the Android Keystore.
- **Where you left off** in each file, and which files you've watched.
- **Settings**, any per-source PINs (stored as a salted hash, not the PIN itself), cached thumbnails, and a playback log of recent files and their codecs, used for troubleshooting.

All of this stays in the app's private storage and is deleted when you clear the app's data or uninstall it.

## Network access

The app connects only to:
- the servers you add, using the details you entered;
- your local network, to discover file servers that advertise themselves (mDNS/Bonjour).

It makes no other internet connections.

## Device storage

When you open a USB drive, Android asks for permission to read media files. The app only reads photos, music, videos and subtitles to show and play them. It never changes, copies or uploads them.

## Children

The app isn't directed at children and collects no personal information from anyone.

## Changes and contact

Changes to this policy will be published at this address. Questions: open an issue at https://github.com/georgestephanis/nas-player/issues.
