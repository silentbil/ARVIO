package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Clean-room `LoadResponse` hierarchy. Plugins consume the concrete subtypes
 * (`MovieLoadResponse`, `TvSeriesLoadResponse`, `AnimeLoadResponse`, etc.)
 * and expect to be able to mutate `plot`, `posterUrl`, `recommendations`,
 * `actors`, etc. after construction — hence the `var` fields.
 */
abstract class LoadResponse {
    abstract val name: String
    abstract val url: String
    abstract val apiName: String
    abstract val type: TvType
    abstract var posterUrl: String?
    abstract var year: Int?
    abstract var plot: String?
    abstract var rating: Int?
    abstract var tags: List<String>?
    abstract var duration: Int?
    abstract var trailers: MutableList<TrailerData>
    abstract var recommendations: List<SearchResponse>?
    abstract var actors: List<ActorData>?
    abstract var comingSoon: Boolean
    abstract var syncData: MutableMap<String, String>
    abstract var posterHeaders: Map<String, String>?
    abstract var backgroundPosterUrl: String?
    abstract var contentRating: String?
}

data class TrailerData(
    val extractorUrl: String,
    val referer: String? = null,
    val raw: Boolean = false,
    val mirros: List<ExtractorLink> = emptyList()
)

data class MovieLoadResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.Movie,
    val dataUrl: String,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var rating: Int? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var syncData: MutableMap<String, String> = mutableMapOf(),
    override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null,
    override var contentRating: String? = null
) : LoadResponse()

data class TvSeriesLoadResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.TvSeries,
    val episodes: List<Episode>,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    var showStatus: ShowStatus? = null,
    override var rating: Int? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var syncData: MutableMap<String, String> = mutableMapOf(),
    override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null,
    override var contentRating: String? = null
) : LoadResponse()

data class AnimeLoadResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.Anime,
    val episodes: Map<DubStatus, List<Episode>> = emptyMap(),
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    var showStatus: ShowStatus? = null,
    override var rating: Int? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var syncData: MutableMap<String, String> = mutableMapOf(),
    override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null,
    override var contentRating: String? = null,
    var engName: String? = null,
    var japName: String? = null
) : LoadResponse()

data class LiveStreamLoadResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.Live,
    val dataUrl: String,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var rating: Int? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var syncData: MutableMap<String, String> = mutableMapOf(),
    override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null,
    override var contentRating: String? = null
) : LoadResponse()

data class TorrentLoadResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.Torrent,
    val magnet: String? = null,
    val torrent: String? = null,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var rating: Int? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var syncData: MutableMap<String, String> = mutableMapOf(),
    override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null,
    override var contentRating: String? = null
) : LoadResponse()

enum class ShowStatus {
    Completed,
    Ongoing
}
