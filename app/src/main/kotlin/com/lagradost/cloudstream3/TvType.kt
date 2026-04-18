package com.lagradost.cloudstream3

/**
 * Clean-room re-implementation of CloudStream3's `TvType` enum, matching the
 * public API surface observable from published `.cs3` plugins on
 * github.com/recloudstream/extensions. Values kept in declaration order so
 * `ordinal`s remain stable across plugin versions that may compare on integer
 * serialization.
 */
enum class TvType {
    Movie,
    AnimeMovie,
    TvSeries,
    Cartoon,
    Anime,
    OVA,
    Torrent,
    Documentary,
    AsianDrama,
    Live,
    NSFW,
    Others,
    Music,
    AudioBook,
    Audio,
    CustomMedia
}

/** Kept so DexClassLoader-loaded plugins that reference these helpers resolve. */
fun TvType.isMovieType(): Boolean = when (this) {
    TvType.Movie,
    TvType.AnimeMovie,
    TvType.Torrent,
    TvType.Documentary,
    TvType.Others,
    TvType.Live,
    TvType.CustomMedia -> true
    else -> false
}

fun TvType.isLiveStream(): Boolean = this == TvType.Live

fun TvType.isAnimeOp(): Boolean = this == TvType.Anime || this == TvType.OVA
