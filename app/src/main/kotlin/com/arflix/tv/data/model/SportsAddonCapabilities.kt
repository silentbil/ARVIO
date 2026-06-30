package com.arflix.tv.data.model

import java.util.Locale

object SportsAddonCapabilities {
    const val SPORTS_CATEGORY_ROW_ID = "sports"
    const val POPULAR_LIVE_TV_ROW_ID = "popular_live_tv"
    const val SPORTS_STATUS_PREFIX = "sports:"
    const val SPORTS_LOCKED_STATUS_PREFIX = "sports_locked:"
    const val SPORTS_EVENT_STATUS_PREFIX = "sports_event:"
    const val SPORTS_COLLECTION_PREFIX = "sports_collection:"

    private val sportTerms = setOf(
        "sport",
        "sports",
        "football",
        "soccer",
        "basketball",
        "tennis",
        "motorsport",
        "formula",
        "racing",
        "rugby",
        "hockey",
        "baseball",
        "boxing",
        "ufc",
        "mma",
        "cricket",
        "golf"
    )

    private val liveTerms = setOf("live", "event", "events", "match", "matches", "game", "games")

    fun isSportsHomeStatus(status: String?): Boolean {
        val value = status ?: return false
        return value.startsWith(SPORTS_STATUS_PREFIX) ||
            value.startsWith(SPORTS_LOCKED_STATUS_PREFIX) ||
            value.startsWith(SPORTS_EVENT_STATUS_PREFIX)
    }

    fun isSportsCategoryStatus(status: String?): Boolean =
        status?.startsWith(SPORTS_STATUS_PREFIX) == true

    fun isSportsEventStatus(status: String?): Boolean =
        status?.startsWith(SPORTS_EVENT_STATUS_PREFIX) == true

    fun sportsCollectionCatalogId(sportId: String): String =
        "$SPORTS_COLLECTION_PREFIX$sportId"

    fun isSportsCollectionCatalogId(catalogId: String?): Boolean =
        catalogId?.startsWith(SPORTS_COLLECTION_PREFIX) == true

    fun sportIdFromCollectionCatalogId(catalogId: String): String? =
        catalogId.takeIf { isSportsCollectionCatalogId(it) }
            ?.removePrefix(SPORTS_COLLECTION_PREFIX)
            ?.takeIf { it.isNotBlank() }

    fun isSportsLiveTvAddon(addon: Addon): Boolean =
        addon.manifest?.let(::isSportsLiveTvManifest) == true

    fun isSportsLiveTvManifest(manifest: AddonManifest): Boolean {
        val resources = manifest.safeResources()
        val catalogs = manifest.safeCatalogs()
        val hasStream = resources.any { resource ->
            resource.safeName().equals("stream", ignoreCase = true) ||
                resource.safeName().equals("streams", ignoreCase = true)
        }
        val hasCatalog = resources.any { resource ->
            resource.safeName().equals("catalog", ignoreCase = true) ||
                resource.safeName().equals("catalogs", ignoreCase = true)
        } || catalogs.isNotEmpty()

        if (!hasStream || !hasCatalog) return false

        if (catalogs.any(::isSportsCatalog)) return true

        val manifestText = normalizedText(
            manifest.id,
            manifest.name,
            manifest.description,
            manifest.safeTypes().joinToString(" "),
            resources.flatMap { it.safeTypes() }.joinToString(" ")
        )
        return containsAny(manifestText, sportTerms) && containsAny(manifestText, liveTerms)
    }

    fun isSportsCatalog(catalog: AddonCatalog): Boolean {
        val text = normalizedText(
            catalog.type,
            catalog.id,
            catalog.name,
            catalog.genres.orEmpty().joinToString(" ")
        )
        return containsAny(text, sportTerms)
    }

    fun isLiveSportsCatalog(catalog: AddonCatalog): Boolean {
        val text = normalizedText(catalog.type, catalog.id, catalog.name)
        return isSportsCatalog(catalog) || containsAny(text, liveTerms)
    }

    fun sportsSyntheticId(raw: String): Int =
        (raw.hashCode() and Int.MAX_VALUE).takeIf { it > 0 } ?: 1

    private fun normalizedText(vararg parts: String?): String =
        parts.filterNotNull().joinToString(" ").lowercase(Locale.US)

    private fun containsAny(text: String, terms: Set<String>): Boolean =
        terms.any { term -> text.contains(term) }

    private fun AddonManifest.safeResources() = runCatching { resources }.getOrNull().orEmpty()

    private fun AddonManifest.safeCatalogs() = runCatching { catalogs }.getOrNull().orEmpty()

    private fun AddonManifest.safeTypes() = runCatching { types }.getOrNull().orEmpty()

    private fun AddonResource.safeName() = runCatching { name }.getOrNull().orEmpty()

    private fun AddonResource.safeTypes() = runCatching { types }.getOrNull().orEmpty()
}
