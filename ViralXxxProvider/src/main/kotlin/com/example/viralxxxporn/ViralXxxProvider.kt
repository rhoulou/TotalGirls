package com.example.viralxxxporn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * CloudStream 3 provider for ViralXXXPorn (https://viralxxxporn.com).
 *
 * Server-rendered tube with the same "vx-" template as the CoomerVideo mirror:
 *  - Models   -> /models/ cards (a.vx-name[href*='/models/']), paginated /models/N/.
 *                Each model page is a TvSeries whose episodes are the model's
 *                videos (a.vx-media[href*='/video/']), paginated
 *                /models/<slug>/N/, capped to a few hundred episodes.
 *  - Latest   -> /latest-updates/ video cards, paginated /latest-updates/N/.
 *  - Category -> /categories/ cards (a.vx-name[href*='/categories/']), paginated
 *                /categories/N/; a category page is a video list paginated
 *                /categories/<slug>/N/.
 *  - Video    -> the page embeds a `flashvars` JS block with direct quality
 *                MP4s (video_url=480p, video_alt_url=720p, video_alt_url2=1080p)
 *                signed with a v-acctoken. loadLinks hands them straight to the
 *                player as VIDEO links with a site referer.
 *
 * Search hits /search/<q>/ (video cards). Every fetch is logged and guarded
 * against anti-bot pages, mirroring the Coomer provider.
 */
class ViralXxxProvider : MainAPI() {
    override var mainUrl = "https://viralxxxporn.com"
    override var name = "ViralXXXPorn"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    override var getMainPageTimeoutMs: Long? = 60_000L

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page <= 1) {
            val rows = mutableListOf<HomePageList>()
            val latest = runCatching { scrapeVideoCards("$BASE/latest-updates/") }.getOrElse {
                println("ViralXxx getMainPage(latest) failed: $it")
                emptyList()
            }
            if (latest.isNotEmpty()) {
                rows.add(HomePageList("Latest Videos", latest.map { it.toSearchResponse() }))
            }
            val models = runCatching { scrapeModels("$BASE/models/") }.getOrElse {
                println("ViralXxx getMainPage(models) failed: $it")
                emptyList()
            }
            if (models.isNotEmpty()) {
                rows.add(HomePageList("Top Models", models.take(24).map { it.toSearchResponse() }))
            }
            val cats = runCatching { scrapeCategories("$BASE/categories/") }.getOrElse {
                println("ViralXxx getMainPage(categories) failed: $it")
                emptyList()
            }
            if (cats.isNotEmpty()) {
                rows.add(HomePageList("Categories", cats.take(24).map { it.toSearchResponse() }))
            }
            if (rows.isEmpty()) return null
            return newHomePageResponse(rows)
        }
        val row = when (request.name) {
            "Latest Videos" -> RowSpec("$BASE/latest-updates/$page/", "latest")
            "Top Models" -> RowSpec("$BASE/models/$page/", "models")
            "Categories" -> RowSpec("$BASE/categories/$page/", "categories")
            else -> return null
        }
        val items = when (row.kind) {
            "models" -> scrapeModels(row.url)
            "categories" -> scrapeCategories(row.url)
            else -> scrapeVideoCards(row.url)
        }
        if (items.isEmpty()) return null
        return newHomePageResponse(
            HomePageList(request.name, items.map { it.toSearchResponse() }),
            hasNext = items.size >= PAGE_SIZE
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim()
        if (q.isBlank()) return null
        val cards = runCatching {
            scrapeVideoCards("$BASE/search/${urlEncode(q)}/")
        }.getOrElse {
            println("ViralXxx search failed: $it")
            emptyList()
        }
        if (cards.isEmpty()) return null
        return cards.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val normalized = if (url.endsWith("/")) url else "$url/"
        return when {
            normalized.contains("/models/") -> loadModel(normalized)
            normalized.contains("/video/") -> loadVideo(normalized)
            normalized.contains("/categories/") -> loadCategory(normalized)
            else -> {
                println("ViralXxx load: unsupported path $url")
                null
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videos = when {
            data.startsWith("VIDEOS::") -> {
                println("ViralXxx loadLinks: RESOLVER=VIDEOS")
                data.removePrefix("VIDEOS::").split("||").map { it.trim() }.filter { it.startsWith("http") }
            }
            data.startsWith("http") -> {
                println("ViralXxx loadLinks: RESOLVER=URL $data")
                val response = load(data) ?: return false
                val episodeData = (response as? TvSeriesLoadResponse)?.episodes?.firstOrNull()?.data ?: return false
                if (!episodeData.startsWith("VIDEOS::")) return false
                episodeData.removePrefix("VIDEOS::").split("||").map { it.trim() }.filter { it.startsWith("http") }
            }
            else -> return false
        }
        if (videos.isEmpty()) return false
        videos.forEach { videoUrl ->
            val quality = Regex("_(\\d{3,4})p\\.mp4").find(videoUrl)?.groupValues?.get(1)?.toIntOrNull()
            val label = quality?.let { "${it}p" } ?: "Video"
            println("ViralXxx loadLinks: VIDEO HOST=${hostOf(videoUrl)} quality=${quality ?: "?"}")
            callback.invoke(
                newExtractorLink(
                    source = "ViralXXXPorn",
                    name = label,
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality ?: Qualities.Unknown.value
                    headers = mapOf(
                        "User-Agent" to BROWSER_UA,
                        "Referer" to "$BASE/"
                    )
                }
            )
        }
        println("ViralXxx loadLinks: emitted ${videos.size} links")
        return true
    }

    // ------------------------------------------------------- scraping

    private data class Card(val title: String, val thumb: String?, val url: String)

    private suspend fun scrapeVideoCards(url: String): List<Card> {
        val html = fetchText(url) ?: return emptyList()
        val doc = Jsoup.parse(html, url)
        return doc.select("a.vx-media[href*='/video/']").mapNotNull { a ->
            val href = a.absUrl("href").trim()
            if (href.isBlank()) return@mapNotNull null
            val img = a.selectFirst("img.vx-img")
            val thumb = when {
                img == null -> null
                img.attr("src").isNotBlank() && !img.attr("src").startsWith("data:") -> img.attr("src").trim()
                img.attr("data-webp").isNotBlank() -> img.attr("data-webp").trim()
                img.attr("data-original").isNotBlank() -> img.attr("data-original").trim()
                else -> null
            }
            val title = a.attr("title").trim()
                .ifBlank { img?.attr("alt")?.trim().orEmpty() }
            if (title.isBlank()) null else Card(title, thumb, href)
        }.distinctBy { it.url }
    }

    private suspend fun scrapeModels(url: String): List<Card> {
        val html = fetchText(url) ?: return emptyList()
        val doc = Jsoup.parse(html, url)
        return doc.select("a.vx-name[href*='/models/']").mapNotNull { a ->
            val href = a.absUrl("href").trim()
            if (href.isBlank()) return@mapNotNull null
            val title = a.attr("title").trim().ifBlank { a.text().trim() }
            if (title.isBlank()) return@mapNotNull null
            val gallery = a.closest("div.vx-card-gallery")
            val thumb = gallery?.selectFirst("img[data-original]")?.attr("data-original")?.trim()
                ?: gallery?.selectFirst("img.vx-img[data-webp]")?.attr("data-webp")?.trim()
            Card(title, thumb, href)
        }.distinctBy { it.url }
    }

    private suspend fun scrapeCategories(url: String): List<Card> {
        val html = fetchText(url) ?: return emptyList()
        val doc = Jsoup.parse(html, url)
        return doc.select("a.vx-name[href*='/categories/']").mapNotNull { a ->
            val href = a.absUrl("href").trim()
            if (href.isBlank()) return@mapNotNull null
            val title = a.attr("title").trim().ifBlank { a.text().trim() }
            if (title.isBlank()) return@mapNotNull null
            val gallery = a.closest("div.vx-card-gallery")
            val thumb = gallery?.selectFirst("img[data-original]")?.attr("data-original")?.trim()
                ?: gallery?.selectFirst("img.vx-img[data-webp]")?.attr("data-webp")?.trim()
            Card(title, thumb, href)
        }.distinctBy { it.url }
    }

    private fun Card.toSearchResponse(): SearchResponse =
        newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = thumb
        }

    // ------------------------------------------------------- load

    private suspend fun loadModel(url: String): LoadResponse? {
        val first = fetchText(url) ?: return null
        val doc = Jsoup.parse(first, url)
        val name = (doc.selectFirst("h1")?.text() ?: "")
            .let { Regex("#\\d+\\s*(.*)").find(it)?.groupValues?.get(1)?.trim() ?: it.trim() }
            .ifBlank { "Model" }
        val cards = mutableListOf<Card>()
        cards.addAll(parseVideoCards(first, url))
        var page = 2
        while (page <= MAX_MODEL_PAGES && cards.size < MAX_MODEL_EPISODES) {
            val pageHtml = fetchText("$url$page/") ?: break
            val pageCards = parseVideoCards(pageHtml, "$url$page/")
            if (pageCards.isEmpty()) break
            val before = cards.size
            cards.addAll(pageCards)
            if (cards.size == before) break
            page++
        }
        val episodes = cards.distinctBy { it.url }.mapIndexed { index, card ->
            newEpisode(card.url, {
                this.name = card.title
                this.posterUrl = card.thumb
                this.episode = index + 1
                this.season = 1
            }, false)
        }
        if (episodes.isEmpty()) {
            println("ViralXxx loadModel: no videos on $url")
            return null
        }
        val poster = episodes.firstOrNull { it.posterUrl != null }?.posterUrl
        return newTvSeriesLoadResponse(name, url, TvType.NSFW, episodes) {
            this.posterUrl = poster
        }
    }

    private suspend fun loadVideo(url: String): LoadResponse? {
        val html = fetchText(url) ?: return null
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst("title")?.text()
            ?.substringBefore(" - ViralXXXPorn")?.trim(' ', '-')?.trim()
            ?.takeIf { it.isNotBlank() } ?: "Video"
        val sources = viralGetFileUrls(html)
        if (sources.isEmpty()) {
            println("ViralXxx loadVideo: no get_file sources on $url")
            return null
        }
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: doc.selectFirst("img[src*='videos_screenshots']")?.attr("src")?.trim()
        val episode = newEpisode("VIDEOS::" + sources.joinToString("||"), {
            this.name = title
            this.posterUrl = poster
        }, false)
        return newTvSeriesLoadResponse(title, url, TvType.NSFW, listOf(episode)) {
            this.posterUrl = poster
        }
    }

    private suspend fun loadCategory(url: String): LoadResponse? {
        val first = fetchText(url) ?: return null
        val doc = Jsoup.parse(first, url)
        val name = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { "Category" }
            ?: "Category"
        val cards = mutableListOf<Card>()
        cards.addAll(parseVideoCards(first, url))
        var page = 2
        while (page <= MAX_MODEL_PAGES && cards.size < MAX_MODEL_EPISODES) {
            val pageHtml = fetchText("$url$page/") ?: break
            val pageCards = parseVideoCards(pageHtml, "$url$page/")
            if (pageCards.isEmpty()) break
            val before = cards.size
            cards.addAll(pageCards)
            if (cards.size == before) break
            page++
        }
        val episodes = cards.distinctBy { it.url }.mapIndexed { index, card ->
            newEpisode(card.url, {
                this.name = card.title
                this.posterUrl = card.thumb
                this.episode = index + 1
                this.season = 1
            }, false)
        }
        if (episodes.isEmpty()) {
            println("ViralXxx loadCategory: no videos on $url")
            return null
        }
        val poster = episodes.firstOrNull { it.posterUrl != null }?.posterUrl
        return newTvSeriesLoadResponse(name, url, TvType.NSFW, episodes) {
            this.posterUrl = poster
        }
    }

    private fun parseVideoCards(html: String, url: String): List<Card> {
        val doc = Jsoup.parse(html, url)
        return doc.select("a.vx-media[href*='/video/']").mapNotNull { a ->
            val href = a.absUrl("href").trim()
            if (href.isBlank()) return@mapNotNull null
            val img = a.selectFirst("img.vx-img")
            val thumb = when {
                img == null -> null
                img.attr("src").isNotBlank() && !img.attr("src").startsWith("data:") -> img.attr("src").trim()
                img.attr("data-webp").isNotBlank() -> img.attr("data-webp").trim()
                img.attr("data-original").isNotBlank() -> img.attr("data-original").trim()
                else -> null
            }
            val title = a.attr("title").trim()
                .ifBlank { img?.attr("alt")?.trim().orEmpty() }
            if (title.isBlank()) null else Card(title, thumb, href)
        }.distinctBy { it.url }
    }

    /** All direct quality MP4s from the video page `flashvars` block. */
    private fun viralGetFileUrls(html: String): List<String> {
        val raw = Regex("""video_(?:url|alt_url|alt_url2)\s*:\s*'(https://viralxxxporn.com/get_file/[^']+)'""")
            .findAll(html).map { it.value }
            .filter { it.contains(".mp4") && !it.contains("_preview") }
            .distinctBy { it.substringBefore('?') }
            .toList()
        val byQuality = raw.sortedBy { url ->
            Regex("_(\\d{3,4})p\\.mp4").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        return byQuality
    }

    // ------------------------------------------------------- helpers

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Scheme+host of a URL (never logs full paths). */
    private fun hostOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url.substringBefore('?')
        val rest = url.substring(schemeEnd + 3)
        return url.substring(0, schemeEnd + 3) + rest.substringBefore('/').substringBefore('?')
    }

    // ------------------------------------------------------- network helpers

    /** Logged fetch: status code, final URL after redirects, content-type and
     *  body size. Retries once, skips anti-bot pages, never throws. */
    private suspend fun fetchText(url: String): String? {
        val delays = listOf(600L, 1500L)
        repeat(3) { attempt ->
            try {
                val res = app.get(url, headers = headers())
                val code = res.okhttpResponse.code
                val finalUrl = res.okhttpResponse.request.url.toString()
                val ctype = res.okhttpResponse.header("content-type") ?: ""
                val body = res.text
                println("ViralXxx GET $url -> HTTP $code final=$finalUrl type=$ctype ${body.length}B")
                if (code !in 200..299) {
                    println("ViralXxx: non-2xx response from $url")
                    return null
                }
                if (isBlockPage(body)) {
                    println("ViralXxx: anti-bot/Cloudflare page detected from $url")
                    return null
                }
                return body
            } catch (e: Exception) {
                println("ViralXxx fetch attempt ${attempt + 1} failed for $url: $e")
                if (attempt < delays.size) delay(delays[attempt])
            }
        }
        return null
    }

    private fun isBlockPage(body: String): Boolean =
        body.length < 300 ||
            body.contains("cf-browser-verification", true) ||
            body.contains("Just a moment", true) ||
            body.contains("_cf_chl_", true) ||
            body.contains("Attention Required", true)

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Referer" to "$BASE/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private data class RowSpec(val url: String, val kind: String)

    companion object {
        private const val BASE = "https://viralxxxporn.com"
        private const val PAGE_SIZE = 60
        private const val MAX_MODEL_PAGES = 20
        private const val MAX_MODEL_EPISODES = 240
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.7103.48 Safari/537.36"
    }
}
