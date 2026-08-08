package com.example.cams

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
 * CloudStream 3 provider for Cams.com live cams.
 *
 * Scrapes www.cams.com directly from the phone (no addon server), mirroring the
 * logic in bobs/cams/viewer.php:
 *
 *   * Category page -> https://www.cams.com/webcam/<gender>/<tag> carries a
 *                  __NEXT_DATA__ script whose
 *                  props.pageProps.initialData.wonStore.compressedWonResponse
 *                  holds a `mapping` key array and a `models` array of value
 *                  arrays that are zipped together (stream_id, screen_name,
 *                  stream_name, public_age, hq_enabled, ...).
 *   * Poster    -> dynimages.securedataimages.com proxy of the streamray 640 gif
 *   * Stream    -> https://camshls.cams.com/cdn-<stream_name>.m3u8 passed
 *                  straight to the player.
 *
 * cams.com does not publish a viewer count for guests, so none is shown.
 */
class CamsProvider : MainAPI() {
    override var mainUrl = "https://www.cams.com"
    override var name = "Cams.com"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var vpnStatus = VPNStatus.MightBeNeeded

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page <= 1) {
            val rows = CATEGORIES.mapNotNull { spec ->
                val items = getCategory(spec.gender, spec.tag)
                if (items.isEmpty()) null
                else HomePageList(spec.label, items.take(PAGE_SIZE).map { it.toSearchResponse(this) })
            }
            return newHomePageResponse(rows)
        }
        val spec = CATEGORIES.firstOrNull { it.label == request.name } ?: return null
        val items = getCategory(spec.gender, spec.tag)
        val from = (page - 1) * PAGE_SIZE
        val slice = items.drop(from).take(PAGE_SIZE)
        return newHomePageResponse(
            HomePageList(spec.label, slice.map { it.toSearchResponse(this) }),
            hasNext = from + slice.size < items.size
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        // No server-side search on the category pages: filter what we have.
        val wanted = query.lowercase()
        val matches = mutableListOf<Model>()
        synchronized(cacheLock) {
            byUsername.entries.forEach { (username, _) ->
                if (username.contains(wanted)) {
                    loadByUsername(username)?.let { m ->
                        if (matches.none { it.username == m.username }) matches.add(m)
                    }
                }
            }
        }
        if (matches.isEmpty()) return null
        return matches.map { it.toSearchResponse(this) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val username = url.trimEnd('/').substringAfterLast('/')
        val model = resolveModel(username) ?: return null
        return newLiveStreamLoadResponse(model.username ?: username, url, url) {
            posterUrl = model.posterUrl()
            plot = model.age?.let { "Age: $it" }
            tags = if (model.isHd) listOf("HD") else emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val username = data.trimEnd('/').substringAfterLast('/')
        val model = resolveModel(username) ?: return false
        callback.invoke(
            newExtractorLink(
                source = "Cams.com",
                name = "Auto",
                url = model.hlsUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = REFERER
                quality = Qualities.Unknown.value
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to REFERER)
            }
        )
        return true
    }

    // ------------------------------------------------------- search entries

    private fun Model.toSearchResponse(provider: CamsProvider): SearchResponse =
        provider.newLiveSearchResponse(username ?: "", roomUrl(username), TvType.Live) {
            posterUrl = posterUrl()
        }

    private fun roomUrl(username: String?) = "$mainUrl/${URLEncoder.encode(username ?: "", "utf8")}"

    /** Look in the in-memory map, falling back to the All Female page. */
    private suspend fun resolveModel(username: String): Model? {
        loadByUsername(username.lowercase())?.let { return it }
        getCategory("female", "all") // populates byUsername
        return loadByUsername(username.lowercase())
    }

    private fun loadByUsername(username: String): Model? {
        synchronized(cacheLock) { return byUsername[username] }
    }

    // ------------------------------------------------------- categories

    private data class CategorySpec(val label: String, val gender: String, val tag: String)

    private suspend fun getCategory(gender: String, tag: String): List<Model> {
        val key = "$gender/$tag"
        synchronized(cacheLock) {
            val c = categories[key]
            if (c != null && System.currentTimeMillis() - c.fetchedAt < CACHE_TTL_MS) return c.models
        }
        var doFetch = false
        val job = synchronized(cacheLock) {
            val pending = inflight[key]
            if (pending != null) {
                pending
            } else {
                doFetch = true
                CompletableDeferred<List<Model>>().also { inflight[key] = it }
            }
        }
        if (doFetch) {
            val list = runCatching { fetchCategory(gender, tag) }.getOrDefault(emptyList())
            synchronized(cacheLock) {
                categories[key] = CategoryCache(list, System.currentTimeMillis())
                list.forEach { model ->
                    model.username?.lowercase()?.let { byUsername[it] = model }
                }
                if (inflight[key] === job) inflight.remove(key)
            }
            job.complete(list)
        }
        return job.await()
    }

    private suspend fun fetchCategory(gender: String, tag: String): List<Model> {
        val url = "$mainUrl/webcam/$gender/$tag"
        val html = fetch(url) ?: return emptyList()
        val match = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">([\\s\\S]*?)</script>")
            .find(html) ?: return emptyList()
        val next = tryParseJson<NextData>(match.groupValues[1]) ?: return emptyList()
        val resp = next.props?.pageProps?.initialData?.wonStore?.compressedWonResponse ?: return emptyList()
        return buildModels(resp)
    }

    private fun buildModels(resp: Compressed): List<Model> {
        val mapping = resp.mapping.orEmpty()
        val out = mutableListOf<Model>()
        resp.models.orEmpty().forEach { row ->
            val m = mapping.withIndex().associate { (i, k) -> k to row.getOrNull(i) }
            val screenName = m["screen_name"]?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
            val streamName = m["stream_name"]?.toString()?.takeIf { it.isNotBlank() } ?: screenName
            val streamId = m["stream_id"]?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
            out.add(
                Model(
                    id = streamId,
                    username = screenName,
                    streamName = streamName,
                    gender = m["gender"]?.toString(),
                    age = m["public_age"]?.toString(),
                    isHd = m["hq_enabled"]?.toString() == "2"
                )
            )
        }
        return out
    }

    // ------------------------------------------------------- low level fetch

    private suspend fun fetch(url: String): String? {
        val target = wrap(url)
        for (attempt in 0 until 3) {
            try {
                val res = app.get(
                    target,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to REFERER,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.5"
                    )
                )
                if (res.isSuccessful && res.text.isNotBlank()) {
                    println("Cams GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code} (${res.text.length}B)")
                    return res.text
                }
                println("Cams GET ${res.okhttpResponse.request.url} -> ${res.okhttpResponse.code}")
            } catch (e: Exception) {
                println("Cams request failed (attempt $attempt): $e")
            }
            delay(RETRY_PAUSE_MS * (attempt + 1))
        }
        return null
    }

    /** Route a request through the user's proxy when one is configured. */
    private fun wrap(url: String): String {
        val p = Settings.proxy()
        return if (p.isBlank()) url else p + URLEncoder.encode(url, "utf8")
    }

    // ------------------------------------------------------- JSON models

    private data class NextData(@JsonProperty("props") val props: Props? = null)

    private data class Props(@JsonProperty("pageProps") val pageProps: PageProps? = null)

    private data class PageProps(@JsonProperty("initialData") val initialData: InitialData? = null)

    private data class InitialData(@JsonProperty("wonStore") val wonStore: WonStore? = null)

    private data class WonStore(@JsonProperty("compressedWonResponse") val compressedWonResponse: Compressed? = null)

    private data class Compressed(
        @JsonProperty("mapping") val mapping: List<String>? = null,
        @JsonProperty("models") val models: List<List<Any?>>? = null
    )

    private data class CategoryCache(val models: List<Model>, val fetchedAt: Long)

    private data class Model(
        @JsonProperty("id") val id: String,
        @JsonProperty("username") val username: String,
        @JsonProperty("streamName") val streamName: String,
        @JsonProperty("gender") val gender: String? = null,
        @JsonProperty("age") val age: String? = null,
        @JsonProperty("isHd") val isHd: Boolean = false
    ) {
        fun posterUrl(): String =
            "https://dynimages.securedataimages.com/unsigned/rs:fill:640::0/g:no/plain/https%3A%2F%2Fimages4.streamray.com%2Fimages%2Fstreamray%2Fstreams%2F" +
                username.lowercase() + "_640.gif@webp"

        val hlsUrl: String
            get() = "https://camshls.cams.com/cdn-" + streamName.lowercase() + ".m3u8"
    }

    companion object {
        private const val REFERER = "https://www.cams.com/"
        private const val PAGE_SIZE = 60
        private const val CACHE_TTL_MS = 90_000L
        private const val RETRY_PAUSE_MS = 800L

        private const val USER_AGENT = ("Mozilla/5.0 (X11; Linux x86_64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

        // gender + tag pairs mirroring bobs/cams/viewer.php categoryMap.
        private val CATEGORIES = listOf(
            CategorySpec("All Female", "female", "all"),
            CategorySpec("New", "female", "new"),
            CategorySpec("Age 18-29", "female", "age-29"),
            CategorySpec("Age 30-39", "female", "age-39"),
            CategorySpec("Age 40-49", "female", "age-49"),
            CategorySpec("Age 50+", "female", "age-above-50"),
            CategorySpec("Asian", "female", "asian"),
            CategorySpec("Ebony", "female", "ebony"),
            CategorySpec("Latina", "female", "latina"),
            CategorySpec("White", "female", "white"),
            CategorySpec("Other", "female", "other"),
            CategorySpec("Blonde", "female", "blonde-webcams"),
            CategorySpec("Brunette", "female", "brunette-webcams"),
            CategorySpec("Redhead", "female", "redhead-webcams"),
            CategorySpec("Black Hair", "female", "black-hair"),
            CategorySpec("BBW", "female", "bbw"),
            CategorySpec("Curvy", "female", "curvy"),
            CategorySpec("Petite", "female", "petite"),
            CategorySpec("Huge Tits", "female", "huge-tits"),
            CategorySpec("Big Boobs", "female", "big-boobs"),
            CategorySpec("Small Tits", "female", "small-tits"),
            CategorySpec("Anal", "female", "anal-sex-webcams"),
            CategorySpec("Bondage", "female", "bondage-fetish"),
            CategorySpec("Foot Fetish", "female", "foot-fetish-webcams"),
            CategorySpec("High Heels", "female", "high-heels-fetish"),
            CategorySpec("Hairy", "female", "hairy-pussy-webcams"),
            CategorySpec("Tattoos", "female", "tattoos-fetish"),
            CategorySpec("Masturbation", "female", "masturbation-webcams"),
            CategorySpec("Oral Sex", "female", "oral-sex-webcams"),
            CategorySpec("Vibrators", "female", "vibrators-fetish"),
            CategorySpec("Male", "male", "all"),
            CategorySpec("Trans", "trans", "all")
        )

        private val cacheLock = Any()

        private val categories = HashMap<String, CategoryCache>()
        private val inflight = HashMap<String, CompletableDeferred<List<Model>>>()
        private val byUsername = HashMap<String, Model>()
    }
}
