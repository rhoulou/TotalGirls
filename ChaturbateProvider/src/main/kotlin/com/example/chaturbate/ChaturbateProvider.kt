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
 *   * Master HLS -> passed straight to the player (ExoPlayer resolves the
 *                   LL-HLS master and its ?session= variant chunklists)
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
        val code = GENDER_CODES[gender] ?: gender
        val filtered = getRooms().filter { it.gender == code }
        val from = (page - 1) * PAGE_SIZE
        val slice = filtered.drop(from).take(PAGE_SIZE)
        return newHomePageResponse(
            HomePageList(request.name, slice.map { it.toSearchResponse(this) }),
            hasNext = from + slice.size < filtered.size
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
        callback.invoke(
            newExtractorLink(
                source = "Chaturbate",
                name = "Chaturbate",
                url = hlsSource,
                type = ExtractorLinkType.M3U8
            ) {
                referer = REFERER
                quality = Qualities.Unknown.value
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to REFERER)
            }
        )
        return true
    }

    // ------------------------------------------------------- search entries

    private fun Room.toSearchResponse(provider: ChaturbateProvider): SearchResponse =
        provider.newLiveSearchResponse(username ?: "", roomUrl(username), TvType.Live) {
            posterUrl = img
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
                        "Accept" to "application/json, text/plain, */*",
                        "Accept-Language" to "en-US,en;q=0.9"
                    )
                )
                if (res.isSuccessful) return res.text
                if (res.okhttpResponse.code == 429) { // rate limited -> longer pause
                    delay(RATE_LIMIT_PAUSE_MS)
                    continue
                }
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
        val html = fetch(roomUrl(username))
        if (html.isBlank()) return null
        val match = DOSSIER_REGEX.find(html) ?: return null
        val raw = match.groupValues[1]
        // The captured value is a JS string literal holding a JSON string.
        val inner = tryParseJson<String>("\"$raw\"") ?: return null
        return tryParseJson<RoomDossier>(inner)
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

        // Config-page target codes -> roomlist gender codes (s = trans).
        private val GENDER_CODES = mapOf("f" to "f", "m" to "m", "t" to "s")

        private val DOSSIER_REGEX = Regex("""window\.initialRoomDossier = "((?:[^"\\]|\\.)*)";""")

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
