package com.example.camsoda

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import java.net.URLEncoder
import kotlin.random.Random

/**
 * CloudStream 3 provider for CamSoda live cams (girls only).
 *
 * Scrapes camsoda.com directly from the phone (no addon server), mirroring the
 * working camsoda.js / viewer.php scrapers and the site's own SPA calls:
 *
 *   * Home rows -> GET /api/v1/browse/react/<path>?p=<page>&gender-hide=m,t,c
 *                  &perPage=98 (browser UA + Referer). The site filters
 *                  server-side by URL path, so every row (girls, girls/new,
 *                  girls/asian, ...) is one direct request, like the SPA does.
 *                  gender-hide=m,t,c keeps it strictly female (the site's girls
 *                  tab itself also hides couples when the category is clicked).
 *   * Search    -> GET /api/v1/browse/react/search/<query>?p=<page>&perPage=98
 *                  (same userList response as the browse call).
 *   * Stream    -> GET /api/v1/video/vtoken/<username>?username=guest_<n>
 *                  returns edge_servers / app / stream_name / token, from which
 *                  the live HLS master is built per server:
 *                  https://<server>/<app>/mp4:<stream_name>_mjpeg/playlist.m3u8
 *                  ?token=<token> (same template as CamsodaRecorder / streamlink).
 *                  The m3u8 is served to any client, so it is passed straight
 *                  to the player. Private shows / offline models yield no links.
 *   * Metadata  -> poster from the listing (offlinePictureUrl is the preview
 *                  image) - the profile pages are Cloudflare-protected, so no
 *                  dossier scraping.
 *
 * Home rows: All Girls + the 18 curated categories mapped to the real
 * /girls/<slug> paths (anal, asian, bbw, big-ass, big-tits, dildo, ebony,
 * latina, lovense, mature, milf, new, petite, pornstar, squirt, teen-18,
 * top-rated, white).
 *
 * Robustness: browser-like headers, request pacing and HTTP 429 backoff, plus
 * graceful handling of the Cloudflare challenge page (non-JSON responses
 * become empty rows).
 */
