# Apps

This repo has two separate Android apps:

- **Atlas** (`atlas/`): an unofficial reader for the FMHY index of free sites and tools.
- **Vault** (`app/`): see below.

## Atlas

Reads FMHY's public Markdown pages (github.com/fmhy/edit) and shows them as a native app.
It hosts nothing itself.

- 25 pages, about 16,800 sites, organised into sections like the website.
  Shows 🌟 / ⭐ ratings, descriptions and mirror links.
- Global search across every page, filtering inside a page, a section list and
  "Recommended only" mode.
- FMHY links between pages open inside the app.
- Bookmarks and history.
- Works offline once loaded and refreshes daily.
- In-app browser built for video sites:
  - blocks ads, trackers and malware (StevenBlack hosts list)
  - blocks pop-ups and redirects (offers to open them anyway)
  - warns before opening sites FMHY lists as unsafe
  - full-screen landscape video with picture-in-picture
  - downloads to the Downloads folder
  - desktop mode
  - can turn off blocking per site
- Debug report (Settings → Diagnostics) for crashes and errors.

# Vault

A private Android app (18+) for browsing an auto-updating performer directory and finding
performers on your own list of websites. It stores no adult content itself — everything
opens from the original sites.

## Features

- **Age gate** on first launch.
- **Performer directory** from Wikidata (free, no account or key; about 13,000 profiles), plus ThePornDB
  if you add an optional key. Updates itself in the background every 12 hours.
  Only adults are shown: anyone with a known birthday under 18 is always excluded.
- **Filters & sorting**: age range, active/retired, debut year, height, gender, ethnicity,
  nationality, hair, eyes, cup size, tattoos, piercings and your own tags. Sort by name, age,
  debut, career length, scene count or recently updated.
- **More filters**: weight, bust, waist, hips, natural/enhanced, cup size, zodiac, birthplace,
  age at debut, years active and "has photo".
- **Profiles** with full stats, bio, favourites and custom tags.
- **Videos**: a performer's full video list (with a ThePornDB key), searchable, filterable by tag
  and sortable.
- **Video page** with preview, cast, tags and **Find on my sites**: it searches each of your sites
  and shows matching videos in one ranked list (thumbnail, title, duration, % match), like a tube
  aggregator. It detects each site's search link automatically, or you can set it yourself.
  Sites that block apps can still be opened by hand.
- **Find on my sites**: import your list of websites by uploading an HTML file (e.g. a bookmarks export) or pasting HTML, links or domains.
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
2. Open the app. It downloads the free profile database by itself (a few minutes on first run).
3. Optional: for more profiles, scene lists and ✓ site matches, create a free account at theporndb.net
   and paste your API token in **Settings → ThePornDB API key**.
4. Go to **My sites** and tap **Upload file** to pick your HTML file, or **Paste** to paste HTML, links or domains.

## Building

- **GitHub**: every push runs *Build APK* in GitHub Actions. Download the APK from the run's
  **Artifacts** (`vault-apk`).
- **Locally**: `./gradlew :app:assembleRelease` (needs JDK 17+ and the Android SDK).
  The APK is in `app/build/outputs/apk/release/`.

Release builds are signed with a debug key so they install directly. Builds from different
machines use different keys, so uninstall before switching between a local build and a CI build.
