package com.example.coomer

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import java.util.Collections

/**
 * CloudStream 3 provider for the Coomer archive.
 *
 * Creator index is read from a static GitHub snapshot (domain-independent), so
 * browsing/search never depends on the archive host. Profile + posts come from
 * the archive API: {domain}/api/v1/{service}/user/{id}/profile and /posts. The
 * base domain is user-configurable (Settings) - swap in a working mirror when
 * the current domain goes down; media hosts are derived from it.
 *
 * Photos are skipped (lean port - no custom gallery UI); only video posts are
 * emitted as episodes, each resolved to direct MP4 links in loadLinks. The
 * archive site requires an "Accept: text/css" header to scrape (see its 403
 * notice), so all requests send it.
 */
class CoomerProvider : MainAPI() {
    override var mainUrl = Settings.DEFAULT_DOMAIN
    override var name = "Coomer"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val creators = fetchCreators()
        if (creators.isEmpty()) return null
        val rows = creators.shuffled().chunked(4000).mapIndexed { index, group ->
            HomePageList(
                "Creators ${index + 1}",
                group.map { it.toSearchResponse() },
                true
            )
        }
        if (rows.isEmpty()) return null
        return newHomePageResponse(rows, false)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return emptyList()
        val matches = fetchCreators()
            .filter { it.name.contains(query, true) || it.id.contains(query, true) }
            .take(50)
            .map { it.toSearchResponse() }
        if (matches.isEmpty()) return null
        return matches
    }

    override suspend fun load(url: String): LoadResponse? {
        val service = url.substringAfter("/v1/").substringBefore("/")
        val id = url.substringAfter("/user/").substringBefore("/")
        if (service.isBlank() || id.isBlank()) return null

        val profileText = fetch("${base()}/api/v1/$service/user/$id/profile") ?: return null
        val profile = runCatching {
            jacksonObjectMapper().readValue<Map<String, Any>>(profileText)
        }.getOrNull() ?: return null

        val creatorName = profile["name"]?.toString()?.trim()
            ?.takeIf { it.isNotBlank() } ?: id

        val episodes = mutableListOf<Episode>()
        for (post in fetchPosts(service, id)) {
            val videoUrls = mutableListOf<String>()
            var thumb: String? = null
            post.file.path?.let { p ->
                if (isVideo(p)) videoUrls.add(dataUrl(p))
                else if (isImage(p)) thumb = thumbUrl(p)
            }
            post.attachments.forEach { att ->
                att.path?.let { p -> if (isVideo(p)) videoUrls.add(dataUrl(p)) }
            }
            if (videoUrls.isEmpty()) continue
            val title = post.title?.trim()?.takeIf { it.isNotBlank() }?.take(50)
            episodes.add(
                newEpisode("VIDEOS::" + videoUrls.joinToString("||")) {
                    this.name = title ?: "Video ${episodes.size + 1}"
                    this.episode = episodes.size + 1
                    this.posterUrl = thumb
                }
            )
        }
        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(creatorName, url, TvType.NSFW, episodes) {
            this.posterUrl = "${img()}/banners/$service/$id"
            this.plot = "18+ only. Creator: $creatorName\nService: $service\n"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("IMAGES::")) return true
        if (!data.startsWith("VIDEOS::")) return false
        val videos = data.substringAfter("VIDEOS::").split("||").filter { it.isNotBlank() }
        if (videos.isEmpty()) return false
        videos.forEachIndexed { index, videoUrl ->
            println("Coomer loadLinks: VIDEO #${index + 1} HOST=${hostOf(videoUrl)}")
            callback.invoke(
                newExtractorLink(
                    source = "Coomer",
                    name = "$name Video ${index + 1}",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    headers = mapOf("User-Agent" to BROWSER_UA, "Referer" to "${base()}/")
                }
            )
        }
        return true
    }

    // ------------------------------------------------------- creator index

    private suspend fun fetchCreators(): List<Creator> {
        creatorsCache?.let { return it }
        val raw = runCatching { app.get(CREATORS_URL).textLarge }.getOrNull() ?: return emptyList()
        val parsed = runCatching {
            val text = Jsoup.parse(raw).body().text()
            val list: List<Creator> = jacksonObjectMapper().readValue(text)
            list.filter { it.name.isNotBlank() && it.id.isNotBlank() && it.service.isNotBlank() }
        }.getOrNull().orEmpty()
        if (parsed.isNotEmpty()) creatorsCache = parsed
        return parsed
    }

    private fun Creator.toSearchResponse(): SearchResponse =
        newMovieSearchResponse(
            name,
            "${base()}/api/v1/${service}/user/${id}/profile",
            TvType.NSFW
        ) {
            this.posterUrl = "${img()}/icons/$service/$id"
            this.posterHeaders = mapOf(
                "Referer" to "${base()}/",
                "User-Agent" to GOOGLE_UA
            )
        }

    // ------------------------------------------------------- posts pagination

    private suspend fun fetchPosts(service: String, id: String): List<Post> {
        val allPosts = Collections.synchronizedList(mutableListOf<Post>())
        val mapper = jacksonObjectMapper()

        withTimeoutOrNull(3000) {
            coroutineScope {
                val firstPageUrl = "${base()}/api/v1/$service/user/$id/posts"
                try {
                    val first = app.get(firstPageUrl, headers = headers()).textLarge
                    allPosts.addAll(mapper.readValue<List<Post>>(first, postTypeRef))
                } catch (e: Exception) {
                    return@coroutineScope
                }

                var offset = 50
                val batchSize = 5
                while (offset <= 5000) {
                    val batchJobs = mutableListOf<Deferred<Pair<Int, List<Post>?>>>()
                    repeat(batchSize) { i ->
                        val currentOffset = offset + (i * 50)
                        if (currentOffset > 5000) return@repeat
                        batchJobs.add(
                            async {
                                try {
                                    val pageUrl = "${base()}/api/v1/$service/user/$id/posts?o=$currentOffset"
                                    val body = app.get(pageUrl, headers = headers()).textLarge
                                    if (body.contains("\"error\"")) return@async Pair(currentOffset, null)
                                    val pagePosts: List<Post> = mapper.readValue(body, postTypeRef)
                                    if (pagePosts.isEmpty()) return@async Pair(currentOffset, null)
                                    Pair(currentOffset, pagePosts)
                                } catch (e: Exception) {
                                    Pair(currentOffset, null)
                                }
                            }
                        )
                    }
                    val results = batchJobs.awaitAll()
                    var shouldStop = false
                    results.sortedBy { it.first }.forEach { (_, posts) ->
                        if (posts == null) shouldStop = true
                        else if (!shouldStop) allPosts.addAll(posts)
                    }
                    if (shouldStop) break
                    offset += batchSize * 50
                }
            }
        }
        return allPosts
    }

    // ------------------------------------------------------- helpers

    /** Read the user-configured (normalized) archive domain live. */
    private fun base(): String = Settings.base()

    private fun host(): String = base().substringAfter("://").substringBefore('/').trimEnd('/')

    private fun img(): String = "https://img.${host()}"

    private fun dataUrl(path: String) = "${base()}/data$path"

    private fun thumbUrl(path: String) = "${img()}/thumbnail/data$path"

    private fun isImage(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
    }

    private fun isVideo(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".avi")
    }

    /** Scheme+host of a URL for debug logging (never log full paths). */
    private fun hostOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url.substringBefore('?')
        val rest = url.substring(schemeEnd + 3)
        return url.substring(0, schemeEnd + 3) + rest.substringBefore('/').substringBefore('?')
    }

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to GOOGLE_UA,
        "Referer" to "${base()}/",
        "Accept" to "text/css"
    )

    private suspend fun fetch(url: String): String? {
        return try {
            val res = app.get(url, headers = headers())
            if (res.isSuccessful && res.text.isNotBlank()) {
                println("Coomer GET $url -> ${res.okhttpResponse.code} (${res.text.length}B)")
                res.text
            } else {
                println("Coomer GET $url -> ${res.okhttpResponse.code}")
                null
            }
        } catch (e: Exception) {
            println("Coomer fetch failed: $e")
            null
        }
    }

    // ------------------------------------------------------- models

    private data class Creator(
        @JsonProperty("id") val id: String = "",
        @JsonProperty("name") val name: String = "",
        @JsonProperty("service") val service: String = ""
    )

    private data class Post(
        @JsonProperty("id") val id: String = "",
        @JsonProperty("user") val user: String = "",
        @JsonProperty("service") val service: String = "",
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("substring") val substring: String? = null,
        @JsonProperty("published") val published: String? = null,
        @JsonProperty("file") val file: FileEntry = FileEntry(),
        @JsonProperty("attachments") val attachments: List<FileEntry> = emptyList()
    )

    private data class FileEntry(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("path") val path: String? = null
    )

    companion object {
        private const val GOOGLE_UA =
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.7103.48 Safari/537.36"
        private const val CREATORS_URL =
            "https://raw.githubusercontent.com/Kraptor123/Cs-GizliKeyif/refs/heads/master/.github/commer.json"

        private val postTypeRef: TypeReference<List<Post>> = object : TypeReference<List<Post>>() {}

        @Volatile private var creatorsCache: List<Creator>? = null
    }
}
