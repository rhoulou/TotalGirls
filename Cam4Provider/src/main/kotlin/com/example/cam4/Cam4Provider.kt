package com.example.cam4

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * CloudStream 3 provider for Cam4 live cams.
 *
 * Scrapes cam4.com directly from the phone (no addon server), mirroring the
 * logic in the working PHP scrapers:
 *
 *   * Room list -> POST https://www.cam4.com/graph?operation=
 *                  getGenderPreferencePageData&ssr=false (GraphQL, header
 *                  `apollographql-client-name: CAM4-client`). Filters by
 *                  `gender` server-side (female / male / transgender - the
 *                  GenderEnum values) plus a category `filters` slug per home
 *                  row, and pages with `cursor: { first: 200, offset }`. Each
 *                  item already carries the live HLS master (`preview.src`) and
 *                  poster (`profileImageURL`).
 *   * Search    -> GET /api/directoryCams?...&search=<query> (returns a bare
 *                  JSON array of users; no GraphQL search exists).
 *   * Stream    -> GET /api/directoryCams?...&username=<user> yields
 *                  `hlsPreviewUrl` (the model's live HLS master). Master,
 *                  variant and segments all serve 200 to any client, so the
 *                  master is passed straight to the player.
 *   * Metadata  -> same directory username= lookup (poster, viewers, tags).
 *
 * Category rows use the server-side GraphQL `filters` slugs (the compound
 * forms like `petite-female-body` / `bbw-female-body` / `black`; the short
 * labels like `petite`/`bbw`/`ebony`/`latina` are ignored by the API). HD and
 * Morocco have no working filter. Gender rows (Guys / Trans) appear when those
 * genders are enabled in the plugin settings.
 */
class Cam4Provider : MainAPI() {
    override var mainUrl = "https://www.cam4.com"
    override var name = "Cam4"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage: List<MainPageData>
        get() {
            val g = Settings.genders()
            val rows = mutableListOf<Pair<String, String>>()
            if ("female" in g) {
                Settings.ALL_ROWS.filter { (key, _) -> Settings.isRowEnabled(key) }
                    .forEach { rows.add(it) }
            }
            if ("male" in g) rows.add("g=male" to "Guys")
            if ("transgender" in g) rows.add("g=transgender" to "Trans")
            return mainPageOf(*rows.toTypedArray())
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val specs = rowSpecs()
        if (page <= 1) {
            val rows = specs.mapNotNull { spec ->
                val items = fetchBroadcasts(spec.gender, specFilters(spec), 0)
                if (items.isEmpty()) null
                else HomePageList(spec.name, items.map { it.toSearchResponse() }, isHorizontalImages = true)
            }
            return newHomePageResponse(rows)
        }
        val spec = specs.firstOrNull { it.name == request.name } ?: return null
        val items = fetchBroadcasts(spec.gender, specFilters(spec), (page - 1) * PAGE_SIZE)
        return newHomePageResponse(
            HomePageList(spec.name, items.map { it.toSearchResponse() }, isHorizontalImages = true),
            hasNext = items.size >= PAGE_SIZE
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
        val user = fetchUser(username)
        return newLiveStreamLoadResponse(username, url, url) {
            posterUrl = user?.posterUrl()
            plot = user?.plot()
            tags = user?.tags()
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
        val master = fetchUser(username)?.hlsPreviewUrl
        if (master.isNullOrBlank()) return false
        // The xcdnpro master serves 200 to any client and ExoPlayer resolves
        // its variants, so it can be passed straight through.
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

    private data class RowSpec(val name: String, val gender: String, val filterSlug: String)

    /** Rows for the currently enabled genders (category rows are female). */
    private fun rowSpecs(): List<RowSpec> {
        val g = Settings.genders()
        val out = mutableListOf<RowSpec>()
        if ("female" in g) {
            Settings.ALL_ROWS.forEach { (key, name) ->
                if (Settings.isRowEnabled(key)) out.add(RowSpec(name, "female", key))
            }
        }
        if ("male" in g) out.add(RowSpec("Guys", "male", ""))
        if ("transgender" in g) out.add(RowSpec("Trans", "transgender", ""))
        return out
    }

    private fun specFilters(spec: RowSpec): List<String> =
        if (spec.filterSlug.isBlank()) emptyList() else listOf(spec.filterSlug)

    private fun Item.toSearchResponse(): SearchResponse =
        newLiveSearchResponse(username, roomUrl(username), TvType.Live) {
            posterUrl = profileImageURL
        }

    private fun User.toSearchResponse(): SearchResponse =
        newLiveSearchResponse(username, roomUrl(username), TvType.Live) {
            posterUrl = snapshotImageLink
        }

    private fun roomUrl(username: String) = "$BASE_URL/$username"

    /** Single-user directory lookup (the working hls.php approach). */
    private suspend fun fetchUser(username: String): User? {
        val wanted = username.lowercase()
        val url = "$BASE_URL/api/directoryCams?directoryJson=true&online=true&url=true" +
            "&username=${URLEncoder.encode(username, "utf8")}"
        return fetchJson<DirectoryResponse>(url)?.users
            ?.firstOrNull { it.username.lowercase() == wanted }
    }

    /** GraphQL broadcasts query for one row (filters) + offset. */
    private suspend fun fetchBroadcasts(gender: String, filters: List<String>, offset: Int): List<Item> {
        val body = JSONObject()
            .put("operationName", "getGenderPreferencePageData")
            .put(
                "variables",
                JSONObject().put(
                    "input",
                    JSONObject()
                        .put("orderBy", "trending")
                        .put("filters", JSONArray(filters))
                        .put("gender", gender)
                        .put("cursor", JSONObject().put("first", PAGE_SIZE).put("offset", offset))
                )
            )
            .put("query", GRAPH_QUERY)
            .toString()
            .toRequestBody(JSON_MEDIA)
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to REFERER,
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.9",
            "apollographql-client-name" to "CAM4-client"
        )
        val data = fetchJsonPost<BroadcastsResponse>(GRAPH_URL, headers, body)
        return data?.data?.broadcasts?.items.orEmpty()
    }

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

    /** Route a GET through the user's proxy when one is configured (POSTs stay direct). */
    private fun wrap(url: String): String {
        val p = Settings.proxy()
        return if (p.isBlank()) url else p + URLEncoder.encode(url, "utf8")
    }

    private suspend fun fetchPost(url: String, headers: Map<String, String>, body: okhttp3.RequestBody): String {
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            try {
                pace()
                val res = app.post(url, headers = headers, requestBody = body)
                if (res.isSuccessful) {
                    println("Cam4 POST $url -> ${res.okhttpResponse.code} (${res.text.length}B)")
                    return res.text
                }
                if (res.okhttpResponse.code == 429) { // rate limited -> longer pause
                    println("Cam4 POST $url -> 429 (rate limited)")
                    delay(RATE_LIMIT_PAUSE_MS)
                    continue
                }
                println("Cam4 POST $url -> ${res.okhttpResponse.code}")
                return ""
            } catch (e: Exception) {
                lastError = e // network hiccup -> retry
                delay(RETRY_PAUSE_MS * (attempt + 1))
            }
        }
        println("Cam4 POST failed: $lastError")
        return ""
    }

    private suspend inline fun <reified T : Any> fetchJson(url: String): T? =
        parseJson(fetch(url))

    private suspend inline fun <reified T : Any> fetchJsonPost(
        url: String,
        headers: Map<String, String>,
        body: okhttp3.RequestBody
    ): T? = parseJson(fetchPost(url, headers, body))

    private inline fun <reified T : Any> parseJson(text: String): T? {
        // The API returns an HTML Cloudflare challenge page instead of JSON.
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
        @JsonProperty("profileImageLink") val profileImageLink: String? = null,
        @JsonProperty("gender") val gender: String? = null,
        @JsonProperty("viewers") val viewers: Int? = null,
        @JsonProperty("statusMessage") val statusMessage: String? = null,
        @JsonProperty("age") val age: Int? = null,
        @JsonProperty("countryCode") val countryCode: String? = null,
        @JsonProperty("hlsPreviewUrl") val hlsPreviewUrl: String? = null,
        @JsonProperty("showType") val showType: String? = null,
        @JsonProperty("newPerformer") val newPerformer: Boolean? = null
    ) {
        fun posterUrl(): String? = snapshotImageLink?.takeIf { it.isNotBlank() }
            ?: profileImageLink?.takeIf { it.isNotBlank() }

        fun plot(): String? = buildString {
            viewers?.let { append("Watching: ").append(it) }
            if (!statusMessage.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(statusMessage.trim())
            }
        }.takeIf { it.isNotBlank() }

        fun tags(): List<String> = buildList {
            gender?.takeIf { it.isNotBlank() }?.let { add(it.replaceFirstChar { c -> c.uppercaseChar() }) }
            age?.let { add("$it y/o") }
            if (showType == "HD" || showType == "hd") add("HD")
            if (newPerformer == true) add("New")
            if (!countryCode.isNullOrBlank()) add(countryCode.uppercase())
        }
    }

    private data class BroadcastsResponse(
        @JsonProperty("data") val data: BroadcastsData? = null
    )

    private data class BroadcastsData(
        @JsonProperty("broadcasts") val broadcasts: Broadcasts? = null
    )

    private data class Broadcasts(
        @JsonProperty("total") val total: Int? = null,
        @JsonProperty("items") val items: List<Item>? = null
    )

    private data class Item(
        @JsonProperty("username") val username: String = "",
        @JsonProperty("country") val country: String? = null,
        @JsonProperty("viewers") val viewers: Int? = null,
        @JsonProperty("gender") val gender: String? = null,
        @JsonProperty("showType") val showType: String? = null,
        @JsonProperty("hasNewBroadcasterBadge") val hasNewBroadcasterBadge: Boolean? = null,
        @JsonProperty("profileImageURL") val profileImageURL: String? = null,
        @JsonProperty("preview") val preview: Preview? = null,
        @JsonProperty("tags") val tags: List<Tag>? = null
    )

    private data class Preview(@JsonProperty("src") val src: String? = null)

    private data class Tag(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null
    )

    companion object {
        private const val BASE_URL = "https://www.cam4.com"
        private const val GRAPH_URL = "$BASE_URL/graph?operation=getGenderPreferencePageData&ssr=false"
        private const val PAGE_SIZE = 200 // GraphQL first/cursor page size

        private const val GRAPH_QUERY = "query getGenderPreferencePageData(" +
            "\$input: BroadcastsInput) { broadcasts(input: \$input) { total items { " +
            "username country viewers gender broadcastType showType " +
            "hasNewBroadcasterBadge hasLiveTouchBadge hasBoostBadge " +
            "profileImageURL preview { src } tags { name slug } } } }"

        private const val MIN_INTERVAL_MS = 350L // minimum gap between requests
        private const val RATE_LIMIT_PAUSE_MS = 2_500L // on HTTP 429
        private const val RETRY_PAUSE_MS = 800L // between failed attempts

        private const val REFERER = "https://www.cam4.com/"
        private const val USER_AGENT = ("Mozilla/5.0 (X11; Linux x86_64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaTypeOrNull()

        private val paceLock = Any()
        @Volatile private var lastRequestAt = 0L
    }
}
