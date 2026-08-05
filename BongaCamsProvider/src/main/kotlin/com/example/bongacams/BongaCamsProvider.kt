package com.example.bongacams

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.net.URLEncoder

/**
 * CloudStream 3 provider for BongaCams live cams (girls only).
 *
 * Scrapes bongacams.com directly from the phone (no addon server), mirroring
 * the working bongacams.js / viewer.php scrapers:
 *
 *   * Room list -> GET /tools/listing_v3.php?livetab=female&limit=140&offset=
 *                  [&category=<slug>] (browser UA + Referer + X-Requested-With).
 *                  Each model already carries the thumb, viewers, country and
 *                  the `esid` used to build the live HLS master.
 *   * Stream    -> the master is built from the listing: 
 *                  https://<esid>-rn.bcvcdn.com/hls/stream_<username>/playlist.m3u8
 *                  (the bcvcdn edge serves it to any client, so it is passed
 *                  straight to the player, like the working scraper does).
 *   * Search    -> no listing search param exists, so we filter a cached
 *                  "all female" snapshot client-side (same as the JS viewer).
 *   * Metadata  -> poster / viewers / country from the cached listing (the
 *                  profile pages are Cloudflare-protected, so no dossier).
 *
 * Home rows mirror the viewer's category list: All Female + the 18 categories
 * (arab, asian, bbw, bdsm, big-tits, blonde, brunette, college-girls, ebony,
 * latina, mature, medium-tits, milf, shaved-pussy, small-tits, teens-18,
 * white-girls, young).
 *
 * Robustness: browser-like headers, request pacing and HTTP 429 backoff, a
 * shared model cache (every listing response feeds it, so any model shown on
 * the home page has its esid available for playback), plus graceful handling
 * of the Cloudflare challenge page (non-JSON responses become empty rows).
 */
