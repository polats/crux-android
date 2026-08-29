# Crux v2.0.0 - Release Notes

The project is now **Crux**. This release renames the application, replaces its icon and identity,
repoints all release and update infrastructure at this repository, and adds a build-version stamp
on the launcher icon for sideloaded builds.

## Read this before updating

- **This release does not upgrade an existing OC Remote install.** The application ID changed from
  `dev.minios.ocremote` to `casa.crux.app`, so Android treats Crux as a separate application. It
  installs alongside OC Remote rather than replacing it.
- **Settings, servers and passwords do not carry over automatically.** Preferences, saved servers,
  sync secrets, notification channel choices, drafts and pending prompts all live in the old
  application's private storage, which a new application ID cannot read.
- **To bring your setup across**, use settings sync before updating: export from OC Remote to a
  GitHub Gist, WebDAV target, or a file, then import it in Crux. Crux still reads the old
  `OCRemote.json` sync file, so an existing Gist or document works unchanged; new writes use
  `Crux.json`.
- Once you are satisfied Crux has everything, uninstall OC Remote to reclaim its storage.

## Highlights

- Renamed the application to Crux throughout, including all 15 localizations, and changed the
  application ID to `casa.crux.app`.
- Replaced the launcher icon with the Crux mark — the constellation Crux, the Southern Cross —
  and added a proper adaptive icon with background, foreground and monochrome layers, so themed
  icons and per-launcher masks now render correctly on Android 8 and later.
- Regenerated the launcher rasters at correct densities, fixing an hdpi icon that had shipped at
  49x49 instead of 72x72.
- Added `scripts/stamp-icon.py`, which burns the version and build number onto the launcher icons
  so a sideloaded build identifies itself from the home screen. It stamps the adaptive foreground
  as well as the legacy rasters, and restores the originals afterwards so a stamp is never
  committable.
- Added `scripts/device.py`, which stamps, builds, installs, launches and restores in one command.
- Repointed in-app update discovery, update verification, and the Termux local-server setup script
  at this repository. Update manifests and APKs are now resolved from
  `polats/crux-android`, and release APKs are named `crux-<version>.apk`.
- Regenerated the Termux setup script checksum, which the script verifies against before running.
- Recorded Crux's own trademark and branding policy, and its attribution as an independently
  maintained fork of OC Remote.

## Notes

- Because the application ID changed, the in-app updater treats this as a first install. Update
  verification compares an update's signing certificate against the installed app's, so future
  Crux releases must be signed with the same key as this one.
- No functional changes to chat, sessions, terminal, sync or connection handling are included in
  this release. Everything inherited from OC Remote 1.9.0 behaves as it did.

## Version

- `versionName`: `2.0.0`
- `versionCode`: `27`
