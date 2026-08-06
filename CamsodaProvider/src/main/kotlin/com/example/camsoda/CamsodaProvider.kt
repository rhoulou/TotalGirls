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

/**
 * CloudStream 3 provider for CamSoda live cams (girls only).
 *
 * Uses the official camsoda.com API instead of the lemoncams.com aggregator.
 * camsoda.com API is Cloudflare-protected, so the JSON calls are fetched
 * through the user's own proxy (proxy.rhoulou.com), which replays the request
 * with a valid browser session:
 *
 *   * Room list -> PROXY(https://www.camsoda.com/api/v1/browse/online)
 *                  returns every online room as {tpl:{...}} tuples:
 *                  tpl[1] username, [2] display name, [3] status,
 *                  [4] viewers, [6] topic, [7] stream path, [8] gender,
 *                  [9] edge servers, [10] thumb, [13] HD flag.
 *   * Stream    -> PROXY(https://www.camsoda.com/api/v1/video/vtoken/<user>
 *                  ?username=guest_<n>) returns a fresh playback token; the
 *                  HLS master is then passed straight to the player from the
 *                  livemediahost edge (no proxy - that CDN is not protected).
 *   * Search    -> client-side substring match on username / display name /
 *                  topic of the official room list (real matches only).
 *
 * Home rows (the browse API has no category/tags, so rows are limited to):
 *   All Female, HD Female.
 *
 * Robustness: request pacing, HTTP 429 backoff, a short-lived room-list cache
 * (so a home load with several rows does not hammer the proxy), a shared
 * model cache for playback, and graceful handling of non-JSON responses.
 */
class CamsodaProvider : MainAPI() {
    override var mainUrl = "https://www.camsoda.com"
    override var name = "Camsoda Girls"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "female" to "All Female",
        "hd" to "HD Female",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // The browse API returns the whole online list in one call; rows are
        // client-side filters over that list and paginated locally.
        val filtered = when (request.data) {
            "hd" -> fetchAll().filter { it.isReal && it.isHd() && it.gender() == "f" }
            else -> fetchAll().filter { it.isReal && it.gender() == "f" }
        }
        val slice = filtered.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
        return newHomePageResponse(
            HomePageList(request.name, slice.map { it.toSearchResponse() }, isHorizontalImages = false),
            hasNext = (page * PAGE_SIZE) < filtered.size
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim().lowercase()
        val models = fetchAll()
        val matches = models.filter { m ->
            m.isReal && (
                m.username().lowercase().contains(q) ||
                m.displayName().lowercase().contains(q) ||
                m.topic()?.lowercase()?.contains(q) == true
                )
        }
        println("Camsoda search '$query' -> ${matches.size} results")
        return matches.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val username = url.trimEnd('/').substringAfterLast('/')
        if (username.isBlank()) return null
        val model = findModel(username)
        return newLiveStreamLoadResponse(model?.displayName()?.takeIf { it.isNotBlank() } ?: username, url, url) {
            posterUrl = model?.thumb()
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
        if (username.isBlank()) return false
        val token = fetchToken(username) ?: return false
        val edge = token.edgeServers?.firstOrNull { it.isNotBlank() } ?: return false
        val stream = token.streamName?.takeIf { it.isNotBlank() } ?: return false
        // Direct livemediahost HLS master - same URL pattern the site uses,
        // served to any client, so no proxy is needed for playback.
        val hls = buildString {
            append("https://").append(edge).append("/").append(stream)
            if (!stream.contains('.')) append("_v1/index.m3u8")
            token.token?.takeIf { it.isNotBlank() }?.let { append("?token=").append(it) }
        }
        println("Camsoda master $username -> ${hls.take(110)}")
        callback.invoke(
            newExtractorLink(
                source = "Camsoda",
                name = "Auto",
                url = hls,
                type = ExtractorLinkType.M3U8
            ) {
                referer = ""
                quality = Qualities.Unknown.value
                headers = mapOf("User-Agent" to USER_AGENT)
            }
        )
        return true
    }

    // ------------------------------------------------------- helpers

    private fun Model.toSearchResponse(): SearchResponse =
        newLiveSearchResponse(username(), roomUrl(username()), TvType.Live) {
            posterUrl = thumb()
            posterHeaders = mapOf("User-Agent" to USER_AGENT)
        }

    private fun roomUrl(username: String) = "$mainUrl/$username"

    /** Look a model up in the shared cache, else query the official list. */
    private suspend fun findModel(username: String): Model? {
        val wanted = username.lowercase()
        modelCache[wanted]?.let { return it }
        val model = fetchAll().firstOrNull { it.username().lowercase() == wanted }
        if (model != null) synchronized(modelLock) { modelCache[wanted] = model }
        return model
    }

    /** Whole online list, cached for a short time so rows don't re-fetch. */
    private suspend fun fetchAll(): List<Model> {
        val now = System.currentTimeMillis()
        cachedModels?.let { if (now - cachedAt < CACHE_TTL_MS) return it }
        val text = fetch(proxyUrl(BROWSE_URL))
        val models = parseJson<BrowseResponse>(text)?.results.orEmpty()
            .mapNotNull { it.tpl?.let(::Model) }
            .filter { it.isReal }
        synchronized(cacheLock) {
            cachedModels = models
            cachedAt = System.currentTimeMillis()
        }
        println("Camsoda browse -> ${models.size} models")
        return models
    }

    /** Fresh playback token for a room (one proxied call per stream start). */
    private suspend fun fetchToken(username: String): TokenResponse? {
        val target = "https://www.camsoda.com/api/v1/video/vtoken/$username" +
            "?username=guest_${(10_000..99_999).random()}"
        return parseJson<TokenResponse>(fetch(proxyUrl(target)))
    }

    private fun proxyUrl(target: String): String = PROXY + URLEncoder.encode(target, "utf8")

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
                        "Accept" to "application/json, text/plain, */*"
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
        // A non-JSON response means a challenge / error page - treat as empty
        // but log a snippet so it shows up in CloudStream's logs.
        if (text.isBlank() || text.startsWith("<!DOCTYPE", ignoreCase = true) ||
            text.startsWith("<html", ignoreCase = true)
        ) {
            println("Camsoda non-JSON response: ${text.take(500)}")
            return null
        }
        return tryParseJson<T>(text)
    }

