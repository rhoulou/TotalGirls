package com.example.stripchatmanstremio

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
 * CloudStream 3 provider for Stripchat live cams - Guys (Stremio addon bridge).
 *
 * Bridges the Stripchat Stremio addon (community.stripchat) REST API directly
 * from the phone - no addon server required. The public instance at
 * stripchat.stremio.homes serves a per-target base URL; this provider uses the
 * "girls" segment.
 *
 *   * Listing -> /catalog/Stripchat/<id>{/genre=G|/skip=N}.json with the
 *                catalogs Popular, New & Trending, Couples Live, VR Cams, plus
 *                one row per genre (tag) of the addon's genre list.
 *   * Room    -> /meta/Stripchat/<id>.json (id is "stripchat:<modelId>"; the
 *                meta carries poster, live viewer count, tags).
 *   * Stream  -> the addon's /stream endpoint only serves a "Web" note, so we
 *                build the saawsedge master playlist from the model id (same
 *                strategy as the direct StripchatProvider) and hand it to the
 *                player as an M3U8 link.
 *
 * The addon has no search extra, so quick search fetches the live Popular
 * catalog (a few pages) and filters by room name locally.
 */
class StripchatStremioProvider : MainAPI() {
    override var mainUrl = "https://stripchat.stremio.homes"
    override var name = "Stripchat Guys Stremio"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val seg: String = "men"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page <= 1) {
            val rows = mutableListOf<HomePageList>()
            val specs = CATALOGS + GENRES.map { RowSpec(it, null, it) }
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
        val json = fetch("${base()}/meta/Stripchat/$id.json") ?: return null
        val meta = runCatching {
            tryParseJson<StremioMetaResponse>(json)
        }.getOrNull()?.meta ?: return null

        val title = meta.name?.trim()?.takeIf { it.isNotBlank() }
            ?: id.substringAfter(":").ifBlank { id }
        return newLiveStreamLoadResponse(title, url, url) {
            this.posterUrl = posterUrlOf(meta.poster)
            this.posterHeaders = mapOf(
                "Referer" to REFERER,
                "User-Agent" to UA
            )
            this.plot = meta.description?.trim()?.takeIf { it.isNotBlank() }
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
        val modelId = id.substringAfter(":").ifBlank { return false }
        // The addon /stream endpoint only returns a "Web" note, so build the
        // saawsedge master playlist from the model id (addon/repo behavior).
        val master = "https://edge-hls.saawsedge.com/hls/$modelId/master/${modelId}_auto.m3u8"
        println("StripchatStremio loadLinks: $modelId TYPE=M3U8 master=$master")
        callback.invoke(
            newExtractorLink(
                source = "Stripchat",
                name = "Auto",
                url = master,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = REFERER
                this.quality = Qualities.Unknown.value
                headers = mapOf("User-Agent" to UA, "Referer" to REFERER)
            }
        )
        return true
    }

    // ------------------------------------------------------- listing parsing

    private fun specs(): List<RowSpec> = CATALOGS + GENRES.map { RowSpec(it, null, it) }

    private suspend fun parseCatalog(spec: RowSpec, skip: Int): List<SearchResponse> {
        val json = fetch(catalogUrl(spec, skip)) ?: return emptyList()
        val catalog = runCatching {
            tryParseJson<StremioCatalog>(json)
        }.getOrNull() ?: return emptyList()
        return catalog.metas.orEmpty().mapNotNull { it.toSearchResponse(this) }
    }

    private fun StremioMeta.toSearchResponse(provider: StripchatStremioProvider): SearchResponse? {
        val title = (name ?: id)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val id = id ?: return null
        return provider.newLiveSearchResponse(title, id, TvType.Live) {
            this.posterUrl = posterUrlOf(poster)
            this.posterHeaders = mapOf(
                "Referer" to REFERER,
                "User-Agent" to UA
            )
        }
    }

    // ------------------------------------------------------- helpers

    private fun base(): String = "https://stripchat.stremio.homes/$seg"

    private fun catalogUrl(spec: RowSpec, skip: Int): String {
        val parts = mutableListOf<String>()
        spec.genre?.let { parts.add("genre=${URLEncoder.encode(it, "utf8")}") }
        if (skip > 0) parts.add("skip=$skip")
        val extra = if (parts.isEmpty()) "" else "/" + parts.joinToString("/")
        return "${base()}/catalog/Stripchat/${spec.catalogId}$extra.json"
    }

    private fun idOf(url: String): String? =
        url.substringAfterLast("/").substringBefore(".json").takeIf { it.isNotBlank() }

    /**
     * The addon serves posters on img.strpst.com, which no longer resolves; the
     * live thumbnail host is img.doppiocdn.media. Rebuild the snapshot URL from
     * the served path (…/thumbs/<snapshotTs>/<modelId>_webp) as
     * https://img.doppiocdn.media/snapshot/<modelId>/<snapshotTs>.
     */
    private fun posterUrlOf(served: String?): String? {
        if (served.isNullOrBlank()) return null
        val m = Regex("""thumbs/(\d+)/(\d+)_webp""").find(served)
        return if (m != null) {
            val ts = m.groupValues[1]
            val modelId = m.groupValues[2]
            "https://img.doppiocdn.media/snapshot/$modelId/$ts"
        } else {
            served
        }
    }

    // ------------------------------------------------------- low level fetch

    private suspend fun fetch(url: String): String? {
        return try {
            val res = app.get(url, headers = headers())
            if (res.isSuccessful && res.text.isNotBlank()) {
                println("StripchatStremio GET $url -> ${res.okhttpResponse.code} (${res.text.length}B)")
                res.text
            } else {
                println("StripchatStremio GET $url -> ${res.okhttpResponse.code}")
                null
            }
        } catch (e: Exception) {
            println("StripchatStremio fetch failed: $e")
            null
        }
    }

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json,text/plain,*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to REFERER
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
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("cast") val cast: List<String>? = null,
        @JsonProperty("country") val country: String? = null,
        @JsonProperty("countryName") val countryName: String? = null,
        @JsonProperty("hlsPlaylist") val hlsPlaylist: String? = null,
        @JsonProperty("status") val status: String? = null
    )

    companion object {
        private const val PAGE_SIZE = 60
        private const val ROW_PACE_MS = 400L
        private const val MAX_SEARCH_SKIP = 120
        private const val REFERER = "https://stripchat.com/"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0"

        /** The addon's named catalogs (countries catalog skipped: 57 rows). */
        private val CATALOGS = listOf(
            RowSpec("popular", null, "Popular"),
            RowSpec("new", null, "New & Trending"),
            RowSpec("vr", null, "VR Cams"),
            RowSpec("couples", "Popular", "Couples Live")
        )

        /** Genre (tag) rows from the addon manifest genre options (headers stripped). */
        private val GENRES = listOf(
            "Teen 18", "Young 22", "MILF", "Mature", "Granny",
            "Skinny", "Athletic", "Medium", "Curvy", "BBW",
            "Arab", "Asian", "Ebony", "India", "Latina", "White"
        )
    }
}
