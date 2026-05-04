package com.arflix.tv.data.repository

import android.content.Context
import android.util.Log
import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.ProxyHeaders
import com.arflix.tv.data.model.StreamBehaviorHints
import com.arflix.tv.data.model.StreamSource
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.ExtractorLink
import dagger.hilt.android.qualifiers.ApplicationContext
import dalvik.system.DexClassLoader
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

private const val TAG = "CloudstreamRuntime"

/**
 * Loads real CloudStream `.cs3` plugins (ZIP containing `manifest.json` +
 * `classes.dex`), instantiates their `BasePlugin` subclass, and exposes the
 * `MainAPI` instances the plugin registers. Designed to be binary-compatible
 * with the published plugin ecosystem at
 * https://github.com/recloudstream/extensions.
 *
 * Plugin lifecycle:
 * 1. `.cs3` arrives at a private filesystem path via
 *    [CloudstreamPluginInstaller].
 * 2. The ZIP's `manifest.json` is read to find `pluginClassName`.
 * 3. `DexClassLoader` is constructed rooted at the app's classloader so the
 *    dex can see the vendored `com.lagradost.cloudstream3.*` and NiceHttp
 *    symbols.
 * 4. The plugin class is instantiated (no-arg constructor) and `load(context)`
 *    is invoked. The plugin registers one or more `MainAPI`s back on itself.
 * 5. We cache the registered APIs keyed by artifact path; subsequent
 *    search/load/loadLinks calls reuse them without re-loading the dex.
 */
