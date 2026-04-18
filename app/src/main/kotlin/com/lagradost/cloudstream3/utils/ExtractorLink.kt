package com.lagradost.cloudstream3.utils

/**
 * Clean-room re-implementation of CloudStream's `ExtractorLink`. Kept as an
 * open class (not data class) because plugins sometimes subclass it and the
 * real CloudStream shape mutates individual fields via synthetic setters.
 */
open class ExtractorLink(
    open val source: String,
    open val name: String,
    open val url: String,
    open val referer: String,
    open var quality: Int,
    open var isM3u8: Boolean = false,
    open var headers: Map<String, String> = emptyMap(),
    open var extractorData: String? = null,
    open var type: ExtractorLinkType? = null
) {
    /** Matches Kotlin property-setter shape the plugin dex refers to. */
    fun getQualityInt(): Int = quality
}

/**
 * Dedicated subtitle link — separate from `ExtractorLink` in upstream because
 * the player treats them differently.
 */
open class ExtractorSubtitleLink(
    name: String,
    url: String,
    referer: String,
    open val lang: String? = null,
    headers: Map<String, String> = emptyMap()
) : ExtractorLink(
    source = name,
    name = name,
    url = url,
    referer = referer,
    quality = Qualities.Unknown.value,
    headers = headers
)

enum class ExtractorLinkType {
    VIDEO,
    M3U8,
    DASH,
    TORRENT,
    MAGNET,
    LIVE
}

/**
 * `Qualities` is used as an enum by plugins but they access it as
 * `Qualities.P1080.value` — integer resolution hint. Matches upstream values.
 */
enum class Qualities(val value: Int) {
    Unknown(0),
    P144(144),
    P240(240),
    P360(360),
    P480(480),
    P720(720),
    P1080(1080),
    P1440(1440),
    P2160(2160),
    P4k(2160);

    companion object {
        fun getStringByInt(qual: Int?): String = when (qual) {
            in 1..143 -> "Unknown"
            in 144..239 -> "144p"
            in 240..359 -> "240p"
            in 360..479 -> "360p"
            in 480..719 -> "480p"
            in 720..1079 -> "720p"
            in 1080..1439 -> "1080p"
            in 1440..2159 -> "1440p"
            in 2160..Int.MAX_VALUE -> "4K"
            else -> "Unknown"
        }
    }
}

data class SubtitleFile(
    val lang: String,
    val url: String
)
