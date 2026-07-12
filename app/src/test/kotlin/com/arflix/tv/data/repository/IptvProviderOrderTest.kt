package com.arflix.tv.data.repository

import com.arflix.tv.data.model.IptvChannel
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IptvProviderOrderTest {

    @Test
    fun mergeUsesPlaylistOrderAndKeepsXtreamMetadataAndIds() {
        val apiChannels = listOf(
            apiChannel(101, "API One", "API News"),
            apiChannel(102, "API Two", "API Sports"),
            apiChannel(103, "API Three", "API Sports", catchupDays = 7),
            apiChannel(104, "API Only", "API Extra"),
        )
        val providerPlaylist = listOf(
            playlistChannel(103, "Provider Three", "Provider Sports"),
            playlistChannel(101, "Provider One", "Provider News"),
            playlistChannel(102, "Provider Two", "Provider Sports"),
        )

        val merged = mergeXtreamChannelsInProviderOrder(providerPlaylist, apiChannels)

        assertThat(merged.map { it.id })
            .containsExactly("xtream:103", "xtream:101", "xtream:102", "xtream:104")
            .inOrder()
        assertThat(merged.take(3).map { it.name })
            .containsExactly("Provider Three", "Provider One", "Provider Two")
            .inOrder()
        assertThat(merged.take(3).map { it.group })
            .containsExactly("Provider Sports", "Provider News", "Provider Sports")
            .inOrder()
        assertThat(merged.first().catchupDays).isEqualTo(7)
        assertThat(merged.first().xtreamStreamId).isEqualTo(103)
    }

    @Test
    fun mergeWithoutApiStillPreservesPlaylistSequence() {
        val providerPlaylist = listOf(
            playlistChannel(30, "Third", "Group B"),
            playlistChannel(10, "First", "Group A"),
            playlistChannel(20, "Second", "Group A"),
        )

        val merged = mergeXtreamChannelsInProviderOrder(providerPlaylist, emptyList())

        assertThat(merged.map { it.name }).containsExactly("Third", "First", "Second").inOrder()
        assertThat(merged.map { it.xtreamStreamId }).containsExactly(30, 10, 20).inOrder()
    }

    private fun apiChannel(
        streamId: Int,
        name: String,
        group: String,
        catchupDays: Int = 0,
    ): IptvChannel = IptvChannel(
        id = "xtream:$streamId",
        name = name,
        streamUrl = "https://provider.test/live/user/pass/$streamId.ts",
        group = group,
        xtreamStreamId = streamId,
        catchupDays = catchupDays,
        catchupType = if (catchupDays > 0) "xtream" else null,
    )

    private fun playlistChannel(streamId: Int, name: String, group: String): IptvChannel = IptvChannel(
        id = "playlist-$streamId",
        name = name,
        streamUrl = "https://provider.test/live/user/pass/$streamId.ts",
        group = group,
        epgId = "epg-$streamId",
    )
}
