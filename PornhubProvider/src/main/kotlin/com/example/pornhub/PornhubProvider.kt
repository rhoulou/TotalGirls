package com.example.pornhub

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
 * CloudStream 3 provider for Pornhub videos.
 *
 * Scrapes www.pornhub.com directly from the phone (no addon server):
 *
 *   * Listing  -> /video, /video/trending, /video/hot, /video?hd=1,
 *                 /video?o=lg, /video?o=mv and /video/search?search=<query>
 *                 cards carry href="/view_video.php?viewkey=..." anchors.
 *   * Video    -> /view_video.php?viewkey=<vk> embeds a "mediaDefinitions"
 *                 JSON array (HLS .m3u8 masters per quality, plus an mp4
 *                 fallback) that is passed straight to the player.
 *
 * Pornhub occasionally serves a tiny JS-proof-of-work challenge page instead
 * of the real listing, so fetches are retried with a short delay and rows are
 * paced to avoid tripping the rate limiter.
 */
class PornhubProvider : MainAPI() {
    override var mainUrl = BASE_URL
    override var name = "Pornhub"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true
    override var vpnStatus = VPNStatus.MightBeNeeded

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page <= 1) {
            val rows = mutableListOf<HomePageList>()
            for ((i, row) in ROWS.withIndex()) {
                val (path, label) = row
                val items = parsePage("$BASE_URL$path")
                if (items.isNotEmpty()) {
                    rows.add(HomePageList(label, items.take(PAGE_SIZE).map { it.toSearchResponse(this) }))
                }
                if (i < ROWS.lastIndex) delay(ROW_PACE_MS)
            }
            return newHomePageResponse(rows)
        }
        val spec = ROWS.firstOrNull { it.second == request.name } ?: return null
        val items = parsePage("$BASE_URL${pageUrl(spec.first, page)}")
        if (items.isEmpty()) return null
        return newHomePageResponse(
            HomePageList(spec.second, items.map { it.toSearchResponse(this) })
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val items = parsePage("$BASE_URL/video/search?search=${URLEncoder.encode(query, "utf8")}")
        if (items.isEmpty()) return null
        return items.map { it.toSearchResponse(this) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val viewkey = viewkeyOf(url) ?: return null
        val html = fetchWithRetry("$BASE_URL/view_video.php?viewkey=$viewkey")
        val title = html?.let { unescape(meta(it, "og:title") ?: "") }
            ?.takeIf { it.isNotBlank() } ?: viewkey
        val poster = html?.let { posterFrom(it) }
        val duration = html?.let { parseDuration(it) }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            plot = title
            this.duration = duration
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val viewkey = viewkeyOf(data) ?: return false
        val html = fetchWithRetry("$BASE_URL/view_video.php?viewkey=$viewkey") ?: return false
        val raw = extractJsonArray(html, "mediaDefinitions") ?: return false
        val defs = runCatching {
            tryParseJson<List<MediaDefinition>>(raw.replace("&amp;", "&"))
        }.getOrNull() ?: return false

        val hls = defs.filter { it.format == "hls" && !it.videoUrl.isNullOrBlank() }
            .sortedByDescending { it.qualityNum() }
            .distinctBy { it.videoUrl }
        val mp4 = defs.filter { it.format == "mp4" && !it.videoUrl.isNullOrBlank() }
            .sortedByDescending { it.qualityNum() }
            .firstOrNull()

        if (hls.isEmpty() && mp4 == null) return false

        hls.forEach { def ->
            callback.invoke(
                newExtractorLink(
                    source = "Pornhub",
                    name = def.label(),
                    url = def.videoUrl.orEmpty(),
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = "$BASE_URL/"
                    quality = def.qualityNum()
                    headers = linkHeaders()
                }
            )
        }
        mp4?.let {
            callback.invoke(
                newExtractorLink(
                    source = "Pornhub",
                    name = "MP4 ${it.label()}",
                    url = it.videoUrl.orEmpty(),
                    type = ExtractorLinkType.VIDEO
                ) {
                    referer = "$BASE_URL/"
                    quality = it.qualityNum()
                    headers = linkHeaders()
                }
            )
        }
        return true
    }

    // ------------------------------------------------------- listing parsing

    /** Fetch a listing page, retrying when Pornhub serves a challenge page. */
    private suspend fun parsePage(url: String): List<VideoCard> {
        repeat(MAX_ATTEMPTS) { attempt ->
            val html = fetch(url)
            if (html != null) {
                val cards = parseCards(html)
                if (cards.isNotEmpty()) return cards
            }
            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
        return emptyList()
    }

    private fun parseCards(html: String): List<VideoCard> {
        val anchor = Regex(
            """<a[^>]*?href="/view_video\.php\?viewkey=([a-zA-Z0-9]+)"[^>]*?title="([^"]*)"[^>]*?>[\s\S]{0,6000}?</a>"""
        )
        val poster = Regex("""<(?:img|div)[^>]*?(?:data-src|data-background-image|src)="([^"]*)"""")
        // Each card has two anchors (title-only + thumbnail-with-img); the first
        // match wins for the title and a later match fills the poster.
        val found = LinkedHashMap<String, VideoCard>()
        for (m in anchor.findAll(html)) {
            val vk = m.groupValues[1]
            val title = unescape(m.groupValues[2]).trim()
            val img = poster.find(m.groupValues[0])?.groupValues?.get(1)
                ?.takeIf { it.startsWith("http") }
            val existing = found[vk]
            if (existing == null) {
                found[vk] = VideoCard(vk, title, img)
            } else if (existing.poster == null && img != null) {
                found[vk] = existing.copy(poster = img)
            }
        }
        return found.values.toList()
    }

    private fun VideoCard.toSearchResponse(provider: PornhubProvider): SearchResponse =
        provider.newMovieSearchResponse(title, "$BASE_URL/view_video.php?viewkey=$viewkey", TvType.NSFW) {
            posterUrl = poster
        }

    // ------------------------------------------------------- video page

    private fun meta(html: String, property: String): String? {
        val tag = Regex("""<meta[^>]*?(?:property|name)="$property"[^>]*?>""").find(html)?.value
            ?: return null
        return Regex("""content="([^"]*)"""").find(tag)?.groupValues?.get(1)?.let { unescape(it) }
    }

    /** Poster for the player page: og:image, then twitter:image, then image_src. */
    private fun posterFrom(html: String): String? {
        meta(html, "og:image")?.takeIf { it.isNotBlank() }?.let { return it }
        meta(html, "twitter:image")?.takeIf { it.isNotBlank() }?.let { return it }
        return Regex("""<link[^>]*?rel="image_src"[^>]*?href="([^"]*)"""").find(html)
            ?.groupValues?.get(1)
    }

    private fun parseDuration(html: String): Int? =
        meta(html, "og:video:duration")?.trim()?.toIntOrNull()

    // ------------------------------------------------------- helpers

    private fun viewkeyOf(url: String): String? =
        url.substringAfter("viewkey=", "").substringBefore('&').takeIf { it.isNotBlank() }

    private fun pageUrl(path: String, page: Int) =
        "$path${if ('?' in path) '&' else '?'}page=$page"

    /** Fetch a video page, retrying when Pornhub serves a challenge page. */
    private suspend fun fetchWithRetry(url: String): String? {
        repeat(MAX_ATTEMPTS) { attempt ->
            val html = fetch(url)
            // Real pages are hundreds of KB; the JS challenge is ~1.5KB.
            if (html != null && html.length > MIN_PAGE_BYTES) return html
            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
        return null
    }

    /**
     * Extract a balanced JSON array for [key] (e.g. "mediaDefinitions"). A
     * plain non-greedy regex is not enough: entries can contain empty arrays
     * ("quality":[]) whose closing bracket would truncate the match.
     */
    private fun extractJsonArray(html: String, key: String): String? {
        val start = html.indexOf("\"$key\":")
        if (start < 0) return null
        val open = html.indexOf('[', start)
        if (open < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in open until html.length) {
            val c = html[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else {
                    when (c) {
                        '\\' -> escaped = true
                        '"' -> inString = false
                    }
                }
            } else {
                when (c) {
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return html.substring(open, i + 1)
                    }
                    '"' -> inString = true
                }
            }
        }
        return null
    }

    private fun unescape(s: String): String = s
        .replace("&amp;", "&")
        .replace("&#039;", "'")
        .replace("&#x27;", "'")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    // ------------------------------------------------------- low level fetch

    /** Try direct (or the user-configured proxy) first. */
    private suspend fun fetch(url: String): String? {
        val candidates = mutableListOf<String>()
        val configured = Settings.proxy()
        if (configured.isBlank()) candidates.add(url)
        else candidates.add(configured + URLEncoder.encode(url, "utf8"))

        for (target in candidates) {
            try {
                val res = app.get(target, headers = headers())
                if (res.isSuccessful && res.text.isNotBlank() && res.text.length > 64) {
                    println("Pornhub GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code} (${res.text.length}B)")
                    return res.text
                }
            } catch (e: Exception) {
                println("Pornhub fetch failed: $e")
            }
        }
        return null
    }

    private fun headers(): Map<String, String> = buildMap {
        put("User-Agent", UA)
        put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        put("Accept-Language", "en-US,en;q=0.9")
        put("Upgrade-Insecure-Requests", "1")
        Settings.cookie().takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
    }

    private fun linkHeaders(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Referer" to "$BASE_URL/",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // ------------------------------------------------------- models

    private data class VideoCard(val viewkey: String, val title: String, val poster: String?)

    private data class MediaDefinition(
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("quality") val quality: Any? = null,
        @JsonProperty("qualityLabel") val qualityLabel: String? = null,
        @JsonProperty("height") val height: Int? = null,
        @JsonProperty("defaultQuality") val defaultQuality: Boolean? = null,
        @JsonProperty("videoUrl") val videoUrl: String? = null
    ) {
        fun label(): String {
            qualityLabel?.takeIf { it.isNotBlank() }?.let { return it }
            val q = (quality as? String)?.takeIf { it.isNotBlank() }
            if (q != null) return q.let { if (it.all(Char::isDigit)) "${it}P" else it }
            height?.let { return "${it}P" }
            return "Auto"
        }

        fun qualityNum(): Int {
            qualityLabel?.filter(Char::isDigit)?.toIntOrNull()?.let { return it }
            ((quality as? String)?.toIntOrNull())?.let { return it }
            height?.let { return it }
            return Qualities.Unknown.value
        }
    }

    companion object {
        private const val BASE_URL = "https://www.pornhub.com"

        private const val PAGE_SIZE = 30
        private const val MAX_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 800L
        private const val ROW_PACE_MS = 600L
        private const val MIN_PAGE_BYTES = 5_000
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val ROWS = listOf(
            "/video" to "Latest",
            "/video/trending" to "Trending",
            "/video/hot" to "Hot",
            "/video?hd=1" to "HD",
            "/video?o=lg" to "Longest",
            "/video?o=mv" to "Most Viewed"
        )
    }
}
