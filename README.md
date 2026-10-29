# Apollo

Apollo is a personal assistant for an Android phone. It has three main parts:

- **The phone app** (`app/`, Kotlin + Jetpack Compose). It senses facts on the phone (notifications, calls, drives,
  sleep, app use, calendar, music), shows a home screen of cards, runs chat threads, and carries out actions. Anything
  that leaves the phone (a message, a call, a calendar write) waits for a tap; money and deletes need a fingerprint.
- **The server** (`server/`, Python + FastAPI). It stores chats, facts and mini apps, talks to outside services
  (weather, maps, traffic, music), and gives the AI brain its tools over MCP.
- **The AI brain** ("Athena", `server/apollo/athena.md` and `server/setup_brain.py`). A Claude agent that reads the
  facts, decides what to do, and drives the phone and the mini apps through the server. Apollo itself is a "dumb
  pipe": it senses and acts, the brain decides.

**Mini apps** (`server/seed/`) are small folders with a SQLite schema, notes and HTML pages: calendar, tasks, money,
people, trips, closet, fuel, sports, weather, music and more. The brain can create and change them, and the phone
shows their pages and widgets.

Other pieces: a vault for keys and private items (Calypso), encrypted backups (Mnemosyne), a server console with a
terminal and remote desktop (Lemnos), a sandbox box for the brain's tools (`server/box/`), and deploy scripts for a
small Linux VPS (`server/deploy/`).

## Layout

| Path | What it is |
|------|------------|
| `app/` | Android app (Kotlin), with unit tests in `app/src/test` |
| `server/apollo/` | FastAPI server, MCP tools, the brain's prompt |
| `server/seed/` | Mini app templates (schemas, notes, pages) |
| `server/tests/` | Server tests (pytest) |
| `server/deploy/` | Install and service scripts for the VPS |
| `docs/` | API reference, design notes and plans |

## Building the app: two SDKs you add yourself

The Android build reads two SDKs from local Maven folders under `app/libs/` (see `settings.gradle.kts`). They are not
in this copy, so Gradle stops with "Could not find ..." until you add them:

- **Google Home APIs** (`com.google.android.gms:play-services-home` and `play-services-home-types`, 17.1.0). Download
  the Android SDK from the Google Home Developers site (developers.home.google.com) and unzip its Maven folders into
  `app/libs/home-sdk/`, so you get `app/libs/home-sdk/com/google/android/gms/play-services-home/17.1.0/...`.
- **Spotify App Remote** (`com.spotify.android:app-remote`, 0.8.0). Download `spotify-app-remote-release-0.8.0.aar`
  from the Spotify Android SDK releases (github.com/spotify/android-sdk) and place it, with a small `.pom`, at
  `app/libs/spotify/com/spotify/android/app-remote/0.8.0/app-remote-0.8.0.aar`.

Gradle dependency verification is on (`gradle/verification-metadata.xml`), so the files must match the official
releases' sha256.

The weather mini app's Samsung scene artwork is also left out; see `server/seed/weather/README.md`.

## Status

This is a personal project, shared here as a portfolio piece. It was built for one person and one phone, so names,
places and hosts in this copy are placeholders (`example.com`, `YOUR_SERVER_IP`, `YOUR_SPOTIFY_CLIENT_ID`, and so
on). It is not meant to run as is without your own server, keys and Firebase project.
