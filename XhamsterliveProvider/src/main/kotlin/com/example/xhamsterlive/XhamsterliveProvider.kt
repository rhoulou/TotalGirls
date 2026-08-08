package com.example.xhamsterlive

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
 * CloudStream 3 provider for XHamsterLive live cams.
 *
 * Scrapes xhamsterlive.com directly from the phone (no addon server), mirroring
 * the logic in bobs/xhamster/viewer.php:
 *
 *   * Room list -> https://xhamsterlive.com/api/front/v2/models?primaryTag=
 *                  (girls|couples|men|trans)&limit=60&topLimit=61, which returns
 *                  `blocks` (e.g. url "girls/popular", "girls/arab", ...) each
 *                  carrying a `models` array. Optional `filterGroupTags` (e.g.
 *                  [["ageTeen"]]) applies ethnic/age/body genres server-side.
 *   * Poster    -> https://img.doppiocdn.live/thumbs/<snapshotTimestamp>/<id>
 *   * Stream    -> https://edge-hls.saawsedge.com/hls/<id>/master/<id>_auto.m3u8
 *                  passed straight to the player (same as the Stripchat provider).
 *
 * The home page mirrors the addon viewer's category dropdown: the gender bases
 * plus their subcategory blocks and the ethnic/age/body filter rows.
 */
