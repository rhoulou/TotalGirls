package com.example.chaturbate

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
 * CloudStream 3 provider for Chaturbate live cams.
 *
 * Scrapes chaturbate.com directly from the phone (no addon server), mirroring
 * the logic reverse-engineered in Streamio/Chaturbate (Node/Kodi addons):
 *
 *   * Room list  -> https://chaturbate.com/api/ts/roomlist/room-list/
 *                   (the API caps `limit` at 100, ignores the `gender` param,
 *                   and returns a mixed list - so we filter by room.gender)
 *   * Room page  -> https://chaturbate.com/<username>/ embeds
 *                   window.initialRoomDossier (a double-encoded JSON string)
 *                   whose `hls_source` is a signed edge-stream master HLS
 *   * Stream     -> we fetch the master ourselves (browser UA + Referer) and
 *                   hand the player the per-variant chunklists, best quality
 *                   first (mirrors the Stremio addon; the master endpoint 403s
 *                   non-browser clients, the variants do not)
 *
 * The home page mirrors the addon's catalogs: Popular, 5 regions, Couples Live
 * and one row per genre (tag) for the provider's gender.
 *
 * Robustness: a browser-like User-Agent, a cookie jar (NiceHttp persists it),
 * request pacing and HTTP 429 backoff (Chaturbate rate-limits aggressively),
 * plus an in-memory roomlist snapshot cache (90 s) so catalog loads don't
 * hammer the API. One provider per target (f = Girls, m = Guys, t = Trans).
 */
