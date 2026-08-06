package com.example.streamate

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import java.net.URLEncoder

/**
 * CloudStream 3 provider for Streamate live cams.
 *
 * Scrapes streamate.com (also the platform behind white-labels like am4.com)
 * directly from the phone. All API calls are GETs on the public /v4 guest
 * gateway; they only need a User-Agent, Referer and the fixed public gateway id
 * (X-GATEWAY = the "search.apigateway" constant published in the site config):
 *
 *   * Live-now feed  -> GET /v4/performers/guest?domain=streamate.com&from=&size=
 *                       &algo=loggedout&loggedOutRec=fallback. Paged via
 *                       from/size (offset/size), 50 per page.
 *   * Keyword rows   -> GET /v4/search/guest?...&query=<keyword>&gender=<codes>.
 *                       Free-text over model profiles; reliable keywords are
 *                       configured as home rows (milf, bbw, latina, ...).
 *   * Search         -> GET /v4/autocomplete/guest?filters=gender:<codes>
 *                       &performerCount=50&domain=streamate.com&query=<q>.
 *   * Stream         -> GET https://manifest-server.naiadsystems.com/live/s:<nickname>.json
 *                       (no auth). `formats["mp4-hls"].encodings[].location` is
 *                       the live HLS media playlist - the highest encoding is
 *                       handed straight to the player.
 *   * Poster         -> performers[].thumbnail (imagetransform.icfcdn.com/avatar).
 *
 * The streamate.com /v4 API ignores most filter params on the logged-out feed
 * (it is a female "live now" recommendation feed), so gender rows are not
 * exposed; genders only apply to search and the keyword rows.
 */
