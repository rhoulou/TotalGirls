package com.example.pornhub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

/**
 * CloudStream 3 provider for Pornhub videos.
 *
 * Scrapes www.pornhub.com directly from the phone (no addon server):
 *
 *   * Listing  -> /video (Latest), /video/trending, /video/hot and
 *                 /video/search?search=<query> cards carry
 *                 href="/view_video.php?viewkey=..." anchors.
 *   * Video    -> /view_video.php?viewkey=<vk> embeds a "mediaDefinitions"
 *                 JSON array (HLS .m3u8 masters per quality, plus an mp4
 *                 fallback) that is passed straight to the player.
 *
 * Pornhub blocks datacenter IPs, so a direct fetch falls back to the
 * proxy.rhoulou.com relay (same as the other providers in this repo).
 */
class PornhubProvider : MainAPI() {
    override var mainUrl = BASE_URL
    override var name = "Pornhub"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true
    override var vpnStatus = VPNStatus.MightBeNeeded

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page <= 1) {
            val rows = ROWS.mapNotNull { (path, label) ->
                val items = parsePage("$BASE_URL$path")
                if (items.isEmpty()) null
                else HomePageList(label, items.take(PAGE_SIZE).map { it.toSearchResponse(this) })
            }
            return newHomePageResponse(rows)
        }
        val spec = ROWS.firstOrNull { it.second == request.name } ?: return null
        val items = parsePage("$BASE_URL${spec.first}?page=$page")
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
        val html = fetch("$BASE_URL/view_video.php?viewkey=$viewkey")
        val title = html?.let { unescape(meta(it, "og:title") ?: "") }
            ?.takeIf { it.isNotBlank() } ?: viewkey
        val poster = html?.let { meta(it, "og:image") }
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
        val html = fetch("$BASE_URL/view_video.php?viewkey=$viewkey") ?: return false
        val raw = Regex("\"mediaDefinitions\":(\\[.*?\\])", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1) ?: return false
        val defs = runCatching {
            tryParseJson<List<MediaDefinition>>(raw.replace("&amp;", "&"))
        }.getOrNull() ?: return false

        val hls = defs.filter { it.format == "hls" && !it.videoUrl.isNullOrBlank() }
            .sortedByDescending { it.height ?: 0 }
            .distinctBy { it.videoUrl }
        val mp4 = defs.filter { it.format == "mp4" && !it.videoUrl.isNullOrBlank() }
            .sortedByDescending { it.height ?: 0 }
            .firstOrNull()

        if (hls.isEmpty() && mp4 == null) return false

        hls.forEach { def ->
            val label = def.quality?.takeIf { it.isNotBlank() }
                ?: def.height?.let { "${it}p" }
                ?: "Auto"
            callback.invoke(
                newExtractorLink(
                    source = "Pornhub",
                    name = label,
                    url = def.videoUrl.orEmpty(),
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = "$BASE_URL/"
                    quality = def.height ?: Qualities.Unknown.value
                    headers = linkHeaders()
                }
            )
        }
        mp4?.let {
            callback.invoke(
                newExtractorLink(
                    source = "Pornhub",
                    name = "MP4 ${it.height ?: ""}".trim(),
                    url = it.videoUrl.orEmpty(),
                    type = ExtractorLinkType.VIDEO
                ) {
                    referer = "$BASE_URL/"
                    quality = it.height ?: Qualities.Unknown.value
                    headers = linkHeaders()
                }
            )
        }
        return true
    }

    // ------------------------------------------------------- listing parsing

    private suspend fun parsePage(url: String): List<VideoCard> {
        val html = fetch(url) ?: return emptyList()
        val anchor = Regex(
            """<a[^>]*?href="/view_video\.php\?viewkey=([a-zA-Z0-9]+)"[^>]*?title="([^"]*)"[^>]*?>[\s\S]{0,1600}?</a>"""
        )
        val poster = Regex("""<(?:img|div)[^>]*?(?:data-src|data-background-image|src)="([^"]*)"""")
        val cards = mutableListOf<VideoCard>()
        for (m in anchor.findAll(html)) {
            val vk = m.groupValues[1]
            if (cards.any { it.viewkey == vk }) continue
            val title = unescape(m.groupValues[2]).trim()
            val img = poster.find(m.groupValues[0])?.groupValues?.get(1)
                ?.takeIf { it.startsWith("http") }
            cards.add(VideoCard(vk, title, img))
        }
        return cards
    }

    private fun VideoCard.toSearchResponse(provider: PornhubProvider): SearchResponse =
        provider.newMovieSearchResponse(title, "$BASE_URL/view_video.php?viewkey=$viewkey", TvType.NSFW) {
            posterUrl = poster
        }

    // ------------------------------------------------------- video page

    private fun meta(html: String, property: String): String? {
        val tag = Regex("""<meta[^>]*?property="$property"[^>]*?>""").find(html)?.value
            ?: return null
        return Regex("""content="([^"]*)"""").find(tag)?.groupValues?.get(1)?.let { unescape(it) }
    }

    private fun parseDuration(html: String): Int? =
        meta(html, "og:video:duration")?.trim()?.toIntOrNull()

    // ------------------------------------------------------- helpers

    private fun viewkeyOf(url: String): String? =
        url.substringAfter("viewkey=", "").substringBefore('&').takeIf { it.isNotBlank() }

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

    /** Try the direct/configured proxy first, then the hardcoded fallback relay. */
    private suspend fun fetch(url: String): String? {
        val candidates = mutableListOf<String>()
        val configured = Settings.proxy()
        if (configured.isBlank()) candidates.add(url)
        else candidates.add(configured + URLEncoder.encode(url, "utf8"))
        candidates.add(FALLBACK_PROXY + URLEncoder.encode(url, "utf8"))

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
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("height") val height: Int? = null,
        @JsonProperty("defaultQuality") val defaultQuality: Boolean? = null,
        @JsonProperty("videoUrl") val videoUrl: String? = null
    )

    companion object {
        private const val BASE_URL = "https://www.pornhub.com"
        private const val FALLBACK_PROXY = "https://proxy.rhoulou.com:7676/proxy.php?url="

        private const val PAGE_SIZE = 30
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val ROWS = listOf(
            "/video" to "Latest",
            "/video/trending" to "Trending",
            "/video/hot" to "Hot"
        )
    }
}
