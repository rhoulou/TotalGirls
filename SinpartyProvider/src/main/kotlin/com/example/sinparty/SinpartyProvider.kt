package com.example.sinparty

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
 * CloudStream 3 provider for Sinparty live cams.
 *
 * Scrapes api.sinparty.com directly from the phone (no addon server), mirroring
 * the logic in bobs/sinparty:
 *
 *   * Room list -> https://api.sinparty.com/v2/web/live-cams/web-rtc with
 *                  so=has_straight&per_page=100000&gender[]=f plus optional
 *                  category/gender/ethnicity/age/body/hair/is_new filters.
 *                  Returns data.items[] with slug, creator_user_hash, Snapshot,
 *                  viewers, playback_url, live_url.
 *   * HLS, in order:
 *        1. the model's playback_url, if present
 *        2. the per-model API .../web-rtc/<user_hash> -> data.playback_url
 *        3. the Streamate manifest server (aggregated models)
 *           https://manifest-server.naiadsystems.com/live/s:<username>
 *        4. scraping https://sinparty.com/<username> for initialRoomDossier or
 *           a .m3u8 URL (preferring icfrooms/streamparty hosts), with proxy relay
 *           fallback when sinparty.com blocks direct requests.
 */
class SinpartyProvider : MainAPI() {
    override var mainUrl = "https://sinparty.com"
    override var name = "Sinparty"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var vpnStatus = VPNStatus.MightBeNeeded

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page <= 1) {
            val rows = ROWS.mapNotNull { spec ->
                val items = getSnapshot(spec.name, spec.name)
                if (items.isEmpty()) null
                else HomePageList(spec.label, items.take(PAGE_SIZE).map { it.toSearchResponse(this) })
            }
            return newHomePageResponse(rows)
        }
        val spec = ROWS.firstOrNull { it.name == request.name } ?: return null
        val items = getSnapshot(spec.name, spec.name)
        val from = (page - 1) * PAGE_SIZE
        val slice = items.drop(from).take(PAGE_SIZE)
        return newHomePageResponse(
            HomePageList(spec.label, slice.map { it.toSearchResponse(this) }),
            hasNext = from + slice.size < items.size
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        // No server-side search: filter whatever snapshots are already cached.
        val wanted = query.lowercase()
        val matches = mutableListOf<Model>()
        synchronized(snapshotLock) {
            snapshots.values.forEach { snap ->
                snap.items.filter { it.username().lowercase().contains(wanted) }.forEach {
                    if (matches.none { m -> m.username() == it.username() }) matches.add(it)
                }
            }
        }
        if (matches.isEmpty()) return null
        return matches.map { it.toSearchResponse(this) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.trimEnd('/').substringAfterLast('/')
        val model = findModel(slug) ?: return null
        return newLiveStreamLoadResponse(model.title ?: slug, url, url) {
            posterUrl = model.posterUrl()
            plot = buildString {
                model.viewers?.let { append("Watching: ").append(it) }
                if (model.isNew == true) {
                    if (isNotEmpty()) append("\n\n")
                    append("New model")
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug = data.trimEnd('/').substringAfterLast('/')
        val model = findModel(slug) ?: return false
        val hls = resolveHls(model) ?: return false
        callback.invoke(
            newExtractorLink(
                source = "Sinparty",
                name = "Auto",
                url = hls,
                type = ExtractorLinkType.M3U8
            ) {
                referer = "https://sinparty.com/"
                quality = Qualities.Unknown.value
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://sinparty.com/")
            }
        )
        return true
    }

    // ------------------------------------------------------- search entries

    private fun Model.toSearchResponse(provider: SinpartyProvider): SearchResponse =
        provider.newLiveSearchResponse(title ?: username(), profileUrl(), TvType.Live) {
            posterUrl = posterUrl()
        }

    private fun findModel(slug: String): Model? {
        val wanted = slug.lowercase()
        synchronized(snapshotLock) {
            for (snap in snapshots.values) {
                val found = snap.items.firstOrNull { it.username().lowercase() == wanted }
                if (found != null) return found
            }
        }
        return null
    }

    // ------------------------------------------------------- snapshots

    private suspend fun getSnapshot(key: String, rowName: String): List<Model> {
        synchronized(snapshotLock) {
            val s = snapshots[key]
            if (s != null && System.currentTimeMillis() - s.fetchedAt < CACHE_TTL_MS) return s.items
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
            val list = runCatching { fetchModels(rowName) }.getOrDefault(emptyList())
            synchronized(snapshotLock) {
                snapshots[key] = Snapshot(list, System.currentTimeMillis())
                if (inflightSnapshots[key] === job) inflightSnapshots.remove(key)
            }
            job.complete(list)
        }
        return job.await()
    }

    private suspend fun fetchModels(rowName: String): List<Model> {
        val spec = ROWS.firstOrNull { it.name == rowName } ?: return emptyList()
        val url = "$API_BASE_URL/v2/web/live-cams/web-rtc?${spec.query()}"
        val text = fetchApi(url) ?: return emptyList()
        val data = tryParseJson<ListResponse>(text) ?: return emptyList()
        return data.data?.items.orEmpty()
    }

    // ------------------------------------------------------- hls resolution

    private suspend fun resolveHls(model: Model): String? {
        // 1. playback_url provided by the listing.
        model.playbackUrl?.takeIf { it.isNotBlank() }?.let { return it }

        // 2. Per-model API by user hash.
        val hash = model.userHash
        if (!hash.isNullOrBlank() && hash.toIntOrNull() == null) {
            val url = "$API_BASE_URL/v2/web/live-cams/web-rtc/${URLEncoder.encode(hash, "utf8")}"
            val text = fetchApi(url)
            if (text != null) {
                val p = tryParseJson<HashResponse>(text)?.data?.playbackUrl
                if (!p.isNullOrBlank()) return p
            }
        }

        // 3. Streamate manifest server (aggregated models).
        val username = model.username()
        if (username.isNotBlank()) {
            val text = fetchApi("https://manifest-server.naiadsystems.com/live/s:${URLEncoder.encode(username, "utf8")}")
            if (text != null) {
                val manifest = tryParseJson<StreamateResponse>(text)?.formats
                val hls = manifest?.get("mp4-hls")?.manifest
                    ?: manifest?.get("hls")?.manifest
                    ?: manifest?.get("mp4-rtmp")?.encodings?.firstOrNull()?.location
                if (!hls.isNullOrBlank()) return hls
            }
        }

        // 4. Scrape the sinparty profile page.
        return scrapeHls("https://sinparty.com/" + username.lowercase())
    }

    private suspend fun scrapeHls(url: String): String? {
        val html = fetchScrape(url) ?: return null

        // initialRoomDossier (JSON with \u escapes).
        Regex("window\\.initialRoomDossier\\s*=\\s*\"([\\s\\S]*?)\";").find(html)?.let { m ->
            val raw = m.groupValues[1]
            val unescaped = Regex("\\\\u([0-9a-fA-F]{4})").replace(raw) { mm ->
                String(Character.toChars(mm.groupValues[1].toInt(16)))
            }
            val map = runCatching { tryParseJson<Map<String, Any?>>(unescaped) }.getOrNull()
            if (map != null) {
                (map["hls_source"] as? String)?.takeIf { it.isNotBlank() }?.let { return it }
                (map["hls_url"] as? String)?.takeIf { it.isNotBlank() }?.let { return it }
                (map["hls_playlist"] as? String)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }

        // Any .m3u8 URL, preferring icfrooms/streamparty hosts.
        val m3u8 = Regex("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*").findAll(html).map {
            it.value.replace("\\/", "/").replace("\\u0026", "&").replace("\\u0026amp;", "&")
        }.toList()
        if (m3u8.isNotEmpty()) {
            return m3u8.firstOrNull { it.contains("icfrooms") || it.contains("streamparty") } ?: m3u8.first()
        }
        return null
    }

    // ------------------------------------------------------- low level fetch

    private suspend fun fetchApi(url: String): String? {
        val target = wrap(url)
        for (attempt in 0 until 3) {
            try {
                val res = app.get(
                    target,
                    headers = mapOf(
                        "Accept" to "application/json, text/plain, */*",
                        "User-Agent" to USER_AGENT,
                        "Referer" to "https://sinparty.com/",
                        "Origin" to "https://sinparty.com"
                    )
                )
                if (res.isSuccessful && res.text.isNotBlank()) {
                    println("Sinparty GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code} (${res.text.length}B)")
                    return res.text
                }
            } catch (e: Exception) {
                println("Sinparty request failed (attempt $attempt): $e")
            }
            delay(RETRY_PAUSE_MS * (attempt + 1))
        }
        return null
    }

    /** Scrape with direct/configured proxy, then the hardcoded fallback relay. */
    private suspend fun fetchScrape(url: String): String? {
        val candidates = mutableListOf<String>()
        val configured = Settings.proxy()
        if (configured.isBlank()) candidates.add(url) else candidates.add(configured + URLEncoder.encode(url, "utf8"))
        candidates.add(FALLBACK_PROXY + URLEncoder.encode(url, "utf8"))

        for (target in candidates) {
            try {
                val res = app.get(
                    target,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "https://sinparty.com/",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.5"
                    )
                )
                if (res.isSuccessful && res.text.isNotBlank() && res.text.length > 1000) {
                    println("Sinparty scrape ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code} (${res.text.length}B)")
                    return res.text
                }
            } catch (e: Exception) {
                println("Sinparty scrape failed: $e")
            }
        }
        return null
    }

    /** Route a request through the user's proxy when one is configured. */
    private fun wrap(url: String): String {
        val p = Settings.proxy()
        return if (p.isBlank()) url else p + URLEncoder.encode(url, "utf8")
    }

    // ------------------------------------------------------- JSON models

    private data class ListResponse(@JsonProperty("data") val data: Data? = null)

    private data class Data(@JsonProperty("items") val items: List<Model>? = null)

    private data class HashResponse(@JsonProperty("data") val data: HashData? = null)

    private data class HashData(@JsonProperty("playback_url") val playbackUrl: String? = null)

    private data class StreamateResponse(@JsonProperty("formats") val formats: Map<String, StreamateFormat>? = null)

    private data class StreamateFormat(
        @JsonProperty("manifest") val manifest: String? = null,
        @JsonProperty("encodings") val encodings: List<StreamateEncoding>? = null
    )

    private data class StreamateEncoding(@JsonProperty("location") val location: String? = null)

    private data class Snapshot(val items: List<Model>, val fetchedAt: Long)

    private data class Model(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("creator_user_hash") val userHash: String? = null,
        @JsonProperty("Snapshot") val snapshot: String? = null,
        @JsonProperty("Thumbnail") val thumbnail: String? = null,
        @JsonProperty("thumbnail_url") val thumbnailUrl: String? = null,
        @JsonProperty("country") val country: String? = null,
        @JsonProperty("viewers") val viewers: Int? = null,
        @JsonProperty("is_new") val isNew: Boolean? = null,
        @JsonProperty("gender") val gender: String? = null,
        @JsonProperty("playback_url") val playbackUrl: String? = null,
        @JsonProperty("live_url") val liveUrl: String? = null
    ) {
        fun username(): String = slug ?: ""

        fun posterUrl(): String? =
            snapshot?.takeIf { it.isNotBlank() }
                ?: thumbnail?.takeIf { it.isNotBlank() }
                ?: thumbnailUrl?.takeIf { it.isNotBlank() }

        fun profileUrl(): String =
            liveUrl?.let { "https://sinparty.com$it" }
                ?: "https://sinparty.com/live/${username().lowercase()}"
    }

    companion object {
        private const val API_BASE_URL = "https://api.sinparty.com"
        private const val FALLBACK_PROXY = "https://proxy.rhoulou.com:7676/proxy.php?url="

        private const val PAGE_SIZE = 100
        private const val CACHE_TTL_MS = 90_000L
        private const val RETRY_PAUSE_MS = 800L

        private const val USER_AGENT = ("Mozilla/5.0 (X11; Linux x86_64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")

        // Row name -> query string (bobs/sinparty/fetcher.php filters).
        private data class RowSpec(val name: String, val label: String, val extra: String)

        private val ROWS = listOf(
            RowSpec("All Girls", "All Girls", ""),
            RowSpec("Couples", "Couples", "&category[]=couples"),
            RowSpec("Guys", "Guys", "&gender[]=m"),
            RowSpec("Trans", "Trans", "&gender[]=trans"),
            RowSpec("Asian", "Asian", "&ethnicity[]=asian"),
            RowSpec("Latina", "Latina", "&ethnicity[]=hispanic"),
            RowSpec("Ebony", "Ebony", "&ethnicity[]=ebony"),
            RowSpec("White", "White", "&ethnicity[]=white"),
            RowSpec("European", "European", "&ethnicity[]=european"),
            RowSpec("18-19", "18-19", "&age[]=18-19"),
            RowSpec("18-21", "18-21", "&age[]=18-21"),
            RowSpec("18-24", "18-24", "&age[]=18-24"),
            RowSpec("18-29", "18-29", "&age[]=18-29"),
            RowSpec("30-39", "30-39", "&age[]=30-39"),
            RowSpec("40+", "40+", "&age[]=40"),
            RowSpec("BBW", "BBW", "&body[]=bbw"),
            RowSpec("Petite", "Petite", "&body[]=petite"),
            RowSpec("Athletic", "Athletic", "&body[]=athletic"),
            RowSpec("Curvy", "Curvy", "&body[]=curvaceous"),
            RowSpec("Blonde", "Blonde", "&hair[]=blonde"),
            RowSpec("Brunette", "Brunette", "&hair[]=brown"),
            RowSpec("Redhead", "Redhead", "&hair[]=red"),
            RowSpec("New", "New", "&is_new=true")
        )

        private fun RowSpec.query(): String =
            "so=has_straight&per_page=100000&gender[]=f$extra"

        private val snapshotLock = Any()

        private val snapshots = HashMap<String, Snapshot>()
        private val inflightSnapshots = HashMap<String, CompletableDeferred<List<Model>>>()
    }
}