    /** Minimum gap between proxy requests, shared across providers. */
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

    private data class BrowseResponse(
        @JsonProperty("count_total") val countTotal: Int? = null,
        @JsonProperty("results") val results: List<BrowseRoom>? = null
    )

    private data class BrowseRoom(
        @JsonProperty("tpl") val tpl: Map<String, Any?>? = null
    )

    private data class TokenResponse(
        @JsonProperty("edge_servers") val edgeServers: List<String>? = null,
        @JsonProperty("stream_name") val streamName: String? = null,
        @JsonProperty("token") val token: String? = null,
        @JsonProperty("status") val status: String? = null
    )

    /** Wrapper over the tpl tuple of a browse/online room. */
    private class Model(val tpl: Map<String, Any?>) {
        fun username(): String = (tpl["1"] as? String).orEmpty()
        fun displayName(): String = (tpl["2"] as? String) ?: username()
        fun gender(): String = (tpl["8"] as? String).orEmpty()
        fun viewers(): Int? = (tpl["4"] as? Number)?.toInt()
        fun topic(): String? = (tpl["6"] as? String)?.takeIf { it.isNotBlank() }
        fun isHd(): Boolean = (tpl["13"] as? Number)?.toInt() == 1

        val isReal: Boolean get() = username().isNotBlank() && username().lowercase() != "profile"

        /** Fix protocol-relative poster URLs (the API usually gives full ones). */
        fun thumb(): String? {
            val raw = (tpl["10"] as? String) ?: return null
            return if (raw.startsWith("//")) "https:$raw" else raw
        }

        fun plot(): String? = buildString {
            topic()?.let { append(it) }
            viewers()?.let {
                if (it > 0) {
                    if (isNotEmpty()) append("\n\n")
                    append("Watching: ").append(it)
                }
            }
        }.takeIf { it.isNotBlank() }

        fun tags(): List<String> = buildList {
            if (isHd()) add("HD")
        }
    }

    companion object {
        private const val PROXY = "https://proxy.rhoulou.com:7676/proxy.php?url="
        private const val BROWSE_URL = "https://www.camsoda.com/api/v1/browse/online"
        private const val PAGE_SIZE = 36 // rooms shown per row page

        private const val CACHE_TTL_MS = 30_000L // room-list cache lifetime
        private const val MIN_INTERVAL_MS = 350L // minimum gap between requests
        private const val RATE_LIMIT_PAUSE_MS = 2_500L // on HTTP 429
        private const val RETRY_PAUSE_MS = 800L // between failed attempts

        private const val USER_AGENT = ("Mozilla/5.0 (X11; Linux x86_64; rv:150.0) "
            + "Gecko/20100101 Firefox/150.0")

        private val modelLock = Any()
        private val cacheLock = Any()
        private val paceLock = Any()

        private val modelCache = HashMap<String, Model>()

        @Volatile private var cachedModels: List<Model>? = null
        @Volatile private var cachedAt = 0L
        @Volatile private var lastRequestAt = 0L
    }
}