class ChaturbateProvider(private val gender: String, displayName: String) : MainAPI() {
    override var mainUrl = "https://chaturbate.com"
    override var name = displayName
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var vpnStatus = VPNStatus.MightBeNeeded

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val rooms = getRooms()
        val specs = rowSpecs()
        if (page <= 1) {
            // Page 1 = one row per category/genre, mirroring the addon's catalogs.
            val rows = specs.mapNotNull { spec ->
                val items = filterRooms(rooms, spec)
                if (items.isEmpty()) null
                else HomePageList(spec.name, items.take(PAGE_SIZE).map { it.toSearchResponse(this) })
            }
            return newHomePageResponse(rows)
        }
        // Next pages page each row independently (CloudStream tags the request
        // with the row name we return in the HomePageList).
        val spec = specs.firstOrNull { it.name == request.name } ?: return null
        val items = filterRooms(rooms, spec)
        val from = (page - 1) * PAGE_SIZE
        val slice = items.drop(from).take(PAGE_SIZE)
        return newHomePageResponse(
            HomePageList(spec.name, slice.map { it.toSearchResponse(this) }),
            hasNext = from + slice.size < items.size
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.lowercase()
        return getRooms()
            .filter { (it.username ?: "").contains(q) }
            .map { it.toSearchResponse(this) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val username = url.trimEnd('/').substringAfterLast('/')
        // Prefer the cached roomlist meta (same fields as the addon's meta()).
        val room = freshRooms().firstOrNull { it.username == username }
        if (room != null) {
            return newLiveStreamLoadResponse(room.username ?: username, url, url) {
                posterUrl = room.img
                plot = buildString {
                    if (room.numUsers != null) {
                        append("Watching: ").append(room.numUsers)
                    }
                    if (!room.roomSubject.isNullOrBlank()) {
                        if (isNotEmpty()) append("\n\n")
                        append(room.roomSubject)
                    }
                }
                tags = room.tags
            }
        }
        // Fallback: parse the room page directly.
        val doc = runCatching { app.get(url).document }.getOrNull() ?: return null
        val name = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.removePrefix("Watch ")?.removeSuffix(" live on Chaturbate!") ?: username
        return newLiveStreamLoadResponse(name, url, url) {
            posterUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
            plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val username = data.trimEnd('/').substringAfterLast('/')
        val dossier = fetchDossier(username) ?: return false
        val hlsSource = dossier.hlsSource ?: return false
        // Fetch the master playlist ourselves (browser UA + Referer) and hand the
        // player only the per-variant chunklist URLs, like the Stremio addon does.
        // The mmcdn master endpoint 403s requests without a browser fingerprint,
        // but the variant chunklists are served to any client.
        val master = fetchDirect(hlsSource)
        if (master.isBlank()) return false
        val variants = parseMasterVariants(master, hlsSource)
        if (variants.isEmpty()) {
            println("Chaturbate no HLS variants parsed from master for $username")
            return false
        }
        println("Chaturbate ${variants.size} HLS variants for $username")
        variants.forEach { (label, url) ->
            println("Chaturbate variant $label -> $url")
            callback.invoke(
                newExtractorLink(
                    source = "Chaturbate",
                    name = label,
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = REFERER
                    quality = label.removeSuffix("p").toIntOrNull() ?: Qualities.Unknown.value
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to REFERER)
                }
            )
        }
        return true
    }

    // ------------------------------------------------------- category rows

    /** A home-page row: gender filter + optional region / genre (tag) filter. */
    private data class RowSpec(
        val name: String,
        val genderCode: String,
        val region: String? = null,
        val tag: String? = null
    )

    private fun genderLabel(): String = when (gender) {
        "m" -> "men"
        "t" -> "trans"
        else -> "women"
    }

    /**
     * Rows shown on the home page, mirroring the addon's catalogs: the gender's
     * Popular + the 5 region catalogs + Couples Live, then one row per genre in
     * the gender's genre list (Teen first, etc.).
     */
    private fun rowSpecs(): List<RowSpec> {
        val code = GENDER_CODES[gender] ?: gender
        val specs = mutableListOf(
            RowSpec("Popular", code),
            RowSpec("North America", code, region = "north_america"),
            RowSpec("South America", code, region = "south_america"),
            RowSpec("Asia", code, region = "asia"),
            RowSpec("Europe/Russia", code, region = "europe_russia"),
            RowSpec("Other Regions", code, region = "other"),
            RowSpec("Couples Live", "c")
        )
        GENRES.getValue(genderLabel()).forEach { tag ->
            if (Settings.isRowEnabled(tag)) specs.add(RowSpec(tag, code, tag = tag))
        }
        return specs
    }

    /** Filter the roomlist snapshot by a row spec (same rules as the addon). */
    private fun filterRooms(rooms: List<Room>, spec: RowSpec): List<Room> =
        rooms.filter { room ->
            room.gender == spec.genderCode &&
                (spec.region == null || countryRegion(room.country) == spec.region) &&
                (spec.tag == null || hasTag(room, spec.tag))
        }

    /** Map a room's ISO country code to one of the addon's regions. */
    private fun countryRegion(country: String?): String {
        if (country.isNullOrBlank()) return "other"
        val code = country.uppercase()
        REGION_COUNTRIES.forEach { (region, set) -> if (code in set) return region }
        return "other"
    }

    /** Case-insensitive match of a genre against a room's tags (addon hasTag). */
    private fun hasTag(room: Room, genre: String): Boolean {
        val wanted = genre.lowercase()
        return room.tags.orEmpty().any { it.lowercase() == wanted }
    }

    // ------------------------------------------------------- search entries

    private fun Room.toSearchResponse(provider: ChaturbateProvider): SearchResponse =
        provider.newLiveSearchResponse(username ?: "", roomUrl(username), TvType.Live) {
            posterUrl = img
        }

    // ------------------------------------------------------- low level fetch

    private suspend fun fetch(url: String): String =
        fetchDirect(wrap(url))

    /** Route a request through the user's proxy when one is configured. */
    private fun wrap(url: String): String {
        val p = Settings.proxy()
        return if (p.isBlank()) url else p + URLEncoder.encode(url, "utf8")
    }

    /** Plain request, never proxied (room pages / the HLS master stay direct). */
    private suspend fun fetchDirect(url: String): String {
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
                    println("Chaturbate GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code} (${res.text.length}B)")
                    return res.text
                }
                if (res.okhttpResponse.code == 429) { // rate limited -> longer pause
                    println("Chaturbate GET ${res.okhttpResponse.request.url} -> 429 (rate limited)")
                    delay(RATE_LIMIT_PAUSE_MS)
                    continue
                }
                println("Chaturbate GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code}")
                return ""
            } catch (e: Exception) {
                lastError = e // network hiccup -> retry
                delay(RETRY_PAUSE_MS * (attempt + 1))
            }
        }
        println("Chaturbate request failed: $lastError")
        return ""
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

    /** Parse the window.initialRoomDossier JSON from a room page. */
    private suspend fun fetchDossier(username: String): RoomDossier? {
        val html = fetchDirect(roomUrl(username))
        if (html.isBlank()) return null
        val match = DOSSIER_REGEX.find(html) ?: return null
        val raw = match.groupValues[1]
        // The captured value is a JS string literal holding a JSON string.
        val inner = tryParseJson<String>("\"$raw\"") ?: return null
        return tryParseJson<RoomDossier>(inner)
    }

    /**
     * Parse an LL-HLS master playlist into its variant chunklists, best quality
     * first. Mirrors parseMasterPlaylist in the working Stremio addon: variant
     * URIs are root-relative paths carrying a ?session= param; we resolve them
     * against the master URL. The #EXT-X-MEDIA audio group is ignored (the
     * variants are video-only, as in the addon).
     */
    private fun parseMasterVariants(master: String, baseUrl: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val lines = master.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF")) { i++; continue }
            val height = RESOLUTION_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull()
            var uri: String? = null
            while (i + 1 < lines.size) {
                i++
                val next = lines[i].trim()
                if (next.isEmpty() || next.startsWith("#")) continue
                uri = next
                break
            }
            if (!uri.isNullOrBlank()) {
                val resolved = runCatching { java.net.URI(baseUrl).resolve(uri).toString() }
                    .getOrNull() ?: uri
                val label = if (height != null) "${height}p" else "Auto"
                out.add(label to resolved)
            }
        }
        return out.sortedByDescending { (label, _) ->
            label.removeSuffix("p").toIntOrNull() ?: 0
        }
    }

    // ------------------------------------------------------ roomlist snapshot

    private suspend fun getRooms(): List<Room> {
        synchronized(roomLock) {
            if (rooms.isNotEmpty() && System.currentTimeMillis() - roomsFetchedAt < CACHE_TTL_MS) {
                return rooms
            }
        }
        var doFetch = false
        val job = synchronized(roomLock) {
            val pending = inflight
            if (pending != null) {
                pending
            } else {
                doFetch = true
                CompletableDeferred<List<Room>>().also { inflight = it }
            }
        }
        if (doFetch) {
            val list = runCatching { fetchAllRooms() }.getOrDefault(emptyList())
            rooms = list
            roomsFetchedAt = System.currentTimeMillis()
            job.complete(list)
            synchronized(roomLock) { if (inflight === job) inflight = null }
        }
        return job.await()
    }

    private fun freshRooms(): List<Room> = synchronized(roomLock) {
        if (rooms.isNotEmpty() && System.currentTimeMillis() - roomsFetchedAt < CACHE_TTL_MS) {
            rooms
        } else {
            emptyList()
        }
    }

    private suspend fun fetchAllRooms(): List<Room> {
        val collected = mutableListOf<Room>()
        var emptyStreak = 0
        for (page in 0 until MAX_PAGES) {
            val data = runCatching {
                fetchJson<RoomlistResponse>(
                    "$ROOMLIST_URL?limit=$PAGE_SIZE&offset=${page * PAGE_SIZE}"
                )
            }.getOrNull()
            val pageRooms = data?.rooms ?: emptyList()
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

    /** Minimum gap between chaturbate.com requests, shared across providers. */
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

    private data class RoomlistResponse(@JsonProperty("rooms") val rooms: List<Room>? = null)

    private data class Room(
        @JsonProperty("username") val username: String? = null,
        @JsonProperty("img") val img: String? = null,
        @JsonProperty("room_subject") val roomSubject: String? = null,
        @JsonProperty("tags") val tags: List<String>? = null,
        @JsonProperty("gender") val gender: String? = null,
        @JsonProperty("country") val country: String? = null,
        @JsonProperty("num_users") val numUsers: Int? = null
    )

    private data class RoomDossier(
        @JsonProperty("broadcaster_username") val username: String? = null,
        @JsonProperty("room_title") val roomTitle: String? = null,
        @JsonProperty("hls_source") val hlsSource: String? = null,
        @JsonProperty("num_viewers") val numViewers: Int? = null
    )

    companion object {
        private const val ROOMLIST_URL = "https://chaturbate.com/api/ts/roomlist/room-list/"
        private const val PAGE_SIZE = 100 // roomlist API caps a page at 100 rooms
        private const val MAX_PAGES = 20 // pages fetched per snapshot (~2000 rooms)
        private const val CACHE_TTL_MS = 90_000L // keep the snapshot this long
        private const val MIN_INTERVAL_MS = 350L // minimum gap between requests
        private const val RATE_LIMIT_PAUSE_MS = 2_500L // on HTTP 429
        private const val RETRY_PAUSE_MS = 800L // between failed attempts

        private const val REFERER = "https://chaturbate.com/"
        private const val USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")

        // Config-page target codes -> roomlist gender codes (s = trans, c = couples).
        private val GENDER_CODES = mapOf("f" to "f", "m" to "m", "t" to "s", "c" to "c")

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

        // Genre (tag) lists per gender, from the addon's manifest extras
        // (lib/config.js GENRES, minus the UI section separators).
        private val GENRES = mapOf(
            "women" to listOf(
                "Teen", "Young", "MILF", "Mature", "Bigboobs", "Bigass", "Hairy", "Latina",
                "BBW", "Squirt", "Skinny", "Smalltits", "Feet", "Fuckmachine"
            ),
            "men" to listOf(
                "Teen", "Young", "DILF", "Mature", "Bigcook", "Cum", "Lovense", "Muscle",
                "Latino", "Hairy", "New", "Feet"
            ),
            "trans" to listOf(
                "Teen", "Young", "ILF", "Mature", "Bigcook", "Smallcook", "Mistress", "Femboy",
                "Partyhouse", "Fuckmachine", "Bigass", "Lovense"
            )
        )

        private val DOSSIER_REGEX = Regex("""window\.initialRoomDossier = "((?:[^"\\]|\\.)*)";""")
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=\d+x(\d+)""")

        private val roomLock = Any()
        private val paceLock = Any()

        @Volatile private var rooms: List<Room> = emptyList()
        @Volatile private var roomsFetchedAt = 0L
        @Volatile private var inflight: CompletableDeferred<List<Room>>? = null
        @Volatile private var lastRequestAt = 0L

        private fun roomUrl(username: String?) =
            "https://chaturbate.com/${URLEncoder.encode(username ?: "", "utf8")}/"
    }
}
