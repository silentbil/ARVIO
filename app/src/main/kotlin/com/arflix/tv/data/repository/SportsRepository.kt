package com.arflix.tv.data.repository

import com.arflix.tv.data.api.StremioMetaPreview
import com.arflix.tv.data.api.StremioStream
import com.arflix.tv.data.api.StreamApi
import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.AddonCatalog
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.ProxyHeaders
import com.arflix.tv.data.model.SportsAddonCapabilities
import com.arflix.tv.data.model.StreamBehaviorHints
import com.arflix.tv.data.model.StreamSource
import com.arflix.tv.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SportsRepository @Inject constructor(
    private val streamRepository: StreamRepository,
    private val streamApi: StreamApi
) {
    data class SportsPlayback(
        val mediaId: Int,
        val title: String,
        val streamUrl: String,
        val addonId: String,
        val sourceName: String
    )

    private data class SportsCategoryDef(
        val id: String,
        val title: String,
        val keywords: Set<String>
    )

    private data class ParsedEventStatus(
        val addonId: String,
        val type: String,
        val eventId: String
    )

    private val sportsCategories = listOf(
        SportsCategoryDef("football", "Football", setOf("football", "soccer")),
        SportsCategoryDef("basketball", "Basketball", setOf("basketball", "nba")),
        SportsCategoryDef("tennis", "Tennis", setOf("tennis", "atp", "wta")),
        SportsCategoryDef("motorsport", "Motorsport", setOf("motorsport", "formula", "f1", "racing")),
        SportsCategoryDef("rugby", "Rugby", setOf("rugby")),
        SportsCategoryDef("hockey", "Hockey", setOf("hockey", "nhl")),
        SportsCategoryDef("baseball", "Baseball", setOf("baseball", "mlb")),
        SportsCategoryDef("combat", "Combat Sports", setOf("boxing", "ufc", "mma", "combat"))
    )

    fun defaultHomeRows(): List<Category> = buildLockedRows()

    suspend fun buildHomeRows(
        addons: List<Addon>,
        selectedSportId: String? = null
    ): List<Category> = withContext(Dispatchers.IO) {
        val sportsAddons = addons.filter { addon ->
            addon.isInstalled &&
                addon.isEnabled &&
                !addon.url.isNullOrBlank() &&
                SportsAddonCapabilities.isSportsLiveTvAddon(addon)
        }

        if (sportsAddons.isEmpty()) return@withContext buildLockedRows()

        val liveRow = loadPopularLiveTvCategory(
            addons = sportsAddons,
            selectedSportId = selectedSportId
        ) ?: Category(
            id = SportsAddonCapabilities.POPULAR_LIVE_TV_ROW_ID,
            title = "Popular Live TV",
            items = listOf(
                placeholderItem(
                    key = "empty",
                    title = "No live events found",
                    subtitle = "Sports addon",
                    overview = "The installed sports addon did not return live event cards yet."
                )
            )
        )

        listOf(sportsCategoryRow(), liveRow)
    }

    fun mergeSportsRows(
        categories: List<Category>,
        sportsRows: List<Category>
    ): List<Category> {
        if (sportsRows.isEmpty()) return categories
        val base = categories.filterNot { category ->
            category.id == SportsAddonCapabilities.SPORTS_CATEGORY_ROW_ID ||
                category.id == SportsAddonCapabilities.POPULAR_LIVE_TV_ROW_ID
        }
        val insertAfter = maxOf(
            base.indexOfFirst { it.id == "continue_watching" },
            base.indexOfFirst { it.id == HomeViewModelFavorites.favoriteTvCategoryId }
        )
        val insertIndex = if (insertAfter >= 0) insertAfter + 1 else 0
        return base.take(insertIndex) + sportsRows + base.drop(insertIndex)
    }

    fun selectedSportIdFromStatus(status: String?): String? {
        if (!SportsAddonCapabilities.isSportsCategoryStatus(status)) return null
        return status?.removePrefix(SportsAddonCapabilities.SPORTS_STATUS_PREFIX)
            ?.takeIf { it.isNotBlank() }
    }

    suspend fun resolvePlayback(status: String, title: String): SportsPlayback? = withContext(Dispatchers.IO) {
        val parsed = parseEventStatus(status) ?: return@withContext null
        val addon = streamRepository.installedAddons.first().firstOrNull { addon ->
            addon.id == parsed.addonId &&
                addon.isInstalled &&
                addon.isEnabled &&
                SportsAddonCapabilities.isSportsLiveTvAddon(addon)
        } ?: return@withContext null

        val baseUrl = addonBaseUrl(addon) ?: return@withContext null
        val requestUrl = "$baseUrl/stream/${encodePathSegment(parsed.type)}/${encodePathSegment(parsed.eventId)}.json"

        val streams = runCatching { streamApi.getAddonStreams(requestUrl).streams.orEmpty() }
            .onFailure { error ->
                AppLogger.recordException(
                    throwable = error,
                    context = mapOf(
                        "error_area" to "SportsRepository",
                        "sports_phase" to "resolve_stream",
                        "addon_id" to addon.id
                    )
                )
            }
            .getOrDefault(emptyList())

        val source = streams.asSequence()
            .mapNotNull { stream -> stream.toStreamSource(addon, fallbackTitle = title) }
            .firstOrNull { source -> !source.url.isNullOrBlank() }
            ?: return@withContext null

        val resolved = streamRepository.resolveStreamForPlayback(source) ?: source
        val streamUrl = resolved.url?.takeIf { it.isNotBlank() } ?: return@withContext null
        SportsPlayback(
            mediaId = SportsAddonCapabilities.sportsSyntheticId("${addon.id}:${parsed.type}:${parsed.eventId}"),
            title = title,
            streamUrl = streamUrl,
            addonId = addon.id,
            sourceName = resolved.source.ifBlank { title }
        )
    }

    private suspend fun loadPopularLiveTvCategory(
        addons: List<Addon>,
        selectedSportId: String?
    ): Category? {
        val items = mutableListOf<MediaItem>()
        for (addon in addons) {
            val catalogs = candidateCatalogs(addon, selectedSportId)
            for (catalog in catalogs.take(3)) {
                val baseUrl = addonBaseUrl(addon) ?: continue
                val url = "$baseUrl/catalog/${encodePathSegment(catalog.type)}/${encodePathSegment(catalog.id)}.json"
                val metas = runCatching {
                    val response = streamApi.getAddonCatalog(url)
                    response.metas ?: response.items ?: emptyList()
                }.onFailure { error ->
                    AppLogger.breadcrumb(
                        tag = "Sports",
                        message = "sports_catalog_failed addon=${addon.id} catalog=${catalog.id} error=${error::class.java.simpleName}",
                        severity = "warning"
                    )
                }.getOrDefault(emptyList())

                metas.mapNotNullTo(items) { meta -> meta.toMediaItem(addon, catalog) }
                if (items.size >= MAX_EVENT_ITEMS) break
            }
            if (items.size >= MAX_EVENT_ITEMS) break
        }

        if (items.isEmpty()) return null
        val title = selectedSportId
            ?.let { id -> sportsCategories.firstOrNull { it.id == id }?.title }
            ?.let { "$it Live" }
            ?: "Popular Live TV"

        return Category(
            id = SportsAddonCapabilities.POPULAR_LIVE_TV_ROW_ID,
            title = title,
            items = items.distinctBy { it.status }.take(MAX_EVENT_ITEMS)
        )
    }

    private fun buildLockedRows(): List<Category> = listOf(
        sportsCategoryRow(),
        Category(
            id = SportsAddonCapabilities.POPULAR_LIVE_TV_ROW_ID,
            title = "Popular Live TV",
            items = listOf(
                placeholderItem(
                    key = "locked",
                    title = "Add Sports Live TV Addon",
                    subtitle = "Required for playback",
                    overview = "Install a sports live TV addon before ARVIO can show or play live sports events.",
                    badge = "LOCKED"
                )
            )
        )
    )

    private fun sportsCategoryRow(): Category = Category(
        id = SportsAddonCapabilities.SPORTS_CATEGORY_ROW_ID,
        title = "Sports",
        items = sportsCategories.map { sport ->
            MediaItem(
                id = SportsAddonCapabilities.sportsSyntheticId("${SportsAddonCapabilities.SPORTS_STATUS_PREFIX}${sport.id}"),
                title = sport.title,
                subtitle = "Sports",
                overview = "Browse ${sport.title.lowercase(Locale.US)} events from your installed sports live TV addon.",
                mediaType = MediaType.TV,
                badge = "SPORT",
                status = "${SportsAddonCapabilities.SPORTS_STATUS_PREFIX}${sport.id}"
            )
        }
    )

    private fun placeholderItem(
        key: String,
        title: String,
        subtitle: String,
        overview: String,
        badge: String? = null
    ): MediaItem = MediaItem(
        id = SportsAddonCapabilities.sportsSyntheticId("${SportsAddonCapabilities.SPORTS_LOCKED_STATUS_PREFIX}$key"),
        title = title,
        subtitle = subtitle,
        overview = overview,
        mediaType = MediaType.TV,
        badge = badge,
        status = "${SportsAddonCapabilities.SPORTS_LOCKED_STATUS_PREFIX}$key"
    )

    private fun candidateCatalogs(addon: Addon, selectedSportId: String?): List<AddonCatalog> {
        val sport = selectedSportId?.let { id -> sportsCategories.firstOrNull { it.id == id } }
        val catalogs = addon.manifest?.catalogs.orEmpty()
            .filter { catalog ->
                when {
                    sport != null -> catalog.matchesSport(sport)
                    else -> SportsAddonCapabilities.isLiveSportsCatalog(catalog)
                }
            }
            .sortedWith(
                compareByDescending<AddonCatalog> { catalog ->
                    catalog.text().contains("popular") || catalog.text().contains("live")
                }.thenBy { it.name.ifBlank { it.id } }
            )

        if (catalogs.isNotEmpty()) return catalogs
        return addon.manifest?.catalogs.orEmpty()
            .filter(SportsAddonCapabilities::isSportsCatalog)
    }

    private fun StremioMetaPreview.toMediaItem(addon: Addon, catalog: AddonCatalog): MediaItem? {
        val eventId = id?.takeIf { it.isNotBlank() } ?: return null
        val eventType = type?.takeIf { it.isNotBlank() } ?: catalog.type
        val status = eventStatus(addon.id, eventType, eventId)
        val title = name?.takeIf { it.isNotBlank() } ?: return null
        val genreText = genres.orEmpty().firstOrNull().orEmpty()
        return MediaItem(
            id = SportsAddonCapabilities.sportsSyntheticId(status),
            title = title,
            subtitle = listOf(genreText, addon.name).filter { it.isNotBlank() }.joinToString(" | "),
            overview = description.orEmpty(),
            year = year ?: released?.take(4).orEmpty(),
            releaseDate = released,
            mediaType = MediaType.TV,
            image = poster ?: logo ?: "",
            backdrop = background ?: poster,
            badge = if (looksLive(this, catalog)) "LIVE" else null,
            status = status,
            primaryNetworkLogo = addon.logo
        )
    }

    private fun StremioStream.toStreamSource(addon: Addon, fallbackTitle: String): StreamSource? {
        val rawUrl = getStreamUrl()?.takeIf { it.isNotBlank() }
            ?: ytId?.takeIf { it.isNotBlank() }?.let { "https://www.youtube.com/watch?v=$it" }
            ?: return null
        val requestHeaders = mergeHeaders(headers, behaviorHints?.headers, behaviorHints?.proxyHeaders?.request)
        val apiHints = behaviorHints
        val torrentName = getTorrentName().ifBlank { title ?: name ?: fallbackTitle }
        val quality = getQuality().takeIf { it.isNotBlank() && it != "Unknown" } ?: "Live"
        val size = getSize()
        return StreamSource(
            source = torrentName,
            addonName = addon.name + " - " + getSourceName(),
            addonId = addon.id,
            quality = quality,
            size = size,
            sizeBytes = parseSizeToBytes(size),
            url = rawUrl,
            infoHash = infoHash,
            fileIdx = fileIdx,
            behaviorHints = StreamBehaviorHints(
                notWebReady = apiHints?.notWebReady ?: false,
                cached = apiHints?.cached,
                bingeGroup = apiHints?.bingeGroup,
                countryWhitelist = apiHints?.countryWhitelist,
                proxyHeaders = if (requestHeaders.isNotEmpty() || apiHints?.proxyHeaders?.response != null) {
                    ProxyHeaders(
                        request = requestHeaders.takeIf { it.isNotEmpty() },
                        response = apiHints?.proxyHeaders?.response
                    )
                } else {
                    null
                },
                videoHash = apiHints?.videoHash,
                videoSize = apiHints?.videoSize,
                filename = apiHints?.filename
            ),
            subtitles = emptyList(),
            sources = sources.orEmpty(),
            description = description
        )
    }

    private fun looksLive(meta: StremioMetaPreview, catalog: AddonCatalog): Boolean {
        val text = listOf(meta.name, meta.description, catalog.name, catalog.id)
            .filterNotNull()
            .joinToString(" ")
            .lowercase(Locale.US)
        return text.contains("live") || text.contains("vs") || text.contains(" v ")
    }

    private fun AddonCatalog.matchesSport(sport: SportsCategoryDef): Boolean {
        val text = text()
        return sport.keywords.any { keyword -> text.contains(keyword) }
    }

    private fun AddonCatalog.text(): String =
        listOf(type, id, name, genres.orEmpty().joinToString(" "))
            .joinToString(" ")
            .lowercase(Locale.US)

    private fun eventStatus(addonId: String, type: String, eventId: String): String =
        SportsAddonCapabilities.SPORTS_EVENT_STATUS_PREFIX +
            listOf(addonId, type, eventId).joinToString("|") { encodeStatusPart(it) }

    private fun parseEventStatus(status: String): ParsedEventStatus? {
        val payload = status.removePrefix(SportsAddonCapabilities.SPORTS_EVENT_STATUS_PREFIX)
        val parts = payload.split("|")
        if (parts.size != 3) return null
        return ParsedEventStatus(
            addonId = decodeStatusPart(parts[0]),
            type = decodeStatusPart(parts[1]),
            eventId = decodeStatusPart(parts[2])
        )
    }

    private fun addonBaseUrl(addon: Addon): String? {
        val raw = addon.transportUrl
            ?: addon.url?.removeSuffix("/manifest.json")?.removeSuffix("/")
            ?: return null
        return raw.trim().removeSuffix("/").takeIf { it.isNotBlank() }
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun encodeStatusPart(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun decodeStatusPart(value: String): String =
        URLDecoder.decode(value, "UTF-8")

    private fun mergeHeaders(vararg maps: Map<String, String>?): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        maps.forEach { headers ->
            headers.orEmpty().forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank()) merged[key] = value
            }
        }
        return merged
    }

    private fun parseSizeToBytes(sizeStr: String): Long {
        if (sizeStr.isBlank()) return 0L
        val match = Regex("""(?i)(\d+(?:[.,]\d+)?)\s*(TB|GB|MB|KB)""")
            .find(sizeStr.replace(",", "."))
            ?: return 0L
        val value = match.groupValues[1].toDoubleOrNull() ?: return 0L
        return when (match.groupValues[2].uppercase(Locale.US)) {
            "TB" -> (value * 1024 * 1024 * 1024 * 1024).toLong()
            "GB" -> (value * 1024 * 1024 * 1024).toLong()
            "MB" -> (value * 1024 * 1024).toLong()
            "KB" -> (value * 1024).toLong()
            else -> 0L
        }
    }

    private object HomeViewModelFavorites {
        const val favoriteTvCategoryId = "favorite_tv"
    }

    private companion object {
        const val MAX_EVENT_ITEMS = 24
    }
}
