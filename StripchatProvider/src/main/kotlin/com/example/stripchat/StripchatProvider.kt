package com.example.stripchat

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
 * CloudStream 3 provider for Stripchat live cams.
 *
 * Scrapes stripchat.com directly from the phone (no addon server), mirroring
 * the logic in Streamio/Stripchat:
 *
 *   * Session   -> https://stripchat.com/api/front/v3/config/initial-dynamic
 *                  yields `initialDynamic.userHash`, a long-lived guest hash the
 *                  roomlist endpoint requires (userRole=guest&guestHash=...)
 *   * Room list -> https://stripchat.com/api/front/models with `primaryTag`
 *                  (girls|men|trans|couples), sorted by stripRanking. The API
 *                  caps `limit` at 99 and filters by `primaryTag` server-side.
 *                  Age genres (Teen/Young/MILF/Mature) are applied server-side
 *                  via `filterGroupTags` (the payload carries no tag list).
 *   * Poster    -> https://img.doppiocdn.live/thumbs/<snapshotTimestamp>/<id>
 *   * Stream    -> https://edge-hls.saawsedge.com/hls/<id>/master/<id>_auto.m3u8
 *                  passed straight to the player; the master serves 200 to any
 *                  client and its variants/media playlists are plain HLS.
 *
 * The home page mirrors the addon catalogs: Popular, 5 regions, Couples Live,
 * plus one row per flag genre (HD/New/VR/Mobile/Lovense/Kiiroo, client-side)
 * and one row per age genre for the provider's gender (server-side snapshot).
 */
