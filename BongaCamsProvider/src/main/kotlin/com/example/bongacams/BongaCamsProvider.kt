package com.example.bongacams

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
 * CloudStream 3 provider for BongaCams live cams (girls only).
 *
 * Data comes from the lemoncams.com open API instead of bongacams.com directly:
 * bongacams.com/tools/listing_v3.php is Cloudflare-protected (cookie challenge)
 * and cannot be scraped from a phone without a browser, so we use the aggregator
 * that mirrors the bongacams room list and already exposes the playable stream:
 *
 *   * Room list -> GET https://api-v2-prod.lemoncams.com/main
 *                  ?page=<n>&provider=bongacams&function=cams&project=lemoncams
 *                  &gender=female[&category=<slug>|&haircolor=<slug>|&ishd=true
 *                  |&minage=<n>&maxage=<n>]  (browser UA + lemoncams Referer).
 *                  The response cams[] array carries the thumb, viewer count,
 *                  country, age, tags and the direct HLS master (embedUrl).
 *   * Stream    -> cam.embedUrl is the bcvcdn LL-HLS master the lemoncams player
 *                  itself uses (https://<edge>.bcvcdn.com/hls/stream_<user>/
 *                  playlist.m3u8) - served to any client, so it is passed
 *                  straight to the player. Private shows / offline models
 *                  either lack embedUrl or simply fail to play.
 *   * Search    -> the API supports ?query=<term>.
 *   * Metadata  -> poster / title / viewers / country / age / languages from
 *                  the cam object (no profile-page scraping).
 *
 * Home rows are all gender=female plus the aggregator's category / hair color /
 * HD / age filters: All Female, HD, Under 20, Twenties, Thirties, 40+, Asian,
 * Big Dick, Big Tits, BDSM, Ebony, Hairy, Latina, Mature, MILF, Small Tits,
 * Tattoo, Teen, Blonde, Brunette, Redhead.
 *
 * Robustness: browser-like headers, request pacing and HTTP 429 backoff, a
 * shared model cache (every listing response feeds it, so any model shown on
 * the home page has its embedUrl available for playback), plus graceful
 * handling of non-JSON responses (empty rows).
 */
