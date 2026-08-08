package com.example.mestrip

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
 * CloudStream 3 provider for Mestrip live cams.
 *
 * Scrapes mestrip.com directly from the phone (no addon server), mirroring the
 * logic in bobs/mestrip/viewer.php:
 *
 *   * Room list -> https://mestrip.com/api/front/v2/models?primaryTag=
 *                  (girls|couples|men|trans)&limit=60, returning `blocks` (e.g.
 *                  url "girls/arab", "couples/popular") each carrying a
 *                  `models` array. Each category row matches the block whose url
 *                  equals the category's blockUrl (empty matches all blocks); the
 *                  "All" row unions all four primary tags.
 *   * Poster    -> https://img.doppiocdn.live/thumbs/<snapshotTimestamp>/<id>
 *   * Stream    -> https://edge-hls.saawsedge.com/hls/<id>/master/<id>_auto.m3u8
 *                  passed straight to the player (same as the Stripchat provider).
 */
class MestripProvider : MainAPI() {
    override var mainUrl = BASE_URL
    override var name = "Mestrip"
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
        println("Mestrip search '$query' -> ${models.size} results")
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
                source = "Mestrip",
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

    /** A home-page row: labels + primary tags to union + optional block url. */
    private data class RowSpec(
        val name: String,
        val primaryTags: List<String>,
        val blockUrl: String? = null
    )

    /** Models for a row spec, deduped across primary tags and block-filtered. */
    private suspend fun filterModels(spec: RowSpec): List<Model> {
        val out = LinkedHashMap<Int, Model>()
        for (tag in spec.primaryTags) {
            getSnapshot(tag, tag).forEach { model ->
                if (spec.blockUrl == null || model.blockUrls == spec.blockUrl) {
                    model.id?.let { out.putIfAbsent(it, model) }
                }
            }
        }
        return out.values.toList()
    }

    // ------------------------------------------------------- search entries

    private fun Model.toSearchResponse(provider: MestripProvider): SearchResponse =
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

