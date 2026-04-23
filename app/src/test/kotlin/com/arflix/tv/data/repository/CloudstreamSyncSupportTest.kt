package com.arflix.tv.data.repository

import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.AddonInstallSource
import com.arflix.tv.data.model.AddonType
import com.arflix.tv.data.model.RuntimeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudstreamSyncSupportTest {

    @Test
    fun `sanitizeAddonsForCloudSync strips device-local artifact paths only for cloudstream addons`() {
        val addons = listOf(
            Addon(
                id = "cloud.one",
                name = "Cloud One",
                version = "1",
                description = "",
                isInstalled = true,
                isEnabled = true,
                type = AddonType.CUSTOM,
                runtimeKind = RuntimeKind.CLOUDSTREAM,
                installSource = AddonInstallSource.CLOUDSTREAM_REPOSITORY,
                repoUrl = "https://example.com/repo.json",
                pluginPackageUrl = "https://example.com/plugin.cs3",
                installedArtifactPath = "/tmp/plugin.cs3"
            ),
            Addon(
                id = "stremio.one",
                name = "Stremio One",
                version = "1",
                description = "",
                isInstalled = true,
                isEnabled = true,
                type = AddonType.CUSTOM,
                runtimeKind = RuntimeKind.STREMIO,
                url = "https://strem.io/manifest.json",
                installedArtifactPath = "/tmp/leave-me.json"
            )
        )

        val sanitized = sanitizeAddonsForCloudSync(addons)

        assertNull(sanitized[0].installedArtifactPath)
        assertEquals("/tmp/leave-me.json", sanitized[1].installedArtifactPath)
    }

    @Test
    fun `mergeCloudstreamRepositoriesFromAddons recovers missing repo records from synced addons`() {
        val repositories = listOf(
            CloudstreamRepositoryRecord(
                url = "https://known.example/repo.json",
                name = "Known Repo",
                description = null,
                manifestVersion = 1,
                iconUrl = null
            )
        )
        val addons = listOf(
            Addon(
                id = "cloud.one",
                name = "Cloud One",
                version = "1",
                description = "",
                isInstalled = true,
                isEnabled = true,
                type = AddonType.CUSTOM,
                runtimeKind = RuntimeKind.CLOUDSTREAM,
                installSource = AddonInstallSource.CLOUDSTREAM_REPOSITORY,
                repoUrl = "https://missing.example/repo.json",
                logo = "https://missing.example/icon.png"
            ),
            Addon(
                id = "stremio.one",
                name = "Stremio One",
                version = "1",
                description = "",
                isInstalled = true,
                isEnabled = true,
                type = AddonType.CUSTOM,
                runtimeKind = RuntimeKind.STREMIO
            )
        )

        val merged = mergeCloudstreamRepositoriesFromAddons(repositories, addons)

        assertEquals(2, merged.size)
        assertTrue(merged.any { it.url == "https://known.example/repo.json" })
        val recovered = merged.first { it.url == "https://missing.example/repo.json" }
        assertEquals("missing.example", recovered.name)
        assertEquals("https://missing.example/icon.png", recovered.iconUrl)
    }
}
