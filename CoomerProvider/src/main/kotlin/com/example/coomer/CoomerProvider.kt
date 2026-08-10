package com.example.coomer

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * CloudStream 3 provider for the Coomer archive mirrors.
 *
 * The original archive (coomer.st) is dead, so this provider scrapes two live,
 * server-rendered tube mirrors instead (configurable in Settings):
 *  - IncestFlix: home `/?s=`-free post grid, WordPress search `/?s=q`, post
 *    pages with a direct signed MP4 in <video><source>.
 *  - CoomerVideo: home + `/search/?q=q` video cards, video pages exposing
 *    direct `/get_file/..._NNNp.mp4/` sources.
 *
 * Home returns one row per active source, search merges results from all active
 * sources (deduped by URL), and load()/loadLinks() hand the direct MP4s back to
 * CloudStream with proper headers. Every fetch is logged (status, final URL
 * after redirects, content-type, size) and guarded against Cloudflare/anti-bot
 * pages, so a dead source is skipped instead of blanking the app.
 */
class CoomerProvider : MainAPI() {
    override var mainUrl = Settings.INCESTFLIX
    override var name = "Coomer"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override var getMainPageTimeoutMs: Long? = 60_000L

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val rows = mutableListOf<HomePageList>()
        for (source in Settings.sources()) {
            val cards = runCatching { scrapeHome(source) }.getOrElse {
                println("Coomer getMainPage($source) failed: $it")
                emptyList()
            }
            if (cards.isEmpty()) continue
            rows.add(HomePageList(rowTitle(source), cards.map { it.toSearchResponse() }, true))
        }
        if (Settings.sources().any { hostOf(it).contains("official.coomer") }) {
            val models = runCatching { scrapeCoomerModels("${Settings.COOMERVIDEO}/models/") }.getOrElse {
                println("Coomer getMainPage(models) failed: $it")
                emptyList()
            }
            if (models.isNotEmpty()) {
                rows.add(HomePageList("CoomerVideo — Top Models", models.take(24).map { it.toSearchResponse() }, true))
            }
        }
        if (rows.isEmpty()) return null
        return newHomePageResponse(rows, false)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return emptyList()
        val deduped = LinkedHashMap<String, SearchResponse>()
        for (source in Settings.sources()) {
            val cards = runCatching { scrapeSearch(source, query) }.getOrElse {
                println("Coomer search($source) failed: $it")
                emptyList()
            }
            cards.forEach { card ->
                if (card.url.isNotBlank()) deduped.putIfAbsent(card.url, card.toSearchResponse())
            }
        }
        val models = runCatching { searchModels(query) }.getOrElse {
            println("Coomer search(models) failed: $it")
            emptyList()
        }
        models.forEach { model ->
            if (model.url.isNotBlank()) deduped.putIfAbsent(model.url, model)
        }
        if (deduped.isEmpty()) return null
        return deduped.values.take(50)
    }

    override suspend fun load(url: String): LoadResponse? {
        val host = hostOf(url)
        return when {
            host.contains("incestflix") -> loadIncestflix(url)
            host.contains("official.coomer") && url.contains("/models/") -> loadCoomerModel(url)
            host.contains("official.coomer") -> loadCoomerVideo(url)
            else -> {
                println("Coomer load: unsupported host $host")
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
            data.startsWith("IMAGES::") -> return true
            data.startsWith("VIDEOS::") -> {
                println("Coomer loadLinks: RESOLVER=VIDEOS")
                data.removePrefix("VIDEOS::").split("||").map { it.trim() }.filter { it.startsWith("http") }
            }
            data.startsWith("http") -> {
                println("Coomer loadLinks: RESOLVER=URL $data")
                val response = load(data) ?: return false
                val episodeData = (response as? TvSeriesLoadResponse)?.episodes?.firstOrNull()?.data ?: return false
                if (!episodeData.startsWith("VIDEOS::")) return false
                episodeData.removePrefix("VIDEOS::").split("||").map { it.trim() }.filter { it.startsWith("http") }
            }
            else -> return false
        }
        if (videos.isEmpty()) return false
        videos.forEachIndexed { index, videoUrl ->
            val quality = Regex("_(\\d{3,4})p\\.mp4").find(videoUrl)?.groupValues?.get(1)?.toIntOrNull()
            println("Coomer loadLinks: VIDEO #${index + 1} HOST=${hostOf(videoUrl)} quality=${quality ?: "?"}")
            callback.invoke(
                newExtractorLink(
                    source = "Coomer",
                    name = "Coomer Video ${index + 1}",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality ?: Qualities.Unknown.value
                    headers = mapOf(
                        "User-Agent" to BROWSER_UA,
                        "Referer" to refererFor(videoUrl)
                    )
                }
            )
        }
        println("Coomer loadLinks: RESOLVER=VIDEOS emitted ${videos.size} links")
        return true
    }

    // ------------------------------------------------------- scraping

    private data class Card(val title: String, val thumb: String?, val url: String)

    private suspend fun scrapeHome(source: String): List<Card> = when (hostOf(source)) {
        "incestflix.com.co" -> scrapeIncestflixCards("$source/")
        else -> scrapeCoomerVideoCards("$source/")
    }

    private suspend fun scrapeSearch(source: String, query: String): List<Card> = when (hostOf(source)) {
        "incestflix.com.co" -> scrapeIncestflixCards("$source/?s=${urlEncode(query)}")
        else -> scrapeCoomerVideoCards("$source/search/?q=${urlEncode(query)}")
    }

    private suspend fun scrapeIncestflixCards(url: String): List<Card> {
        val html = fetchText(url) ?: return emptyList()
        val doc = Jsoup.parse(html, url)
        return doc.select("a.video").mapNotNull { a ->
            val href = a.attr("href").trim().ifBlank { return@mapNotNull null }
            val thumb = Regex("url\\('([^']+)'\\)").find(a.attr("style"))?.groupValues?.get(1)
            val title = a.attr("title").trim()
                .ifBlank { a.selectFirst("h2.vtitle")?.text()?.trim().orEmpty() }
            if (title.isBlank()) null else Card(title, thumb, href)
        }.distinctBy { it.url }
    }

    private suspend fun scrapeCoomerVideoCards(url: String): List<Card> {
        val html = fetchText(url) ?: return emptyList()
        return parseCoomerVideoCards(html, url)
    }

    private fun parseCoomerVideoCards(html: String, url: String): List<Card> {
        val doc = Jsoup.parse(html, url)
        return doc.select("a.vx-media").mapNotNull { a ->
            val href = a.attr("href").trim()
            if (href.isBlank() || !href.contains("/video/")) return@mapNotNull null
            val img = a.selectFirst("img.vx-img")
            val thumb = when {
                img == null -> null
                img.attr("src").isNotBlank() && !img.attr("src").startsWith("data:") -> img.attr("src").trim()
                img.attr("data-webp").isNotBlank() -> img.attr("data-webp").trim()
                else -> null
            }
            val title = a.attr("title").trim()
                .ifBlank { img?.attr("alt")?.trim().orEmpty() }
            if (title.isBlank()) null else Card(title, thumb, href)
        }.distinctBy { it.url }
    }

    private suspend fun scrapeCoomerModels(url: String): List<Card> {
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

    private fun rowTitle(source: String): String =
        if (hostOf(source).contains("incestflix")) "IncestFlix — Latest" else "CoomerVideo — Latest"

    private fun Card.toSearchResponse(): SearchResponse =
        newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = thumb
        }

    // ------------------------------------------------------- load

    private suspend fun loadIncestflix(url: String): LoadResponse? {
        val html = fetchText(url) ?: return null
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst("title")?.text()
            ?.substringBefore(" - IncestFlix")?.trim(' ', '-')?.trim()
            ?.takeIf { it.isNotBlank() } ?: "Video"
        val src = (doc.selectFirst("video#my-video source[src]")
            ?: doc.select("source[src]").firstOrNull { it.attr("src").contains(".mp4") })
            ?.attr("src")?.trim()
        if (src.isNullOrBlank()) {
            println("Coomer loadIncestflix: no video source on $url")
            return null
        }
        val poster = doc.selectFirst("video#my-video[poster]")?.attr("poster")?.trim()
            ?.takeIf { it.startsWith("http") }
        val episode = newEpisode("VIDEOS::$src", {
            this.name = title
            this.posterUrl = poster
        }, false)
        return newTvSeriesLoadResponse(title, url, TvType.NSFW, listOf(episode)) {
            this.posterUrl = poster
        }
    }

    private suspend fun loadCoomerVideo(url: String): LoadResponse? {
        val videoId = Regex("/video/(\\d+)/").find(url)?.groupValues?.get(1)
        println("Coomer loadCoomerVideo: start url=$url videoId=$videoId")
        val html = fetchText(url)
        if (html == null) {
            println("Coomer loadCoomerVideo: fetchText returned null (blocked/throttled/HTTP error) for $url -> load() NULL")
            return null
        }
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst("title")?.text()
            ?.substringBefore(" - CoomerVideo")?.trim(' ', '-')?.trim()
            ?.takeIf { it.isNotBlank() } ?: "Video"
        println("Coomer loadCoomerVideo: title=\"$title\" htmlChars=${html.length}")
        var sources = coomerGetFileUrls(html, videoId)
        println("Coomer loadCoomerVideo: in-page get_file count=${sources.size} for $url")
        if (sources.isEmpty()) {
            val embedId = videoId ?: Regex("/embed/(\\d+)").find(html)?.groupValues?.get(1)
            if (embedId != null) {
                val embedUrl = "${Settings.COOMERVIDEO}/embed/$embedId"
                println("Coomer loadCoomerVideo: no in-page get_file for $url, trying $embedUrl")
                val embedHtml = fetchText(embedUrl)
                val embedSources = if (embedHtml != null) coomerGetFileUrls(embedHtml, videoId) else emptyList()
                println("Coomer loadCoomerVideo: embed get_file count=${embedSources.size} (embedHtml=${embedHtml != null})")
                if (embedSources.isNotEmpty()) sources = embedSources
            } else {
                println("Coomer loadCoomerVideo: no in-page get_file and no embedId found on $url")
            }
        }
        if (sources.isEmpty()) {
            println("Coomer loadCoomerVideo: sources empty after in-page+embed, retrying video page once after delay for $url")
            delay(1000)
            val retryHtml = fetchText(url)
            if (retryHtml != null) {
                val retrySources = coomerGetFileUrls(retryHtml, videoId)
                println("Coomer loadCoomerVideo: retry get_file count=${retrySources.size}")
                if (retrySources.isNotEmpty()) sources = retrySources
            } else {
                println("Coomer loadCoomerVideo: retry fetchText also returned null for $url")
            }
        }
        if (sources.isEmpty()) {
            println("Coomer loadCoomerVideo: NO SOURCES after in-page+embed+retry for $url -> load() NULL")
            return null
        }
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: doc.selectFirst("img[src*='videos_screenshots']")?.attr("src")?.trim()
        println("Coomer loadCoomerVideo: OK sources=${sources.size} poster=${poster != null} for $url")
        val episode = newEpisode("VIDEOS::" + sources.joinToString("||"), {
            this.name = title
            this.posterUrl = poster
        }, false)
        return newTvSeriesLoadResponse(title, url, TvType.NSFW, listOf(episode)) {
            this.posterUrl = poster
        }
    }

    private fun coomerGetFileUrls(html: String, videoId: String?): List<String> {
        val raw = Regex("https?://(?:www\\.)?(?:official\\.)?coomer\\.com\\.co/get_file/[^\"'\\s<>]+")
            .findAll(html).map { it.value }
            .filter { it.contains(".mp4") && !it.contains("_preview") }
            .filter { url ->
                videoId == null || Regex("get_file/[^/]+/[^/]+/\\d+/$videoId/").containsMatchIn(url)
            }
            .distinctBy { it.substringBefore('?') }
            .toList()
        val quality = raw.filter { Regex("_\\d{3,4}p\\.mp4").containsMatchIn(it) }
        return if (quality.isNotEmpty()) quality else raw
    }

    private suspend fun loadCoomerModel(url: String): LoadResponse? {
        val normalized = if (url.endsWith("/")) url else "$url/"
        val first = fetchText(normalized) ?: return null
        val doc = Jsoup.parse(first, normalized)
        val name = (doc.selectFirst("h1")?.text() ?: "")
            .let { Regex("#\\d+\\s*(.*)").find(it)?.groupValues?.get(1)?.trim() ?: it.trim() }
            .ifBlank { "Model" }
        val cards = mutableListOf<Card>()
        cards.addAll(parseCoomerVideoCards(first, normalized))
        var page = 2
        while (page <= MAX_MODEL_PAGES && cards.size < MAX_MODEL_EPISODES) {
            val pageHtml = fetchText("$normalized$page/") ?: break
            val pageCards = parseCoomerVideoCards(pageHtml, "$normalized$page/")
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
            println("Coomer loadCoomerModel: no videos on $url")
            return null
        }
        val poster = episodes.firstOrNull { it.posterUrl != null }?.posterUrl
        return newTvSeriesLoadResponse(name, url, TvType.NSFW, episodes) {
            this.posterUrl = poster
        }
    }

    private suspend fun searchModels(query: String): List<SearchResponse> {
        val results = LinkedHashMap<String, SearchResponse>()
        val slug = slugify(query)
        if (slug.isNotBlank()) {
            val model = runCatching { loadCoomerModelAsCard("${Settings.COOMERVIDEO}/models/$slug/") }.getOrNull()
            if (model != null && model.url.isNotBlank()) {
                println("Coomer searchModels: slug '$slug' -> ${model.title}")
                results.putIfAbsent(model.url, model.toSearchResponse())
            }
        }
        val top = runCatching { scrapeCoomerModels("${Settings.COOMERVIDEO}/models/") }.getOrNull().orEmpty()
        top.filter { it.title.contains(query, ignoreCase = true) }
            .forEach { results.putIfAbsent(it.url, it.toSearchResponse()) }
        return results.values.toList()
    }

    private suspend fun loadCoomerModelAsCard(url: String): Card? {
        val html = fetchText(url) ?: return null
        val doc = Jsoup.parse(html, url)
        if (doc.select("a.vx-media[href*='/video/']").isEmpty()) return null
        val name = (doc.selectFirst("h1")?.text() ?: "")
            .let { Regex("#\\d+\\s*(.*)").find(it)?.groupValues?.get(1)?.trim() ?: it.trim() }
            .ifBlank { "Model" }
        val thumb = doc.selectFirst("img[data-original]")?.attr("data-original")?.trim()
        return Card(name, thumb, url)
    }

    private fun slugify(value: String): String =
        value.lowercase().trim()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    // ------------------------------------------------------- network helpers

    /** Logged fetch: status code, final URL after redirects, content-type and
     *  body size. Retries once, skips Cloudflare/anti-bot pages, never throws. */
    private suspend fun fetchText(url: String): String? {
        val delays = listOf(600L, 1500L)
        repeat(3) { attempt ->
            try {
                val res = app.get(url, headers = headers(url))
                val code = res.okhttpResponse.code
                val finalUrl = res.okhttpResponse.request.url.toString()
                val ctype = res.okhttpResponse.header("content-type") ?: ""
                val body = res.text
                println("Coomer GET $url -> HTTP $code final=$finalUrl type=$ctype ${body.length}B")
                if (code !in 200..299) {
                    println("Coomer: non-2xx response from $url")
                    return null
                }
                if (isBlockPage(body)) {
                    println("Coomer: anti-bot/Cloudflare page detected from $url")
                    return null
                }
                return body
            } catch (e: Exception) {
                println("Coomer fetch attempt ${attempt + 1} failed for $url: $e")
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

    private fun headers(url: String): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Referer" to refererFor(url),
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private fun refererFor(url: String): String =
        if (url.contains("official.coomer")) Settings.COOMERVIDEO else Settings.INCESTFLIX

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Scheme+host of a URL (never logs full paths). */
    private fun hostOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url.substringBefore('?')
        val rest = url.substring(schemeEnd + 3)
        return url.substring(0, schemeEnd + 3) + rest.substringBefore('/').substringBefore('?')
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.7103.48 Safari/537.36"
        private const val MAX_MODEL_PAGES = 20
        private const val MAX_MODEL_EPISODES = 240
    }
}
