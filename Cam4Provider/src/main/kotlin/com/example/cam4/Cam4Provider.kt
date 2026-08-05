package com.example.cam4

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
 * CloudStream 3 provider for Cam4 live cams.
 *
 * Scrapes cam4.com directly from the phone (no addon server), mirroring the
 * logic in punpunsx/cloudstream-18plus-Extensions:
 *
 *   * Room list -> https://www.cam4.com/api/directoryCams?directoryJson=true&
 *                  online=true&url=true&orderBy=VIDEO_QUALITY&resultsPerPage=60
 *                  plus an optional `gender`/`broadcastType` filter per row and
 *                  `page=N` for pagination.
 *   * Search    -> same endpoint with `search=<query>` (returns a JSON array).
 *   * Poster    -> `snapshotImageLink` (https://snapshots.xcdnpro.com/...).
 *   * Metadata  -> https://www.cam4.com/rest/v1.0/profile/<user>/info
 *                  (name, profileImageUrl/avatarUrl, bio). The room pages are a
 *                  JS-rendered SPA without og: meta tags, so scraping the HTML
 *                  (as the original did) yields a blank load screen.
 *   * Stream    -> https://www.cam4.com/rest/v1.0/profile/<user>/streamInfo
 *                  yields `cdnURL`, a master playlist on cam4-hls.xcdnpro.com.
 *                  Master, chunklist and segments all serve 200 to any client,
 *                  so the master is passed straight to the player.
 */
class Cam4Provider : MainAPI() {
    override var mainUrl = "https://www.cam4.com"
    override var name = "Cam4"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${DIRECTORY_URL}${QUERY_ALL}" to "All",
        "${DIRECTORY_URL}${QUERY_FEMALE}" to "Female",
        "${DIRECTORY_URL}${QUERY_COUPLES}" to "Couples",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "$mainUrl${request.data}&page=$page"
        val users = fetchJson<DirectoryResponse>(url)?.users.orEmpty()
        if (users.isEmpty()) return null
        return newHomePageResponse(
            HomePageList(
                request.name,
                users.map { it.toSearchResponse() },
                isHorizontalImages = true
            ),
            hasNext = users.size >= PAGE_SIZE
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$BASE_URL/api/directoryCams?directoryJson=true&online=true" +
            "&search=${URLEncoder.encode(query, "utf8")}&resultsPerPage=$PAGE_SIZE"
        // The search endpoint returns a bare JSON array of users.
        val users = fetchJson<List<User>>(url).orEmpty()
        return users.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val username = url.trimEnd('/').substringAfterLast('/')
        if (username.isBlank()) return null
        val info = fetchJson<PerformerInfo>(profileInfoUrl(username))
        return newLiveStreamLoadResponse(username, url, url) {
            posterUrl = info?.posterUrl()
            plot = info?.plot()
            tags = info?.tags()
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
        val streamInfo = fetchJson<StreamInfo>(streamInfoUrl(username))
        val master = streamInfo?.cdnURL
        if (master.isNullOrBlank()) return false
        // The xcdnpro master serves 200 to any client and ExoPlayer resolves
        // its relative chunklist, so it can be passed straight through.
        callback.invoke(
            newExtractorLink(
                source = "Cam4",
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

    private fun User.toSearchResponse(): SearchResponse =
        newLiveSearchResponse(username, roomUrl(username), TvType.Live) {
            posterUrl = snapshotImageLink
        }

    private fun roomUrl(username: String) = "$BASE_URL/$username"

    private fun profileInfoUrl(username: String) = "$BASE_URL/rest/v1.0/profile/$username/info"

    private fun streamInfoUrl(username: String) = "$BASE_URL/rest/v1.0/profile/$username/streamInfo"

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
                        "Accept" to "application/json, text/plain, */*",
                        "Accept-Language" to "en-US,en;q=0.9"
                    )
                )
                if (res.isSuccessful) {
                    println("Cam4 GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code} (${res.text.length}B)")
                    return res.text
                }
                if (res.okhttpResponse.code == 429) { // rate limited -> longer pause
                    println("Cam4 GET ${res.okhttpResponse.request.url} -> 429 (rate limited)")
                    delay(RATE_LIMIT_PAUSE_MS)
                    continue
                }
                println("Cam4 GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code}")
                return ""
            } catch (e: Exception) {
                lastError = e // network hiccup -> retry
                delay(RETRY_PAUSE_MS * (attempt + 1))
            }
        }
        println("Cam4 request failed: $lastError")
        return ""
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

    /** Minimum gap between cam4.com requests, shared across providers. */
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

    private data class DirectoryResponse(
        @JsonProperty("totalCount") val totalCount: Long? = null,
        @JsonProperty("users") val users: List<User>? = null
    )

    private data class User(
        @JsonProperty("username") val username: String = "",
        @JsonProperty("snapshotImageLink") val snapshotImageLink: String? = null,
        @JsonProperty("gender") val gender: String? = null,
        @JsonProperty("viewers") val viewers: Int? = null
    )

    private data class PerformerInfo(
        @JsonProperty("username") val username: String? = null,
        @JsonProperty("profileImageUrl") val profileImageUrl: String? = null,
        @JsonProperty("avatarUrl") val avatarUrl: String? = null,
        @JsonProperty("bio") val bio: String? = null,
        @JsonProperty("age") val age: Int? = null,
        @JsonProperty("gender") val gender: String? = null,
        @JsonProperty("city") val city: String? = null,
        @JsonProperty("mainLanguage") val mainLanguage: String? = null
    ) {
        fun posterUrl(): String? = profileImageUrl?.takeIf { it.isNotBlank() }
            ?: avatarUrl?.takeIf { it.isNotBlank() }

        fun plot(): String? = buildString {
            val parts = mutableListOf<String>()
            gender?.takeIf { it.isNotBlank() }?.let { parts.add(it.replaceFirstChar { c -> c.uppercaseChar() }) }
            age?.let { parts.add("$it y/o") }
            city?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            mainLanguage?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            if (parts.isNotEmpty()) append(parts.joinToString(" · "))
            if (!bio.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(bio.trim())
            }
        }.takeIf { it.isNotBlank() }

        fun tags(): List<String> = listOfNotNull(
            gender?.takeIf { it.isNotBlank() }
        )
    }

    private data class StreamInfo(
        @JsonProperty("abr") val abr: Boolean? = null,
        @JsonProperty("canUseCDN") val canUseCDN: Boolean? = null,
        @JsonProperty("edgeURL") val edgeURL: String? = null,
        @JsonProperty("cdnURL") val cdnURL: String? = null
    )

    companion object {
        private const val BASE_URL = "https://www.cam4.com"
        private const val PAGE_SIZE = 60 // max rooms per directory request
        private const val DIRECTORY_URL = "$BASE_URL/api/directoryCams" +
            "?directoryJson=true&online=true&url=true&orderBy=VIDEO_QUALITY" +
            "&resultsPerPage=$PAGE_SIZE"

        // Row filters (query suffix appended to DIRECTORY_URL).
        private const val QUERY_ALL = ""
        private const val QUERY_FEMALE = "&gender=female&broadcastType=female_group" +
            "&broadcastType=solo&broadcastType=male_female_group"
        private const val QUERY_COUPLES = "&broadcastType=male_group" +
            "&broadcastType=female_group&broadcastType=male_female_group"

        private const val MIN_INTERVAL_MS = 350L // minimum gap between requests
        private const val RATE_LIMIT_PAUSE_MS = 2_500L // on HTTP 429
        private const val RETRY_PAUSE_MS = 800L // between failed attempts

        private const val REFERER = "https://www.cam4.com/"
        private const val USER_AGENT = ("Mozilla/5.0 (X11; Linux x86_64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

        private val paceLock = Any()
        @Volatile private var lastRequestAt = 0L
    }
}