class StreamateProvider : MainAPI() {
    override var mainUrl = "https://streamate.com"
    override var name = "Streamate"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage: List<MainPageData>
        get() {
            val rows = mutableListOf<Pair<String, String>>("live" to "Live Now")
            Settings.ALL_ROWS.filter { (key, _) -> Settings.isRowEnabled(key) }
                .forEach { rows.add(it.first to it.second) }
            return mainPageOf(*rows.toTypedArray())
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page <= 1) {
            val rows = rowSpecs().mapNotNull { spec ->
                val items = fetchList(spec, 0)
                if (items.isEmpty()) null
                else HomePageList(spec.name, items.map { it.toSearchResponse() }, isHorizontalImages = true)
            }
            return newHomePageResponse(rows)
        }
        val spec = rowSpecs().firstOrNull { it.name == request.name } ?: return null
        val items = fetchList(spec, (page - 1) * PAGE_SIZE)
        return newHomePageResponse(
            HomePageList(spec.name, items.map { it.toSearchResponse() }, isHorizontalImages = true),
            hasNext = items.size >= PAGE_SIZE
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        // Name/autocomplete search on the guest gateway (gender filter applied).
        val filters = "gender:" + Settings.genders().joinToString(",")
        val url = "$BASE_URL/v4/autocomplete/guest" +
            "?filters=${URLEncoder.encode(filters, "utf8")}" +
            "&performerCount=$SEARCH_COUNT&domain=$DOMAIN&tagCount=0" +
            "&query=${URLEncoder.encode(query, "utf8")}"
        val data = fetchJson<PerformersResponse>(url)
        val items = data?.performers.orEmpty()
        // Always surface the queried name even when the API finds no near match.
        if (items.isEmpty()) {
            return listOf(newLiveSearchResponse(query, roomUrl(query), TvType.Live))
        }
        return items.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val nickname = url.trimEnd('/').substringAfterLast('/')
        if (nickname.isBlank()) return null
        return newLiveStreamLoadResponse(nickname, url, url) {
            posterUrl = avatarUrl(nickname)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val nickname = data.trimEnd('/').substringAfterLast('/')
        if (nickname.isBlank()) return false
        val manifest = fetchJson<ManifestResponse>("$MANIFEST_URL/live/s:${URLEncoder.encode(nickname, "utf8")}.json")
        val hls = manifest?.bestHlsLocation() ?: return false
        callback.invoke(
            newExtractorLink(
                source = "Streamate",
                name = "Auto",
                url = hls,
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

    private data class RowSpec(val name: String, val keyword: String?)

    private fun rowSpecs(): List<RowSpec> {
        val specs = mutableListOf(RowSpec("Live Now", null))
        Settings.ALL_ROWS.filter { (key, _) -> Settings.isRowEnabled(key) }
            .forEach { (key, name) -> specs.add(RowSpec(name, key)) }
        return specs
    }

    /** Fetch one page of a row: the live-now feed or a keyword search page. */
    private suspend fun fetchList(spec: RowSpec, from: Int): List<Performer> {
        val gender = Settings.genders().joinToString(",")
        val url = if (spec.keyword == null) {
            "$BASE_URL/v4/performers/guest?domain=$DOMAIN&from=$from&size=$PAGE_SIZE" +
                "&algo=loggedout&loggedOutRec=fallback"
        } else {
            "$BASE_URL/v4/search/guest?domain=$DOMAIN&from=$from&size=$PAGE_SIZE" +
                "&query=${URLEncoder.encode(spec.keyword, "utf8")}&gender=${URLEncoder.encode(gender, "utf8")}"
        }
        return fetchJson<PerformersResponse>(url)?.performers.orEmpty()
    }

    private fun Performer.toSearchResponse(): SearchResponse =
        newLiveSearchResponse(nickname, roomUrl(nickname), TvType.Live) {
            posterUrl = thumbnail?.takeIf { it.isNotBlank() } ?: avatarUrl(nickname)
        }

    private fun roomUrl(nickname: String) = "$BASE_URL/$nickname"

    private fun avatarUrl(nickname: String) = "$AVATAR_URL/avatar/$nickname.webp"

    // ------------------------------------------------------- low level fetch

    private suspend fun fetch(url: String): String {
        val target = wrap(url)
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            try {
                pace()
                val res = app.get(
                    target,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to REFERER,
                        "Accept" to "application/json, text/plain, */*",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "X-GATEWAY" to GATEWAY_ID
                    )
                )
                if (res.isSuccessful) {
                    println("Streamate GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code} (${res.text.length}B)")
                    return res.text
                }
                if (res.okhttpResponse.code == 429) { // rate limited -> longer pause
                    println("Streamate GET ${res.okhttpResponse.request.url} -> 429 (rate limited)")
                    delay(RATE_LIMIT_PAUSE_MS)
                    continue
                }
                println("Streamate GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code}")
                return ""
            } catch (e: Exception) {
                lastError = e // network hiccup -> retry
                delay(RETRY_PAUSE_MS * (attempt + 1))
            }
        }
        println("Streamate request failed: $lastError")
        return ""
    }

    /** Route a GET through the user's proxy when one is configured. */
    private fun wrap(url: String): String {
        val p = Settings.proxy()
        return if (p.isBlank()) url else p + URLEncoder.encode(url, "utf8")
    }

    private suspend inline fun <reified T : Any> fetchJson(url: String): T? {
        val text = fetch(url)
        if (text.isBlank() || text.startsWith("<!DOCTYPE", ignoreCase = true) ||
            text.startsWith("<html", ignoreCase = true)
        ) {
            return null
        }
        return tryParseJson<T>(text)
    }

    /** Minimum gap between streamate.com requests, shared across providers. */
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

    private data class PerformersResponse(
        @JsonProperty("performers") val performers: List<Performer>? = null
    )

    private data class Performer(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("nickname") val nickname: String = "",
        @JsonProperty("gender") val gender: String? = null,
        @JsonProperty("country") val country: String? = null,
        @JsonProperty("online") val online: Boolean? = null,
        @JsonProperty("new") val new: Boolean? = null,
        @JsonProperty("highDefinition") val highDefinition: Boolean? = null,
        @JsonProperty("rating") val rating: Int? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("headlineMessage") val headlineMessage: String? = null
    )

    private data class ManifestResponse(
        @JsonProperty("formats") val formats: Map<String, ManifestFormat>? = null
    ) {
        /** Highest-resolution HLS location (falls back to the lowest quality). */
        fun bestHlsLocation(): String? {
            val hls = formats?.get("mp4-hls") ?: return null
            val encodings = hls.encodings.orEmpty()
            if (encodings.isEmpty()) return null
            return encodings.maxByOrNull { it.videoWidth ?: 0 }?.location
        }
    }

    private data class ManifestFormat(
        @JsonProperty("encodings") val encodings: List<ManifestEncoding>? = null
    )

    private data class ManifestEncoding(
        @JsonProperty("videoWidth") val videoWidth: Int? = null,
        @JsonProperty("videoHeight") val videoHeight: Int? = null,
        @JsonProperty("location") val location: String? = null
    )

    companion object {
        private const val BASE_URL = "https://streamate.com"
        private const val MANIFEST_URL = "https://manifest-server.naiadsystems.com"
        private const val AVATAR_URL = "https://imagetransform.icfcdn.com"
        private const val DOMAIN = "streamate.com"
        // Public gateway id for the search service (published in the site config).
        private const val GATEWAY_ID = "da48cfde-b78e-4dca-bd12-d6660fcccf2d"

        private const val PAGE_SIZE = 50
        private const val SEARCH_COUNT = 50

        private const val MIN_INTERVAL_MS = 350L // minimum gap between requests
        private const val RATE_LIMIT_PAUSE_MS = 2_500L // on HTTP 429
        private const val RETRY_PAUSE_MS = 800L // between failed attempts

        private const val REFERER = "https://streamate.com/"
        private const val USER_AGENT = ("Mozilla/5.0 (X11; Linux x86_64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

        private val paceLock = Any()
        @Volatile private var lastRequestAt = 0L
    }
}
