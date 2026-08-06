# Cam plugins - CloudStream 3

Live cam plugins (18+) for **CloudStream 3**: **Chaturbate**, **Stripchat**,
**Cam4**, **BongaCams** and **CamSoda** (girls only). They **scrape the sites
directly from the phone** — no addon server needed. The Kotlin ports mirror the
battle-tested client logic from the Node addons (`Streamio/Chaturbate`,
`Streamio/Stripchat`) and the `punpunsx/cloudstream-18plus-Extensions` Cam4
provider plus the working `bongacams.js` / `viewer.php` and `camsoda.js` /
`viewer.php` scrapers: roomlist snapshots, cookie jar, request pacing, HTTP 429
backoff and Cloudflare detection.

Each plugin provides the **Girls** category (female cams plus a Couples row on
the home page); the Guys and Trans categories are deliberately left out.

> Note on current CloudStream 3: since v0.5.x the app only installs
> *compiled* plugins (`.cs3` files served from a repository); the old
> plain-JS addons are no longer supported. This project therefore follows the
> official Kotlin plugin format (same one used by
> `hexated/cloudstream-extensions`).

## Install

Easiest (no hosting): copy the `.cs3` file to your phone and open it with
CloudStream 3 ("Install from file"). Or use the one-click page:

- https://rhoulou.github.io/TotalGirls/

Direct repo URL (works without the page):

- https://raw.githubusercontent.com/rhoulou/TotalGirls/main/repo.json

(The page deep-links into CloudStream 3 via the `cloudstreamrepo://` scheme.
The repo descriptors `repo.json` / `plugins.json` and the `.cs3` files are
served from `raw.githubusercontent.com` (git `main`), so updates go live on
every push.)

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

### Stripchat

- **Homepage**: categorized rows mirroring the addon catalogs — Popular, the 5
  regions, Couples Live, one row per flag genre (HD/New/VR/Mobile/Lovense/
  Kiiroo, filtered client-side) and one row per age genre (Teen/Young/MILF/
  Mature, filtered **server-side** via `filterGroupTags`). The roomlist API
  (`/api/front/models`) caps a page at 99 and filters by `primaryTag`
  server-side; snapshots are cached for 90s (gender + couples bases, plus one
  per age genre, fetched with a paced budget).
- **Session**: a guest `userHash` from the `initial-dynamic` config endpoint is
  required by the roomlist API (`userRole=guest`), cached for an hour.
- **Poster**: live snapshot thumbnail
  `https://img.doppiocdn.live/thumbs/<snapshotTimestamp>/<id>`.
- **Playback**: the model's `_auto.m3u8` master
  (`https://edge-hls.saawsedge.com/hls/<id>/master/<id>_auto.m3u8`) is passed
  straight to the player — it serves 200 to any client and its variants are
  plain HLS.

### Cam4

- **Homepage**: female-only category rows — New, Teen, MILF, Babe, Mature,
  Petite, Skinny, BBW, Asian, Black/Ebony, Latina/Hispanic and White (Couples,
  Male and Transgender tabs are skipped). Listing uses the GraphQL endpoint
  (`/graph?operation=getGenderPreferencePageData`) with the
  `apollographql-client-name: CAM4-client` header, filtered server-side by
  `gender` (female) plus a category `filters` slug per row, and paged with
  `cursor: { first: 200, offset }`. Each item already carries the live HLS
  master (`preview.src`) and poster (`profileImageURL`). Only the compound
  filter slugs the API honours are used (e.g. `petite-female-body`,
  `bbw-female-body`, `black`, `hispanic`).
- **Search**: the directory endpoint (`/api/directoryCams`) with a `search=`
  param (returns a bare JSON array).
- **Detail / playback**: single-user directory lookup
  (`/api/directoryCams?...&username=<user>`) for poster/viewers metadata and
  the `hlsPreviewUrl` master; master, variant and segments all serve 200 to any
  client, so the master is passed straight to the player.

### BongaCams

- **Homepage**: female-only category rows — All Female plus the 18 listing
  categories (Arab, Asian, BBW, BDSM, Big Tits, Blonde, Brunette, College
  Girls, Ebony, Latina, Mature, Medium Tits, MILF, Shaved Pussy, Small Tits,
  Teens 18+, White Girls, Young). Each row is a server-side `category=` filter
  of the listing endpoint `/tools/listing_v3.php?livetab=female&limit=140&
  offset=<n>` (browser UA + Referer + `X-Requested-With: XMLHttpRequest`),
  paged with `offset`.
- **Search**: no listing search param exists, so the cached "all female"
  snapshot (up to 5 pages, 90s cache) is filtered client-side by username /
  display name.
