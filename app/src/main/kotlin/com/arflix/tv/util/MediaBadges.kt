package com.arflix.tv.util

import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val releaseDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

fun isInCinema(item: MediaItem, now: LocalDate = LocalDate.now()): Boolean {
    if (item.mediaType != MediaType.MOVIE) return false
    val releaseDate = item.releaseDate?.takeIf { it.isNotBlank() } ?: return false
    val parsedDate = kotlin.runCatching {
        LocalDate.parse(releaseDate, releaseDateFormatter)
    }.getOrNull() ?: return false

    if (parsedDate.isAfter(now)) return false
    return ChronoUnit.DAYS.between(parsedDate, now) < 60
}

fun parseRatingValue(raw: String): Float {
    if (raw.isBlank()) return 0f
    return raw.trim().replace(',', '.').toFloatOrNull() ?: 0f
}
