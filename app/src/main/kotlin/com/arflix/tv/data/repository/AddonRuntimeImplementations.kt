package com.arflix.tv.data.repository

import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.RuntimeKind
import com.arflix.tv.data.model.StreamSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class StremioAddonRuntime(
    private val movieResolver: suspend (Addon, String) -> List<StreamSource>,
    private val episodeResolver: suspend (Addon, EpisodeRuntimeRequest) -> List<StreamSource>
) : AddonRuntime {
    override val kind: RuntimeKind = RuntimeKind.STREMIO

    override suspend fun resolveMovieStreams(
        addons: List<Addon>,
        request: MovieRuntimeRequest
    ): List<StreamSource> = coroutineScope {
        addons.map { addon -> async { movieResolver(addon, request.imdbId) } }
            .awaitAll()
            .flatten()
    }

    override suspend fun resolveEpisodeStreams(
        addons: List<Addon>,
        request: EpisodeRuntimeRequest
    ): List<StreamSource> = coroutineScope {
        addons.map { addon -> async { episodeResolver(addon, request) } }
            .awaitAll()
            .flatten()
    }
}

class CloudstreamAddonRuntime(
    private val providerRuntime: CloudstreamProviderRuntime
) : AddonRuntime {
    override val kind: RuntimeKind = RuntimeKind.CLOUDSTREAM

    override suspend fun resolveMovieStreams(
        addons: List<Addon>,
        request: MovieRuntimeRequest
    ): List<StreamSource> {
        return providerRuntime.resolveMovieStreams(
            addons = addons,
            title = request.title,
            year = request.year
        )
    }

    override suspend fun resolveEpisodeStreams(
        addons: List<Addon>,
        request: EpisodeRuntimeRequest
    ): List<StreamSource> {
        return providerRuntime.resolveEpisodeStreams(
            addons = addons,
            title = request.title,
            year = null,
            season = request.season,
            episode = request.episode,
            airDate = request.airDate
        )
    }
}
