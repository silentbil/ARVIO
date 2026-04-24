package com.arflix.tv.cloudstream

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arflix.tv.MainActivity
import com.arflix.tv.di.RepositoryAccessEntryPoint
import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.Profile
import com.arflix.tv.data.model.ProfileColors
import com.arflix.tv.data.model.RuntimeKind
import com.arflix.tv.data.model.StreamSource
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.ProfileManager
import com.arflix.tv.data.repository.ProfileRepository
import com.arflix.tv.data.repository.StreamRepository
import com.arflix.tv.util.Constants
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class CloudstreamPlaybackE2ETest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private data class MovieProbe(
        val query: String,
        val title: String,
        val year: Int
    )

    private data class ResolvedMovieCandidate(
        val mediaId: Int,
        val title: String,
        val year: Int,
        val imdbId: String,
        val addonId: String,
        val addonName: String,
        val streamTitle: String
    )

    @Test
    fun cloudstreamMovieSourceAppearsAndStartsPlayback() {
        val deps = entryPoint()
        val candidate = runBlocking {
            val profile = ensureFreshProfile(deps)
            println("Using test profile ${profile.name} (${profile.id})")

            val installedAddons = installValidationPlugins(deps.streamRepository())
            assertTrue("Expected CloudStream plugins to install for validation", installedAddons.isNotEmpty())

            val resolved = findWorkingMovieCandidate(deps, installedAddons)
            assertNotNull("No CloudStream movie candidate resolved from installed providers", resolved)
            resolved!!
        }

        launchContinueIntent(
            Uri.parse("arvio://continue/movie/${candidate.mediaId}")
        )

        waitForText("Sources", timeoutMs = 60_000)
        composeRule.onNodeWithText("Sources").performClick()

        waitForText(candidate.addonName, timeoutMs = 90_000)
        waitForText(candidate.streamTitle, timeoutMs = 90_000, substring = true)
        composeRule.onAllNodesWithText(candidate.streamTitle, substring = true)
            .get(0)
            .performClick()

        composeRule.waitUntil(timeoutMillis = 120_000) {
            entryPoint().streamRepository().getAddonHealthBias(candidate.addonId) > 0
        }

        println(
            "Verified CloudStream playback addon=${candidate.addonName} " +
                "title=${candidate.title} stream=${candidate.streamTitle}"
        )
    }

    private fun entryPoint(): RepositoryAccessEntryPoint {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        return EntryPointAccessors.fromApplication(context, RepositoryAccessEntryPoint::class.java)
    }

    private suspend fun ensureFreshProfile(deps: RepositoryAccessEntryPoint): Profile {
        val profile = deps.profileRepository().createProfile(
            name = "Cloudstream E2E ${System.currentTimeMillis()}",
            avatarColor = ProfileColors.colors.first()
        )
        deps.profileRepository().setActiveProfile(profile.id)
        deps.profileManager().setCurrentProfileId(profile.id)
        deps.profileManager().setCurrentProfileName(profile.name)
        return profile
    }

    private suspend fun installValidationPlugins(streamRepository: StreamRepository): List<Addon> {
        val (repoUrl, manifest, plugins) = streamRepository
            .addCloudstreamRepository(CLOUDSTREAM_REPO_URL)
            .getOrThrow()

        val installed = mutableListOf<Addon>()
        for (internalName in PRIORITY_PLUGIN_NAMES) {
            val plugin = plugins.firstOrNull { it.internalName.equals(internalName, ignoreCase = true) }
                ?: continue
            val addon = streamRepository.installCloudstreamPlugin(repoUrl, manifest, plugin).getOrThrow()
            installed += addon
        }
        return installed
    }

    private suspend fun findWorkingMovieCandidate(
        deps: RepositoryAccessEntryPoint,
        installedAddons: List<Addon>
    ): ResolvedMovieCandidate? {
        val installedAddonIds = installedAddons.map { it.id }.toSet()

        for (probe in MOVIE_PROBES) {
            val item = findBestSearchMatch(
                mediaRepository = deps.mediaRepository(),
                query = probe.query,
                expectedTitle = probe.title,
                expectedYear = probe.year,
                mediaType = MediaType.MOVIE
            ) ?: continue

            val imdbId = deps.tmdbApi()
                .getMovieExternalIds(item.id, Constants.TMDB_API_KEY)
                .imdbId
                ?.trim()
                .takeUnless { it.isNullOrBlank() }
                ?: continue

            val result = withTimeout(90_000L) {
                deps.streamRepository()
                    .resolveMovieStreamsProgressive(
                        imdbId = imdbId,
                        title = item.title,
                        year = probe.year
                    )
                    .first { it.isFinal }
            }

            val stream = result.streams.firstOrNull { it.addonId in installedAddonIds }
                ?: continue
            val displayTitle = streamDisplayTitle(stream)
            if (displayTitle.isBlank()) continue

            println(
                "Resolved movie candidate title=${item.title} year=${probe.year} " +
                    "addon=${stream.addonName} stream=$displayTitle"
            )

            return ResolvedMovieCandidate(
                mediaId = item.id,
                title = item.title,
                year = probe.year,
                imdbId = imdbId,
                addonId = stream.addonId,
                addonName = stream.addonName.split(" - ").firstOrNull()?.trim().orEmpty()
                    .ifBlank { stream.addonName },
                streamTitle = displayTitle.take(80)
            )
        }

        return null
    }

    private suspend fun findBestSearchMatch(
        mediaRepository: MediaRepository,
        query: String,
        expectedTitle: String,
        expectedYear: Int,
        mediaType: MediaType
    ): MediaItem? {
        val results = mediaRepository.search(query)
            .filter { it.mediaType == mediaType }
        if (results.isEmpty()) return null

        val normalizedExpected = normalizeTitle(expectedTitle)
        return results.firstOrNull {
            normalizeTitle(it.title) == normalizedExpected && it.year == expectedYear.toString()
        } ?: results.firstOrNull {
            it.year == expectedYear.toString() &&
                normalizeTitle(it.title).contains(normalizedExpected)
        } ?: results.firstOrNull {
            normalizeTitle(it.title) == normalizedExpected
        }
    }

    private fun launchContinueIntent(uri: Uri) {
        val context = composeRule.activity
        val intent = Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        composeRule.runOnUiThread {
            context.startActivity(intent)
        }
    }

    private fun waitForText(
        text: String,
        timeoutMs: Long,
        substring: Boolean = false
    ) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun streamDisplayTitle(stream: StreamSource): String {
        return stream.behaviorHints?.filename
            ?.takeIf { it.isNotBlank() }
            ?: stream.source
    }

    private fun normalizeTitle(value: String): String {
        return value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    companion object {
        private const val CLOUDSTREAM_REPO_URL =
            "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json"

        private val PRIORITY_PLUGIN_NAMES = listOf(
            "CineStream",
            "NetflixMirrorProvider",
            "Bollyflix"
        )

        private val MOVIE_PROBES = listOf(
            MovieProbe(query = "Pushpa 2 The Rule", title = "Pushpa 2: The Rule", year = 2024),
            MovieProbe(query = "Jawan", title = "Jawan", year = 2023),
            MovieProbe(query = "Animal", title = "Animal", year = 2023),
            MovieProbe(query = "Stree 2", title = "Stree 2", year = 2024),
            MovieProbe(query = "Dune Part Two", title = "Dune: Part Two", year = 2024),
            MovieProbe(query = "Inception", title = "Inception", year = 2010)
        )
    }
}
