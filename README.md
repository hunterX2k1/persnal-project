# Vault

A private Android app (18+) for browsing an auto-updating performer directory and finding
performers on your own list of websites. It stores no adult content itself — everything
opens from the original sites.

## Features

- **Age gate** on first launch.
- **Performer directory** from ThePornDB that updates itself in the background every 12 hours.
  Only adults are shown: anyone with a known birthday under 18 is always excluded.
- **Filters & sorting**: age range, active/retired, debut year, height, gender, ethnicity,
  nationality, hair, eyes, cup size, tattoos, piercings and your own tags. Sort by name, age,
  debut, career length, scene count or recently updated.
- **Profiles** with full stats, bio, scene list with previews, favourites and custom tags.
- **Find on my sites**: import your list of websites from an HTML file (e.g. a bookmarks export).
  On any profile, tap *Find on my sites*. Sites where the database confirms the performer appeared
  are marked ✓ at the top. Tapping a site opens it searching for that performer's name.
  You can give each site its own search link (`https://site.com/search?q={q}`).
- **Web search** with DuckDuckGo, Google or Bing in a private in-app browser
  (blocks pop-ups and jumps to other apps, full-screen video, clears data on close).
- **Native video player**: double-tap to skip 10 s, speed menu, picture-in-picture.
- **Discreet**:
  - neutral name and icon, switchable between *Vault*, *Notes* and *Files*
  - blank preview in Recent apps and screenshots blocked
  - optional blur on every image
  - optional PIN lock every time the app is reopened
  - quick-hide button
  - no notifications
  - excluded from cloud backup

## Setup

1. Install the APK (allow "Install unknown apps" for your browser or file manager).
2. Create a free account at theporndb.net. Copy your API token from your profile.
3. In the app, go to **Settings → ThePornDB API key**, paste the token and tap **Update now**.
4. Go to **My sites → Import HTML file** and pick your file.

## Building

- **GitHub**: every push runs *Build APK* in GitHub Actions. Download the APK from the run's
  **Artifacts** (`vault-apk`).
- **Locally**: `./gradlew :app:assembleRelease` (needs JDK 17+ and the Android SDK).
  The APK is in `app/build/outputs/apk/release/`.

Release builds are signed with a debug key so they install directly. Builds from different
machines use different keys, so uninstall before switching between a local build and a CI build.
