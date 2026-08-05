# Chaturbate - CloudStream 3 plugin

A **CloudStream 3** plugin for live Chaturbate cams (18+). It **scrapes
chaturbate.com directly from the phone** — no addon server needed. The Kotlin
port mirrors the battle-tested client logic from the Node/Kodi addons
(`Streamio/Chaturbate` / `Kodi/Chaturbate`): roomlist snapshot, cookie jar,
request pacing, HTTP 429 backoff, Cloudflare detection, and the
`window.initialRoomDossier` double-decode.

It registers three providers — **Chaturbate Girls** (f), **Chaturbate Guys**
(m) and **Chaturbate Trans** (t) — mirroring the original configure page.

> Note on current CloudStream 3: since v0.5.x the app only installs
> *compiled* plugins (`.cs3` files served from a repository); the old
> plain-JS addons are no longer supported. This project therefore follows the
> official Kotlin plugin format (same one used by
> `hexated/cloudstream-extensions`).

## Install

Easiest (no hosting): copy `ChaturbateProvider.cs3` to your phone and open it
with CloudStream 3 ("Install from file"). Or use the one-click page:

- https://rhoulou.github.io/cloudstream/

(The page deep-links into CloudStream 3 via the `cloudstreamrepo://` scheme.
The repo descriptors `repo.json` / `plugins.json` are served from GitHub
Pages.)

## How it works

- **Homepage**: one list per provider, paged. The roomlist API
  (`/api/ts/roomlist/room-list/`) caps a page at 100 rooms and ignores the
  `gender` param, so a 20-page snapshot (~2000 rooms, 90s cache) is filtered
  client-side by gender code (`f`/`m`/`s`). The first load takes a few
  seconds; the following pages are instant from cache.
- **Search**: client-side username substring match over the snapshot.
- **Detail**: room meta (poster, room subject, viewer count, tags) from the
  snapshot; falls back to the room page's og meta tags.
- **Playback**: the room page's `window.initialRoomDossier` is double-decoded
  and its `hls_source` (a signed LL-HLS master playlist) is passed straight to
  the player, which resolves the `?session=` variant chunklists.
- **Robustness**: browser-like User-Agent, persistent cookie jar, ≥350ms
  pacing between requests, 3 retries with 2.5s backoff on HTTP 429, and empty
  lists instead of errors when Chaturbate answers with a Cloudflare JS
  interstitial page.

## Build the .cs3 plugin

You need **Android Studio** (or a JDK 11+ with the Android SDK and Gradle):

```bash
# from this folder
gradle wrapper        # first time only (creates gradlew)
./gradlew :ChaturbateProvider:cloudstreamBuild   # or assembleDebug
```

The build output is
`ChaturbateProvider/build/outputs/.../ChaturbateProvider.cs3`. (If the task
name differs in your setup, run `./gradlew tasks`.)

The repository ships a GitHub Actions workflow (`.github/workflows/build.yml`)
that builds the plugin and commits the resulting `.cs3` back to the repo, so
GitHub Pages always serves the latest build.

## Layout

```
repo.json                  CloudStream repository descriptor (manifestVersion 1)
plugins.json               Plugin list (one entry per .cs3 file)
build.gradle.kts           Root build script (Cloudstream gradle plugin)
settings.gradle.kts        Includes ChaturbateProvider
.github/workflows/build.yml   Builds + commits the .cs3 on push
gradle.properties
gradle/wrapper/            Gradle wrapper properties
ChaturbateProvider/
  build.gradle.kts         Plugin metadata (version, description, icon...)
  src/main/AndroidManifest.xml
  src/main/kotlin/com/example/chaturbate/
    ChaturbateProvider.kt      Direct-scrape provider (roomlist + dossier)
    ChaturbateProviderPlugin.kt   Registers Girls/Guys/Trans providers
```

## Notes

- 18+ content; not affiliated with or endorsed by Chaturbate.
- Room items open with the live HLS stream. If a room is offline there is
  nothing to play (no "Web / Chat" fallback in CloudStream).
- Chaturbate occasionally serves a Cloudflare JS interstitial that Jsoup
  cannot parse — in that case lists come back empty and `vpnStatus` is set to
  "might need VPN".
