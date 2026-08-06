package com.example.bongacams

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import java.net.URLEncoder
import kotlin.math.ceil

/**
 * CloudStream 3 provider for BongaCams live cams (girls only).
 *
 * Uses the official bongacams.com listing API instead of the lemoncams.com
 * aggregator. bongacams.com is Cloudflare-protected, so the JSON calls are
 * fetched through the user's own proxy (proxy.rhoulou.com), which replays the
 * request with a valid browser session (incl. X-Requested-With):
 *
 *   * Room list -> PROXY(https://bongacams.com/tools/listing_v3.php
 *                  ?online_only=1&limit=<n>&offset=<m>&c[]=female
 *                  &model_search[base_sort]=popular)
 *                  returns {status, total_count, models:[{username,
 *                  display_name, gender, vq, viewers, esid, thumb_image, ...}]}.
 *                  Only gender (c[]=female) and model_search[display_name][text]
 *                  are honoured server-side; category params are ignored, so
 *                  rows are client-side filters over the full female listing.
 *   * Stream    -> https://{esid}.bcvcdn.com/hls/stream_<user>/playlist.m3u8
 *                  - the bcvcdn edge the site uses, served to any client, so it
 *                  is passed straight to the player (no proxy).
 *   * Search    -> listing_v3?model_search[display_name][text]=<q> - matches
 *                  the model's real username / display name from the API.
 *
 * Home rows: All Female, HD (client-side vq filter).
 * Robustness: request pacing, HTTP 429 backoff, retries, a cached full listing
 * (every row paginates over it locally), a shared model cache for playback, and
 * graceful handling of non-JSON responses (empty rows).
 */