    private suspend fun getSnapshot(key: String, primaryTag: String): List<Model> {
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
            val list = runCatching { fetchModels(primaryTag) }.getOrDefault(emptyList())
            synchronized(snapshotLock) {
                snapshots[key] = Snapshot(list, System.currentTimeMillis())
                if (inflightSnapshots[key] === job) inflightSnapshots.remove(key)
            }
            job.complete(list)
        }
        return job.await()
    }

    private suspend fun fetchModels(primaryTag: String): List<Model> {
        val url = "$MODELS_URL?primaryTag=${URLEncoder.encode(primaryTag, "utf8")}&limit=$PAGE_SIZE"
        val data = fetchJson<ModelsResponse>(url) ?: return emptyList()
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
                    println("Mestrip GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code} (${res.text.length}B)")
                    return res.text
                }
                if (res.okhttpResponse.code == 429) { // rate limited -> longer pause
                    println("Mestrip GET ${res.okhttpResponse.request.url} -> 429 (rate limited)")
                    delay(RATE_LIMIT_PAUSE_MS)
                    continue
                }
                println("Mestrip GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code}")
                return ""
            } catch (e: Exception) {
                lastError = e // network hiccup -> retry
                delay(RETRY_PAUSE_MS * (attempt + 1))
            }
        }
        println("Mestrip request failed: $lastError")
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

    /** Minimum gap between mestrip.com requests, shared across providers. */
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
        private const val BASE_URL = "https://mestrip.com"
        private const val MODELS_URL = "$BASE_URL/api/front/v2/models"
        private const val SUGGEST_URL = "$BASE_URL/api/front/v4/models/search/suggestion"
        private const val FRONT_VERSION = "11.6.18"

        private const val PAGE_SIZE = 60
        private const val CACHE_TTL_MS = 90_000L // keep a snapshot this long
        private const val MIN_INTERVAL_MS = 350L // minimum gap between requests
        private const val RATE_LIMIT_PAUSE_MS = 2_500L // on HTTP 429
        private const val RETRY_PAUSE_MS = 800L // between failed attempts

        private const val REFERER = "$BASE_URL/"
        private const val USER_AGENT = ("Mozilla/5.0 (X11; Linux x86_64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

        // Flag genres, filtered client-side on the base snapshot.
        private val FLAG_GENRES = listOf("HD", "New", "VR", "Mobile", "Lovense", "Kiiroo")

        // Home rows mirroring the viewer's category table (bobs/mestrip/viewer.php).
        private val ROWS = listOf(
            RowSpec("All Models", listOf("girls", "couples", "men", "trans")),
            RowSpec("Female", listOf("girls")),
            RowSpec("Popular", listOf("girls"), blockUrl = "girls/popular"),
            RowSpec("Arabic", listOf("girls"), blockUrl = "girls/arab"),
            RowSpec("Asian", listOf("girls"), blockUrl = "girls/asian"),
            RowSpec("Ebony", listOf("girls"), blockUrl = "girls/ebony"),
            RowSpec("Latina", listOf("girls"), blockUrl = "girls/latina"),
            RowSpec("White", listOf("girls"), blockUrl = "girls/white"),
            RowSpec("Indian", listOf("girls"), blockUrl = "girls/indian"),
            RowSpec("Mixed", listOf("girls"), blockUrl = "girls/mixed"),
            RowSpec("Mobile", listOf("girls"), blockUrl = "girls/mobile"),
            RowSpec("New", listOf("girls"), blockUrl = "girls/new"),
            RowSpec("VR", listOf("girls"), blockUrl = "girls/vr"),
            RowSpec("HD", listOf("girls"), blockUrl = "girls/hd"),
            RowSpec("Recordable", listOf("girls"), blockUrl = "girls/recordable"),
            RowSpec("Teen 18+", listOf("girls"), blockUrl = "girls/teens"),
            RowSpec("Young 22+", listOf("girls"), blockUrl = "girls/young"),
            RowSpec("MILF", listOf("girls"), blockUrl = "girls/milfs"),
            RowSpec("Mature", listOf("girls"), blockUrl = "girls/mature"),
            RowSpec("Granny", listOf("girls"), blockUrl = "girls/grannies"),
            RowSpec("Skinny", listOf("girls"), blockUrl = "girls/petite"),
            RowSpec("Athletic", listOf("girls"), blockUrl = "girls/athletic"),
            RowSpec("Medium", listOf("girls"), blockUrl = "girls/medium"),
            RowSpec("Curvy", listOf("girls"), blockUrl = "girls/curvy"),
            RowSpec("BBW", listOf("girls"), blockUrl = "girls/bbw"),
            RowSpec("Blonde", listOf("girls"), blockUrl = "girls/blondes"),
            RowSpec("Brunette", listOf("girls"), blockUrl = "girls/brunettes"),
            RowSpec("Redhead", listOf("girls"), blockUrl = "girls/redheads"),
            RowSpec("Black Hair", listOf("girls"), blockUrl = "girls/black-hair"),
            RowSpec("Shaven", listOf("girls"), blockUrl = "girls/shaven"),
            RowSpec("Trimmed", listOf("girls"), blockUrl = "girls/trimmed"),
            RowSpec("Hairy Pussy", listOf("girls"), blockUrl = "girls/hairy"),
            RowSpec("Big Tits", listOf("girls"), blockUrl = "girls/big-tits"),
            RowSpec("Small Tits", listOf("girls"), blockUrl = "girls/small-tits"),
            RowSpec("Big Ass", listOf("girls"), blockUrl = "girls/big-ass"),
            RowSpec("Anal", listOf("girls"), blockUrl = "girls/anal"),
            RowSpec("Blowjob", listOf("girls"), blockUrl = "girls/blowjob"),
            RowSpec("Masturbation", listOf("girls"), blockUrl = "girls/masturbation"),
            RowSpec("Dildo/Vibrator", listOf("girls"), blockUrl = "girls/dildo-or-vibrator"),
            RowSpec("Sex Toys", listOf("girls"), blockUrl = "girls/sex-toys"),
            RowSpec("Foot Fetish", listOf("girls"), blockUrl = "girls/foot-fetish"),
            RowSpec("Spanking", listOf("girls"), blockUrl = "girls/spanking"),
            RowSpec("Cowgirl", listOf("girls"), blockUrl = "girls/cowgirl"),
            RowSpec("Doggy Style", listOf("girls"), blockUrl = "girls/doggy-style"),
            RowSpec("Threesome", listOf("girls"), blockUrl = "girls/threesome"),
            RowSpec("Orgasm", listOf("girls"), blockUrl = "girls/orgasm"),
            RowSpec("Squirt", listOf("girls"), blockUrl = "girls/squirt"),
            RowSpec("Deepthroat", listOf("girls"), blockUrl = "girls/deepthroat"),
            RowSpec("Creampie", listOf("girls"), blockUrl = "girls/creampie"),
            RowSpec("Ahegao", listOf("girls"), blockUrl = "girls/ahegao"),
            RowSpec("Role Play", listOf("girls"), blockUrl = "girls/role-play"),
            RowSpec("Cosplay", listOf("girls"), blockUrl = "girls/cosplay"),
            RowSpec("Striptease", listOf("girls"), blockUrl = "girls/striptease"),
            RowSpec("Oil Show", listOf("girls"), blockUrl = "girls/oil-show"),
            RowSpec("Lovense", listOf("girls"), blockUrl = "girls/lovense"),
            RowSpec("Couples", listOf("couples")),
            RowSpec("Couples Popular", listOf("couples"), blockUrl = "couples/popular"),
            RowSpec("Group Sex", listOf("couples"), blockUrl = "couples/group-sex"),
            RowSpec("Lesbians", listOf("couples"), blockUrl = "couples/lesbians"),
            RowSpec("Couples HD", listOf("couples"), blockUrl = "couples/hd"),
            RowSpec("Couples New", listOf("couples"), blockUrl = "couples/new"),
            RowSpec("Male", listOf("men")),
            RowSpec("Male Popular", listOf("men"), blockUrl = "men/popular"),
            RowSpec("Male Gays", listOf("men"), blockUrl = "men/gays"),
            RowSpec("Male Straight", listOf("men"), blockUrl = "men/straight"),
            RowSpec("Male Arabic", listOf("men"), blockUrl = "men/arab"),
            RowSpec("Male HD", listOf("men"), blockUrl = "men/hd"),
            RowSpec("Male Mobile", listOf("men"), blockUrl = "men/mobile"),
            RowSpec("Male New", listOf("men"), blockUrl = "men/new"),
            RowSpec("Trans", listOf("trans")),
            RowSpec("Trans Popular", listOf("trans"), blockUrl = "trans/popular"),
            RowSpec("Trans Couples", listOf("trans"), blockUrl = "trans/couples"),
            RowSpec("Trans Arabic", listOf("trans"), blockUrl = "trans/arab")
        ).filter { Settings.isRowEnabled(it.name) }

        private val snapshotLock = Any()
        private val paceLock = Any()

        private val snapshots = HashMap<String, Snapshot>()
        private val inflightSnapshots = HashMap<String, CompletableDeferred<List<Model>>>()

        @Volatile private var lastRequestAt = 0L
    }
}
