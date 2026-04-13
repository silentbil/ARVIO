package com.arflix.tv.data.repository

import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.StreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudstreamProviderRuntime @Inject constructor() {
    suspend fun resolveMovieStreams(
        addons: List<Addon>,
        title: String,
        year: Int?
    ): List<StreamSource> = withContext(Dispatchers.IO) {
        // Cloudstream repository and install support is wired in v1, but provider execution
        // still depends on Cloudstream's larger runtime surface. Return no streams rather than
        // risking crashes in ARVIO's existing playback pipeline.
        emptyList()
    }

    suspend fun resolveEpisodeStreams(
        addons: List<Addon>,
        title: String,
        year: Int?,
        season: Int,
        episode: Int,
        airDate: String?
    ): List<StreamSource> = withContext(Dispatchers.IO) {
        emptyList()
    }
}
