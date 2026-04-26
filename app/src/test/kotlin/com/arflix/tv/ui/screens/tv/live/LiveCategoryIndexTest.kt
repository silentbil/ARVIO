package com.arflix.tv.ui.screens.tv.live

import com.arflix.tv.data.model.IptvChannel
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveCategoryIndexTest {

    @Test
    fun channelsForKeepsFavoriteOrderAndUsesStaticBuckets() {
        val channels = listOf(
            channel("1", "NL News HD", "NL | News"),
            channel("2", "US Sports 4K", "US | Sports"),
            channel("3", "Kids SD", "Kids"),
        ).mapIndexed { index, channel -> channel.enrich(index + 100) }

        val index = buildCategoryIndex(channels)

        assertThat(index.channelsFor("fav", favorites = listOf("2", "1"), recents = emptyList()).map { it.id })
            .containsExactly("2", "1")
            .inOrder()
        assertThat(index.channelsFor("g-sports", favorites = emptyList(), recents = emptyList()).map { it.id })
            .containsExactly("2")
        assertThat(index.channelsFor("NL-news", favorites = emptyList(), recents = emptyList()).map { it.id })
            .containsExactly("1")
    }

    @Test
    fun channelsForReturnsNewestRecentFirst() {
        val channels = listOf(
            channel("1", "One", "General"),
            channel("2", "Two", "General"),
            channel("3", "Three", "General"),
        ).mapIndexed { index, channel -> channel.enrich(index + 100) }
        val recents = linkedSetOf("1", "3", "2")

        val index = buildCategoryIndex(channels)

        assertThat(index.channelsFor("recent", favorites = emptyList(), recents = recents).map { it.id })
            .containsExactly("2", "3", "1")
            .inOrder()
    }

    private fun channel(id: String, name: String, group: String): IptvChannel =
        IptvChannel(
            id = id,
            name = name,
            streamUrl = "https://example.test/$id.m3u8",
            group = group,
        )
}
