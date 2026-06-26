package com.arflix.tv.data.repository

import com.arflix.tv.data.api.StremioMetaPreview
import com.arflix.tv.data.api.StremioStream
import com.arflix.tv.data.api.StreamApi
import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.AddonCatalog
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.CatalogConfig
import com.arflix.tv.data.model.CatalogKind
import com.arflix.tv.data.model.CatalogSourceType
import com.arflix.tv.data.model.CollectionGroupKind
import com.arflix.tv.data.model.CollectionSourceConfig
import com.arflix.tv.data.model.CollectionSourceKind
import com.arflix.tv.data.model.CollectionTileShape
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
        val catalogIds: Set<String>,
        val keywords: Set<String>,
        val artworkUrl: String
    )

    private data class ParsedEventStatus(
        val addonId: String,
        val type: String,
        val eventId: String
    )

    private val sportsCategories = listOf(
        SportsCategoryDef("basketball", "Basketball", setOf("sports_basketball"), setOf("basketball", "nba", "wnba"), unsplash("photo-1770064319432-9c5f134afca7")),
        SportsCategoryDef("football", "Football", setOf("sports_football"), setOf("football", "soccer"), unsplash("photo-1690663245372-429a9fe2f1b7")),
        SportsCategoryDef("american-football", "American Football", setOf("sports_american_football"), setOf("american football", "nfl"), unsplash("photo-1464983308776-3c7215084895")),
        SportsCategoryDef("tennis", "Tennis", setOf("sports_tennis"), setOf("tennis", "atp", "wta"), unsplash("photo-1761404984667-16d6c9158c59")),
        SportsCategoryDef("motor-sports", "Motor Sports", setOf("sports_motor_sports"), setOf("motorsport", "motor sports", "motor_sports", "motor-sports", "formula", "f1", "racing", "motogp", "nascar"), unsplash("photo-1752884991452-b06745698a9d")),
        SportsCategoryDef("rugby", "Rugby", setOf("sports_rugby"), setOf("rugby"), unsplash("photo-1540747913346-19e32dc3e97e")),
        SportsCategoryDef("hockey", "Hockey", setOf("sports_hockey"), setOf("hockey", "nhl"), unsplash("photo-1514511719-9f5849dc16d0")),
        SportsCategoryDef("baseball", "Baseball", setOf("sports_baseball"), setOf("baseball", "mlb"), unsplash("photo-1515871204537-49a5fe66a31f")),
        SportsCategoryDef("fight", "Fight", setOf("sports_fight"), setOf("boxing", "ufc", "mma", "combat", "fight"), unsplash("photo-1549719386-74dfcbf7dbed")),
        SportsCategoryDef("golf", "Golf", setOf("sports_golf"), setOf("golf"), unsplash("photo-1587174486073-ae5e5cff23aa")),
        SportsCategoryDef("cricket", "Cricket", setOf("sports_cricket"), setOf("cricket"), unsplash("photo-1531415074968-036ba1b575da")),
        SportsCategoryDef("darts", "Darts", setOf("sports_darts"), setOf("darts"), unsplash("photo-1517672651691-24622a91b550")),
        SportsCategoryDef("billiards", "Billiards", setOf("sports_billiards"), setOf("billiards", "snooker", "pool"), unsplash("photo-1535007813616-79dc02ba4021")),
        SportsCategoryDef("afl", "AFL", setOf("sports_afl"), setOf("afl", "aussie rules", "australian football"), unsplash("photo-1540747913346-19e32dc3e97e")),
        SportsCategoryDef("other", "Other", setOf("sports_other"), setOf("other"), unsplash("photo-1540747913346-19e32dc3e97e"))
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
        }.prioritizedSportsAddons()

        if (sportsAddons.isEmpty()) return@withContext buildLockedRows()

        val liveRow = loadPopularLiveTvCategory(
            addons = sportsAddons,
            selectedSportId = selectedSportId
        ) ?: Category(
            id = SportsAddonCapabilities.POPULAR_LIVE_TV_ROW_ID,
            title = "Popular Live Sports",
            items = listOf(
                placeholderItem(
                    key = "empty",
                    title = "No live events found",
                    subtitle = "Sports addon",
                    overview = "The installed sports addon did not return live event cards yet."
                )
            )
        )

        listOf(sportsCategoryRow(locked = false), liveRow)
    }

    fun sportsCollectionCatalog(catalogId: String): CatalogConfig? {
        val sportId = SportsAddonCapabilities.sportIdFromCollectionCatalogId(catalogId) ?: return null
        val sport = sportsCategories.firstOrNull { it.id == sportId } ?: return null
        return CatalogConfig(
            id = catalogId,
            title = sport.title,
            sourceType = CatalogSourceType.PREINSTALLED,
            isPreinstalled = true,
            kind = CatalogKind.COLLECTION,
            collectionGroup = CollectionGroupKind.SERVICE,
            collectionDescription = "Live and upcoming ${sport.title.lowercase(Locale.US)} events from your installed sports live TV addon.",
            collectionTileShape = CollectionTileShape.LANDSCAPE,
            collectionSources = listOf(
                CollectionSourceConfig(
                    kind = CollectionSourceKind.CURATED_IDS,
                    mediaType = "tv",
                    curatedRefs = emptyList()
                )
            )
        )
    }

    suspend fun loadSportsCollectionPage(
        catalogId: String,
        offset: Int,
        limit: Int
    ): MediaRepository.CategoryPageResult = withContext(Dispatchers.IO) {
        val sportId = SportsAddonCapabilities.sportIdFromCollectionCatalogId(catalogId)
            ?: return@withContext MediaRepository.CategoryPageResult(emptyList(), hasMore = false)
        val addons = streamRepository.installedAddons.first().filter { addon ->
            addon.isInstalled &&
                addon.isEnabled &&
                !addon.url.isNullOrBlank() &&
                SportsAddonCapabilities.isSportsLiveTvAddon(addon)
        }.prioritizedSportsAddons()
        if (addons.isEmpty() || limit <= 0 || offset < 0) {
            return@withContext MediaRepository.CategoryPageResult(emptyList(), hasMore = false)
        }

        val items = loadSportItems(
            addons = addons,
            selectedSportId = sportId,
            maxItems = offset + limit + 1,
            liveOnly = false
        ).distinctBy { it.status }

        MediaRepository.CategoryPageResult(
            items = items.drop(offset).take(limit),
            hasMore = items.size > offset + limit
        )
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
        val insertAfter = base.indexOfFirst { category ->
            category.id.equals("trending_anime", ignoreCase = true) ||
                category.title.equals("Trending Anime", ignoreCase = true) ||
                category.title.equals("Trending in Anime", ignoreCase = true)
        }.takeIf { it >= 0 } ?: base.indexOfFirst { category ->
            category.id.contains("anime", ignoreCase = true) ||
                category.title.contains("anime", ignoreCase = true)
        }.takeIf { it >= 0 }

        val insertIndex = if (insertAfter != null) insertAfter + 1 else base.size
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
        val items = loadSportItems(
            addons = addons,
            selectedSportId = selectedSportId,
            maxItems = MAX_EVENT_ITEMS,
            liveOnly = true
        )

        if (items.isEmpty()) return null
        val title = selectedSportId
            ?.let { id -> sportsCategories.firstOrNull { it.id == id }?.title }
            ?.let { "$it Live" }
            ?: "Popular Live Sports"

        return Category(
            id = SportsAddonCapabilities.POPULAR_LIVE_TV_ROW_ID,
            title = title,
            items = items.distinctBy { it.status }.take(MAX_EVENT_ITEMS)
        )
    }

    private fun buildLockedRows(): List<Category> = listOf(
        sportsCategoryRow(locked = true),
        Category(
            id = SportsAddonCapabilities.POPULAR_LIVE_TV_ROW_ID,
            title = "Popular Live Sports",
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

    private fun sportsCategoryRow(locked: Boolean = false): Category = Category(
        id = SportsAddonCapabilities.SPORTS_CATEGORY_ROW_ID,
        title = "Sports",
        items = sportsCategories.map { sport ->
            val status = if (locked) {
                "${SportsAddonCapabilities.SPORTS_LOCKED_STATUS_PREFIX}${sport.id}"
            } else {
                "collection:${SportsAddonCapabilities.sportsCollectionCatalogId(sport.id)}"
            }
            MediaItem(
                id = SportsAddonCapabilities.sportsSyntheticId(status),
                title = sport.title,
                subtitle = "Sports",
                overview = "Browse ${sport.title.lowercase(Locale.US)} events from your installed sports live TV addon.",
                mediaType = MediaType.TV,
                badge = "SPORT",
                image = sport.artworkUrl,
                backdrop = sport.artworkUrl,
                status = status,
                collectionGroup = if (locked) null else CollectionGroupKind.SERVICE,
                collectionTileShape = if (locked) null else CollectionTileShape.LANDSCAPE
            )
        }
    )

    private suspend fun loadSportItems(
        addons: List<Addon>,
        selectedSportId: String?,
        maxItems: Int,
        liveOnly: Boolean
    ): List<MediaItem> {
        if (maxItems <= 0) return emptyList()
        val items = mutableListOf<MediaItem>()
        for (addon in addons) {
            val catalogs = candidateCatalogs(addon, selectedSportId)
            for (catalog in catalogs.take(MAX_CATALOGS_PER_LOAD)) {
                val metas = loadCatalogMetas(addon, catalog)
                metas.asSequence()
                    .filter { meta -> !liveOnly || isCurrentlyLive(meta) }
                    .mapNotNullTo(items) { meta -> meta.toMediaItem(addon, catalog) }
                if (items.size >= maxItems) break
            }
            if (items.size >= maxItems) break
        }
        return items.take(maxItems)
    }

    private suspend fun loadCatalogMetas(
        addon: Addon,
        catalog: AddonCatalog
    ): List<StremioMetaPreview> {
        val baseUrl = addonBaseUrl(addon) ?: return emptyList()
        val url = "$baseUrl/catalog/${encodePathSegment(catalog.type)}/${encodePathSegment(catalog.id)}.json"
        return runCatching {
            val response = streamApi.getAddonCatalog(url)
            response.metas ?: response.items ?: emptyList()
        }.onFailure { error ->
            AppLogger.breadcrumb(
                tag = "Sports",
                message = "sports_catalog_failed addon=${addon.id} catalog=${catalog.id} error=${error::class.java.simpleName}",
                severity = "warning"
            )
        }.getOrDefault(emptyList())
    }

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
                    else -> catalog.isPopularLiveCatalog()
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
        val isLive = isCurrentlyLive(this)
        return MediaItem(
            id = SportsAddonCapabilities.sportsSyntheticId(status),
            title = title,
            subtitle = listOf(genreText, if (isLive) "Live" else "Upcoming").filter { !it.isNullOrBlank() }.joinToString(" | "),
            overview = cleanSportsDescription(description.orEmpty()),
            year = "",
            releaseDate = null,
            mediaType = MediaType.TV,
            image = poster ?: logo ?: "",
            backdrop = background ?: poster,
            badge = if (isLive) "LIVE" else "UPCOMING",
            status = status,
            primaryNetworkLogo = addon.logo
        )
    }

    private fun StremioStream.toStreamSource(addon: Addon, fallbackTitle: String): StreamSource? {
        val rawUrl = getStreamUrl()?.takeIf { it.isNotBlank() }
            ?: ytId?.takeIf { it.isNotBlank() }?.let { "https://www.youtube.com/watch?v=$it" }
            ?: return null
        if (!isPlayableSportsStream(rawUrl)) return null
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

    private fun StremioStream.isPlayableSportsStream(rawUrl: String): Boolean {
        val url = rawUrl.trim().lowercase(Locale.US)
        val text = listOf(name, title, description, rawUrl)
            .filterNotNull()
            .joinToString(" ")
            .lowercase(Locale.US)

        // Highfly and similar sports addons use these as CTA/error placeholders.
        if (url == "https://www.google.com" || url == "http://www.google.com" ||
            url == "https://google.com" || url == "http://google.com"
        ) return false

        // A notWebReady sports stream generally means "open externally / premium / unavailable";
        // ARVIO should only launch streams it can play in the native player.
        if (behaviorHints?.notWebReady == true) return false

        val blockedPhrases = listOf(
            "upgrade to watch",
            "premium",
            "unavailable",
            "stream has ended",
            "not available"
        )
        return blockedPhrases.none { phrase -> text.contains(phrase) }
    }

    private fun isCurrentlyLive(meta: StremioMetaPreview): Boolean {
        val text = listOf(meta.releaseInfo, meta.description)
            .filterNotNull()
            .joinToString(" ")
            .lowercase(Locale.US)
        return text.contains("live")
    }

    private fun AddonCatalog.matchesSport(sport: SportsCategoryDef): Boolean {
        val text = text()
        if (sport.catalogIds.any { id -> id.equals(this.id, ignoreCase = true) }) return true
        if (sport.id == "football" && text.contains("american football")) return false
        return sport.keywords.any { keyword -> text.contains(keyword) }
    }

    private fun AddonCatalog.isPopularLiveCatalog(): Boolean {
        val text = text()
        return text.contains("live") ||
            text.contains("popular") ||
            id.equals("sports_live", ignoreCase = true)
    }

    private fun List<Addon>.prioritizedSportsAddons(): List<Addon> =
        sortedWith(
            compareBy<Addon> { addon ->
                val text = listOf(addon.id, addon.name, addon.url, addon.transportUrl)
                    .filterNotNull()
                    .joinToString(" ")
                    .lowercase(Locale.US)
                when {
                    text.contains("sports.fly") || text.contains("highfly") -> 0
                    text.contains("sports") -> 1
                    else -> 2
                }
            }.thenBy { addon -> addon.name.ifBlank { addon.id } }
        )

    private fun cleanSportsDescription(value: String): String =
        value.lines()
            .filterNot { line ->
                val normalized = line.lowercase(Locale.US)
                normalized.contains(" utc") &&
                    (normalized.contains(" jan ") || normalized.contains(" feb ") ||
                        normalized.contains(" mar ") || normalized.contains(" apr ") ||
                        normalized.contains(" may ") || normalized.contains(" jun ") ||
                        normalized.contains(" jul ") || normalized.contains(" aug ") ||
                        normalized.contains(" sep ") || normalized.contains(" oct ") ||
                        normalized.contains(" nov ") || normalized.contains(" dec "))
            }
            .joinToString("\n")
            .trim()

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

    private companion object {
        const val MAX_EVENT_ITEMS = 24
        const val MAX_CATALOGS_PER_LOAD = 3

        fun unsplash(photoId: String): String =
            "https://images.unsplash.com/$photoId?auto=format&fit=crop&w=3840&q=80"
    }
}
