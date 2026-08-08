package com.example.freecams

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
 * CloudStream 3 provider for FreeCams.me live cams.
 *
 * Scrapes www.freecams.me directly from the phone (no addon server), mirroring
 * the logic in bobs/freecams:
 *
 *   * Room list -> /api/ts/roomlist/room-list/?genders=<f|c|m|t>&limit=90
 *                  (JSON + X-Requested-With header), falling back to the
 *                  proxy.rhoulou.com:7676 relay when freecams.me blocks us.
 *   * Room page  -> https://www.freecams.me/<username>/ carries
 *                  window.initialRoomDossier (JSON with \u escapes) whose
 *                  hls_source / hls_url is the HLS stream, passed straight to
 *                  the player. Needs a cf_clearance cookie (Settings) or the
 *                  proxy fallback.
 */
class FreecamsProvider : MainAPI() {
    override var mainUrl = BASE_URL
    override var name = "FreeCams"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var vpnStatus = VPNStatus.MightBeNeeded

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page <= 1) {
            val rows = GENDERS.mapNotNull { (code, label) ->
                val items = getSnapshot(code, code)
                if (items.isEmpty()) null
                else HomePageList(label, items.take(PAGE_SIZE).map { it.toSearchResponse(this) })
            }
            return newHomePageResponse(rows)
        }
        val spec = GENDERS.firstOrNull { it.second == request.name } ?: return null
        val items = getSnapshot(spec.first, spec.first)
        val from = (page - 1) * PAGE_SIZE
        val slice = items.drop(from).take(PAGE_SIZE)
        return newHomePageResponse(
            HomePageList(spec.second, slice.map { it.toSearchResponse(this) }),
            hasNext = from + slice.size < items.size
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        // No server-side search: filter whatever snapshots are already cached.
        val wanted = query.lowercase()
        val matches = mutableListOf<Room>()
        synchronized(snapshotLock) {
            snapshots.values.forEach { snap ->
                snap.rooms.filter { it.username?.lowercase()?.contains(wanted) == true }.forEach {
                    if (matches.none { r -> r.username == it.username }) matches.add(it)
                }
            }
        }
        if (matches.isEmpty()) return null
        return matches.map { it.toSearchResponse(this) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val username = url.trimEnd('/').substringAfterLast('/')
        val room = findRoom(username) ?: return null
        return newLiveStreamLoadResponse(room.username ?: username, url, url) {
            posterUrl = room.img
            plot = room.roomSubject
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val username = data.trimEnd('/').substringAfterLast('/')
        val hls = resolveHls(username) ?: return false
        callback.invoke(
            newExtractorLink(
                source = "FreeCams",
                name = "Auto",
                url = hls,
                type = ExtractorLinkType.M3U8
            ) {
                referer = REFERER
                quality = Qualities.Unknown.value
                headers = mapOf("User-Agent" to ROOM_UA, "Referer" to REFERER)
            }
        )
        return true
    }

    // ------------------------------------------------------- search entries

    private fun Room.toSearchResponse(provider: FreecamsProvider): SearchResponse =
        provider.newLiveSearchResponse(username ?: "", roomUrl(username), TvType.Live) {
            posterUrl = img
        }

    private fun roomUrl(username: String?) = "$BASE_URL/${URLEncoder.encode(username ?: "", "utf8")}/"

    /** Resolve a room across the four gender snapshots. */
    private suspend fun findRoom(username: String): Room? {
        val wanted = username.lowercase()
        for ((code, _) in GENDERS) {
            val found = getSnapshot(code, code).firstOrNull { it.username?.lowercase() == wanted }
            if (found != null) return found
        }
        return null
    }

    // ------------------------------------------------------- snapshots

    private suspend fun getSnapshot(key: String, gender: String): List<Room> {
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
                CompletableDeferred<List<Room>>().also { inflightSnapshots[key] = it }
            }
        }
        if (doFetch) {
            val list = runCatching { fetchRooms(gender) }.getOrDefault(emptyList())
            synchronized(snapshotLock) {
                snapshots[key] = Snapshot(list, System.currentTimeMillis())
                if (inflightSnapshots[key] === job) inflightSnapshots.remove(key)
            }
            job.complete(list)
        }
        return job.await()
    }

    private suspend fun fetchRooms(gender: String): List<Room> {
        val url = "$ROOMLIST_URL?genders=$gender&limit=$LIMIT&offset=0"
        val text = fetch(url, listHeaders()) ?: return emptyList()
        val data = runCatching { tryParseJson<RoomListResponse>(text) }.getOrNull() ?: return emptyList()
        return data.rooms.orEmpty()
    }

    // ------------------------------------------------------- hls resolution

    private suspend fun resolveHls(username: String): String? {
        val url = "$BASE_URL/${URLEncoder.encode(username, "utf8")}/"
        val text = fetch(url, roomHeaders()) ?: return null
        val m = Regex("window\\.initialRoomDossier\\s*=\\s*\"([\\s\\S]*?)\";").find(text)
            ?: return null
        val raw = m.groupValues[1]
        val unescaped = Regex("\\\\u([0-9a-fA-F]{4})").replace(raw) { mm ->
            String(Character.toChars(mm.groupValues[1].toInt(16)))
        }
        val map = runCatching { tryParseJson<Map<String, Any?>>(unescaped) }.getOrNull() ?: return null
        return (map["hls_source"] as? String)?.takeIf { it.isNotBlank() }
            ?: (map["hls_url"] as? String)?.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------- low level fetch

    /** Try the direct/configured proxy first, then the hardcoded fallback relay. */
    private suspend fun fetch(url: String, headers: Map<String, String>): String? {
        val candidates = mutableListOf<String>()
        val configured = Settings.proxy()
        if (configured.isBlank()) candidates.add(url) else candidates.add(configured + URLEncoder.encode(url, "utf8"))
        candidates.add(FALLBACK_PROXY + URLEncoder.encode(url, "utf8"))

        for (target in candidates) {
            try {
                val res = app.get(target, headers = headers)
                if (res.isSuccessful && res.text.isNotBlank() && res.text.length > 64) {
                    println("FreeCams GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code} (${res.text.length}B)")
                    return res.text
                }
            } catch (e: Exception) {
                println("FreeCams fetch failed: $e")
            }
        }
        return null
    }

    private fun listHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "User-Agent" to LIST_UA,
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$REFERER/"
    )

    private fun roomHeaders(): Map<String, String> = buildMap {
        put("User-Agent", ROOM_UA)
        put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        put("Accept-Language", "en-US,en;q=0.9")
        put("Alt-Used", "www.freecams.me")
        put("Upgrade-Insecure-Requests", "1")
        Settings.cookie().takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
    }

    // ------------------------------------------------------- JSON models

    private data class RoomListResponse(@JsonProperty("rooms") val rooms: List<Room>? = null)

    private data class Snapshot(val rooms: List<Room>, val fetchedAt: Long)

    private data class Room(
        @JsonProperty("username") val username: String? = null,
        @JsonProperty("country") val country: String? = null,
        @JsonProperty("num_users") val numUsers: Int? = null,
        @JsonProperty("gender") val gender: String? = null,
        @JsonProperty("img") val img: String? = null,
        @JsonProperty("is_new") val isNew: Boolean? = null,
        @JsonProperty("room_subject") val roomSubject: String? = null
    )

    companion object {
        private const val BASE_URL = "https://www.freecams.me"
        private const val ROOMLIST_URL = "$BASE_URL/api/ts/roomlist/room-list"
        private const val FALLBACK_PROXY = "https://proxy.rhoulou.com:7676/proxy.php?url="

        private const val PAGE_SIZE = 90
        private const val LIMIT = 90
        private const val CACHE_TTL_MS = 60_000L

        private const val REFERER = "https://www.freecams.me"
        private const val LIST_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        private const val ROOM_UA = "Mozilla/5.0 (X11; Linux x86_64; rv:150.0) Gecko/20100101 Firefox/150.0"

        // gender code -> home row label (bobs/freecams/viewer.php dropdown).
        private val GENDERS = listOf(
            "f" to "Female",
            "c" to "Couples",
            "m" to "Male",
            "t" to "Trans"
        )

        private val snapshotLock = Any()

        private val snapshots = HashMap<String, Snapshot>()
        private val inflightSnapshots = HashMap<String, CompletableDeferred<List<Room>>>()
    }
}
