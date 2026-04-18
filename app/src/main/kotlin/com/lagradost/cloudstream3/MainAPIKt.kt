@file:Suppress("unused", "FunctionName")

package com.lagradost.cloudstream3

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Helper-function overloads that CloudStream plugins call via top-level
 * `MainAPIKt.*` references. The names and argument shapes match upstream so
 * the dex-level references from published `.cs3` plugins resolve at load
 * time (Kotlin compiles top-level extension functions into a `{File}Kt`
 * class on the JVM, which the dex loader looks up by name).
 */

private val sharedMapper: JsonMapper = jacksonObjectMapper() as JsonMapper

/** Used by plugins as `MainAPIKt.getMapper()` for JSON parse/serialize. */
fun getMapper(): JsonMapper = sharedMapper

/**
 * Build a [MovieSearchResponse] and let the caller mutate it in a trailing
 * lambda (upstream uses the same pattern). The `construct` block runs
 * synchronously on the freshly built response so plugins can set
 * `posterUrl`, `year`, `quality`, etc. without re-allocating.
 */
fun MainAPI.newMovieSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Movie,
    fix: Boolean = true,
    construct: MovieSearchResponse.() -> Unit = {}
): MovieSearchResponse = MovieSearchResponse(
    name = name,
    url = fixUrlNull(url) ?: url,
    apiName = this.name,
    type = type
).apply(construct)

fun MainAPI.newTvSeriesSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.TvSeries,
    fix: Boolean = true,
    construct: TvSeriesSearchResponse.() -> Unit = {}
): TvSeriesSearchResponse = TvSeriesSearchResponse(
    name = name,
    url = fixUrlNull(url) ?: url,
    apiName = this.name,
    type = type
).apply(construct)

fun MainAPI.newAnimeSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Anime,
    fix: Boolean = true,
    construct: AnimeSearchResponse.() -> Unit = {}
): AnimeSearchResponse = AnimeSearchResponse(
    name = name,
    url = fixUrlNull(url) ?: url,
    apiName = this.name,
    type = type
).apply(construct)

fun MainAPI.newLiveSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Live,
    fix: Boolean = true,
    construct: LiveSearchResponse.() -> Unit = {}
): LiveSearchResponse = LiveSearchResponse(
    name = name,
    url = fixUrlNull(url) ?: url,
    apiName = this.name,
    type = type
).apply(construct)

suspend fun MainAPI.newMovieLoadResponse(
    name: String,
    url: String,
    type: TvType = TvType.Movie,
    dataUrl: String,
    construct: suspend MovieLoadResponse.() -> Unit = {}
): MovieLoadResponse = MovieLoadResponse(
    name = name,
    url = fixUrlNull(url) ?: url,
    apiName = this.name,
    type = type,
    dataUrl = dataUrl
).also { it.construct() }

suspend fun MainAPI.newTvSeriesLoadResponse(
    name: String,
    url: String,
    type: TvType = TvType.TvSeries,
    episodes: List<Episode>,
    construct: suspend TvSeriesLoadResponse.() -> Unit = {}
): TvSeriesLoadResponse = TvSeriesLoadResponse(
    name = name,
    url = fixUrlNull(url) ?: url,
    apiName = this.name,
    type = type,
    episodes = episodes
).also { it.construct() }

suspend fun MainAPI.newAnimeLoadResponse(
    name: String,
    url: String,
    type: TvType = TvType.Anime,
    episodes: Map<DubStatus, List<Episode>> = emptyMap(),
    construct: suspend AnimeLoadResponse.() -> Unit = {}
): AnimeLoadResponse = AnimeLoadResponse(
    name = name,
    url = fixUrlNull(url) ?: url,
    apiName = this.name,
    type = type,
    episodes = episodes
).also { it.construct() }

suspend fun MainAPI.newLiveStreamLoadResponse(
    name: String,
    url: String,
    dataUrl: String,
    construct: suspend LiveStreamLoadResponse.() -> Unit = {}
): LiveStreamLoadResponse = LiveStreamLoadResponse(
    name = name,
    url = fixUrlNull(url) ?: url,
    apiName = this.name,
    dataUrl = dataUrl
).also { it.construct() }

fun newEpisode(
    data: String,
    construct: Episode.() -> Unit = {}
): Episode = Episode(data = data).apply(construct)

fun newHomePageResponse(
    name: String,
    list: List<SearchResponse>,
    hasNext: Boolean = false
): HomePageResponse = HomePageResponse(
    items = listOf(HomePageList(name = name, list = list)),
    hasNext = hasNext
)

fun newHomePageResponse(
    request: MainPageRequest,
    list: List<SearchResponse>,
    hasNext: Boolean = false
): HomePageResponse = HomePageResponse(
    items = listOf(HomePageList(name = request.name, list = list, isHorizontalImages = request.horizontalImages)),
    hasNext = hasNext
)

fun newHomePageResponse(
    list: List<HomePageList>,
    hasNext: Boolean = false
): HomePageResponse = HomePageResponse(items = list, hasNext = hasNext)

/** URL normalization helpers that plugins call to absolutize hrefs. */
fun fixUrl(url: String, baseUrl: String): String {
    if (url.startsWith("http")) return url
    if (url.startsWith("//")) return "https:$url"
    if (url.startsWith("/")) return "${baseUrl.trimEnd('/')}$url"
    return "${baseUrl.trimEnd('/')}/$url"
}

fun fixUrlNull(url: String?): String? = url?.takeIf { it.isNotBlank() }

fun MainAPI.fixUrl(url: String): String = fixUrl(url, mainUrl)
fun MainAPI.fixUrlNull(url: String?): String? = url?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }

/** Called by plugins to infer `TvType` from a URL fragment or title. */
fun getQualityFromName(name: String?): Int {
    val n = (name ?: "").lowercase()
    return when {
        "2160" in n || "4k" in n -> com.lagradost.cloudstream3.utils.Qualities.P2160.value
        "1440" in n -> com.lagradost.cloudstream3.utils.Qualities.P1440.value
        "1080" in n -> com.lagradost.cloudstream3.utils.Qualities.P1080.value
        "720" in n -> com.lagradost.cloudstream3.utils.Qualities.P720.value
        "480" in n -> com.lagradost.cloudstream3.utils.Qualities.P480.value
        "360" in n -> com.lagradost.cloudstream3.utils.Qualities.P360.value
        "240" in n -> com.lagradost.cloudstream3.utils.Qualities.P240.value
        "144" in n -> com.lagradost.cloudstream3.utils.Qualities.P144.value
        else -> com.lagradost.cloudstream3.utils.Qualities.Unknown.value
    }
}

/**
 * `ExtractorLink` constructor wrapper preferred by real plugins — the
 * property names match upstream so dex-level setter calls resolve.
 */
fun newExtractorLink(
    source: String,
    name: String,
    url: String,
    referer: String = "",
    construct: ExtractorLink.() -> Unit = {}
): ExtractorLink = ExtractorLink(
    source = source,
    name = name,
    url = url,
    referer = referer,
    quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value
).apply(construct)