class CamsodaProvider : MainAPI() {
    override var mainUrl = "https://www.camsoda.com"
    override var name = "Camsoda Girls"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "girls" to "All Girls",
        "girls/new" to "New",
        "girls/teen-18" to "Teen 18+",
        "girls/milf" to "MILF",
        "girls/mature" to "Mature",
        "girls/petite" to "Petite",
        "girls/bbw" to "BBW",
        "girls/asian" to "Asian",
        "girls/ebony" to "Ebony",
        "girls/latina" to "Latina",
        "girls/white" to "White",
        "girls/big-tits" to "Big Tits",
        "girls/big-ass" to "Big Ass",
        "girls/anal" to "Anal",
        "girls/squirt" to "Squirt",
        "girls/dildo" to "Dildo",
        "girls/lovense" to "Lovense",
        "girls/top-rated" to "Top Rated",
        "girls/pornstar" to "Pornstar",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Each row is a direct server-side filtered request, paged via `p`.
        val items = fetchModels(request.data, page)
        return newHomePageResponse(
            HomePageList(request.name, items.map { it.toSearchResponse() }, isHorizontalImages = true),
            hasNext = items.size >= PAGE_SIZE
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val results = mutableListOf<SearchResponse>()
        for (page in 0 until MAX_SEARCH_PAGES) {
            val items = fetchModels("search/${URLEncoder.encode(query, "utf8")}", page)
            if (items.isEmpty()) break
            val fresh = items.map { it.toSearchResponse() }
            if (results.containsAll(fresh)) break // paged past the end -> same page again
            results.addAll(fresh)
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val username = url.trimEnd('/').substringAfterLast('/')
        if (username.isBlank()) return null
        // No dossier scraping: the profile pages are Cloudflare-protected and
        // the tile already carries the poster from the listing.
        return newLiveStreamLoadResponse(username, url, url) {
            plot = "CamSoda live show."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val username = data.trimEnd('/').substringAfterLast('/')
        if (username.isBlank()) return false
        val vtoken = fetchVToken(username) ?: return false
        val streamName = vtoken.streamName
        val token = vtoken.token
        if (streamName.isBlank() || token.isBlank()) {
            println("Camsoda $username -> offline (no stream config)")
            return false
        }
        if (!vtoken.privateServers.orEmpty().isEmpty()) {
            println("Camsoda $username -> private show, no public HLS")
            return false
        }
        val servers = vtoken.edgeServers.orEmpty().ifEmpty {
            vtoken.mjpegServer?.let { listOf(it) }.orEmpty()
        }
        if (servers.isEmpty()) {
            println("Camsoda $username -> no edge servers")
            return false
        }
        var emitted = 0
        servers.forEachIndexed { i, server ->
            // Same template as the CamsodaRecorder / streamlink extractors:
            // the _mjpeg playlist is the public variant master.
            val master = "https://$server/${vtoken.app}/mp4:${streamName}_mjpeg/playlist.m3u8?token=$token"
            println("Camsoda master $username -> $master")
            callback.invoke(
                newExtractorLink(
                    source = "Camsoda",
                    name = "Server ${i + 1}",
                    url = master,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = REFERER
                    quality = Qualities.Unknown.value
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to REFERER)
                }
            )
            emitted++
        }
        return emitted > 0
    }

    // ------------------------------------------------------- helpers

    private fun Model.toSearchResponse(): SearchResponse =
        newLiveSearchResponse(displayName?.takeIf { it.isNotBlank() } ?: username, roomUrl(username), TvType.Live) {
            posterUrl = posterUrl()
        }

    private fun roomUrl(username: String) = "$BASE_URL/$username"

    /** One browse/search page. `path` is like "girls/new" or "search/<query>". */
    private suspend fun fetchModels(path: String, page: Int): List<Model> {
        val url = "$BROWSE_URL/$path?p=$page&gender-hide=$GENDER_HIDE&perPage=$PAGE_SIZE"
        val text = fetch(url)
        if (text.isBlank()) return emptyList()
        return parseJson<Response>(text)?.userList.orEmpty().filter { it.isReal }
    }

    private suspend fun fetchVToken(username: String): VToken? {
        val guest = Random.nextInt(10000, 99999)
        val url = "$BASE_URL/api/v1/video/vtoken/$username?username=guest_$guest"
        val text = fetch(url)
        if (text.isBlank()) return null
        return parseJson<VToken>(text)
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
                        "Accept-Language" to "en-US,en;q=0.9"
                    )
                )
                if (res.isSuccessful) {
                    println("Camsoda GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code} (${res.text.length}B)")
                    return res.text
                }
                if (res.okhttpResponse.code == 429) { // rate limited -> longer pause
                    println("Camsoda GET ${res.okhttpResponse.request.url} -> 429 (rate limited)")
                    delay(RATE_LIMIT_PAUSE_MS)
                    continue
                }
                println("Camsoda GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code}")
                return ""
            } catch (e: Exception) {
                lastError = e // network hiccup -> retry
                delay(RETRY_PAUSE_MS * (attempt + 1))
            }
        }
        println("Camsoda request failed: $lastError")
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

    /** Minimum gap between camsoda.com requests, shared across providers. */
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

    private data class Response(
        @JsonProperty("userList") val userList: List<Model>? = null
    )

    private data class Model(
        @JsonProperty("username") val username: String = "",
        @JsonProperty("displayName") val displayName: String? = null,
        @JsonProperty("thumbUrl") val thumbUrl: String? = null,
        @JsonProperty("offlinePictureUrl") val offlinePictureUrl: String? = null,
        @JsonProperty("status") val status: String? = null
    ) {
        // The listing can embed ad / profile entries - skip them.
        val isReal: Boolean get() = username.isNotBlank() && username.lowercase() != "profile"

        /** Prefer the preview image, fall back to the thumb; fix protocol-relative URLs. */
        fun posterUrl(): String? {
            val raw = offlinePictureUrl?.takeIf { it.isNotBlank() } ?: thumbUrl
            return if (raw.isNullOrBlank()) null
            else if (raw.startsWith("//")) "https:$raw" else raw
        }
    }

    private data class VToken(
        @JsonProperty("edge_servers") val edgeServers: List<String>? = null,
        @JsonProperty("private_servers") val privateServers: List<String>? = null,
        @JsonProperty("mjpeg_server") val mjpegServer: String? = null,
        @JsonProperty("app") val app: String = "",
        @JsonProperty("stream_name") val streamName: String = "",
        @JsonProperty("token") val token: String = ""
    )

    companion object {
        private const val BASE_URL = "https://www.camsoda.com"
        private const val BROWSE_URL = "$BASE_URL/api/v1/browse/react"
        private const val GENDER_HIDE = "m,t,c" // strictly female (hide male/trans/couples)
        private const val PAGE_SIZE = 98 // listing perPage used by the site
        private const val MAX_SEARCH_PAGES = 4

        private const val MIN_INTERVAL_MS = 350L // minimum gap between requests
        private const val RATE_LIMIT_PAUSE_MS = 2_500L // on HTTP 429
        private const val RETRY_PAUSE_MS = 800L // between failed attempts

        private const val REFERER = "https://www.camsoda.com/"
        private const val USER_AGENT = ("Mozilla/5.0 (X11; Linux x86_64; rv:150.0) "
            + "Gecko/20100101 Firefox/150.0")

        private val paceLock = Any()

        @Volatile private var lastRequestAt = 0L
    }
}
