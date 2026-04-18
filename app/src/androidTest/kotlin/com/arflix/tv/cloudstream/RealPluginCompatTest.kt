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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Multi-plugin compatibility survey. Downloads a diverse set of real
 * community `.cs3` files (official recloudstream repo + hexated + phisher),
 * installs each into the app-private plugin dir, and exercises the runtime
 * pipeline. Logs a per-plugin pass/fail matrix to aid the phase-2 triage.
 *
 * "Pass" means: plugin installed, `DexClassLoader` resolved every symbol it
 * references against the vendored CS 4.4.0 SDK, its `BasePlugin` subclass
 * instantiated, `load(context)` ran, and `resolveMovieStreams` returned
 * without throwing (results may be empty — scraper sites change markup
 * daily and empty results aren't a regression).
 *
 * "Fail" means: something blew up during load/resolve. Typically
 * `ClassNotFoundException` (needs an SDK symbol we don't ship) or
 * `NoSuchMethodError` (signature drift). Stack traces are logged to aid
 * adding the missing surface.
 */
@RunWith(AndroidJUnit4::class)
class RealPluginCompatTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    data class PluginCase(
        val label: String,
        val url: String,
        val query: String,
        val year: Int? = null
    )

    private val cases = listOf(
        PluginCase(
            label = "Dailymotion (official)",
            url = "https://raw.githubusercontent.com/recloudstream/extensions/builds/DailymotionProvider.cs3",
            query = "big buck bunny"
        ),
        PluginCase(
            label = "InternetArchive (official)",
            url = "https://raw.githubusercontent.com/recloudstream/extensions/builds/InternetArchiveProvider.cs3",
            query = "zelda"
        ),
        PluginCase(
            label = "Invidious (official)",
            url = "https://raw.githubusercontent.com/recloudstream/extensions/builds/InvidiousProvider.cs3",
            query = "cats"
        ),
        PluginCase(
            label = "AllMovieLand (phisher)",
            url = "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/builds/AllMovieLandProvider.cs3",
            query = "inception",
            year = 2010
        ),
        PluginCase(
            label = "AllWish (phisher)",
            url = "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/builds/AllWish.cs3",
            query = "avengers"
        ),
        PluginCase(
            label = "Anichi (hexated)",
            url = "https://raw.githubusercontent.com/hexated/cloudstream-extensions-hexated/builds/Anichi.cs3",
            query = "attack on titan"
        ),
        PluginCase(
            label = "Anilibria (hexated)",
            url = "https://raw.githubusercontent.com/hexated/cloudstream-extensions-hexated/builds/Anilibria.cs3",
            query = "one piece"
        ),
        PluginCase(
            label = "Animeav1 (phisher)",
            url = "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/builds/Animeav1.cs3",
            query = "demon slayer"
        )
    )

    @Before
    fun setUp() {
        setCloudstreamHttpClient(
            OkHttpClient.Builder()
                .callTimeout(45, TimeUnit.SECONDS)
                .build()
        )
    }

    @After
    fun tearDown() {
        File(context.filesDir, "cloudstream_plugins").deleteRecursively()
    }

    @Test
    fun surveyRealPlugins() = runBlocking {
        val runtime = CloudstreamProviderRuntime(context)
        val report = StringBuilder("\n=== CloudStream plugin survey ===\n")
        var passed = 0
        var failed = 0

        for ((index, case) in cases.withIndex()) {
            val result = runOne(runtime, case, index)
            report.appendLine(result.line)
            if (result.pass) passed++ else failed++
        }

        report.appendLine("\n--- summary: $passed passed / $failed failed ---")
        println(report.toString())
        // We don't fail the test on per-plugin failure — this is a survey.
        // A real regression would be a compile/load crash that terminates
        // the whole suite; JUnit catching that is enough.
    }

    private data class SurveyResult(val pass: Boolean, val line: String)

    private suspend fun runOne(
        runtime: CloudstreamProviderRuntime,
        case: PluginCase,
        index: Int
    ): SurveyResult {
        val artifact = try {
            downloadPlugin(case.url, "plugin_$index")
        } catch (t: Throwable) {
            return SurveyResult(false, "✗ ${case.label}  (download failed: ${t.message})")
        }

        val addon = Addon(
            id = "test_$index",
            name = case.label,
            version = "1",
            description = "",
            isInstalled = true,
            isEnabled = true,
            type = AddonType.CUSTOM,
            runtimeKind = RuntimeKind.CLOUDSTREAM,
            installSource = AddonInstallSource.CLOUDSTREAM_REPOSITORY,
            installedArtifactPath = artifact.absolutePath
        )

        val count = try {
            runtime.resolveMovieStreams(
                addons = listOf(addon),
                title = case.query,
                year = case.year
            ).size
        } catch (t: Throwable) {
            return SurveyResult(
                false,
                "✗ ${case.label}  (runtime crash: ${t.javaClass.simpleName}: ${t.message})"
            )
        }
        return SurveyResult(
            true,
            "✓ ${case.label}  (streams=$count, query=\"${case.query}\")"
        )
    }

    private fun downloadPlugin(url: String, internalName: String): File {
        val root = File(context.filesDir, "cloudstream_plugins/test").apply { mkdirs() }
        val dest = File(root, "$internalName.cs3")
        val client = OkHttpClient.Builder()
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            dest.outputStream().use { out ->
                response.body?.byteStream()?.copyTo(out) ?: error("empty body")
            }
        }
        dest.setReadOnly()
        return dest
    }
}