- **Detail / playback**: metadata (poster, viewers, country) comes from the
  cached listing (the profile pages are Cloudflare-protected, so no dossier).
  The live HLS master is built from the listing's `esid` the same way as the
  working scraper:
  `https://<esid>-rn.bcvcdn.com/hls/stream_<username>/playlist.m3u8` — the
  bcvcdn edge serves it to any client, so the master is passed straight to the
  player.
- **Cloudflare note**: `bongacams.com` challenges datacenter IPs. The listing
  endpoint works from a residential/mobile IP with browser headers; when the
  site answers with its challenge page the provider returns empty rows instead
  of crashing.

### Camsoda

- **Homepage**: female-only category rows — All Girls plus the 18 curated
  categories (New, Teen 18+, MILF, Mature, Petite, BBW, Asian, Ebony, Latina,
  White, Big Tits, Big Ass, Anal, Squirt, Dildo, Lovense, Top Rated, Pornstar).
  Each row is a direct server-side request to the site's own SPA endpoint
  `/api/v1/browse/react/<path>?p=<page>&gender-hide=m,t,c&perPage=98` (browser
  UA + Referer), where `<path>` mirrors the `/girls/<slug>` category URLs
  (verified against the `/girls/categories` index). `gender-hide=m,t,c` keeps
  it strictly female (male/trans/couples hidden).
- **Search**: server-side `/api/v1/browse/react/search/<query>?p=<page>&perPage=98`
  (same `userList` response), paged with a dedup break.
- **Detail / playback**: the profile pages are Cloudflare-protected, so the
  detail screen only carries the username (the tile already has the poster from
  the listing, preferring `offlinePictureUrl` over `thumbUrl`). The live master
  comes from the token endpoint `/api/v1/video/vtoken/<username>?username=guest_<n>`,
  which returns `edge_servers` / `app` / `stream_name` / `token`; per server the
  master is built the same way as the CamsodaRecorder / streamlink extractors:
  `https://<server>/<app>/mp4:<stream_name>_mjpeg/playlist.m3u8?token=<token>`
  and passed straight to the player. Private shows / offline models yield no
  links.
- **Cloudflare note**: like BongaCams, `camsoda.com` challenges datacenter IPs;
  the browse and vtoken endpoints work from a residential/mobile IP with browser
  headers, and the provider returns empty rows instead of crashing when the site
  answers with its challenge page.

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
settings.gradle.kts        Includes ChaturbateProvider + StripchatProvider + Cam4Provider + BongaCamsProvider + CamsodaProvider
.github/workflows/build.yml   Builds + commits the .cs3 on push
gradle.properties
gradle/wrapper/            Gradle wrapper properties
ChaturbateProvider/        Chaturbate plugin (.cs3)
  build.gradle.kts         Plugin metadata (version, description, icon...)
  src/main/kotlin/com/example/chaturbate/
    ChaturbateProvider.kt      Direct-scrape provider (roomlist + dossier)
    ChaturbateProviderPlugin.kt   Registers the Girls provider
StripchatProvider/         Stripchat plugin (.cs3)
  build.gradle.kts         Plugin metadata
  src/main/kotlin/com/example/stripchat/
    StripchatProvider.kt      Direct-scrape provider (guest hash + roomlist)
    StripchatProviderPlugin.kt   Registers the Girls provider
Cam4Provider/              Cam4 plugin (.cs3)
  build.gradle.kts         Plugin metadata
  src/main/kotlin/com/example/cam4/
    Cam4Provider.kt           Direct-scrape provider (GraphQL categories + directory)
    Cam4ProviderPlugin.kt     Registers the provider (female category rows)
BongaCamsProvider/         BongaCams plugin (.cs3)
  build.gradle.kts         Plugin metadata
  src/main/kotlin/com/example/bongacams/
    BongaCamsProvider.kt      Direct-scrape provider (listing_v3 categories + bcvcdn master)
    BongaCamsProviderPlugin.kt   Registers the provider (female category rows)
CamsodaProvider/           CamSoda plugin (.cs3)
  build.gradle.kts         Plugin metadata
  src/main/kotlin/com/example/camsoda/
    CamsodaProvider.kt        Direct-scrape provider (browse/react categories + vtoken master)
    CamsodaProviderPlugin.kt  Registers the provider (female category rows)
```

## Notes

- 18+ content; not affiliated with or endorsed by Chaturbate.
- Room items open with the live HLS stream. If a room is offline there is
  nothing to play (no "Web / Chat" fallback in CloudStream).
- Chaturbate occasionally serves a Cloudflare JS interstitial that Jsoup
  cannot parse — in that case lists come back empty and `vpnStatus` is set to
  "might need VPN".
