package com.arflix.tv.data.repository

import android.content.Context
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import com.arflix.tv.BuildConfig
import com.arflix.tv.data.model.ProxyHeaders
import com.arflix.tv.data.model.StreamBehaviorHints
import com.arflix.tv.data.model.StreamSource
import com.arflix.tv.util.settingsDataStore
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

enum class HomeServerKind {
    UNKNOWN,
    JELLYFIN,
    EMBY
}

data class HomeServerConnection(
    val enabled: Boolean = true,
    val serverUrl: String = "",
    val serverName: String = "",
    val serverKind: HomeServerKind = HomeServerKind.UNKNOWN,
    val serverId: String = "",
    val userId: String = "",
    val userName: String = "",
    val accessToken: String = "",
    val lastConnectedAt: Long = 0L
) {
    val isUsable: Boolean
        get() = serverUrl.isNotBlank() && userId.isNotBlank() && accessToken.isNotBlank()
}

internal data class HomeServerCandidateInfo(
    val title: String,
    val productionYear: Int?,
    val providerIds: Map<String, String>
)

internal object HomeServerMatcher {
    fun normalizeTitle(title: String): String {
        val ascii = Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
        return ascii
            .lowercase(Locale.US)
            .replace("&", " and ")
            .replace("[^a-z0-9]+".toRegex(), " ")
            .replace("\\b(the|a|an)\\b".toRegex(), " ")
            .trim()
            .replace("\\s+".toRegex(), " ")
    }

    fun score(
        requestedTitle: String,
        requestedYear: Int?,
        imdbId: String?,
        tmdbId: Int?,
        tvdbId: Int?,
        candidate: HomeServerCandidateInfo
    ): Int {
        var score = 0
        val providers = candidate.providerIds.mapKeys { it.key.lowercase(Locale.US) }
        val cleanImdb = imdbId?.trim()?.lowercase(Locale.US)
        if (!cleanImdb.isNullOrBlank() && providers["imdb"]?.lowercase(Locale.US) == cleanImdb) {
            score += 1000
        }
        if (tmdbId != null && providers["tmdb"]?.toIntOrNull() == tmdbId) {
            score += 900
        }
        if (tvdbId != null && providers["tvdb"]?.toIntOrNull() == tvdbId) {
            score += 900
        }

        val requestedNormalized = normalizeTitle(requestedTitle)
        val candidateNormalized = normalizeTitle(candidate.title)
        if (requestedNormalized.isNotBlank() && candidateNormalized.isNotBlank()) {
            when {
                requestedNormalized == candidateNormalized -> score += 140
                requestedNormalized in candidateNormalized || candidateNormalized in requestedNormalized -> score += 65
            }
        }

        val candidateYear = candidate.productionYear
        if (requestedYear != null && candidateYear != null) {
            val delta = abs(requestedYear - candidateYear)
            when {
                delta == 0 -> score += 90
                delta == 1 -> score += 45
                delta <= 2 -> score += 15
                else -> score -= 120
            }
        }
        return score
    }

    fun isAcceptable(score: Int): Boolean = score >= 150 || score >= 900
}