class BongaCamsProvider : MainAPI() {
    override var mainUrl = "https://bongacams.com"
    override var name = "BongaCams Girls"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "" to "All Female",
        "ishd=true" to "HD",
        "minage=18&maxage=19" to "Under 20",
        "minage=20&maxage=30" to "Twenties",
        "minage=31&maxage=40" to "Thirties",
        "minage=41" to "40+",
        "category=asian" to "Asian",
        "category=bigdick" to "Big Dick",
        "category=bigtits" to "Big Tits",
        "category=bdsm" to "BDSM",
        "category=ebony" to "Ebony",
        "category=hairy" to "Hairy",
        "category=latina" to "Latina",
        "category=mature" to "Mature",
        "category=milf" to "MILF",
        "category=smalltits" to "Small Tits",
        "category=tattoo" to "Tattoo",
        "category=teen" to "Teen 18+",
        "haircolor=blonde" to "Blonde",
        "haircolor=brunette" to "Brunette",
        "haircolor=redhead" to "Redhead",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Each row is one server-side filtered request; the row's data string is
        // the extra filter query (category=..., haircolor=..., ...).
        val items = fetchModels(request.data, page)
        return newHomePageResponse(
            HomePageList(request.name, items.map { it.toSearchResponse() }, isHorizontalImages = true),
            hasNext = items.size >= PAGE_SIZE
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = buildUrl(page = 1, filters = "query=${URLEncoder.encode(query, "utf8")}")
        val text = fetch(url)
        if (text.isBlank()) return emptyList()
        val models = parseJson<Response>(text)?.cams.orEmpty().filter { it.isReal }
        cacheAll(models)
        return models.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val username = url.trimEnd('/').substringAfterLast('/')
        if (username.isBlank()) return null
        val model = findModel(username)
        return newLiveStreamLoadResponse(model?.username?.takeIf { it.isNotBlank() } ?: username, url, url) {
            posterUrl = model?.posterUrl()
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
        val model = findModel(username)
        val embed = model?.embedUrl?.takeIf { it.isNotBlank() } ?: return false
        // The bcvcdn edge serves the master to any client, so it can be passed
        // straight through (same URL the lemoncams player uses).
        println("BongaCams master $username -> $embed")
        callback.invoke(
            newExtractorLink(
                source = "BongaCams",
                name = "Auto",
                url = embed,
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
        newLiveSearchResponse(username, roomUrl(username), TvType.Live) {
            posterUrl = posterUrl()
        }

    private fun roomUrl(username: String) = "$mainUrl/${username.lowercase()}"

    /** Look a model up in the shared cache, else query the API for the username. */
    private suspend fun findModel(username: String): Model? {
        val wanted = username.lowercase()
        modelCache[wanted]?.let { return it }
        val url = buildUrl(page = 1, filters = "query=${URLEncoder.encode(wanted, "utf8")}")
        val text = fetch(url)
        val models = parseJson<Response>(text)?.cams.orEmpty()
        cacheAll(models)
        return models.firstOrNull { it.username.lowercase() == wanted }
    }

    /** One lemoncams page for a row: base call + gender=female + the row filters. */
    private suspend fun fetchModels(filters: String, page: Int): List<Model> {
        val url = buildUrl(page = page, filters = filters)
        val text = fetch(url)
        if (text.isBlank()) return emptyList()
        val models = parseJson<Response>(text)?.cams.orEmpty().filter { it.isReal }
        cacheAll(models)
        return models
    }

    private fun cacheAll(models: List<Model>) {
        if (models.isEmpty()) return
        synchronized(modelLock) {
            models.forEach { m -> modelCache[m.username.lowercase()] = m }
        }
    }

    private fun buildUrl(page: Int, filters: String): String = buildString {
        append(API_URL)
        append("?page=").append(page)
        append("&provider=").append(PROVIDER)
        append("&function=cams")
        append("&project=lemoncams")
        append("&gender=female")
        if (filters.isNotBlank()) append("&").append(filters)
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
                        "Origin" to ORIGIN,
                        "Accept" to "application/json, text/plain, */*",
                        "Accept-Language" to "en-US,en;q=0.9"
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
        // A non-JSON response means a challenge / error page - treat as empty.
        if (text.isBlank() || text.startsWith("<!DOCTYPE", ignoreCase = true) ||
            text.startsWith("<html", ignoreCase = true)
        ) {
            return null
        }
        return tryParseJson<T>(text)
    }

    /** Minimum gap between lemoncams API requests, shared across providers. */
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
        @JsonProperty("cams") val cams: List<Model>? = null,
        @JsonProperty("size") val size: Int? = null,
        @JsonProperty("maxPage") val maxPage: Int? = null
    )

    private data class Model(
        @JsonProperty("username") val username: String = "",
        @JsonProperty("numberOfUsers") val numberOfUsers: Int? = null,
        @JsonProperty("gender") val gender: String? = null,
        @JsonProperty("age") val age: Int? = null,
        @JsonProperty("isHd") val isHd: Boolean = false,
        @JsonProperty("isPrivate") val isPrivate: Boolean = false,
        @JsonProperty("imageUrl") val imageUrl: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("tags") val tags: List<String>? = null,
        @JsonProperty("country") val country: String? = null,
        @JsonProperty("languages") val languages: List<String>? = null,
        @JsonProperty("embedUrl") val embedUrl: String? = null
    ) {
        val isReal: Boolean get() = username.isNotBlank() && username.lowercase() != "profile"

        /** Fix protocol-relative poster URLs. */
        fun posterUrl(): String? {
            val raw = imageUrl ?: return null
            return if (raw.startsWith("//")) "https:$raw" else raw
        }

        /** Room subject + current viewer count, when present. */
        fun plot(): String? = buildString {
            title?.takeIf { it.isNotBlank() }?.let { append(it) }
            numberOfUsers?.let {
                if (it > 0) {
                    if (isNotEmpty()) append("\n\n")
                    append("Watching: ").append(it)
                }
            }
        }.takeIf { it.isNotBlank() }

        fun tags(): List<String> = buildList {
            country?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
            languages?.firstOrNull { it.isNotBlank() }?.let { add(it.uppercase()) }
            age?.let { if (it > 0) add("Age $it") }
            if (isHd) add("HD")
        }
    }

    companion object {
        private const val API_URL = "https://api-v2-prod.lemoncams.com/main"
        private const val PROVIDER = "bongacams"
        private const val PAGE_SIZE = 36 // cams per page returned by the API

        private const val MIN_INTERVAL_MS = 350L // minimum gap between requests
        private const val RATE_LIMIT_PAUSE_MS = 2_500L // on HTTP 429
        private const val RETRY_PAUSE_MS = 800L // between failed attempts

        private const val REFERER = "https://www.lemoncams.com/"
        private const val ORIGIN = "https://www.lemoncams.com"
        private const val USER_AGENT = ("Mozilla/5.0 (X11; Linux x86_64; rv:150.0) "
            + "Gecko/20100101 Firefox/150.0")

        private val modelLock = Any()
        private val paceLock = Any()

        private val modelCache = HashMap<String, Model>()

        @Volatile private var lastRequestAt = 0L
    }
}
