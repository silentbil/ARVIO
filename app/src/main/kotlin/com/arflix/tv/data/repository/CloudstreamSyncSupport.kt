package com.arflix.tv.data.repository

import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.RuntimeKind
import java.net.URI

internal fun sanitizeAddonsForCloudSync(addons: List<Addon>): List<Addon> {
    return addons.map { addon ->
        if (addon.runtimeKind == RuntimeKind.CLOUDSTREAM) {
            addon.copy(installedArtifactPath = null)
        } else {
            addon
        }
    }
}

internal fun mergeCloudstreamRepositoriesFromAddons(
    repositories: List<CloudstreamRepositoryRecord>,
    addons: List<Addon>
): List<CloudstreamRepositoryRecord> {
    val merged = LinkedHashMap<String, CloudstreamRepositoryRecord>()
    repositories.forEach { repository ->
        val normalizedUrl = repository.url.trim()
        if (normalizedUrl.isNotBlank()) {
            merged[normalizedUrl.lowercase()] = repository.copy(url = normalizedUrl)
        }
    }

    addons.forEach { addon ->
        if (addon.runtimeKind != RuntimeKind.CLOUDSTREAM) return@forEach
        val repoUrl = addon.repoUrl?.trim().orEmpty()
        if (repoUrl.isBlank()) return@forEach

        val key = repoUrl.lowercase()
        if (merged.containsKey(key)) return@forEach

        merged[key] = CloudstreamRepositoryRecord(
            url = repoUrl,
            name = inferCloudstreamRepositoryName(repoUrl),
            description = "Recovered from synced Cloudstream plugin metadata",
            manifestVersion = 1,
            iconUrl = addon.logo
        )
    }

    return merged.values.toList()
}

private fun inferCloudstreamRepositoryName(repoUrl: String): String {
    val host = runCatching { URI(repoUrl).host }.getOrNull()
        ?.removePrefix("www.")
        ?.takeIf { it.isNotBlank() }
    return host ?: repoUrl
}
