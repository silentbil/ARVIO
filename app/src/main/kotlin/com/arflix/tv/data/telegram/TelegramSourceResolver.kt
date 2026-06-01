package com.arflix.tv.data.telegram

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.data.model.StreamSource
import com.arflix.tv.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramSourceResolver @Inject constructor(
    private val repository: TelegramRepository,
    private val matcher: TelegramSearchMatcher,
    private val tmdbApi: TmdbApi,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "TelegramResolver"
        private const val SCORE_THRESHOLD = 55
        private const val SEARCH_TIMEOUT_MS = 20_000L
        private const val MAX_RESULTS = 50
    }

    fun isEnabled(): Boolean = repository.isAuthenticated()

    suspend fun resolve(
        title: String,
        year: Int?,
        season: Int? = null,
        episode: Int? = null,
        imdbId: String = "",
        isMovie: Boolean = true
    ): List<StreamSource> {
        if (!repository.isAuthenticated()) return emptyList()

        return try {
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                resolveInternal(title, year, season, episode, imdbId, isMovie)
            } ?: emptyList<StreamSource>().also {
                Log.w(TAG, "Telegram search timed out for '$title'")
                showToast("Telegram search timed out")
            }
        } catch (e: TelegramApiException) {
            Log.w(TAG, "Telegram API error for '$title': ${e.message}")
            showToast("Telegram: ${e.message}")
            emptyList()
        }
    }

    private suspend fun resolveInternal(
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        imdbId: String,
        isMovie: Boolean
    ): List<StreamSource> {
        val excludedIds = repository.getExcludedChatIds().first()
        val hebrewTitle = fetchHebrewTitle(imdbId, isMovie)

        val queries = if (season != null && episode != null)
            matcher.buildSeriesQueries(title, season, episode, hebrewTitle)
        else
            matcher.buildMovieQueries(title, year, hebrewTitle)

        val seen = mutableSetOf<Pair<String, Long>>()
        val allMessages = mutableListOf<TelegramVideoMessage>()

        for (query in queries) {
            try {
                val results = repository.searchVideoMessages(query, MAX_RESULTS)
                    .filter { it.chatId !in excludedIds }
                for (msg in results) {
                    if (seen.add(msg.fileName to msg.fileSize)) allMessages.add(msg)
                }
            } catch (e: TelegramApiException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Search failed for '$query'", e)
            }
        }

        return allMessages
            .mapNotNull { msg ->
                val score = matcher.score(
                    fileName = msg.fileName,
                    caption = msg.caption,
                    title = title,
                    hebrewTitle = hebrewTitle,
                    year = year,
                    season = season,
                    episode = episode
                )
                if (score < SCORE_THRESHOLD) null else msg
            }
            .map { msg ->
                val streamUrl = repository.getStreamUrl(msg.fileId)
                val displayName = if (msg.fileName == "Default_Name.mkv" || msg.fileName == "Default_Name.mp4")
                    msg.caption.takeIf { it.isNotBlank() } ?: msg.fileName
                else msg.fileName
                val quality = parseQuality("${msg.fileName} ${msg.caption}")
                StreamSource(
                    source = displayName,
                    addonName = "Telegram",
                    addonId = "telegram_native",
                    quality = quality,
                    size = formatBytes(msg.fileSize),
                    sizeBytes = msg.fileSize,
                    url = streamUrl,
                    infoHash = null,
                    fileIdx = null,
                    behaviorHints = com.arflix.tv.data.model.StreamBehaviorHints(
                        notWebReady = false,
                        filename = msg.fileName,
                        videoSize = msg.fileSize
                    ),
                    subtitles = emptyList(),
                    sources = emptyList(),
                    description = msg.caption.takeIf { it.isNotBlank() }
                )
            }
            .sortedWith(
                compareByDescending<StreamSource> { qualityTier(it.quality) }
                    .thenByDescending { it.sizeBytes ?: 0L }
            )
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun qualityTier(quality: String): Int = when (quality) {
        "4K" -> 4
        "1080p" -> 3
        "720p" -> 2
        "480p" -> 1
        else -> 0
    }

    private suspend fun fetchHebrewTitle(imdbId: String, isMovie: Boolean): String? {
        if (imdbId.isBlank()) return null
        return try {
            val findResult = tmdbApi.findByExternalId(imdbId, Constants.TMDB_API_KEY)
            val tmdbId = if (isMovie)
                findResult.movieResults.firstOrNull()?.id
            else
                findResult.tvResults.firstOrNull()?.id
            tmdbId ?: return null
            if (isMovie)
                tmdbApi.getMovieDetails(tmdbId, Constants.TMDB_API_KEY, language = "he").title
                    .takeIf { it.isNotBlank() }
            else
                tmdbApi.getTvDetails(tmdbId, Constants.TMDB_API_KEY, language = "he").name
                    .takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch Hebrew title for $imdbId: ${e.message}")
            null
        }
    }

    private fun parseQuality(fileName: String): String = when {
        fileName.contains("2160p", ignoreCase = true) || fileName.contains("4K", ignoreCase = true) -> "4K"
        fileName.contains("1080p", ignoreCase = true) -> "1080p"
        fileName.contains("720p", ignoreCase = true) -> "720p"
        fileName.contains("480p", ignoreCase = true) -> "480p"
        else -> "Unknown"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        else -> "%.0f KB".format(bytes / 1_000.0)
    }
}
