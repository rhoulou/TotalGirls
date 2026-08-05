# Chaturbate - CloudStream 3 plugin

A **CloudStream 3** addon for live Chaturbate cams (18+). It is a *bridge*
to the Chaturbate **Stremio addon server** (the Node project in
`Streamio/Chaturbate`): the plugin reuses the addon's manifest / catalog /
meta / stream endpoints, so all the fragile Chaturbate scraping (cookies,
pacing, Cloudflare, HLS) stays in the battle-tested Node client.

It registers three providers — **Chaturbate Girls** (f), **Chaturbate Guys**
(m) and **Chaturbate Trans** (t) — mirroring the original configure page.

> Note on current CloudStream 3: since v0.5.x the app only installs
> *compiled* plugins (`.cs3` files served from a repository); the old
> plain-JS addons are no longer supported. This project therefore follows the
> official Kotlin plugin format (same one used by
> `hexated/cloudstream-extensions`).

## 1. Deploy the addon server (once)

The Node addon must be reachable from your phone over **https** (Android
blocks plain `http://`):

```bash
cd Streamio/Chaturbate
npm start          # local test: http://localhost:3000/configure/
```

Deploy it to any Node host (Railway / Render / Fly.io — free tiers work) and
note the public URL, e.g. `https://chaturbate-addon.up.railway.app`.

## 2. Point the plugin at it

Edit **one line** in
`ChaturbateProvider/src/main/kotlin/com/example/chaturbate/ChaturbateProvider.kt`:

```kotlin
private const val ADDON_URL = "https://chaturbate-addon.up.railway.app"
```

## 3. Build the .cs3 plugin

You need **Android Studio** (or a JDK 11+ with the Android SDK and Gradle 7.1):

```bash
# from this folder
gradle wrapper        # first time only (creates gradlew)
./gradlew :ChaturbateProvider:cloudstreamBuild   # or assembleDebug
```

The build output is `ChaturbateProvider/build/outputs/.../ChaturbateProvider.cs3`.
(If the task name differs in your setup, run `./gradlew tasks` — the
Cloudstream gradle plugin publishes the plugin file on `assemble`/`publish`.)

## 4. Install in CloudStream 3

Easiest (no hosting): copy the `.cs3` file to your phone and open it with
CloudStream 3 ("Install from file"). Or serve it:

1. Put `ChaturbateProvider.cs3`, `plugins.json` and `repo.json` on any static
   host (GitHub Pages, Cloudflare Pages, Netlify...).
2. Fix the URLs in `plugins.json` (the `.cs3` URL) and `repo.json` (the
   `plugins.json` URL).
3. In CloudStream 3: **Settings → Extensions → Add repository** and paste the
   `repo.json` URL. The Chaturbate providers then appear under Extensions.

## Layout

```
repo.json                  CloudStream repository descriptor (manifestVersion 1)
plugins.json               Plugin list (one entry per .cs3 file)
build.gradle.kts           Root build script (Cloudstream gradle plugin)
settings.gradle.kts        Includes ChaturbateProvider
gradle.properties
gradle/wrapper/            Gradle wrapper properties
ChaturbateProvider/
  build.gradle.kts         Plugin metadata (version, description, icon...)
  src/main/AndroidManifest.xml
  src/main/kotlin/com/example/chaturbate/
    ChaturbateProvider.kt      MainAPI bridge -> the Stremio addon server
    ChaturbateProviderPlugin.kt   Registers Girls/Guys/Trans providers
```

## Notes

- 18+ content; not affiliated with or endorsed by Chaturbate.
- The addon server keeps a 90s roomlist snapshot, so catalog loads are fast
  and Chaturbate's rate limits are respected (the same behavior as the
  Stremio addon).
- Room items open with the live HLS stream; if a room is offline only the
  "Web / Chat Now" fallback is offered.
