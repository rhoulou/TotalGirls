package com.example.porntube

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
 * CloudStream 3 provider for PornTube.
 *
 * Bridges the PornTube Stremio addon (id pw.ers.porntube) REST API directly
 * from the phone - no addon server required. The public instance at
 * dirty-pink.ers.pw serves the catalog/search/meta endpoints plus torrent
 * streams (infoHash) for every title; a tokenized base URL from a
 * debrid-enabled configuration additionally returns direct playback URLs.
 *
 *   * Listing -> /catalog/movie/tpdb_catalog{/genre=X|/search=Y|/skip=N}.json
 *   * Video   -> /meta/movie/<id>.json (single meta object with genres, cast
 *                links, runtime, year, background)
 *   * Streams -> /stream/movie/<id>.json. Direct URLs are passed straight to
 *                the player as M3U8/VIDEO links; torrent streams are emitted
 *                as MAGNET/TORRENT links that the CloudStream app resolves via
 *                its built-in LibreTorrent server bridge (no client-side
 *                torrent engine is implemented here).
 */
class PornTubeProvider : MainAPI() {
    override var mainUrl = Settings.DEFAULT_BASE
    override var name = "PornTube"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true
    override val hasQuickSearch = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page <= 1) {
            val rows = mutableListOf<HomePageList>()
            val specs = listOf<String?>(null) + GENRES
            for ((i, genre) in specs.withIndex()) {
                val label = genre ?: NEW_ROW
                val items = parseCatalog(catalogUrl(genre = genre))
                if (items.isNotEmpty()) {
                    rows.add(HomePageList(label, items.take(PAGE_SIZE)))
                }
                if (i < specs.lastIndex) delay(ROW_PACE_MS)
            }
            return newHomePageResponse(rows)
        }
        val genre = when (request.name) {
            NEW_ROW -> null
            else -> GENRES.firstOrNull { it == request.name } ?: return null
        }
        val items = parseCatalog(catalogUrl(genre = genre, skip = (page - 1) * PAGE_SIZE))
        if (items.isEmpty()) return null
        return newHomePageResponse(
            HomePageList(request.name, items)
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val items = parseCatalog(catalogUrl(query = query))
        if (items.isEmpty()) return null
        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = idOf(url) ?: return null
        val json = fetch("${base()}/meta/movie/$id.json") ?: return null
        val meta = runCatching {
            tryParseJson<StremioMetaResponse>(json)
        }.getOrNull()?.meta ?: return null

        val title = (meta.name ?: meta.title)?.trim()
            ?.takeIf { it.isNotBlank() } ?: id
        val cast = meta.links.orEmpty()
            .filter { it.category == "Cast" }
            .mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }
            .map { ActorData(Actor(it)) }
        val genres = meta.genres.orEmpty().filter { it.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = meta.poster
            this.backgroundPosterUrl = meta.background
            this.plot = meta.description?.trim()?.takeIf { it.isNotBlank() }
            this.duration = meta.runtime?.let { parseRuntime(it) }
            this.year = meta.year
            if (genres.isNotEmpty()) this.tags = genres
            if (cast.isNotEmpty()) this.actors = cast
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = idOf(data) ?: return false
        val json = fetch("${base()}/stream/movie/$id.json") ?: return false
        val streams = runCatching {
            tryParseJson<StremioStreams>(json)
        }.getOrNull()?.streams.orEmpty()
        if (streams.isEmpty()) return false

        var emitted = false
        for (s in streams) {
            val url = s.url?.trim()?.takeIf { it.isNotBlank() }
            if (url != null) {
                val type = when {
                    url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                    url.contains(".torrent", ignoreCase = true) || url.startsWith("magnet:") -> ExtractorLinkType.TORRENT
                    else -> ExtractorLinkType.VIDEO
                }
                // Never log full URLs: direct streams may embed debrid tokens/config.
                println("PornTube loadLinks: $id stream TYPE=$type HOST=${hostOf(url)}")
                callback.invoke(
                    newExtractorLink(
                        source = "PornTube",
                        name = s.title?.trim()?.takeIf { it.isNotBlank() } ?: "Direct ${qualityLabel(s.title)}",
                        url = url,
                        type = type
                    ) {
                        this.quality = qualityOf(s.title)
                        headers = mapOf("User-Agent" to UA, "Referer" to "${base()}/")
                    }
                )
                emitted = true
            }
            val infoHash = s.infoHash?.trim()?.takeIf { it.isNotBlank() }
            if (infoHash != null) {
                println("PornTube loadLinks: $id TYPE=MAGNET INFOHASH=$infoHash")
                callback.invoke(
                    newExtractorLink(
                        source = "PornTube",
                        name = "Magnet ${qualityLabel(s.title)}",
                        url = "magnet:?xt=urn:btih:$infoHash",
                        type = ExtractorLinkType.MAGNET
                    ) {
                        this.quality = qualityOf(s.title)
                    }
                )
                emitted = true
            }
        }
        return emitted
    }

    /** Scheme+host of a URL for debug logging; full URL may contain tokens. */
    private fun hostOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url.substringBefore('?')
        val rest = url.substring(schemeEnd + 3)
        return url.substring(0, schemeEnd + 3) + rest.substringBefore('/').substringBefore('?')
    }

    // ------------------------------------------------------- listing parsing

    private suspend fun parseCatalog(url: String): List<SearchResponse> {
        val json = fetch(url) ?: return emptyList()
        val catalog = runCatching {
            tryParseJson<StremioCatalog>(json)
        }.getOrNull() ?: return emptyList()
        return catalog.metas.orEmpty().mapNotNull { it.toSearchResponse(this) }
    }

    private fun StremioMeta.toSearchResponse(provider: PornTubeProvider): SearchResponse? {
        val title = (name ?: title)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val id = id ?: return null
        return provider.newMovieSearchResponse(title, id, TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = mapOf(
                "Referer" to "https://ptube.ers.pw/",
                "User-Agent" to UA
            )
        }
    }

    // ------------------------------------------------------- helpers

    /** Read the user-configured (normalized) base URL live. */
    private fun base(): String = Settings.base()

    private fun catalogUrl(
        genre: String? = null,
        query: String? = null,
        skip: Int? = null
    ): String {
        val parts = mutableListOf<String>()
        genre?.let { parts.add("genre=${URLEncoder.encode(it, "utf8")}") }
        query?.let { parts.add("search=${URLEncoder.encode(it, "utf8")}") }
        skip?.let { parts.add("skip=$it") }
        val extra = if (parts.isEmpty()) "" else "/" + parts.joinToString("/")
        return "${base()}/catalog/movie/tpdb_catalog$extra.json"
    }

    private fun idOf(url: String): String? =
        url.substringAfterLast("/").substringBefore(".json").takeIf { it.isNotBlank() }

    /** Convert a Stremio runtime ("23m", "1h 12m") to seconds. */
    private fun parseRuntime(text: String): Int? {
        var total = 0
        Regex("""(\d+)\s*(h|hr|hrs|m|min|s|sec)""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { m ->
                val n = m.groupValues[1].toIntOrNull() ?: return@forEach
                when (m.groupValues[2].lowercase()) {
                    "h", "hr", "hrs" -> total += n * 3600
                    "m", "min" -> total += n * 60
                    else -> total += n
                }
            }
        return total.takeIf { it > 0 }
    }

    private fun qualityLabel(title: String?): String {
        val t = title ?: return ""
        Regex("""(?i)(4k|uhd|2160p|1440p|1080p|720p|480p|360p)""").find(t)?.let {
            return "(${it.groupValues[1].uppercase()})"
        }
        return ""
    }

    private fun qualityOf(title: String?): Int {
        val t = title ?: return Qualities.Unknown.value
        return when (Regex("""(?i)\b(4k|uhd|2160p?|1440p?|1080p?|720p?|480p?|360p?)\b""").find(t)?.groupValues?.get(1)?.lowercase()) {
            "4k", "uhd", "2160", "2160p" -> Qualities.P2160.value
            "1440", "1440p" -> Qualities.P1440.value
            "1080", "1080p" -> Qualities.P1080.value
            "720", "720p" -> Qualities.P720.value
            "480", "480p" -> Qualities.P480.value
            "360", "360p" -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    // ------------------------------------------------------- low level fetch

    private suspend fun fetch(url: String): String? {
        return try {
            val res = app.get(url, headers = headers())
            if (res.isSuccessful && res.text.isNotBlank()) {
                println("PornTube GET $url -> ${res.okhttpResponse.code} (${res.text.length}B)")
                res.text
            } else {
                println("PornTube GET $url -> ${res.okhttpResponse.code}")
                null
            }
        } catch (e: Exception) {
            println("PornTube fetch failed: $e")
            null
        }
    }

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json,text/plain,*/*",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // ------------------------------------------------------- models

    private data class StremioCatalog(
        @JsonProperty("metas") val metas: List<StremioMeta>? = null
    )

    private data class StremioMetaResponse(
        @JsonProperty("meta") val meta: StremioMeta? = null
    )

    private data class StremioMeta(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("background") val background: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("links") val links: List<StremioLink>? = null,
        @JsonProperty("runtime") val runtime: String? = null,
        @JsonProperty("year") val year: Int? = null
    )

    private data class StremioLink(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("category") val category: String? = null
    )

    private data class StremioStreams(
        @JsonProperty("streams") val streams: List<StremioStream>? = null
    )

    private data class StremioStream(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("infoHash") val infoHash: String? = null,
        @JsonProperty("title") val title: String? = null
    )

    companion object {
        private const val NEW_ROW = "PornTube New"
        private const val PAGE_SIZE = 36
        private const val ROW_PACE_MS = 500L
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0"

        private val GENRES = listOf(
            "Teen", "MILF", "Mature", "Anal", "Lesbian", "Threesome",
            "Amateur", "Asian", "Babes", "Big Ass", "Blonde", "Brunette",
            "Cumshot", "Interracial", "Pornstar", "Cosplay"
        )
    }
}
