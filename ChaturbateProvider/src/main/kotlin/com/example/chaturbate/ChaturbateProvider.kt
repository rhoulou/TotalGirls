package com.example.chaturbate

// Wildcard import on purpose: `app`, `registerMainAPI` and the HomePage/
// SearchResponse helpers all live in this package (same as the official
// cloudstream-extensions template).
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder

/**
 * CloudStream 3 provider for Chaturbate live cams.
 *
 * This is a *bridge* to the Chaturbate Stremio addon server (the Node project
 * in Streamio/Chaturbate): it reuses the addon's manifest / catalog / meta /
 * stream endpoints instead of duplicating the Chaturbate scraping logic.
 *
 * Setup:
 *   1. Deploy the Node addon server somewhere https-enabled
 *      (e.g. Railway / Render / Fly.io - "npm start" is all it needs).
 *   2. Set [ADDON_URL] below to that URL.
 *   3. Build this project (see README.md) and install the resulting
 *      ChaturbateProvider.cs3 in CloudStream 3.
 *
 * Cleartext note: Android blocks plain http:// by default; use an https URL.
 * The three providers (Girls / Guys / Trans) only differ in the manifest
 * path segment (f / m / t) - the same config-page concept as the original.
 */
class ChaturbateProvider(private val gender: String, displayName: String) : MainAPI() {
    override var mainUrl = ADDON_URL
    override var name = displayName
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val manifest = tryParseJson<Manifest>(
            app.get("$mainUrl/$gender/manifest.json").text
        ) ?: return null
        val lists = mutableListOf<HomePageList>()
        manifest.catalogs.forEach { catalog ->
            catalog.toHomePageList(this)?.let { lists.add(it) }
        }
        return HomePageResponse(lists, false)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val manifest = tryParseJson<Manifest>(
            app.get("$mainUrl/$gender/manifest.json").text
        ) ?: return null
        val list = mutableListOf<SearchResponse>()
        manifest.catalogs.forEach { catalog ->
            list.addAll(catalog.search(query, this))
        }
        return list
    }

    override suspend fun load(url: String): LoadResponse? {
        val entry = tryParseJson<CatalogEntry>(url) ?: throw RuntimeException(url)
        return entry.toLoadResponse(this)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = tryParseJson<StreamsResponse>(app.get(data).text) ?: return false
        res.streams.forEach { stream ->
            stream.runCallback(subtitleCallback, callback)
        }
        return true
    }

    // ------------------------------------------------------- JSON models

    private data class Manifest(val catalogs: List<Catalog>)

    private data class Catalog(
        var name: String?,
        val id: String,
        val type: String?
    ) {
        suspend fun toHomePageList(provider: ChaturbateProvider): HomePageList? {
            val entries = mutableListOf<SearchResponse>()
            val res = tryParseJson<CatalogResponse>(
                app.get(
                    "${provider.mainUrl}/${provider.gender}/catalog/" +
                        "${type.encodeUri()}/${id.encodeUri()}.json"
                ).text
            ) ?: return null
            res.metas.forEach { entry -> entries.add(entry.toSearchResponse(provider)) }
            return HomePageList(name ?: id, entries)
        }

        suspend fun search(query: String, provider: ChaturbateProvider): List<SearchResponse> {
            val entries = mutableListOf<SearchResponse>()
            val res = tryParseJson<CatalogResponse>(
                app.get(
                    "${provider.mainUrl}/${provider.gender}/catalog/" +
                        "${type.encodeUri()}/${id.encodeUri()}.json"
                ).text
            ) ?: return emptyList()
            // The addon has no server-side search extra; filter the popular
            // catalog client-side by room name.
            res.metas.filter { it.name.contains(query, ignoreCase = true) }
                .forEach { entry -> entries.add(entry.toSearchResponse(provider)) }
            return entries
        }
    }

    private data class CatalogResponse(val metas: List<CatalogEntry>)

    private data class CatalogEntry(
        val name: String,
        val id: String,
        val poster: String?,
        val description: String?,
        val type: String?
    ) {
        fun toSearchResponse(provider: ChaturbateProvider): SearchResponse =
            provider.newMovieSearchResponse(name, this.toJson(), TvType.Others) {
                posterUrl = poster
            }

        suspend fun toLoadResponse(provider: ChaturbateProvider): LoadResponse =
            provider.newMovieLoadResponse(
                name,
                "${provider.mainUrl}/${provider.gender}/meta/" +
                    "${type.encodeUri()}/${id.encodeUri()}.json",
                TvType.Others,
                "${provider.mainUrl}/${provider.gender}/stream/" +
                    "${type.encodeUri()}/${id.encodeUri()}.json"
            ) {
                posterUrl = poster
                plot = description
            }
    }

    private data class StreamsResponse(val streams: List<Stream>)

    private data class Stream(
        val name: String?,
        val title: String?,
        val url: String?,
        val externalUrl: String?
    ) {
        suspend fun runCallback(
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            if (url != null) {
                // Signed HLS chunklist - play directly; referer keeps the
                // edge CDN happy.
                callback.invoke(
                    ExtractorLink(
                        name ?: "",
                        title ?: name ?: "",
                        url,
                        "https://chaturbate.com/",
                        Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
            }
            if (externalUrl != null) {
                // "Web / Chat Now!" fallback -> opens the room in the browser.
                loadExtractor(externalUrl, subtitleCallback, callback)
            }
        }
    }

    companion object {
        /**
         * Base URL of the deployed Chaturbate Stremio addon server.
         * Example: "https://chaturbate-addon.up.railway.app"
         */
        private const val ADDON_URL = "https://replace-with-your-addon-url"

        fun String.encodeUri() = URLEncoder.encode(this, "utf8")
    }
}