class StripchatProvider(private val target: String, displayName: String) : MainAPI() {
    override var mainUrl = "https://stripchat.com"
    override var name = displayName
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var vpnStatus = VPNStatus.MightBeNeeded

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val specs = rowSpecs()
        if (page <= 1) {
            // Each row's first page. Snapshot fetches are deduplicated by key
            // (gender + couples bases, plus one per server-side age genre).
            val rows = specs.mapNotNull { spec ->
                val items = filterModels(spec)
                if (items.isEmpty()) null
                else HomePageList(spec.name, items.take(PAGE_SIZE).map { it.toSearchResponse(this) })
            }
            return newHomePageResponse(rows)
        }
        val spec = specs.firstOrNull { it.name == request.name } ?: return null
        val items = filterModels(spec)
        val from = (page - 1) * PAGE_SIZE
        val slice = items.drop(from).take(PAGE_SIZE)
        return newHomePageResponse(
            HomePageList(spec.name, slice.map { it.toSearchResponse(this) }),
            hasNext = from + slice.size < items.size
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val tag = PRIMARY_TAG[target] ?: "girls"
        val url = "$SUGGEST_URL?query=${URLEncoder.encode(query, "utf8")}&limit=10&primaryTag=${URLEncoder.encode(tag, "utf8")}"
        val models = runCatching { fetchJson<SuggestionResponse>(url) }.getOrNull()?.models
            .orEmpty().filter { it.isLive != false }
        println("Stripchat search '$query' -> ${models.size} results")
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
        // The saawsedge master serves 200 to any client and ExoPlayer resolves
        // its variants, so it can be passed straight through (addon behavior).
        callback.invoke(
            newExtractorLink(
                source = "Stripchat",
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

    /** A home-page row: primaryTag + optional region / flag / age genre filter. */
    private data class RowSpec(
        val name: String,
        val primaryTag: String,
        val region: String? = null,
        val flag: String? = null,
        val ageTag: String? = null
    )

    private fun genderLabel(): String = when (target) {
        "m" -> "men"
        "t" -> "trans"
        else -> "women"
    }

    /**
     * Rows shown on the home page, mirroring the addon's catalogs: the gender's
     * Popular + 5 region catalogs + Couples Live, then flag genre rows
     * (client-side) and the gender's server-side age genre rows.
     */
    private fun rowSpecs(): List<RowSpec> {
        val tag = PRIMARY_TAG[target] ?: "girls"
        val specs = mutableListOf(
            RowSpec("Popular", tag),
            RowSpec("North America", tag, region = "north_america"),
            RowSpec("South America", tag, region = "south_america"),
            RowSpec("Asia", tag, region = "asia"),
            RowSpec("Europe/Russia", tag, region = "europe_russia"),
            RowSpec("Other Regions", tag, region = "other"),
            RowSpec("Couples Live", "couples")
        )
        FLAG_GENRES.forEach { flag ->
            if (Settings.isRowEnabled(flag)) specs.add(RowSpec(flag, tag, flag = flag))
        }
        GENDER_AGE_GENRES.getValue(genderLabel()).forEach { age ->
            if (Settings.isRowEnabled(age)) specs.add(RowSpec(age, tag, ageTag = AGE_TAGS.getValue(age)))
        }
        return specs
    }

    /** Snapshot for a row spec, filtered client-side (region / flag). */
    private suspend fun filterModels(spec: RowSpec): List<Model> {
        val key = if (spec.ageTag != null) "${spec.primaryTag}|${spec.ageTag}" else spec.primaryTag
        val rooms = getSnapshot(key, spec.primaryTag, spec.ageTag)
        return rooms.filter { model ->
            (spec.region == null || countryRegion(model.country) == spec.region) &&
                (spec.flag == null || model.hasFlag(spec.flag))
        }
    }

    /** Map a room's ISO country code to one of the addon's regions. */
    private fun countryRegion(country: String?): String {
        if (country.isNullOrBlank()) return "other"
        val code = country.uppercase()
        REGION_COUNTRIES.forEach { (region, set) -> if (code in set) return region }
        return "other"
    }

    // ------------------------------------------------------- search entries

    private fun Model.toSearchResponse(provider: StripchatProvider): SearchResponse =
        provider.newLiveSearchResponse(username ?: "", roomUrl(username), TvType.Live) {
            posterUrl = posterUrl()
        }

    private fun roomUrl(username: String?) =
        "https://stripchat.com/${URLEncoder.encode(username ?: "", "utf8")}/"

    private fun masterPlaylistUrl(id: Int?): String? =
        id?.let { "https://edge-hls.saawsedge.com/hls/$it/master/${it}_auto.m3u8" }

    /** Resolve a model across the gender and couples snapshots. */
    private suspend fun findModel(username: String): Model? {
        val wanted = username.lowercase()
        val tags = listOfNotNull(PRIMARY_TAG[target], "couples").distinct()
        for (tag in tags) {
            val found = getSnapshot(tag, tag).firstOrNull { it.username?.lowercase() == wanted }
            if (found != null) return found
        }
        return null
    }

    // ------------------------------------------------------- snapshots

    private suspend fun getSnapshot(key: String, primaryTag: String, ageTag: String? = null): List<Model> {
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
            val pages = when {
                ageTag != null -> AGE_PAGES
                primaryTag == "couples" -> COUPLES_PAGES
                else -> MAX_PAGES
            }
            val list = runCatching { fetchModelsPages(primaryTag, ageTag, pages) }.getOrDefault(emptyList())
            synchronized(snapshotLock) {
                snapshots[key] = Snapshot(list, System.currentTimeMillis())
                if (inflightSnapshots[key] === job) inflightSnapshots.remove(key)
            }
            job.complete(list)
        }
        return job.await()
    }

    private suspend fun fetchModelsPages(primaryTag: String, ageTag: String?, maxPages: Int): List<Model> {
        val collected = mutableListOf<Model>()
        var emptyStreak = 0
        for (page in 0 until maxPages) {
            val data = runCatching {
                fetchJson<ModelsResponse>(roomlistUrl(primaryTag, page * PAGE_SIZE, ageTag))
            }.getOrNull()
            val pageRooms = data?.models ?: emptyList()
            if (pageRooms.isEmpty()) {
                // An empty page is usually a transient failure, not the end.
                emptyStreak++
                if (emptyStreak >= 3) break
                continue
            }
            emptyStreak = 0
            collected.addAll(pageRooms)
        }
        return collected
    }

    private suspend fun roomlistUrl(primaryTag: String, offset: Int, ageTag: String?): String {
        val params = mutableListOf(
            "limit=$PAGE_SIZE",
            "offset=$offset",
            "primaryTag=${URLEncoder.encode(primaryTag, "utf8")}",
            "sortBy=stripRanking",
            "userRole=guest",
            "guestHash=${URLEncoder.encode(getGuestHash(), "utf8")}",
            "uniq=${java.util.UUID.randomUUID().toString().replace("-", "").take(12)}"
        )
        if (ageTag != null) {
            params.add("filterGroupTags=${URLEncoder.encode("[[\"$ageTag\"]]", "utf8")}")
        }
        return "$ROOMLIST_URL?${params.joinToString("&")}"
    }

    // ------------------------------------------------------- guest hash

    private suspend fun getGuestHash(): String {
        synchronized(hashLock) {
            if (guestHash.isNotBlank() && System.currentTimeMillis() - guestHashFetchedAt < GUEST_HASH_TTL_MS) {
                return guestHash
            }
        }
        var doFetch = false
        val job = synchronized(hashLock) {
            val pending = inflightHash
            if (pending != null) {
                pending
            } else {
                doFetch = true
                CompletableDeferred<String>().also { inflightHash = it }
            }
        }
        if (doFetch) {
            val hash = runCatching {
                fetchJson<InitialDynamicResponse>(CONFIG_URL)?.initialDynamic?.userHash
            }.getOrNull()
            if (!hash.isNullOrBlank()) {
                synchronized(hashLock) {
                    guestHash = hash
                    guestHashFetchedAt = System.currentTimeMillis()
                }
            }
            job.complete(hash ?: "")
            synchronized(hashLock) { if (inflightHash === job) inflightHash = null }
        }
        return job.await()
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
                    println("Stripchat GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code} (${res.text.length}B)")
                    return res.text
                }
                if (res.okhttpResponse.code == 429) { // rate limited -> longer pause
                    println("Stripchat GET ${res.okhttpResponse.request.url} -> 429 (rate limited)")
                    delay(RATE_LIMIT_PAUSE_MS)
                    continue
                }
                println("Stripchat GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code}")
                return ""
            } catch (e: Exception) {
                lastError = e // network hiccup -> retry
                delay(RETRY_PAUSE_MS * (attempt + 1))
            }
        }
        println("Stripchat request failed: $lastError")
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

    /** Minimum gap between stripchat.com requests, shared across providers. */
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

    private data class ModelsResponse(@JsonProperty("models") val models: List<Model>? = null)

    private data class SuggestionResponse(@JsonProperty("models") val models: List<Model>? = null)

    private data class InitialDynamicResponse(
        @JsonProperty("initialDynamic") val initialDynamic: Dynamic? = null
    )

    private data class Dynamic(@JsonProperty("userHash") val userHash: String? = null)

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
        private const val BASE_URL = "https://stripchat.com"
        private const val CONFIG_URL = "$BASE_URL/api/front/v3/config/initial-dynamic?requestPath=%2F"
        private const val ROOMLIST_URL = "$BASE_URL/api/front/models"
        private const val SUGGEST_URL = "$BASE_URL/api/front/v4/models/search/suggestion"
        private const val FRONT_VERSION = "11.6.18"

        private const val PAGE_SIZE = 99 // roomlist API caps a page at 99 rooms
        private const val MAX_PAGES = 15 // base snapshot pages (~1500 rooms)
        private const val COUPLES_PAGES = 10 // couples snapshot pages (~990 rooms)
        private const val AGE_PAGES = 5 // age genre snapshot pages (~500 rooms)
        private const val CACHE_TTL_MS = 90_000L // keep a snapshot this long
        private const val GUEST_HASH_TTL_MS = 3_600_000L // guest hash is long-lived
        private const val MIN_INTERVAL_MS = 350L // minimum gap between requests
        private const val RATE_LIMIT_PAUSE_MS = 2_500L // on HTTP 429
        private const val RETRY_PAUSE_MS = 800L // between failed attempts

        private const val REFERER = "https://stripchat.com/"
        private const val USER_AGENT = ("Mozilla/5.0 (X11; Linux x86_64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

        // Config-page target codes -> Stripchat primaryTag values.
        private val PRIMARY_TAG = mapOf("f" to "girls", "m" to "men", "t" to "trans", "c" to "couples")

        // Flag genres, filtered client-side on the base snapshot.
        private val FLAG_GENRES = listOf("HD", "New", "VR", "Mobile", "Lovense", "Kiiroo")

        // Age genres -> Stripchat filterGroupTags values (server-side filter).
        private val AGE_TAGS = mapOf(
            "Teen" to "ageTeen",
            "Young" to "ageYoung",
            "MILF" to "ageMilf",
            "Mature" to "ageOld"
        )

        // Age genre rows per gender (from the addon's GENRES lists).
        private val GENDER_AGE_GENRES = mapOf(
            "women" to listOf("Teen", "Young", "MILF", "Mature"),
            "men" to listOf("Teen", "Young", "Mature"),
            "trans" to listOf("Teen", "Young", "Mature")
        )

        // ISO 3166-1 alpha-2 country codes per addon region (lib/config.js).
        private val REGION_COUNTRIES = mapOf(
            "north_america" to setOf(
                "AG", "BS", "BB", "BZ", "CA", "CR", "CU", "DM", "DO", "SV", "GD", "GT", "HT",
                "HN", "JM", "MX", "NI", "PA", "PR", "KN", "LC", "VC", "TT", "US"
            ),
            "south_america" to setOf("AR", "BO", "BR", "CL", "CO", "EC", "GY", "PY", "PE", "SR", "UY", "VE"),
            "asia" to setOf(
                "AF", "AM", "AZ", "BH", "BD", "BT", "BN", "KH", "CN", "GE", "HK", "IN", "ID",
                "IR", "IQ", "IL", "JP", "JO", "KZ", "KW", "KG", "LA", "LB", "MO", "MY", "MV",
                "MN", "MM", "NP", "OM", "PK", "PS", "PH", "QA", "SA", "SG", "KR", "LK", "SY",
                "TW", "TJ", "TH", "TL", "TR", "TM", "AE", "UZ", "VN", "YE"
            ),
            "europe_russia" to setOf(
                "AL", "AD", "AT", "BY", "BE", "BA", "BG", "HR", "CY", "CZ", "DK", "EE", "FI",
                "FR", "DE", "GR", "HU", "IS", "IE", "IT", "LV", "LI", "LT", "LU", "MT", "MD",
                "MC", "ME", "NL", "MK", "NO", "PL", "PT", "RO", "RU", "SM", "RS", "SK", "SI",
                "ES", "SE", "CH", "UA", "GB", "VA", "XK"
            )
        )

        private val snapshotLock = Any()
        private val paceLock = Any()
        private val hashLock = Any()

        private val snapshots = HashMap<String, Snapshot>()
        private val inflightSnapshots = HashMap<String, CompletableDeferred<List<Model>>>()

        @Volatile private var guestHash = ""
        @Volatile private var guestHashFetchedAt = 0L
        @Volatile private var inflightHash: CompletableDeferred<String>? = null
        @Volatile private var lastRequestAt = 0L
    }
}
