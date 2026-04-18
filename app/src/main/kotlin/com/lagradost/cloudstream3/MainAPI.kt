package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile

/**
 * Clean-room re-implementation of CloudStream3's `MainAPI` abstract class.
 * All real `.cs3` plugins extend this. Signatures match the public contract
 * observable in the published plugin dex: `search`, `load`, `loadLinks`,
 * `getMainPage` are suspend functions; `mainUrl` / `name` / `supportedTypes`
 * are mutable so subclasses can override via assignment in init blocks.
 */
abstract class MainAPI {
    open var name: String = "MainAPI"
    open var mainUrl: String = ""
    open var storedCredentials: String? = null
    open var canBeOverridden: Boolean = true
    open var lang: String = "en"

    /** Controls whether the search/load/loadLinks paths are exercised. */
    open var supportedTypes: Set<TvType> = setOf(TvType.Movie, TvType.TvSeries)
    open var hasMainPage: Boolean = false
    open var hasQuickSearch: Boolean = false
    open var hasChromecastSupport: Boolean = true
    open var hasDownloadSupport: Boolean = true
    open var vpnStatus: VPNStatus = VPNStatus.None
    open var providerType: ProviderType = ProviderType.MetaProvider
    open var sequentialMainPage: Boolean = false
    open var sequentialMainPageDelay: Long = 0L
    open var sequentialMainPageScrollDelay: Long = 0L

    open var mainPage: List<MainPageRequest> = emptyList()

    /**
     * Return discovery rows for the home page. Plugins that set
     * `hasMainPage = true` override this.
     */
    open suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? = null

    /**
     * Quick search is a latency-sensitive variant used when the user types.
     * Most plugins delegate to `search`.
     */
    open suspend fun quickSearch(query: String): List<SearchResponse>? = null

    /**
     * Free-form search. Must return at least an empty list (not null) so the
     * app can differentiate "no results" from "not implemented".
     */
    open suspend fun search(query: String): List<SearchResponse>? = null

    /**
     * Resolve the detail page for a search hit. The plugin receives the `url`
     * that the `SearchResponse.url` carried. Returning null means the item
     * is unresolvable and should be skipped.
     */
    open suspend fun load(url: String): LoadResponse? = null

    /**
     * Extract playable links. `data` is whatever the plugin put into the
     * LoadResponse's `dataUrl` for movies, or `Episode.data` for TV episodes.
     * Plugins push results via the two callbacks and return `true` on success.
     */
    open suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = false
}

enum class VPNStatus {
    None,
    MightBeNeeded,
    Torrent
}

enum class ProviderType {
    MetaProvider,
    MainProvider
}