class BongaCamsProvider : MainAPI() {
    override var mainUrl = "https://bongacams.com"
    override var name = "BongaCams Girls"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "" to "All Female",
        "hd" to "HD",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val all = fullListing()
        val offset = (page - 1) * PAGE_SIZE
        val filtered = all.filter { it.matchesRow(request.data) }
        val slice = filtered.drop(offset).take(PAGE_SIZE)
        return newHomePageResponse(
            HomePageList(request.name, slice.map { it.toSearchResponse() }, isHorizontalImages = false),
            hasNext = filtered.size > offset + slice.size
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        // Matches the model's real username / display name from the API - no
        // gender filter, so a model's real name is always matched regardless
        // of the room's current gender.
        val encoded = URLEncoder.encode(query, "utf8")
        val url = proxyUrl(buildListingUrl("model_search[display_name][text]=$encoded", 0, gender = ""))
        val text = fetch(url)
        if (text.isBlank()) return null
        val models = parseJson<ListingResponse>(text)?.models.orEmpty().filter { it.isReal }
        println("BongaCams search '$query' -> ${models.size} results")
        cacheAll(models)
        return models.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val username = url.trimEnd('/').substringAfterLast('/')
        if (username.isBlank()) return null
        val model = findModel(username)
        return newLiveStreamLoadResponse(model?.displayName?.takeIf { it.isNotBlank() } ?: username, url, url) {
            posterUrl = model?.posterUrl()
            plot = model?.plot()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val username = data.trimEnd('/').substringAfterLast('/')
        if (username.isBlank()) return false
        val model = findModel(username) ?: return false
        val esid = model.esid?.takeIf { it.isNotBlank() } ?: return false
        // The bcvcdn edge serves the master to any client, so it can be passed
        // straight through (no proxy needed for playback).
        val hls = "https://$esid.bcvcdn.com/hls/stream_$username/playlist.m3u8"
        println("BongaCams master $username -> $hls")
        callback.invoke(
            newExtractorLink(
                source = "BongaCams",
                name = "Auto",
                url = hls,
                type = ExtractorLinkType.M3U8
            ) {
                referer = ""
                quality = Qualities.Unknown.value
                headers = mapOf("User-Agent" to USER_AGENT)
            }
        )
        return true
    }

    // ------------------------------------------------------- helpers

    private fun Model.toSearchResponse(): SearchResponse =
        newLiveSearchResponse(username, roomUrl(username), TvType.Live) {
            posterUrl = posterUrl()
            posterHeaders = mapOf("User-Agent" to USER_AGENT)
        }

    private fun roomUrl(username: String) = "$mainUrl/${username.lowercase()}"

    private fun Model.matchesRow(row: String): Boolean = when (row) {
        "", "all" -> true
        "hd" -> isHd()
        else -> true
    }

    private fun Model.isHd(): Boolean {
        val v = vq.orEmpty()
        return v.contains("1080") || v.contains("2160") || v.contains("hd") || v.contains("high")
    }

    /** Full online female listing, cached briefly; every row pages over it. */
    private suspend fun fullListing(): List<Model> {
        synchronized(listLock) {
            if (allModels.isNotEmpty() && System.currentTimeMillis() - listFetchedAt < LIST_TTL_MS) {
                return allModels
            }
        }
        val collected = ArrayList<Model>()
        var offset = 0
        var total = Int.MAX_VALUE
        while (offset < total && offset < MAX_SCAN) {
            val url = proxyUrl(buildListingUrl("", offset, "female"))
            val text = fetch(url)
            if (text.isBlank()) break
            val parsed = parseJson<ListingResponse>(text) ?: break
            if (offset == 0) total = parsed.totalCount ?: collected.size
            val page = parsed.models.orEmpty().filter { it.isReal }
            if (page.isEmpty()) break
            collected.addAll(page)
            cacheAll(page)
            offset += page.size
        }
        if (collected.isEmpty()) return emptyList()
        println("BongaCams listing -> ${collected.size} online female models")
        synchronized(listLock) {
            allModels = collected
            listFetchedAt = System.currentTimeMillis()
        }
        return collected
    }

    /** Look a model up in the shared cache, else query the API for the name. */
    private suspend fun findModel(username: String): Model? {
        val wanted = username.lowercase()
        modelCache[wanted]?.let { return it }
        val encoded = URLEncoder.encode(wanted, "utf8")
        val url = proxyUrl(buildListingUrl("model_search[display_name][text]=$encoded", 0, gender = ""))
        val text = fetch(url)
        if (text.isBlank()) return null
        val models = parseJson<ListingResponse>(text)?.models.orEmpty().filter { it.isReal }
        cacheAll(models)
        return models.firstOrNull { it.username.lowercase() == wanted } ?: models.firstOrNull()
    }

    private fun buildListingUrl(filters: String, offset: Int, gender: String): String = buildString {
        append(API_URL)
        append("?online_only=1")
        append("&limit=").append(LIST_PAGE_SIZE)
        append("&offset=").append(offset)
        if (gender.isNotBlank()) append("&c[]=").append(gender)
        if (filters.isNotBlank()) append("&").append(filters)
        append("&model_search[base_sort]=popular")
    }

    private fun proxyUrl(target: String): String = PROXY + URLEncoder.encode(target, "utf8")

    private fun cacheAll(models: List<Model>) {
        if (models.isEmpty()) return
        synchronized(modelLock) {
            models.forEach { m -> modelCache[m.username.lowercase()] = m }
        }
    }

    // ------------------------------------------------------- low level fetch

    private suspend fun fetch(url: String): String {
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            try {
                pace()
                val res = app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json, text/plain, */*"
                    )
                )
                if (res.isSuccessful) {
                    println("BongaCams GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code} (${res.text.length}B)")
                    return res.text
                }
                if (res.okhttpResponse.code == 429) { // rate limited -> longer pause
                    println("BongaCams GET ${res.okhttpResponse.request.url} -> 429 (rate limited)")
                    delay(RATE_LIMIT_PAUSE_MS)
                    continue
                }
                println("BongaCams GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code}")
                return ""
            } catch (e: Exception) {
                lastError = e // network hiccup -> retry
                delay(RETRY_PAUSE_MS * (attempt + 1))
            }
        }
        println("BongaCams request failed: $lastError")
        return ""
    }

    private inline fun <reified T : Any> parseJson(text: String): T? {
        // A non-JSON response means a challenge / error page - treat as empty
        // but log a snippet so it shows up in CloudStream's logs.
        if (text.isBlank() || text.startsWith("<!DOCTYPE", ignoreCase = true) ||
            text.startsWith("<html", ignoreCase = true)
        ) {
            println("BongaCams non-JSON response: ${text.take(500)}")
            return null
        }
        return tryParseJson<T>(text)
    }

    /** Minimum gap between proxy requests, shared across providers. */
    private suspend fun pace() {
        val wait: Long
        synchronized(paceLock) {
            val now = System.currentTimeMillis()
            wait = (lastRequestAt + MIN_INTERVAL_MS - now).coerceAtLeast(0)
            lastRequestAt = now + wait
        }
        if (wait > 0) delay(wait)
    }

    // ------------------------------------------------------- JSON models

    private data class ListingResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("total_count") val totalCount: Int? = null,
        @JsonProperty("online_count") val onlineCount: Int? = null,
        @JsonProperty("models") val models: List<Model>? = null
    )

    private data class Model(
        @JsonProperty("username") val username: String = "",
        @JsonProperty("display_name") val displayName: String? = null,
        @JsonProperty("gender") val gender: String? = null,
        @JsonProperty("room") val room: String? = null,
        @JsonProperty("vq") val vq: String? = null,
        @JsonProperty("viewers") val viewers: Int? = null,
        @JsonProperty("esid") val esid: String? = null,
        @JsonProperty("is_top") val isTop: Boolean = false,
        @JsonProperty("thumb_image") val thumbImage: String? = null
    ) {
        val isReal: Boolean get() = username.isNotBlank() && username.lowercase() != "profile"

        /** thumb_image is a {ext} template served on i.bgicdn.com. */
        fun posterUrl(): String? {
            val raw = thumbImage ?: return null
            val fixed = raw.replace("{ext}", "jpg")
            return if (fixed.startsWith("//")) "https:$fixed" else fixed
        }

        /** Current viewer count, when present. */
        fun plot(): String? = buildString {
            val v = viewers ?: 0
            if (v > 0) append("Watching: ").append(v)
        }.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val PROXY = "https://proxy.rhoulou.com:7676/proxy.php?url="
        private const val API_URL = "https://bongacams.com/tools/listing_v3.php"
        private const val PAGE_SIZE = 36 // cams per home page
        private const val LIST_PAGE_SIZE = 100 // models fetched per API call
        private const val LIST_TTL_MS = 90_000L // full listing cache
        private const val MAX_SCAN = 60 * LIST_PAGE_SIZE // safety cap

        private const val MIN_INTERVAL_MS = 350L // minimum gap between requests
        private const val RATE_LIMIT_PAUSE_MS = 2_500L // on HTTP 429
        private const val RETRY_PAUSE_MS = 800L // between failed attempts

        private const val USER_AGENT = ("Mozilla/5.0 (X11; Linux x86_64; rv:150.0) "
            + "Gecko/20100101 Firefox/150.0")

        private val modelLock = Any()
        private val listLock = Any()
        private val paceLock = Any()

        private val modelCache = HashMap<String, Model>()
        private var allModels: List<Model> = emptyList()
        private var listFetchedAt = 0L

        @Volatile private var lastRequestAt = 0L
    }
}