@Singleton
class HomeServerRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val profileManager: ProfileManager
) {
    companion object {
        const val ADDON_ID = "home_server"
        const val ADDON_NAME = "Home Server"
        const val CONNECTION_KEY_NAME = "home_server_connection_v1"
    }

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    val connection: Flow<HomeServerConnection?> = combine(
        profileManager.activeProfileId,
        context.settingsDataStore.data
    ) { profileId, prefs ->
        parseConnection(prefs[connectionKeyFor(profileId)])
    }.distinctUntilChanged()

    suspend fun connect(rawUrl: String, username: String, password: String): Result<HomeServerConnection> =
        withContext(Dispatchers.IO) {
            runCatching {
                val serverUrl = normalizeServerUrl(rawUrl)
                require(serverUrl.isNotBlank()) { "Enter a valid server URL" }
                require(username.isNotBlank()) { "Enter a username" }
                require(password.isNotBlank()) { "Enter a password" }

                val publicInfo = fetchPublicInfo(serverUrl)
                val auth = authenticate(serverUrl, username.trim(), password)
                val connection = HomeServerConnection(
                    enabled = true,
                    serverUrl = serverUrl,
                    serverName = publicInfo.serverName.ifBlank { auth.serverName }.ifBlank { "Home Server" },
                    serverKind = detectServerKind(publicInfo.productName, publicInfo.serverName),
                    serverId = auth.serverId.ifBlank { publicInfo.serverId },
                    userId = auth.userId,
                    userName = auth.userName.ifBlank { username.trim() },
                    accessToken = auth.accessToken,
                    lastConnectedAt = System.currentTimeMillis()
                )
                saveConnection(connection)
                connection
            }
        }

    suspend fun testConnection(): Result<HomeServerConnection> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = currentConnection() ?: error("No Home Server connected")
            require(connection.isUsable) { "Home Server is disabled or incomplete" }
            val info = fetchSystemInfo(connection)
            connection.copy(
                serverName = info.serverName.ifBlank { connection.serverName },
                serverKind = detectServerKind(info.productName, info.serverName).takeUnless { it == HomeServerKind.UNKNOWN }
                    ?: connection.serverKind,
                lastConnectedAt = System.currentTimeMillis()
            ).also { saveConnection(it) }
        }
    }

    suspend fun disconnect() {
        val profileId = profileManager.getProfileId()
        context.settingsDataStore.edit { prefs ->
            prefs.remove(connectionKeyFor(profileId))
        }
    }

    suspend fun currentConnection(): HomeServerConnection? {
        val profileId = profileManager.getProfileId()
        val prefs = context.settingsDataStore.data.first()
        return parseConnection(prefs[connectionKeyFor(profileId)])
    }

    suspend fun resolveMovieSources(
        imdbId: String?,
        title: String,
        year: Int?,
        tmdbId: Int?
    ): List<StreamSource> = withContext(Dispatchers.IO) {
        val connection = currentConnection()?.takeIf { it.isUsable } ?: return@withContext emptyList()
        val item = findBestMovie(connection, imdbId, title, year, tmdbId) ?: return@withContext emptyList()
        buildStreamSources(connection, item)
    }

    suspend fun resolveEpisodeSources(
        imdbId: String?,
        title: String,
        season: Int,
        episode: Int,
        tmdbId: Int?,
        tvdbId: Int?
    ): List<StreamSource> = withContext(Dispatchers.IO) {
        val connection = currentConnection()?.takeIf { it.isUsable } ?: return@withContext emptyList()
        val series = findBestSeries(connection, imdbId, title, null, tmdbId, tvdbId)
            ?: return@withContext emptyList()
        val episodeItem = findEpisode(connection, series.id, season, episode)
            ?: findEpisodeBySearch(connection, title, season, episode, imdbId, tmdbId, tvdbId)
            ?: return@withContext emptyList()
        buildStreamSources(connection, episodeItem)
    }

    private suspend fun saveConnection(connection: HomeServerConnection) {
        val profileId = profileManager.getProfileId()
        context.settingsDataStore.edit { prefs ->
            prefs[connectionKeyFor(profileId)] = gson.toJson(connection)
        }
    }

    private fun connectionKeyFor(profileId: String) =
        profileManager.profileStringKeyFor(profileId, CONNECTION_KEY_NAME)

    private fun parseConnection(json: String?): HomeServerConnection? {
        if (json.isNullOrBlank()) return null
        return runCatching { gson.fromJson(json, HomeServerConnection::class.java) }.getOrNull()
    }

    private fun normalizeServerUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return withScheme.toHttpUrlOrNull()?.toString()?.trimEnd('/').orEmpty()
    }

    private fun detectServerKind(productName: String, serverName: String): HomeServerKind {
        val text = "$productName $serverName".lowercase(Locale.US)
        return when {
            "emby" in text -> HomeServerKind.EMBY
            "jellyfin" in text -> HomeServerKind.JELLYFIN
            else -> HomeServerKind.UNKNOWN
        }
    }

    private fun deviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: "arvio-android"
    }

    private fun authHeader(token: String? = null): String {
        val base = "MediaBrowser Client=\"ARVIO\", Device=\"Android\", DeviceId=\"${deviceId()}\", Version=\"${BuildConfig.VERSION_NAME}\""
        return if (token.isNullOrBlank()) base else "$base, Token=\"$token\""
    }

    private fun requestBuilder(url: String, connection: HomeServerConnection? = null): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "ARVIO/${BuildConfig.VERSION_NAME}")
            .header("X-Emby-Authorization", authHeader(connection?.accessToken))
        if (connection != null) {
            builder.header("X-Emby-Token", connection.accessToken)
        }
        return builder
    }

    private fun playbackHeaders(connection: HomeServerConnection): Map<String, String> = mapOf(
        "User-Agent" to "ARVIO/${BuildConfig.VERSION_NAME}",
        "X-Emby-Authorization" to authHeader(connection.accessToken),
        "X-Emby-Token" to connection.accessToken
    )

    private fun buildUrl(
        baseUrl: String,
        path: String,
        query: Map<String, String?> = emptyMap()
    ): String {
        val base = baseUrl.toHttpUrlOrNull() ?: error("Invalid server URL")
        val builder = base.newBuilder()
        path.trim('/').split('/').filter { it.isNotBlank() }.forEach { builder.addPathSegment(it) }
        query.forEach { (key, value) ->
            if (!value.isNullOrBlank()) builder.addQueryParameter(key, value)
        }
        return builder.build().toString()
    }

    private fun absoluteUrl(baseUrl: String, pathOrUrl: String): String {
        if (pathOrUrl.startsWith("http://", true) || pathOrUrl.startsWith("https://", true)) return pathOrUrl
        val base = baseUrl.toHttpUrlOrNull() ?: return pathOrUrl
        val builder = base.newBuilder()
        pathOrUrl.substringBefore('?').trim('/').split('/').filter { it.isNotBlank() }.forEach {
            builder.addPathSegment(it)
        }
        val query = pathOrUrl.substringAfter('?', missingDelimiterValue = "")
        if (query.isNotBlank()) {
            query.split('&').forEach { part ->
                val key = part.substringBefore('=')
                val value = part.substringAfter('=', missingDelimiterValue = "")
                if (key.isNotBlank()) builder.addEncodedQueryParameter(key, value)
            }
        }
        return builder.build().toString()
    }

    private fun getJson(url: String, connection: HomeServerConnection? = null): JsonObject {
        val request = requestBuilder(url, connection).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Server request failed (${response.code})")
            }
            return JsonParser().parse(body).asJsonObjectOrNull() ?: JsonObject()
        }
    }

    private fun postJson(url: String, bodyJson: JsonObject, connection: HomeServerConnection? = null): JsonObject {
        val request = requestBuilder(url, connection)
            .post(gson.toJson(bodyJson).toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Server sign in failed (${response.code})")
            }
            return JsonParser().parse(body).asJsonObjectOrNull() ?: JsonObject()
        }
    }

    private fun fetchPublicInfo(serverUrl: String): ServerInfo {
        val info = runCatching { getJson(buildUrl(serverUrl, "/System/Info/Public")) }.getOrNull()
        return ServerInfo(
            serverName = info?.string("ServerName").orEmpty(),
            serverId = info?.string("Id").orEmpty(),
            productName = info?.string("ProductName").orEmpty()
        )
    }

    private fun fetchSystemInfo(connection: HomeServerConnection): ServerInfo {
        val info = getJson(buildUrl(connection.serverUrl, "/System/Info"), connection)
        return ServerInfo(
            serverName = info.string("ServerName"),
            serverId = info.string("Id"),
            productName = info.string("ProductName")
        )
    }

    private fun authenticate(serverUrl: String, username: String, password: String): AuthResponse {
        val body = JsonObject().apply {
            addProperty("Username", username)
            addProperty("Pw", password)
            addProperty("Password", password)
        }
        val response = postJson(buildUrl(serverUrl, "/Users/AuthenticateByName"), body)
        val user = response.obj("User")
        return AuthResponse(
            accessToken = response.string("AccessToken"),
            serverId = response.string("ServerId"),
            serverName = user?.string("ServerName").orEmpty(),
            userId = user?.string("Id").orEmpty(),
            userName = user?.string("Name").orEmpty()
        ).also {
            require(it.accessToken.isNotBlank() && it.userId.isNotBlank()) {
                "Server sign in did not return a playable account"
            }
        }
    }

    private fun findBestMovie(
        connection: HomeServerConnection,
        imdbId: String?,
        title: String,
        year: Int?,
        tmdbId: Int?
    ): HomeServerItem? {
        val candidates = linkedMapOf<String, HomeServerItem>()
        providerQueries(imdbId, tmdbId, null).forEach { providerId ->
            queryItems(
                connection,
                itemTypes = "Movie",
                query = mapOf("AnyProviderIdEquals" to providerId, "Limit" to "10")
            ).forEach { candidates[it.id] = it }
        }
        val bestById = bestCandidate(candidates.values, title, year, imdbId, tmdbId, null)
        if (bestById != null && HomeServerMatcher.score(title, year, imdbId, tmdbId, null, bestById.info()) >= 900) {
            return bestById
        }

        if (title.isNotBlank()) {
            queryItems(
                connection,
                itemTypes = "Movie",
                query = mapOf("SearchTerm" to title, "Limit" to "25")
            ).forEach { candidates[it.id] = it }
        }
        return bestCandidate(candidates.values, title, year, imdbId, tmdbId, null)
    }

    private fun findBestSeries(
        connection: HomeServerConnection,
        imdbId: String?,
        title: String,
        year: Int?,
        tmdbId: Int?,
        tvdbId: Int?
    ): HomeServerItem? {
        val candidates = linkedMapOf<String, HomeServerItem>()
        providerQueries(imdbId, tmdbId, tvdbId).forEach { providerId ->
            queryItems(
                connection,
                itemTypes = "Series",
                query = mapOf("AnyProviderIdEquals" to providerId, "Limit" to "10")
            ).forEach { candidates[it.id] = it }
        }
        val bestById = bestCandidate(candidates.values, title, year, imdbId, tmdbId, tvdbId)
        if (bestById != null && HomeServerMatcher.score(title, year, imdbId, tmdbId, tvdbId, bestById.info()) >= 900) {
            return bestById
        }

        if (title.isNotBlank()) {
            queryItems(
                connection,
                itemTypes = "Series",
                query = mapOf("SearchTerm" to title, "Limit" to "25")
            ).forEach { candidates[it.id] = it }
        }
        return bestCandidate(candidates.values, title, year, imdbId, tmdbId, tvdbId)
    }

    private fun providerQueries(imdbId: String?, tmdbId: Int?, tvdbId: Int?): List<String> {
        return buildList {
            imdbId?.trim()?.takeIf { it.isNotBlank() }?.let {
                add("imdb.$it")
                add("Imdb.$it")
            }
            tmdbId?.takeIf { it > 0 }?.let {
                add("tmdb.$it")
                add("Tmdb.$it")
            }
            tvdbId?.takeIf { it > 0 }?.let {
                add("tvdb.$it")
                add("Tvdb.$it")
            }
        }.distinct()
    }

    private fun bestCandidate(
        candidates: Collection<HomeServerItem>,
        title: String,
        year: Int?,
        imdbId: String?,
        tmdbId: Int?,
        tvdbId: Int?
    ): HomeServerItem? {
        return candidates
            .map { item -> item to HomeServerMatcher.score(title, year, imdbId, tmdbId, tvdbId, item.info()) }
            .filter { (_, score) -> HomeServerMatcher.isAcceptable(score) }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    private fun findEpisode(
        connection: HomeServerConnection,
        seriesId: String,
        season: Int,
        episode: Int
    ): HomeServerItem? {
        val byShowEndpoint = getJson(
            buildUrl(
                connection.serverUrl,
                "/Shows/$seriesId/Episodes",
                mapOf(
                    "UserId" to connection.userId,
                    "Season" to season.toString(),
                    "Fields" to itemFields()
                )
            ),
            connection
        ).items()
        return byShowEndpoint.firstOrNull { it.parentIndexNumber == season && it.indexNumber == episode }
            ?: queryItems(
                connection,
                itemTypes = "Episode",
                query = mapOf(
                    "SeriesId" to seriesId,
                    "ParentIndexNumber" to season.toString(),
                    "IndexNumber" to episode.toString(),
                    "Limit" to "10"
                )
            ).firstOrNull { it.parentIndexNumber == season && it.indexNumber == episode }
    }

    private fun findEpisodeBySearch(
        connection: HomeServerConnection,
        title: String,
        season: Int,
        episode: Int,
        imdbId: String?,
        tmdbId: Int?,
        tvdbId: Int?
    ): HomeServerItem? {
        val candidates = queryItems(
            connection,
            itemTypes = "Episode",
            query = mapOf(
                "SearchTerm" to title,
                "ParentIndexNumber" to season.toString(),
                "IndexNumber" to episode.toString(),
                "Limit" to "25"
            )
        ).filter { it.parentIndexNumber == season && it.indexNumber == episode }
        return bestCandidate(candidates, title, null, imdbId, tmdbId, tvdbId)
    }

    private fun queryItems(
        connection: HomeServerConnection,
        itemTypes: String,
        query: Map<String, String?>
    ): List<HomeServerItem> {
        val response = getJson(
            buildUrl(
                connection.serverUrl,
                "/Users/${connection.userId}/Items",
                mapOf(
                    "Recursive" to "true",
                    "IncludeItemTypes" to itemTypes,
                    "Fields" to itemFields()
                ) + query
            ),
            connection
        )
        return response.items()
    }

    private fun itemFields(): String = "ProviderIds,MediaSources,MediaStreams,Path,PremiereDate,ProductionYear,RunTimeTicks"

    private fun buildStreamSources(
        connection: HomeServerConnection,
        item: HomeServerItem
    ): List<StreamSource> {
        val playbackInfo = runCatching {
            postJson(
                buildUrl(
                    connection.serverUrl,
                    "/Items/${item.id}/PlaybackInfo",
                    mapOf(
                        "UserId" to connection.userId,
                        "StartTimeTicks" to "0",
                        "IsPlayback" to "true",
                        "AutoOpenLiveStream" to "true",
                        "MaxStreamingBitrate" to "2147483647"
                    )
                ),
                JsonObject(),
                connection
            )
        }.getOrNull()

        val sources = playbackInfo?.mediaSources().orEmpty().ifEmpty { item.mediaSources }
        return sources
            .mapNotNull { mediaSource ->
                val url = mediaSource.playbackUrl(connection, item.id) ?: return@mapNotNull null
                val quality = qualityLabel(mediaSource)
                val labelParts = listOfNotNull(
                    ADDON_NAME,
                    quality.takeIf { it.isNotBlank() },
                    mediaSource.container.takeIf { it.isNotBlank() }?.uppercase(Locale.US)
                )
                StreamSource(
                    source = labelParts.joinToString(" "),
                    addonName = ADDON_NAME,
                    addonId = ADDON_ID,
                    quality = quality.ifBlank { "Unknown" },
                    size = formatBytes(mediaSource.sizeBytes),
                    sizeBytes = mediaSource.sizeBytes.takeIf { it > 0L },
                    url = url,
                    behaviorHints = StreamBehaviorHints(
                        cached = true,
                        filename = mediaSource.name.ifBlank { item.name },
                        videoSize = mediaSource.sizeBytes.takeIf { it > 0L },
                        proxyHeaders = ProxyHeaders(request = playbackHeaders(connection))
                    )
                )
            }
            .distinctBy { "${it.url?.trim().orEmpty()}|${it.source}" }
            .sortedWith(compareByDescending<StreamSource> { qualityRank(it.quality) }
                .thenByDescending { it.sizeBytes ?: 0L })
    }

    private fun HomeServerMediaSource.playbackUrl(connection: HomeServerConnection, itemId: String): String? {
        path.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }?.let { return it }
        transcodingUrl.takeIf { it.isNotBlank() }?.let { raw ->
            val absolute = absoluteUrl(connection.serverUrl, raw)
            val parsed = absolute.toHttpUrlOrNull() ?: return absolute
            return parsed.newBuilder()
                .apply {
                    if (parsed.queryParameter("api_key").isNullOrBlank()) {
                        addQueryParameter("api_key", connection.accessToken)
                    }
                }
                .build()
                .toString()
        }

        val extension = streamExtension()
        val streamPath = if (extension != null) "/Videos/$itemId/stream.$extension" else "/Videos/$itemId/stream"
        return buildUrl(
            connection.serverUrl,
            streamPath,
            mapOf(
                "Static" to "true",
                "MediaSourceId" to id,
                "DeviceId" to deviceId(),
                "api_key" to connection.accessToken,
                "Tag" to eTag.takeIf { it.isNotBlank() }
            )
        )
    }

    private fun HomeServerMediaSource.streamExtension(): String? {
        val normalized = container
            .split(',')
            .firstOrNull()
            ?.trim()
            ?.lowercase(Locale.US)
            ?.replace("matroska", "mkv")
            ?.replace("[^a-z0-9]".toRegex(), "")
            .orEmpty()
        return normalized.takeIf { it.isNotBlank() && it.length <= 5 }
    }

    private fun qualityLabel(source: HomeServerMediaSource): String {
        val height = source.videoHeight
        val width = source.videoWidth
        return when {
            height >= 2160 || width >= 3800 -> "4K"
            height >= 1440 -> "1440p"
            height >= 1080 -> "1080p"
            height >= 720 -> "720p"
            height >= 576 -> "576p"
            height >= 480 -> "480p"
            height > 0 -> "${height}p"
            else -> source.name.extractQualityLabel()
        }
    }

    private fun qualityRank(quality: String): Int {
        val text = quality.lowercase(Locale.US)
        return when {
            "4k" in text || "2160" in text || "uhd" in text -> 2160
            "1440" in text -> 1440
            "1080" in text -> 1080
            "720" in text -> 720
            "576" in text -> 576
            "480" in text -> 480
            else -> 0
        }
    }

    private fun String.extractQualityLabel(): String {
        val text = lowercase(Locale.US)
        return when {
            "2160" in text || "4k" in text || "uhd" in text -> "4K"
            "1440" in text -> "1440p"
            "1080" in text -> "1080p"
            "720" in text -> "720p"
            "576" in text -> "576p"
            "480" in text -> "480p"
            else -> ""
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return ""
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) {
            String.format(Locale.US, "%.2f GB", gb)
        } else {
            val mb = bytes / (1024.0 * 1024.0)
            String.format(Locale.US, "%.0f MB", mb)
        }
    }

    private fun JsonObject.items(): List<HomeServerItem> {
        return array("Items").mapNotNull { it.asJsonObjectOrNull()?.toHomeServerItem() }
    }

    private fun JsonObject.mediaSources(): List<HomeServerMediaSource> {
        return array("MediaSources").mapNotNull { it.asJsonObjectOrNull()?.toMediaSource() }
    }

    private fun JsonObject.toHomeServerItem(): HomeServerItem {
        val providerIds = obj("ProviderIds")?.entrySet()
            ?.associate { it.key.lowercase(Locale.US) to it.value.asStringOrNull().orEmpty() }
            .orEmpty()
        val year = int("ProductionYear") ?: string("PremiereDate").take(4).toIntOrNull()
        return HomeServerItem(
            id = string("Id"),
            name = string("Name"),
            type = string("Type"),
            productionYear = year,
            providerIds = providerIds,
            indexNumber = int("IndexNumber"),
            parentIndexNumber = int("ParentIndexNumber"),
            mediaSources = mediaSources()
        )
    }

    private fun JsonObject.toMediaSource(): HomeServerMediaSource {
        val streams = array("MediaStreams").mapNotNull { it.asJsonObjectOrNull() }
        val videoStream = streams.firstOrNull { it.string("Type").equals("Video", ignoreCase = true) }
        return HomeServerMediaSource(
            id = string("Id"),
            name = string("Name"),
            path = string("Path"),
            container = string("Container"),
            eTag = string("ETag").ifBlank { string("Etag") },
            sizeBytes = long("Size") ?: long("RunTimeTicks")?.let { 0L } ?: 0L,
            transcodingUrl = string("TranscodingUrl"),
            videoWidth = videoStream?.int("Width") ?: 0,
            videoHeight = videoStream?.int("Height") ?: 0
        )
    }

    private fun JsonObject.string(name: String): String = get(name)?.asStringOrNull().orEmpty()
    private fun JsonObject.int(name: String): Int? = get(name)?.asStringOrNull()?.toIntOrNull()
    private fun JsonObject.long(name: String): Long? = get(name)?.asStringOrNull()?.toLongOrNull()
    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.asJsonObjectOrNull()
    private fun JsonObject.array(name: String): List<JsonElement> =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonElement.asStringOrNull(): String? =
        runCatching { takeUnless { it.isJsonNull }?.asString }.getOrNull()

    private data class ServerInfo(
        val serverName: String,
        val serverId: String,
        val productName: String
    )

    private data class AuthResponse(
        val accessToken: String,
        val serverId: String,
        val serverName: String,
        val userId: String,
        val userName: String
    )

    private data class HomeServerItem(
        val id: String,
        val name: String,
        val type: String,
        val productionYear: Int?,
        val providerIds: Map<String, String>,
        val indexNumber: Int?,
        val parentIndexNumber: Int?,
        val mediaSources: List<HomeServerMediaSource>
    ) {
        fun info() = HomeServerCandidateInfo(
            title = name,
            productionYear = productionYear,
            providerIds = providerIds
        )
    }

    private data class HomeServerMediaSource(
        val id: String,
        val name: String,
        val path: String,
        val container: String,
        val eTag: String,
        val sizeBytes: Long,
        val transcodingUrl: String,
        val videoWidth: Int,
        val videoHeight: Int
    )
}