class BongaCamsProvider : MainAPI() {
    override var mainUrl = "https://bongacams.com"
    override var name = "BongaCams Girls"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "all" to "All Female",
        "arab" to "Arab",
        "asian" to "Asian",
        "bbw" to "BBW",
        "bdsm" to "BDSM",
        "big-tits" to "Big Tits",
        "blonde" to "Blonde",
        "brunette" to "Brunette",
        "college-girls" to "College Girls",
        "ebony" to "Ebony",
        "latina" to "Latina",
        "mature" to "Mature",
        "medium-tits" to "Medium Tits",
        "milf" to "MILF",
        "shaved-pussy" to "Shaved Pussy",
        "small-tits" to "Small Tits",
        "teens-18" to "Teens 18+",
        "white-girls" to "White Girls",
        "young" to "Young",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page <= 1) {
            // Page 1 = one server-side filtered row per category.
            val rows = rowSpecs.mapNotNull { spec ->
                val items = fetchModels(spec.category, 0)
                if (items.isEmpty()) null
                else HomePageList(spec.name, items.map { it.toSearchResponse() }, isHorizontalImages = true)
            }
            return newHomePageResponse(rows)
        }
        // Next pages page each row independently via the listing offset.
        val spec = rowSpecs.firstOrNull { it.name == request.name } ?: return null
        val items = fetchModels(spec.category, (page - 1) * PAGE_SIZE)
        return newHomePageResponse(
            HomePageList(spec.name, items.map { it.toSearchResponse() }, isHorizontalImages = true),
            hasNext = items.size >= PAGE_SIZE
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.lowercase()
        return getAllFemale()
            .filter { it.username.lowercase().contains(q) || (it.displayName ?: "").lowercase().contains(q) }
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val username = url.trimEnd('/').substringAfterLast('/')
        val model = findModel(username)
        return newLiveStreamLoadResponse(model?.displayName?.takeIf { it.isNotBlank() } ?: username, url, url) {
            posterUrl = model?.thumbUrl()
            plot = model?.plot()
            tags = model?.tags()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val username = data.trimEnd('/').substringAfterLast('/')
        val model = findModel(username)
        val esid = model?.esid ?: return false
        // The bcvcdn edge serves the master to any client, so it can be passed
        // straight through (same as the working scraper's hlsUrl construction).
        val host = if (esid.endsWith("-rn")) esid else "$esid-rn"
        val master = "https://$host.bcvcdn.com/hls/stream_$username/playlist.m3u8"
        println("BongaCams master $username -> $master")
        callback.invoke(
            newExtractorLink(
                source = "BongaCams",
                name = "Auto",
                url = master,
                type = ExtractorLinkType.M3U8
            ) {
                referer = REFERER
                quality = Qualities.Unknown.value
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to REFERER)
            }
        )
        return true
    }

    // ------------------------------------------------------- helpers

    private data class RowSpec(val name: String, val category: String?)

    private val rowSpecs = listOf(
        RowSpec("All Female", null),
        RowSpec("Arab", "arab"),
        RowSpec("Asian", "asian"),
        RowSpec("BBW", "bbw"),
        RowSpec("BDSM", "bdsm"),
        RowSpec("Big Tits", "big-tits"),
        RowSpec("Blonde", "blonde"),
        RowSpec("Brunette", "brunette"),
        RowSpec("College Girls", "college-girls"),
        RowSpec("Ebony", "ebony"),
        RowSpec("Latina", "latina"),
        RowSpec("Mature", "mature"),
        RowSpec("Medium Tits", "medium-tits"),
        RowSpec("MILF", "milf"),
        RowSpec("Shaved Pussy", "shaved-pussy"),
        RowSpec("Small Tits", "small-tits"),
        RowSpec("Teens 18+", "teens-18"),
        RowSpec("White Girls", "white-girls"),
        RowSpec("Young", "young"),
    )

    private fun Model.toSearchResponse(): SearchResponse =
        newLiveSearchResponse(displayName?.takeIf { it.isNotBlank() } ?: username, roomUrl(username), TvType.Live) {
            posterUrl = thumbUrl()
        }

    private fun roomUrl(username: String) = "$BASE_URL/${username.lowercase()}"

    /** Look a model up in the shared cache, else refresh the all-female snapshot. */
    private suspend fun findModel(username: String): Model? {
        val wanted = username.lowercase()
        modelCache[wanted]?.let { return it }
        return getAllFemale().firstOrNull { it.username.lowercase() == wanted }
    }

    /** One listing page for a row (category) + offset. */
    private suspend fun fetchModels(category: String?, offset: Int): List<Model> {
        val url = "$LISTING_URL?livetab=female&offset=$offset&limit=$PAGE_SIZE" +
            (if (category.isNullOrBlank()) "" else "&category=${URLEncoder.encode(category, "utf8")}")
        val text = fetch(url)
        if (text.isBlank()) return emptyList()
        val models = parseJson<ListingResponse>(text)?.models
            ?: parseJson<List<Model>>(text).orEmpty() // some builds return a bare array
        if (models.isNotEmpty()) {
            synchronized(modelLock) {
                models.forEach { m -> modelCache[m.username.lowercase()] = m }
            }
        }
        return models
    }

    /** Cached "all female" snapshot (for search + playback esid lookups). */
    private suspend fun getAllFemale(): List<Model> {
        synchronized(snapshotLock) {
            if (snapshot.isNotEmpty() && System.currentTimeMillis() - snapshotAt < CACHE_TTL_MS) {
                return snapshot
            }
        }
        var doFetch = false
        val job = synchronized(snapshotLock) {
            val pending = inflight
            if (pending != null) {
                pending
            } else {
                doFetch = true
                CompletableDeferred<List<Model>>().also { inflight = it }
            }
        }
        if (doFetch) {
            val list = runCatching {
                val collected = mutableListOf<Model>()
                var emptyStreak = 0
                for (page in 0 until MAX_PAGES) {
                    val pageModels = fetchModels(null, page * PAGE_SIZE)
                    if (pageModels.isEmpty()) {
                        emptyStreak++
                        if (emptyStreak >= 2) break
                        continue
                    }
                    emptyStreak = 0
                    collected.addAll(pageModels)
                }
                collected
            }.getOrDefault(emptyList())
            synchronized(snapshotLock) {
                snapshot = list
                snapshotAt = System.currentTimeMillis()
                if (inflight === job) inflight = null
            }
            job.complete(list)
        }
        return job.await()
    }

    // ------------------------------------------------------- low level fetch

    private suspend fun fetch(url: String): String {
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            try {
                pace()
                val res = app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to REFERER,
                        "Accept" to "*/*",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                )
                if (res.isSuccessful) {
                    println("BongaCams GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code} (${res.text.length}B)")
                    return res.text
                }
                if (res.okhttpResponse.code == 429) { // rate limited -> longer pause
                    println("BongaCams GET ${res.okhttpResponse.request.url} -> 429 (rate limited)")
                    delay(RATE_LIMIT_PAUSE_MS)
                    continue
                }
                println("BongaCams GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code}")
                return ""
            } catch (e: Exception) {
                lastError = e // network hiccup -> retry
                delay(RETRY_PAUSE_MS * (attempt + 1))
            }
        }
        println("BongaCams request failed: $lastError")
        return ""
    }

    private inline fun <reified T : Any> parseJson(text: String): T? {
        // Cloudflare serves an HTML challenge page instead of JSON.
        if (text.isBlank() || text.startsWith("<!DOCTYPE", ignoreCase = true) ||
            text.startsWith("<html", ignoreCase = true)
        ) {
            return null
        }
        return tryParseJson<T>(text)
    }

    /** Minimum gap between bongacams.com requests, shared across providers. */
    private suspend fun pace() {
        val wait: Long
        synchronized(paceLock) {
            val now = System.currentTimeMillis()
            wait = (lastRequestAt + MIN_INTERVAL_MS - now).coerceAtLeast(0)
            lastRequestAt = now + wait
        }
        if (wait > 0) delay(wait)
    }

    // ------------------------------------------------------- JSON models

    private data class ListingResponse(
        @JsonProperty("models") val models: List<Model>? = null
    )

    private data class Model(
        @JsonProperty("username") val username: String = "",
        @JsonProperty("display_name") val displayName: String? = null,
        @JsonProperty("thumb_image") val thumbImage: String? = null,
        @JsonProperty("esid") val esid: String? = null,
        @JsonProperty("viewers") val viewers: Int? = null,
        @JsonProperty("country") val country: String? = null,
        @JsonProperty("vq") val vq: String? = null
    ) {
        // The listing sometimes embeds a "profile" ad entry - skip it.
        val isReal: Boolean get() = username.isNotBlank() && username.lowercase() != "profile"

        /** thumb_image uses a `{ext}` placeholder and may be protocol-relative. */
        fun thumbUrl(): String? {
            val raw = thumbImage ?: return null
            val withExt = raw.replace("{ext}", "webp")
            return if (withExt.startsWith("//")) "https:$withExt" else withExt
        }

        fun plot(): String? = buildString {
            viewers?.let { append("Watching: ").append(it) }
            if (!country.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Country: ").append(country.uppercase())
            }
        }.takeIf { it.isNotBlank() }

        fun tags(): List<String> = buildList {
            country?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
            if (vq?.contains("1920x1080") == true) add("HD")
        }
    }

    companion object {
        private const val BASE_URL = "https://bongacams.com"
        private const val LISTING_URL = "$BASE_URL/tools/listing_v3.php"
        private const val PAGE_SIZE = 140 // listing limit per request
        private const val MAX_PAGES = 5 // all-female snapshot pages (~700 models)
        private const val CACHE_TTL_MS = 90_000L // keep the snapshot this long

        private const val MIN_INTERVAL_MS = 350L // minimum gap between requests
        private const val RATE_LIMIT_PAUSE_MS = 2_500L // on HTTP 429
        private const val RETRY_PAUSE_MS = 800L // between failed attempts

        private const val REFERER = "https://bongacams.com/"
        private const val USER_AGENT = ("Mozilla/5.0 (X11; Linux x86_64; rv:150.0) "
            + "Gecko/20100101 Firefox/150.0")

        private val modelLock = Any()
        private val snapshotLock = Any()
        private val paceLock = Any()

        private val modelCache = HashMap<String, Model>()

        @Volatile private var snapshot: List<Model> = emptyList()
        @Volatile private var snapshotAt = 0L
        @Volatile private var inflight: CompletableDeferred<List<Model>>? = null
        @Volatile private var lastRequestAt = 0L
    }
}
