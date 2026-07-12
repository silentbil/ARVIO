package com.arflix.tv.data.repository

import com.arflix.tv.data.model.IptvChannel

/**
 * Uses the provider's M3U sequence as the canonical channel order while retaining
 * richer Xtream API metadata and the stable Xtream channel ids used by favorites.
 */
internal fun mergeXtreamChannelsInProviderOrder(
    playlistChannels: List<IptvChannel>,
    apiChannels: List<IptvChannel>,
): List<IptvChannel> {
    if (playlistChannels.isEmpty()) return apiChannels
    if (apiChannels.isEmpty()) {
        return playlistChannels.map { channel ->
            channel.copy(xtreamStreamId = channel.xtreamStreamId ?: providerOrderXtreamStreamId(channel))
        }
    }

    val apiByStreamId = LinkedHashMap<Int, IptvChannel>(apiChannels.size)
    apiChannels.forEach { channel ->
        providerOrderXtreamStreamId(channel)?.let { streamId ->
            apiByStreamId.putIfAbsent(streamId, channel)
        }
    }

    val emittedApiIds = HashSet<String>(apiChannels.size)
    val ordered = ArrayList<IptvChannel>(maxOf(playlistChannels.size, apiChannels.size))
    playlistChannels.forEach { playlistChannel ->
        val streamId = providerOrderXtreamStreamId(playlistChannel)
        val apiChannel = streamId?.let(apiByStreamId::get)
        if (apiChannel == null) {
            ordered += playlistChannel.copy(
                xtreamStreamId = playlistChannel.xtreamStreamId ?: streamId,
            )
            return@forEach
        }

        if (!emittedApiIds.add(apiChannel.id)) return@forEach
        ordered += apiChannel.copy(
            name = playlistChannel.name.ifBlank { apiChannel.name },
            streamUrl = playlistChannel.streamUrl.ifBlank { apiChannel.streamUrl },
            group = playlistChannel.group.ifBlank { apiChannel.group },
            logo = playlistChannel.logo ?: apiChannel.logo,
            epgId = playlistChannel.epgId ?: apiChannel.epgId,
            rawTitle = playlistChannel.rawTitle.ifBlank { apiChannel.rawTitle },
            xtreamStreamId = apiChannel.xtreamStreamId ?: streamId,
            catchupDays = maxOf(playlistChannel.catchupDays, apiChannel.catchupDays),
            catchupType = playlistChannel.catchupType ?: apiChannel.catchupType,
            catchupSource = playlistChannel.catchupSource ?: apiChannel.catchupSource,
            tvgName = playlistChannel.tvgName ?: apiChannel.tvgName,
            providerChannelNumber = playlistChannel.providerChannelNumber ?: apiChannel.providerChannelNumber,
            requestHeaders = apiChannel.requestHeaders + playlistChannel.requestHeaders,
            language = playlistChannel.language ?: apiChannel.language,
            country = playlistChannel.country ?: apiChannel.country,
            qualityLabel = playlistChannel.qualityLabel ?: apiChannel.qualityLabel,
            variantKey = playlistChannel.variantKey ?: apiChannel.variantKey,
            drmInfo = playlistChannel.drmInfo ?: apiChannel.drmInfo,
        )
    }

    apiChannels.forEach { channel ->
        if (emittedApiIds.add(channel.id)) ordered += channel
    }
    return ordered
}

private fun providerOrderXtreamStreamId(channel: IptvChannel): Int? {
    channel.xtreamStreamId?.let { return it }

    val idMatch = XTREAM_ID_SUFFIX.find(channel.id)
    idMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

    val path = channel.streamUrl
        .substringBefore('?')
        .substringBefore('#')
        .trimEnd('/')
    return path.substringAfterLast('/')
        .substringBefore('.')
        .toIntOrNull()
}

private val XTREAM_ID_SUFFIX = Regex("(?:^|:)xtream:(\\d+)$")
