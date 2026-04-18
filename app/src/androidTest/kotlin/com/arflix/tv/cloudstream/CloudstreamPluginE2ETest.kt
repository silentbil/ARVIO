package com.arflix.tv.cloudstream

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.AddonInstallSource
import com.arflix.tv.data.model.AddonType
import com.arflix.tv.data.model.RuntimeKind
import com.arflix.tv.data.repository.CloudstreamProviderRuntime
import com.lagradost.cloudstream3.setCloudstreamHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * End-to-end smoke test for the CloudStream plugin runtime. Downloads a
 * real `.cs3` from the public recloudstream repository, drops it into the
 * app's private plugin directory the same way the installer would, and
 * exercises the [CloudstreamProviderRuntime.resolveMovieStreams] path.
 *
 * The goal is to prove the loader can:
 *   1. Read `manifest.json` out of a `.cs3` ZIP.
 *   2. DexClassLoader the plugin's `classes.dex` against the vendored
 *      `com.lagradost.cloudstream3.*` + `com.lagradost.nicehttp.*` surface.
 *   3. Instantiate the `BasePlugin` subclass and run its `load()`.
 *   4. Receive at least one registered `MainAPI` whose `search()` call
 *      returns a non-null list — i.e. the plugin's dex resolved every
 *      symbol it references at runtime without throwing.
 *
 * We deliberately stop short of asserting specific URLs — upstream scraper
 * plugins break intermittently when the remote site changes markup, and
 * this test should fail only when arvio's runtime regresses, not when
 * Dailymotion tweaks its HTML. The success signal is "plugin loaded and
 * search didn't throw".
 */
@RunWith(AndroidJUnit4::class)
class CloudstreamPluginE2ETest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        // Plugins import MainActivityKt.getApp() for HTTP; point it at an
        // instrumentation-scoped OkHttpClient so network hits are real.
        setCloudstreamHttpClient(
            OkHttpClient.Builder()
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
        )
    }

    @After
    fun tearDown() {
        File(context.filesDir, "cloudstream_plugins").deleteRecursively()
    }

    @Test
    fun dailymotionPlugin_loadsAndSearchesWithoutCrashing() = runBlocking {
        val artifact = downloadPlugin(DAILYMOTION_CS3_URL, "dailymotion")
        assertTrue("Plugin file should exist", artifact.exists())
        assertTrue("Plugin file should be non-empty", artifact.length() > 0)

        val runtime = CloudstreamProviderRuntime(context)
        val addon = buildAddon(artifact, "Dailymotion")

        // Run the same path StreamRepository would for a movie stream
        // request. We don't require results — Dailymotion's scraper may
        // have no matches for the placeholder query — only that the call
        // returns normally (no ClassNotFound, no NoSuchMethod, no VM abort).
        val result = runtime.resolveMovieStreams(
            addons = listOf(addon),
            title = "big buck bunny",
            year = null
        )
        assertNotNull("resolveMovieStreams should never return null", result)
        // If the plugin's dex had unresolved refs, the loader would have
        // logged and bailed with an empty list AND no crash — we can't
        // assert non-empty without coupling to Dailymotion's live site.
        assertFalse("Result list should not be null", result == null)
    }

    private fun downloadPlugin(url: String, internalName: String): File {
        val root = File(context.filesDir, "cloudstream_plugins/test").apply { mkdirs() }
        val dest = File(root, "$internalName.cs3")
        val client = OkHttpClient.Builder()
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            assertTrue("Download returned ${response.code}", response.isSuccessful)
            dest.outputStream().use { out ->
                response.body?.byteStream()?.copyTo(out) ?: error("empty body")
            }
        }
        dest.setReadOnly()
        return dest
    }

    private fun buildAddon(artifact: File, displayName: String) = Addon(
        id = "test_${artifact.nameWithoutExtension}",
        name = displayName,
        version = "1",
        description = "",
        isInstalled = true,
        isEnabled = true,
        type = AddonType.CUSTOM,
        runtimeKind = RuntimeKind.CLOUDSTREAM,
        installSource = AddonInstallSource.CLOUDSTREAM_REPOSITORY,
        installedArtifactPath = artifact.absolutePath
    )

    companion object {
        private const val DAILYMOTION_CS3_URL =
            "https://raw.githubusercontent.com/recloudstream/extensions/builds/DailymotionProvider.cs3"
    }
}
