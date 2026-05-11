package com.arflix.tv.data.repository

import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.RuntimeKind
import com.arflix.tv.data.model.StreamSource

class AddonRuntimeAggregator(
    private val addonRuntimes: Map<RuntimeKind, AddonRuntime>
) {
    suspend fun resolveMovieStreams(
        stremioAddons: List<Addon>,
        request: MovieRuntimeRequest
    ): List<StreamSource> {
        val streams = mutableListOf<StreamSource>()
        if (stremioAddons.isNotEmpty()) {
            streams += addonRuntimes[RuntimeKind.STREMIO]
                ?.resolveMovieStreams(stremioAddons, request)
                .orEmpty()
        }
        return streams
    }

    suspend fun resolveEpisodeStreams(
        stremioAddons: List<Addon>,
        request: EpisodeRuntimeRequest
    ): List<StreamSource> {
        val streams = mutableListOf<StreamSource>()
        if (stremioAddons.isNotEmpty()) {
            streams += addonRuntimes[RuntimeKind.STREMIO]
                ?.resolveEpisodeStreams(stremioAddons, request)
                .orEmpty()
        }
        return streams
    }
}