class XhamsterliveProvider : MainAPI() {
    override var mainUrl = BASE_URL
    override var name = "XHamsterLive"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var vpnStatus = VPNStatus.MightBeNeeded

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page <= 1) {
            val rows = ROWS.mapNotNull { spec ->
                val items = filterModels(spec)
                if (items.isEmpty()) null
                else HomePageList(spec.name, items.take(PAGE_SIZE).map { it.toSearchResponse(this) })
            }
            return newHomePageResponse(rows)
        }
        val spec = ROWS.firstOrNull { it.name == request.name } ?: return null
        val items = filterModels(spec)
        val from = (page - 1) * PAGE_SIZE
        val slice = items.drop(from).take(PAGE_SIZE)
        return newHomePageResponse(
            HomePageList(spec.name, slice.map { it.toSearchResponse(this) }),
            hasNext = from + slice.size < items.size
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$SUGGEST_URL?query=${URLEncoder.encode(query, "utf8")}&limit=10&primaryTag=${URLEncoder.encode("girls", "utf8")}"
        val models = runCatching { fetchJson<SuggestionResponse>(url) }.getOrNull()?.models
            .orEmpty().filter { it.isLive != false }
        println("XHamsterLive search '$query' -> ${models.size} results")
        return models.map { it.toSearchResponse(this) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val username = url.trimEnd('/').substringAfterLast('/')
        val room = findModel(username) ?: return null
        return newLiveStreamLoadResponse(room.username ?: username, url, url) {
            posterUrl = room.posterUrl()
            plot = buildString {
                room.viewersCount?.let { append("Watching: ").append(it) }
                if (!room.groupShowTopic.isNullOrBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(room.groupShowTopic)
                }
            }
            tags = room.flagGenres()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val username = data.trimEnd('/').substringAfterLast('/')
        val model = findModel(username) ?: return false
        val master = masterPlaylistUrl(model.id) ?: return false
        callback.invoke(
            newExtractorLink(
                source = "XHamsterLive",
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

    // ------------------------------------------------------- category rows

    /** A home-page row: primaryTag + optional block url / server-side filter. */
    private data class RowSpec(
        val name: String,
        val primaryTag: String,
        val blockUrl: String? = null,
        val filterTags: String? = null
    )

    /** Models for a row spec, filtered client-side by block url. */
    private suspend fun filterModels(spec: RowSpec): List<Model> {
        val rooms = getSnapshot(spec.primaryTag, spec.primaryTag, spec.filterTags)
        return if (spec.blockUrl == null) rooms
        else rooms.filter { it.blockUrls == spec.blockUrl }
    }

    // ------------------------------------------------------- search entries

    private fun Model.toSearchResponse(provider: XhamsterliveProvider): SearchResponse =
        provider.newLiveSearchResponse(username ?: "", roomUrl(username), TvType.Live) {
            posterUrl = posterUrl()
        }

    private fun roomUrl(username: String?) =
        "$BASE_URL/${URLEncoder.encode(username ?: "", "utf8")}/"

    private fun masterPlaylistUrl(id: Int?): String? =
        id?.let { "https://edge-hls.saawsedge.com/hls/$it/master/${it}_auto.m3u8" }

    /** Resolve a model across the gender and couples snapshots. */
    private suspend fun findModel(username: String): Model? {
        val wanted = username.lowercase()
        val tags = listOf("girls", "couples", "men", "trans")
        for (tag in tags) {
            val found = getSnapshot(tag, tag).firstOrNull { it.username?.lowercase() == wanted }
            if (found != null) return found
        }
        return null
    }

    // ------------------------------------------------------- snapshots

    private suspend fun getSnapshot(
        key: String,
        primaryTag: String,
        filterTags: String? = null
    ): List<Model> {
        synchronized(snapshotLock) {
            val s = snapshots[key]
            if (s != null && System.currentTimeMillis() - s.fetchedAt < CACHE_TTL_MS) return s.rooms
        }
        var doFetch = false
        val job = synchronized(snapshotLock) {
            val pending = inflightSnapshots[key]
            if (pending != null) {
                pending
            } else {
                doFetch = true
                CompletableDeferred<List<Model>>().also { inflightSnapshots[key] = it }
            }
        }
        if (doFetch) {
            val list = runCatching { fetchModels(primaryTag, filterTags) }.getOrDefault(emptyList())
            synchronized(snapshotLock) {
                snapshots[key] = Snapshot(list, System.currentTimeMillis())
                if (inflightSnapshots[key] === job) inflightSnapshots.remove(key)
            }
            job.complete(list)
        }
        return job.await()
    }

    private suspend fun fetchModels(primaryTag: String, filterTags: String?): List<Model> {
        val params = mutableListOf(
            "primaryTag=${URLEncoder.encode(primaryTag, "utf8")}",
            "limit=$PAGE_SIZE",
            "topLimit=$TOP_LIMIT"
        )
        if (!filterTags.isNullOrBlank()) {
            params.add("filterGroupTags=${URLEncoder.encode(filterTags, "utf8")}")
        }
        val data = fetchJson<ModelsResponse>("$MODELS_URL?${params.joinToString("&")}") ?: return emptyList()
        val out = LinkedHashMap<Int, Model>()
        data.blocks.orEmpty().forEach { block ->
            block.models.orEmpty().forEach { model ->
                model.blockUrls = block.url
                model.id?.let { out.putIfAbsent(it, model) }
            }
        }
        return out.values.toList()
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
                        "Front-Version" to FRONT_VERSION,
                        "Referer" to REFERER,
                        "Accept" to "application/json, text/plain, */*",
                        "Accept-Language" to "en-US,en;q=0.9"
                    )
                )
                if (res.isSuccessful) {
                    println("XHamsterLive GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code} (${res.text.length}B)")
                    return res.text
                }
                if (res.okhttpResponse.code == 429) { // rate limited -> longer pause
                    println("XHamsterLive GET ${res.okhttpResponse.request.url} -> 429 (rate limited)")
                    delay(RATE_LIMIT_PAUSE_MS)
                    continue
                }
                println("XHamsterLive GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code}")
                return ""
            } catch (e: Exception) {
                lastError = e // network hiccup -> retry
                delay(RETRY_PAUSE_MS * (attempt + 1))
            }
        }
        println("XHamsterLive request failed: $lastError")
        return ""
    }

    /** Route a request through the user's proxy when one is configured. */
    private fun wrap(url: String): String {
        val p = Settings.proxy()
        return if (p.isBlank()) url else p + URLEncoder.encode(url, "utf8")
    }

    private suspend inline fun <reified T : Any> fetchJson(url: String): T? {
        val text = fetch(url)
        // The API returns an HTML Cloudflare challenge page instead of JSON.
        if (text.isBlank() || text.startsWith("<!DOCTYPE", ignoreCase = true) ||
            text.startsWith("<html", ignoreCase = true)
        ) {
            return null
        }
        return tryParseJson<T>(text)
    }

    /** Minimum gap between xhamsterlive.com requests, shared across providers. */
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

    private data class ModelsResponse(@JsonProperty("blocks") val blocks: List<Block>? = null)

    private data class Block(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("models") val models: List<Model>? = null
    )

    private data class SuggestionResponse(@JsonProperty("models") val models: List<Model>? = null)

    private data class Snapshot(val rooms: List<Model>, val fetchedAt: Long)

    private data class Model(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("username") val username: String? = null,
        @JsonProperty("country") val country: String? = null,
        @JsonProperty("viewersCount") val viewersCount: Int? = null,
        @JsonProperty("snapshotTimestamp") val snapshotTimestamp: String? = null,
        @JsonProperty("groupShowTopic") val groupShowTopic: String? = null,
        @JsonProperty("isHd") val isHd: Boolean? = null,
        @JsonProperty("isNew") val isNew: Boolean? = null,
        @JsonProperty("isVr") val isVr: Boolean? = null,
        @JsonProperty("isMobile") val isMobile: Boolean? = null,
        @JsonProperty("isLovense") val isLovense: Boolean? = null,
        @JsonProperty("isKiiroo") val isKiiroo: Boolean? = null,
        @JsonProperty("isLive") val isLive: Boolean? = null
    ) {
        var blockUrls: String? = null

        /** Live snapshot thumbnail (the addon's poster logic). */
        fun posterUrl(): String? =
            id?.let { i -> snapshotTimestamp?.let { ts -> "https://img.doppiocdn.live/thumbs/$ts/$i" } }

        fun flagGenres(): List<String> = FLAG_GENRES.filter { hasFlag(it) }

        fun hasFlag(flag: String): Boolean = when (flag) {
            "HD" -> isHd == true
            "New" -> isNew == true
            "VR" -> isVr == true
            "Mobile" -> isMobile == true
            "Lovense" -> isLovense == true
            "Kiiroo" -> isKiiroo == true
            else -> false
        }
    }

    companion object {
        private const val BASE_URL = "https://xhamsterlive.com"
        private const val MODELS_URL = "$BASE_URL/api/front/v2/models"
        private const val SUGGEST_URL = "$BASE_URL/api/front/v4/models/search/suggestion"
        private const val FRONT_VERSION = "11.6.18"

        private const val PAGE_SIZE = 60
        private const val TOP_LIMIT = 61
        private const val CACHE_TTL_MS = 90_000L // keep a snapshot this long
        private const val MIN_INTERVAL_MS = 350L // minimum gap between requests
        private const val RATE_LIMIT_PAUSE_MS = 2_500L // on HTTP 429
        private const val RETRY_PAUSE_MS = 800L // between failed attempts

        private const val REFERER = "$BASE_URL/"
        private const val USER_AGENT = ("Mozilla/5.0 (X11; Linux x86_64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

        // Flag genres, filtered client-side on the base snapshot.
        private val FLAG_GENRES = listOf("HD", "New", "VR", "Mobile", "Lovense", "Kiiroo")

        // Home rows mirroring the viewer's category dropdown (bobs/xhamster/viewer.php).
        private val ROWS = listOf(
            RowSpec("All Girls", "girls"),
            RowSpec("Girls Popular", "girls", blockUrl = "girls/popular"),
            RowSpec("Girls Arab", "girls", blockUrl = "girls/arab"),
            RowSpec("Girls Mobile", "girls", blockUrl = "girls/mobile"),
            RowSpec("Girls New", "girls", blockUrl = "girls/new"),
            RowSpec("Girls VR", "girls", blockUrl = "girls/vr"),
            RowSpec("Couples", "couples"),
            RowSpec("Couples Popular", "couples", blockUrl = "couples/popular"),
            RowSpec("Group Sex", "couples", blockUrl = "couples/group-sex"),
            RowSpec("Lesbians", "couples", blockUrl = "couples/lesbians"),
            RowSpec("Men", "men"),
            RowSpec("Men Popular", "men", blockUrl = "men/popular"),
            RowSpec("Men Gays", "men", blockUrl = "men/gays"),
            RowSpec("Men Straight", "men", blockUrl = "men/straight"),
            RowSpec("Trans", "trans"),
            RowSpec("Trans Popular", "trans", blockUrl = "trans/popular"),
            RowSpec("Trans Couples", "trans", blockUrl = "trans/couples"),
            RowSpec("Arabic", "girls", filterTags = """[["ethnicityMiddleEastern"]]"""),
            RowSpec("Asian Teens", "girls", filterTags = """[["ethnicityAsian","ageTeen"]]"""),
            RowSpec("Teens", "girls", filterTags = """[["ageTeen"]]"""),
            RowSpec("Young", "girls", filterTags = """[["ageYoung"]]"""),
            RowSpec("White", "girls", filterTags = """[["ethnicityWhite"]]"""),
            RowSpec("Ebony", "girls", filterTags = """[["ethnicityEbony"]]"""),
            RowSpec("Latina", "girls", filterTags = """[["ethnicityLatino"]]"""),
            RowSpec("Asian", "girls", filterTags = """[["ethnicityAsian"]]"""),
            RowSpec("Indian", "girls", filterTags = """[["ethnicityIndian"]]"""),
            RowSpec("Athletic", "girls", filterTags = """[["bodyTypeAthletic"]]"""),
            RowSpec("Grannies", "girls", filterTags = """[["ageOld"]]"""),
            RowSpec("Shaven", "girls", filterTags = """[["bodyHairShaven"]]"""),
            RowSpec("Trimmed", "girls", filterTags = """[["bodyHairTrimmed"]]""")
        ).filter { Settings.isRowEnabled(it.name) }

        private val snapshotLock = Any()
        private val paceLock = Any()

        private val snapshots = HashMap<String, Snapshot>()
        private val inflightSnapshots = HashMap<String, CompletableDeferred<List<Model>>>()

        @Volatile private var lastRequestAt = 0L
    }
}
