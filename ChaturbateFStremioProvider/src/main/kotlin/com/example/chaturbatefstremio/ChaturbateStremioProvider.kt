package com.example.chaturbatefstremio

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
 * CloudStream 3 provider for Chaturbate live cams - Girls (Stremio addon bridge).
 *
 * Bridges the Chaturbate Stremio addon (community.chaturbate) REST API directly
 * from the phone - no addon server required. The public instance at
 * chaturbate.stremio.homes serves a per-target base URL; this provider uses the
 * "f" (Female) segment.
 *
 *   * Listing -> /catalog/Chaturbate/<id>{/genre=G|/skip=N}.json with the
 *                catalogs Popular, 5 regions, Couples Live, plus one row per
 *                genre (tag) of the target's genre list.
 *   * Room    -> /meta/Chaturbate/<id>.json (id is "chaturbate:<room>"; the
 *                meta carries poster, live viewer count, room subject, tags).
 *   * Stream  -> /stream/Chaturbate/<id>.json (direct LL-HLS chunklist URLs
 *                per quality, passed straight to the player as M3U8 links).
 *
 * The addon has no search extra, so quick search fetches the live Popular
 * catalog (a few pages) and filters by room name/description locally.
 */
class ChaturbateStremioProvider : MainAPI() {
    override var mainUrl = "https://chaturbate.stremio.homes"
    override var name = "Chaturbate Girls Stremio"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val fmt: String = "f"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page <= 1) {
            val rows = mutableListOf<HomePageList>()
            val specs = CATALOGS + genresFor(fmt).map { RowSpec(it, null, it) }
            for ((i, spec) in specs.withIndex()) {
                val items = parseCatalog(spec, skip = 0)
                if (items.isNotEmpty()) {
                    rows.add(HomePageList(spec.label, items.take(PAGE_SIZE)))
                }
                if (i < specs.lastIndex) delay(ROW_PACE_MS)
            }
            return newHomePageResponse(rows)
        }
        val spec = specs().firstOrNull { it.label == request.name } ?: return null
        val items = parseCatalog(spec, skip = (page - 1) * PAGE_SIZE)
        if (items.isEmpty()) return null
        return newHomePageResponse(
            HomePageList(spec.label, items),
            hasNext = items.size >= PAGE_SIZE
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim().lowercase()
        if (q.isBlank()) return null
        val hits = mutableListOf<SearchResponse>()
        for (skip in (0..MAX_SEARCH_SKIP).step(PAGE_SIZE)) {
            val page = parseCatalog(RowSpec("popular", null, "Popular"), skip = skip)
            val matched = page.filter { it.name.lowercase().contains(q) }
            hits.addAll(matched)
            if (page.size < PAGE_SIZE) break
        }
        return hits.ifEmpty { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = idOf(url) ?: return null
        val json = fetch("${base()}/meta/Chaturbate/$id.json") ?: return null
        val meta = runCatching {
            tryParseJson<StremioMetaResponse>(json)
        }.getOrNull()?.meta ?: return null

        val title = meta.name?.trim()?.takeIf { it.isNotBlank() }
            ?: id.substringAfter(":").ifBlank { id }
        return newLiveStreamLoadResponse(title, url, url) {
            this.posterUrl = meta.poster
            this.plot = buildString {
                val watching = viewersOf(meta.runtime)
                if (watching != null) append("Watching: $watching")
                val desc = meta.description?.trim()?.takeIf { it.isNotBlank() }
                if (desc != null) {
                    if (isNotEmpty()) append("\n\n")
                    append(desc)
                }
            }.takeIf { it.isNotBlank() }
            val tags = meta.genres.orEmpty().filter { it.isNotBlank() }
            if (tags.isNotEmpty()) this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = idOf(data) ?: return false
        val json = fetch("${base()}/stream/Chaturbate/$id.json") ?: return false
        val streams = runCatching {
            tryParseJson<StremioStreams>(json)
        }.getOrNull()?.streams.orEmpty()
        if (streams.isEmpty()) return false

        var emitted = false
        for (s in streams) {
            val url = s.url?.trim()?.takeIf { it.isNotBlank() } ?: continue
            // externalUrl entries (e.g. "Web / Chat Now!") are site links, not streams.
            println("ChaturbateStremio loadLinks: $id TYPE=M3U8 Q=${s.name}")
            callback.invoke(
                newExtractorLink(
                    source = "Chaturbate",
                    name = s.name?.trim()?.takeIf { it.isNotBlank() } ?: "Live ${qualityLabel(s.name)}",
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "${base()}/"
                    this.quality = qualityOf(s.name)
                    headers = mapOf("User-Agent" to UA)
                }
            )
            emitted = true
        }
        return emitted
    }

    // ------------------------------------------------------- listing parsing

    private fun specs(): List<RowSpec> = CATALOGS + genresFor(fmt).map { RowSpec(it, null, it) }

    private suspend fun parseCatalog(spec: RowSpec, skip: Int): List<SearchResponse> {
        val json = fetch(catalogUrl(spec, skip)) ?: return emptyList()
        val catalog = runCatching {
            tryParseJson<StremioCatalog>(json)
        }.getOrNull() ?: return emptyList()
        return catalog.metas.orEmpty().mapNotNull { it.toSearchResponse(this) }
    }

    private fun StremioMeta.toSearchResponse(provider: ChaturbateStremioProvider): SearchResponse? {
        val title = (name ?: id)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val id = id ?: return null
        return provider.newLiveSearchResponse(title, id, TvType.Live) {
            this.posterUrl = poster
            this.posterHeaders = mapOf(
                "Referer" to "https://chaturbate.com/",
                "User-Agent" to UA
            )
        }
    }

    // ------------------------------------------------------- helpers

    private fun base(): String = "https://chaturbate.stremio.homes/$fmt"

    private fun catalogUrl(spec: RowSpec, skip: Int): String {
        val parts = mutableListOf<String>()
        spec.genre?.let { parts.add("genre=${URLEncoder.encode(it, "utf8")}") }
        if (skip > 0) parts.add("skip=$skip")
        val extra = if (parts.isEmpty()) "" else "/" + parts.joinToString("/")
        return "${base()}/catalog/Chaturbate/${spec.catalogId}$extra.json"
    }

    private fun idOf(url: String): String? =
        url.substringAfterLast("/").substringBefore(".json").takeIf { it.isNotBlank() }

    /** Parse the "Watching: <n>" runtime string to an Int. */
    private fun viewersOf(runtime: String?): Int? {
        if (runtime.isNullOrBlank()) return null
        return Regex("""(\d+)""").find(runtime)?.groupValues?.get(1)?.toIntOrNull()
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
                println("ChaturbateStremio GET $url -> ${res.okhttpResponse.code} (${res.text.length}B)")
                res.text
            } else {
                println("ChaturbateStremio GET $url -> ${res.okhttpResponse.code}")
                null
            }
        } catch (e: Exception) {
            println("ChaturbateStremio fetch failed: $e")
            null
        }
    }

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json,text/plain,*/*",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // ------------------------------------------------------- models

    private data class RowSpec(
        val catalogId: String,
        val genre: String?,
        val label: String
    )

    private data class StremioCatalog(
        @JsonProperty("metas") val metas: List<StremioMeta>? = null
    )

    private data class StremioMetaResponse(
        @JsonProperty("meta") val meta: StremioMeta? = null
    )

    private data class StremioMeta(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("background") val background: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("runtime") val runtime: String? = null
    )

    private data class StremioStreams(
        @JsonProperty("streams") val streams: List<StremioStream>? = null
    )

    private data class StremioStream(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("externalUrl") val externalUrl: String? = null
    )

    companion object {
        private const val PAGE_SIZE = 50
        private const val ROW_PACE_MS = 400L
        private const val MAX_SEARCH_SKIP = 100
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0"

        /** The addon's 7 named catalogs. */
        private val CATALOGS = listOf(
            RowSpec("popular", null, "Popular"),
            RowSpec("region.north_america", null, "North America"),
            RowSpec("region.south_america", null, "South America"),
            RowSpec("region.asia", null, "Asia"),
            RowSpec("region.europe_russia", null, "Europe/Russia"),
            RowSpec("region.other", null, "Other Regions"),
            RowSpec("couples", null, "Couples Live")
        )

        /** Genre (tag) rows per target, from the addon manifest genres map. */
        private val GENRES = mapOf(
            "f" to listOf(
                "Teen", "Young", "MILF", "Mature", "Bigboobs", "Bigass", "Hairy",
                "Latina", "BBW", "Squirt", "Skinny", "Smalltits", "Feet", "Fuckmachine"
            ),
            "m" to listOf(
                "Teen", "Young", "DILF", "Mature", "Bigcook", "Cum", "Lovense",
                "Muscle", "Latino", "Hairy", "New", "Feet"
            ),
            "t" to listOf(
                "Teen", "Young", "ILF", "Mature", "Bigcook", "Smallcook", "Mistress",
                "Femboy", "Partyhouse", "Fuckmachine", "Bigass", "Lovense"
            )
        )

        fun genresFor(fmt: String): List<String> = GENRES[fmt].orEmpty()
    }
}