@Singleton
class CloudstreamProviderRuntime @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private data class LoadedPlugin(
        val plugin: BasePlugin,
        val apis: List<MainAPI>
    )

    private val loadMutex = Mutex()
    private val pluginCache = mutableMapOf<String, LoadedPlugin>()

    /**
     * Search every installed plugin for the title, pick the best match per
     * plugin, then run its `load` + `loadLinks` pipeline. Returns a flat list
     * of arvio `StreamSource`s ready to render in the sources sheet.
     */
    suspend fun resolveMovieStreams(
        addons: List<Addon>,
        imdbId: String? = null,
        title: String,
        year: Int?
    ): List<StreamSource> = resolveAcrossAddons(addons) { addon, api ->
        if (!api.supportsRequestKind(RequestKind.MOVIE)) return@resolveAcrossAddons emptyList()
        val load = loadFromImdb(api, imdbId, RequestKind.MOVIE)
            ?: run {
                val match = pickSearchMatch(api, title, year, RequestKind.MOVIE)
                    ?: return@resolveAcrossAddons emptyList()
                Log.d(TAG, "movie match ${api.name}: '${match.name}' (${match.type}, ${match.resultYear()})")
                runCatchingTimeout(LOAD_TIMEOUT_MS, "load") { api.load(match.url) }
            }
            ?: return@resolveAcrossAddons emptyList()
        if (!load.matchesRequestKind(RequestKind.MOVIE)) return@resolveAcrossAddons emptyList()
        val dataUrl = loadResponseToDataUrl(load)
            ?: return@resolveAcrossAddons emptyList()
        val links = extractLinksFromApi(api, dataUrl, addon)
        Log.d(TAG, "resolveMovieStreams returned ${links.size} stream(s) for ${addon.name}")
        links
    }

    suspend fun resolveEpisodeStreams(
        addons: List<Addon>,
        imdbId: String? = null,
        title: String,
        year: Int?,
        season: Int,
        episode: Int,
        airDate: String?
    ): List<StreamSource> = resolveAcrossAddons(addons) { addon, api ->
        if (!api.supportsRequestKind(RequestKind.SERIES)) return@resolveAcrossAddons emptyList()
        val load = loadFromImdb(api, imdbId, RequestKind.SERIES)
            ?: run {
                val match = pickSearchMatch(api, title, year, RequestKind.SERIES)
                    ?: return@resolveAcrossAddons emptyList()
                Log.d(TAG, "series match ${api.name}: '${match.name}' (${match.type}, ${match.resultYear()})")
                runCatchingTimeout(LOAD_TIMEOUT_MS, "load") { api.load(match.url) }
            }
            ?: return@resolveAcrossAddons emptyList()
        if (!load.matchesRequestKind(RequestKind.SERIES)) return@resolveAcrossAddons emptyList()
        val target = when (load) {
            is TvSeriesLoadResponse -> load.episodes.firstOrNull {
                (it.season ?: 1) == season && it.episode == episode
            }
            is AnimeLoadResponse -> load.episodes.values.flatten().firstOrNull {
                (it.season ?: 1) == season && it.episode == episode
            }
            else -> null
        } ?: return@resolveAcrossAddons emptyList()
        val links = extractLinksFromApi(api, target.data, addon)
        Log.d(TAG, "resolveEpisodeStreams returned ${links.size} stream(s) for ${addon.name}")
        links
    }

    private suspend fun resolveAcrossAddons(
        addons: List<Addon>,
        perApi: suspend (Addon, MainAPI) -> List<StreamSource>
    ): List<StreamSource> = coroutineScope {
        addons
            .mapNotNull { addon ->
                val path = addon.installedArtifactPath?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                addon to path
            }
            .map { (addon, path) ->
                async(Dispatchers.IO) {
                    val loaded = runCatching { loadPlugin(path) }
                        .onFailure { Log.e(TAG, "loadPlugin failed for ${addon.name}", it) }
                        .getOrNull()
                        ?: return@async emptyList()
                    loaded.apis.flatMap { api ->
                        runCatching { perApi(addon, api) }
                            .onFailure { Log.e(TAG, "resolve failed on ${api.name}", it) }
                            .getOrDefault(emptyList())
                    }
                }
            }
            .awaitAll()
            .flatten()
    }

    private suspend fun extractLinksFromApi(
        api: MainAPI,
        data: String,
        addon: Addon
    ): List<StreamSource> {
        val rawLinks = mutableListOf<ExtractorLink>()
        val finalLinks = mutableListOf<ExtractorLink>()

        val ok = runCatchingTimeout(LINKS_TIMEOUT_MS, "loadLinks") {
            api.loadLinks(
                data = data,
                isCasting = false,
                subtitleCallback = { /* subtitles integration is phase 2 */ },
                callback = { link -> synchronized(rawLinks) { rawLinks.add(link) } }
            )
        } ?: return emptyList()

        if (!ok && rawLinks.isEmpty()) return emptyList()

        // Phase 2: Extract direct video urls from the links using ExtractorApi.
        // Track the count before/after each call so that if an extractor matches the URL
        // but fails internally (emitting zero links), the original rawLink is still
        // preserved — loadExtractor() returns true on a URL-pattern match, even when
        // the extractor itself emits nothing.
        rawLinks.forEach { rawLink ->
            val before = synchronized(finalLinks) { finalLinks.size }

            val handled = runCatchingTimeout(LINKS_TIMEOUT_MS, "loadExtractor") {
                com.lagradost.cloudstream3.utils.loadExtractor(
                    url = rawLink.url,
                    referer = rawLink.referer,
                    subtitleCallback = { },
                    callback = { extractedLink ->
                        synchronized(finalLinks) { finalLinks.add(extractedLink) }
                    }
                )
            } ?: false

            val emitted = synchronized(finalLinks) { finalLinks.size > before }
            if (!handled || !emitted) {
                // Either no extractor matched, or the extractor matched but produced
                // nothing — fall back to the original playable raw link.
                synchronized(finalLinks) { finalLinks.add(rawLink) }
            }
        }

        return finalLinks.map { it.toStreamSource(addon) }
    }

    /**
     * Best-effort: pick the search result whose title most closely matches.
     * CloudStream plugins often return huge mixed lists; we keep it simple —
     * prefer exact matches, then case-insensitive equals, then contains.
     */
    private suspend fun pickSearchMatch(
        api: MainAPI,
        title: String,
        year: Int?,
        kind: RequestKind
    ): SearchResponse? {
        // CloudStream has two search overloads — legacy `search(String)`
        // returning List<SearchResponse> and the master-branch
        // `search(String, Int)` returning SearchResponseList. Newer plugins
        // override only the paginated one and leave the legacy one as the
        // base-class TODO(). Try paginated first, fall back to legacy.
        val queries = buildList {
            add(title)
            year?.let { add("$title $it") }
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val results: List<SearchResponse> = queries.flatMap { query ->
            val paginated = runCatchingTimeout(SEARCH_TIMEOUT_MS, "search(query,page)") {
                api.search(query, 1)
            }
            paginated?.list
                ?: runCatchingTimeout(SEARCH_TIMEOUT_MS, "search(query)") {
                    api.search(query)
                }.orEmpty()
        }.distinctBy { "${it.apiName}|${it.url}" }
        if (results.isEmpty()) return null

        return results
            .mapNotNull { result ->
                val score = scoreSearchResult(result, title, year, kind)
                if (score >= SEARCH_MATCH_THRESHOLD) result to score else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private suspend fun loadFromImdb(
        api: MainAPI,
        imdbId: String?,
        kind: RequestKind
    ): LoadResponse? {
        val cleanId = imdbId?.trim()?.takeIf { it.matches(Regex("tt\\d{5,}")) } ?: return null
        if (!api.supportedSyncNames.contains(SyncIdName.Imdb)) return null
        val loadUrl = runCatchingTimeout(LOAD_TIMEOUT_MS, "getLoadUrl(Imdb)") {
            api.getLoadUrl(SyncIdName.Imdb, cleanId)
        }?.takeIf { it.isNotBlank() } ?: return null
        return runCatchingTimeout(LOAD_TIMEOUT_MS, "load(imdb)") { api.load(loadUrl) }
            ?.takeIf { it.matchesRequestKind(kind) }
    }

    private fun scoreSearchResult(
        result: SearchResponse,
        targetTitle: String,
        targetYear: Int?,
        kind: RequestKind
    ): Int {
        val target = normalizeMatchTitle(targetTitle)
        val candidate = normalizeMatchTitle(result.name)
        if (target.isBlank() || candidate.isBlank()) return 0

        var score = maxOf(
            me.xdrop.fuzzywuzzy.FuzzySearch.ratio(target, candidate),
            me.xdrop.fuzzywuzzy.FuzzySearch.partialRatio(target, candidate) - 6
        )
        if (candidate == target) score = 115
        if (candidate.startsWith("$target ") || candidate.contains(" $target ")) score += 8
        if (result.type?.matchesRequestKind(kind) == true) score += 14
        if (result.type != null && result.type?.matchesRequestKind(kind) != true) score -= 35

        val resultYear = result.resultYear()
        if (targetYear != null && resultYear != null) {
            val diff = kotlin.math.abs(targetYear - resultYear)
            score += when {
                diff == 0 -> 22
                diff == 1 -> 10
                diff <= 2 && candidate == target -> 2
                else -> -38
            }
        }
        return score
    }

    private fun normalizeMatchTitle(value: String): String = value
        .lowercase()
        .replace(Regex("\\((19|20)\\d{2}\\)"), " ")
        .replace(Regex("\\b(19|20)\\d{2}\\b"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun SearchResponse.resultYear(): Int? = when (this) {
        is MovieSearchResponse -> year
        is TvSeriesSearchResponse -> year
        is AnimeSearchResponse -> year
        else -> null
    }

    private enum class RequestKind {
        MOVIE,
        SERIES
    }

    private fun MainAPI.supportsRequestKind(kind: RequestKind): Boolean {
        return supportedTypes.isEmpty() || supportedTypes.any { it.matchesRequestKind(kind) }
    }

    private fun LoadResponse.matchesRequestKind(kind: RequestKind): Boolean = type.matchesRequestKind(kind)

    private fun TvType.matchesRequestKind(kind: RequestKind): Boolean = when (kind) {
        RequestKind.MOVIE -> this == TvType.Movie ||
            this == TvType.AnimeMovie ||
            this == TvType.Torrent ||
            this == TvType.Documentary ||
            this == TvType.Live
        RequestKind.SERIES -> this == TvType.TvSeries ||
            this == TvType.Anime ||
            this == TvType.Cartoon ||
            this == TvType.OVA ||
            this == TvType.AsianDrama
    }

    private fun loadResponseToDataUrl(response: LoadResponse): String? = when (response) {
        is MovieLoadResponse -> response.dataUrl.takeIf { it.isNotBlank() }
        is TvSeriesLoadResponse -> response.episodes.firstOrNull()?.data?.takeIf { it.isNotBlank() }
        is AnimeLoadResponse -> response.episodes.values.flatten().firstOrNull()?.data?.takeIf { it.isNotBlank() }
        is com.lagradost.cloudstream3.LiveStreamLoadResponse -> response.dataUrl.takeIf { it.isNotBlank() }
        else -> null
    }

    private suspend fun loadPlugin(artifactPath: String): LoadedPlugin? {
        pluginCache[artifactPath]?.let { return it }
        return loadMutex.withLock {
            pluginCache[artifactPath]?.let { return@withLock it }

            val artifact = File(artifactPath)
            if (!artifact.exists() || !artifact.isFile) {
                Log.w(TAG, "artifact missing: $artifactPath")
                return@withLock null
            }
            val privateRoot = File(context.filesDir, "cloudstream_plugins").canonicalFile
            if (!artifact.canonicalPath.startsWith(privateRoot.path)) {
                Log.w(TAG, "refusing out-of-tree artifact: $artifactPath")
                return@withLock null
            }

            val pluginClassName = readPluginClassName(artifact)
                ?: run {
                    Log.w(TAG, "no pluginClassName in $artifactPath")
                    return@withLock null
                }

            val dexDir = File(artifact.parentFile, "optimized_dex").apply { mkdirs() }
            val loader = DexClassLoader(
                artifact.absolutePath,
                dexDir.absolutePath,
                null,
                Plugin::class.java.classLoader
            )
            val pluginClass = runCatching {
                Class.forName(pluginClassName, true, loader)
            }.getOrElse {
                Log.e(TAG, "pluginClassName=$pluginClassName not found", it)
                return@withLock null
            }

            val instance = runCatching {
                pluginClass.getDeclaredConstructor().newInstance()
            }.getOrElse {
                Log.e(TAG, "failed to instantiate $pluginClassName", it)
                return@withLock null
            }
            (instance as? Plugin)?.__filename = artifact.absolutePath
            (instance as? BasePlugin)?.filename = artifact.absolutePath

            // Community CloudStream plugins extend `com.lagradost.cloudstream3
            // .plugins.Plugin` (the app-module class in upstream) rather than
            // our library-visible `BasePlugin`. `Plugin.registerMainAPI()`
            // pushes into the global `APIHolder.allProviders`, not onto the
            // instance. Snapshot its size before calling load() and slice
            // out the APIs the plugin added.
            val apisBefore = synchronized(APIHolder.allProviders) {
                APIHolder.allProviders.toList()
            }

            // Two plugin-base conventions in the wild:
            //   - `com.lagradost.cloudstream3.plugins.Plugin.load(Context)`
            //     (app-module base used by CS 4.x-era plugins)
            //   - `com.lagradost.cloudstream3.plugins.BasePlugin.load()`
            //     (library-module base used by plugins compiled against the
            //     KMP master library)
            // Detect by probing declared methods on the concrete class so
            // either override dispatches correctly.
            runCatching {
                val loadNoArg = runCatching {
                    pluginClass.getMethod("load")
                }.getOrNull()
                val loadWithCtx = runCatching {
                    pluginClass.getMethod("load", Context::class.java)
                }.getOrNull()
                when {
                    loadNoArg != null -> loadNoArg.invoke(instance)
                    loadWithCtx != null -> loadWithCtx.invoke(instance, context)
                    else -> error("$pluginClassName has no load() / load(Context) method")
                }
            }.onFailure { Log.e(TAG, "$pluginClassName.load() threw", it) }

            val apisAfter = synchronized(APIHolder.allProviders) {
                APIHolder.allProviders.toList()
            }
            val registered = (apisAfter - apisBefore.toSet()).toList()

            Log.i(
                TAG,
                "$pluginClassName registered ${registered.size} MainAPI(s): " +
                    registered.joinToString { it.name }
            )

            // Cache alongside a stable plugin-instance marker for teardown.
            val marker: BasePlugin = (instance as? BasePlugin) ?: object : BasePlugin() {}
            val loaded = LoadedPlugin(marker, registered)
            pluginCache[artifactPath] = loaded
            loaded
        }
    }

    private fun readPluginClassName(artifact: File): String? = runCatching {
        ZipFile(artifact).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: return@use null
            val json = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            JSONObject(json).optString("pluginClassName").takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private suspend fun <T> runCatchingTimeout(
        timeoutMs: Long,
        label: String,
        block: suspend () -> T
    ): T? = runCatching {
        withTimeoutOrNull(timeoutMs) { block() }
    }.onFailure {
        Log.w(TAG, "$label failed", it)
    }.getOrNull()

    companion object {
        private const val SEARCH_TIMEOUT_MS = 8_000L
        private const val LOAD_TIMEOUT_MS = 6_000L
        private const val LINKS_TIMEOUT_MS = 8_000L
        private const val SEARCH_MATCH_THRESHOLD = 78
    }
}

private fun ExtractorLink.toStreamSource(addon: Addon): StreamSource = StreamSource(
    source = name.ifBlank { source.ifBlank { addon.name } },
    addonName = addon.name,
    addonId = addon.id,
    quality = when {
        quality >= 2160 -> "4K"
        quality >= 1080 -> "1080p"
        quality >= 720 -> "720p"
        quality >= 480 -> "480p"
        quality >= 360 -> "360p"
        quality > 0 -> "${quality}p"
        else -> ""
    },
    size = "",
    sizeBytes = null,
    url = url,
    infoHash = null,
    fileIdx = null,
    behaviorHints = getAllHeaders()
        .mapNotNull { (key, value) ->
            val sanitizedKey = key.trim()
            val sanitizedValue = value.trim()
            if (sanitizedKey.isBlank() || sanitizedValue.isBlank()) null
            else sanitizedKey to sanitizedValue
        }
        .toMap()
        .takeIf { it.isNotEmpty() }
        ?.let { headers ->
            StreamBehaviorHints(
                proxyHeaders = ProxyHeaders(request = headers)
            )
        },
    subtitles = emptyList(),
    sources = emptyList()
)
