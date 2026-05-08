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
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    EMBY,
    PLEX
}

data class HomeServerCollection(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    val enabled: Boolean = true
)

data class HomeServerConnection(
    val enabled: Boolean = true,
    val connectionId: String = "",
    val serverUrl: String = "",
    val serverName: String = "",
    val serverKind: HomeServerKind = HomeServerKind.UNKNOWN,
    val serverId: String = "",
    val userId: String = "",
    val userName: String = "",
    val accessToken: String = "",
    val collections: List<HomeServerCollection> = emptyList(),
    val lastConnectedAt: Long = 0L
) {
    val isUsable: Boolean
        get() = enabled && serverUrl.isNotBlank() && accessToken.isNotBlank() &&
            (serverKind == HomeServerKind.PLEX || userId.isNotBlank())
}

private data class HomeServerProfileConfig(
    val connections: List<HomeServerConnection> = emptyList()
)

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

    val connections: Flow<List<HomeServerConnection>> = combine(
        profileManager.activeProfileId,
        context.settingsDataStore.data
    ) { profileId, prefs ->
        parseConnections(prefs[connectionKeyFor(profileId)])
    }.distinctUntilChanged()

    val connection: Flow<HomeServerConnection?> = connections
        .map { it.firstOrNull() }
        .distinctUntilChanged()

    suspend fun connect(rawUrl: String, username: String, password: String): Result<HomeServerConnection> =
        withContext(Dispatchers.IO) {
            runCatching {
                val serverUrl = normalizeServerUrl(rawUrl)
                val trimmedUsername = username.trim()
                require(serverUrl.isNotBlank()) { "Enter a valid server URL" }
                require(password.isNotBlank()) { "Enter a password or Plex token" }

                val publicInfo = fetchPublicInfo(serverUrl)
                val detectedKind = publicInfo.serverKind
                    .takeUnless { it == HomeServerKind.UNKNOWN }
                    ?: detectServerKind(publicInfo.productName, publicInfo.serverName)
                val auth = if (detectedKind == HomeServerKind.PLEX) {
                    authenticatePlex(serverUrl, trimmedUsername, password)
                } else {
                    require(trimmedUsername.isNotBlank()) { "Enter a username" }
                    authenticate(serverUrl, trimmedUsername, password)
                }
                val connectionShell = HomeServerConnection(
                    enabled = true,
                    connectionId = createConnectionId(serverUrl, detectedKind, auth.userId.ifBlank { trimmedUsername }),
                    serverUrl = serverUrl,
                    serverName = publicInfo.serverName.ifBlank { auth.serverName }.ifBlank { "Home Server" },
                    serverKind = detectedKind,
                    serverId = auth.serverId.ifBlank { publicInfo.serverId },
                    userId = auth.userId,
                    userName = auth.userName.ifBlank { trimmedUsername },
                    accessToken = auth.accessToken,
                    lastConnectedAt = System.currentTimeMillis()
                )
                val connection = connectionShell.copy(collections = fetchCollections(connectionShell))
                saveConnection(connection)
                connection
            }
        }

    suspend fun testConnection(): Result<HomeServerConnection> = withContext(Dispatchers.IO) {
        testConnections().map { it.first() }
    }

    suspend fun testConnections(): Result<List<HomeServerConnection>> = withContext(Dispatchers.IO) {
        runCatching {
            val current = currentConnections()
            require(current.isNotEmpty()) { "No Home Server connected" }
            val refreshed = current.map { refreshConnection(it) }
            saveConnections(refreshed)
            refreshed
        }
    }

    suspend fun disconnect() {
        val profileId = profileManager.getProfileId()
        context.settingsDataStore.edit { prefs ->
            prefs.remove(connectionKeyFor(profileId))
        }
    }

    suspend fun currentConnection(): HomeServerConnection? {
        return currentConnections().firstOrNull()
    }

    suspend fun currentConnections(): List<HomeServerConnection> {
        val profileId = profileManager.getProfileId()
        val prefs = context.settingsDataStore.data.first()
        return parseConnections(prefs[connectionKeyFor(profileId)])
    }

    suspend fun resolveMovieSources(
        imdbId: String?,
        title: String,
        year: Int?,
        tmdbId: Int?
    ): List<StreamSource> = withContext(Dispatchers.IO) {
        currentConnections()
            .filter { it.isUsable }
            .flatMap { connection ->
                runCatching {
                    val item = findBestMovie(connection, imdbId, title, year, tmdbId) ?: return@runCatching emptyList()
                    buildStreamSources(connection, item)
                }.getOrDefault(emptyList())
            }
            .distinctBy { "${it.addonId}|${it.source}|${it.url}" }
    }

    suspend fun resolveEpisodeSources(
        imdbId: String?,
        title: String,
        season: Int,
        episode: Int,
        tmdbId: Int?,
        tvdbId: Int?
    ): List<StreamSource> = withContext(Dispatchers.IO) {
        currentConnections()
            .filter { it.isUsable }
            .flatMap { connection ->
                runCatching {
                    val series = findBestSeries(connection, imdbId, title, null, tmdbId, tvdbId)
                        ?: return@runCatching emptyList()
                    val episodeItem = findEpisode(connection, series.id, season, episode)
                        ?: findEpisodeBySearch(connection, title, season, episode, imdbId, tmdbId, tvdbId)
                        ?: return@runCatching emptyList()
                    buildStreamSources(connection, episodeItem)
                }.getOrDefault(emptyList())
            }
            .distinctBy { "${it.addonId}|${it.source}|${it.url}" }
    }

    private suspend fun saveConnection(connection: HomeServerConnection) {
        val existing = currentConnections()
        val key = connectionIdentity(connection)
        saveConnections(
            existing
                .filterNot { connectionIdentity(it) == key }
                .plus(connection)
        )
    }

    private suspend fun saveConnections(connections: List<HomeServerConnection>) {
        val profileId = profileManager.getProfileId()
        context.settingsDataStore.edit { prefs ->
            prefs[connectionKeyFor(profileId)] = gson.toJson(
                HomeServerProfileConfig(connections = connections.map { it.sanitized() })
            )
        }
    }

    private fun connectionKeyFor(profileId: String) =
        profileManager.profileStringKeyFor(profileId, CONNECTION_KEY_NAME)

    private fun parseConnection(json: String?): HomeServerConnection? {
        return parseConnections(json).firstOrNull()
    }

    private fun parseConnections(json: String?): List<HomeServerConnection> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = JsonParser().parse(json)
            val connections = when {
                root.isJsonObject && root.asJsonObject.has("connections") -> {
                    gson.fromJson(root, HomeServerProfileConfig::class.java).connections
                }
                root.isJsonArray -> {
                    val type = object : TypeToken<List<HomeServerConnection>>() {}.type
                    gson.fromJson<List<HomeServerConnection>>(root, type)
                }
                root.isJsonObject -> listOf(gson.fromJson(root, HomeServerConnection::class.java))
                else -> emptyList()
            }
            connections
                .map { it.sanitized() }
                .filter { it.serverUrl.isNotBlank() || it.accessToken.isNotBlank() }
                .distinctBy { connectionIdentity(it) }
        }.getOrDefault(emptyList())
    }

    private fun HomeServerConnection.sanitized(): HomeServerConnection {
        return HomeServerConnection(
            enabled = enabled,
            connectionId = connectionId.orEmpty().ifBlank {
                createConnectionId(serverUrl.orEmpty(), serverKind, userId.orEmpty().ifBlank { userName.orEmpty() })
            },
            serverUrl = normalizeServerUrl(serverUrl.orEmpty()),
            serverName = serverName.orEmpty(),
            serverKind = serverKind,
            serverId = serverId.orEmpty(),
            userId = userId.orEmpty(),
            userName = userName.orEmpty(),
            accessToken = accessToken.orEmpty(),
            collections = collections.orEmpty().map {
                HomeServerCollection(
                    id = it.id.orEmpty(),
                    name = it.name.orEmpty(),
                    type = it.type.orEmpty(),
                    enabled = it.enabled
                )
            },
            lastConnectedAt = lastConnectedAt
        )
    }

    private fun createConnectionId(serverUrl: String, kind: HomeServerKind, userIdentity: String): String {
        return "${kind.name}:${serverUrl.trimEnd('/').lowercase(Locale.US)}:${userIdentity.lowercase(Locale.US)}"
            .replace("[^a-z0-9:._-]+".toRegex(), "_")
    }

    private fun connectionIdentity(connection: HomeServerConnection): String {
        return connection.connectionId.ifBlank {
            createConnectionId(
                connection.serverUrl,
                connection.serverKind,
                connection.userId.ifBlank { connection.userName }
            )
        }
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
            "plex" in text -> HomeServerKind.PLEX
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

    private fun plexHeaders(token: String? = null): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("User-Agent", "ARVIO/${BuildConfig.VERSION_NAME}")
        put("X-Plex-Client-Identifier", deviceId())
        put("X-Plex-Product", "ARVIO")
        put("X-Plex-Version", BuildConfig.VERSION_NAME)
        put("X-Plex-Device", "Android")
        put("X-Plex-Platform", "Android")
        token?.takeIf { it.isNotBlank() }?.let { put("X-Plex-Token", it) }
    }

    private fun requestBuilder(url: String, connection: HomeServerConnection? = null): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "ARVIO/${BuildConfig.VERSION_NAME}")
        if (connection?.serverKind == HomeServerKind.PLEX) {
            plexHeaders(connection.accessToken).forEach { (key, value) -> builder.header(key, value) }
        } else {
            builder.header("X-Emby-Authorization", authHeader(connection?.accessToken))
            if (connection != null) {
                builder.header("X-Emby-Token", connection.accessToken)
            }
        }
        return builder
    }

    private fun playbackHeaders(connection: HomeServerConnection): Map<String, String> {
        if (connection.serverKind == HomeServerKind.PLEX) return plexHeaders(connection.accessToken)
        return mapOf(
            "User-Agent" to "ARVIO/${BuildConfig.VERSION_NAME}",
            "X-Emby-Authorization" to authHeader(connection.accessToken),
            "X-Emby-Token" to connection.accessToken
        )
    }

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

    private fun getText(url: String, connection: HomeServerConnection? = null): String {
        val request = requestBuilder(url, connection).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Server request failed (${response.code})")
            }
            return body
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
        if (info != null && info.entrySet().isNotEmpty()) {
            return ServerInfo(
                serverName = info.string("ServerName"),
                serverId = info.string("Id"),
                productName = info.string("ProductName"),
                serverKind = detectServerKind(info.string("ProductName"), info.string("ServerName"))
            )
        }

        val plexIdentity = runCatching { getText(buildUrl(serverUrl, "/identity")) }.getOrNull()
        val (plexName, plexId) = parsePlexIdentity(plexIdentity.orEmpty())
        return ServerInfo(
            serverName = plexName,
            serverId = plexId,
            productName = if (plexId.isNotBlank() || plexIdentity?.contains("MediaContainer") == true) "Plex" else "",
            serverKind = if (plexId.isNotBlank() || plexIdentity?.contains("MediaContainer") == true) HomeServerKind.PLEX else HomeServerKind.UNKNOWN
        )
    }

    private fun fetchSystemInfo(connection: HomeServerConnection): ServerInfo {
        if (connection.serverKind == HomeServerKind.PLEX) {
            val identity = getText(buildUrl(connection.serverUrl, "/identity"), connection)
            val (identityName, identityId) = parsePlexIdentity(identity)
            return ServerInfo(
                serverName = identityName.ifBlank { connection.serverName },
                serverId = identityId.ifBlank { connection.serverId },
                productName = "Plex",
                serverKind = HomeServerKind.PLEX
            )
        }
        val info = getJson(buildUrl(connection.serverUrl, "/System/Info"), connection)
        return ServerInfo(
            serverName = info.string("ServerName"),
            serverId = info.string("Id"),
            productName = info.string("ProductName"),
            serverKind = detectServerKind(info.string("ProductName"), info.string("ServerName"))
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

    private fun authenticatePlex(serverUrl: String, username: String, token: String): AuthResponse {
        val accountToken = token.trim()
        val identityConnection = HomeServerConnection(
            serverUrl = serverUrl,
            serverKind = HomeServerKind.PLEX,
            accessToken = accountToken,
            userId = "plex",
            userName = username.ifBlank { "Plex" }
        )
        val identity = fetchSystemInfo(identityConnection)
        val userName = validatePlexAccount(accountToken)
            .ifBlank { username.ifBlank { "Plex" } }
        val serverToken = resolvePlexServerToken(accountToken, identity.serverId)
            .ifBlank { accountToken }
        val serverConnection = identityConnection.copy(
            accessToken = serverToken,
            serverId = identity.serverId,
            serverName = identity.serverName,
            userName = userName
        )
        runCatching { fetchCollections(serverConnection) }.getOrElse {
            error("Plex token could not access libraries")
        }
        return AuthResponse(
            accessToken = serverToken,
            serverId = identity.serverId,
            serverName = identity.serverName,
            userId = "plex",
            userName = userName
        )
    }

    private fun validatePlexAccount(token: String): String {
        val request = Request.Builder()
            .url("https://plex.tv/api/v2/user")
            .header("Accept", "application/json")
            .header("X-Plex-Token", token)
            .build()
        return runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use ""
                val body = response.body?.string().orEmpty()
                val json = JsonParser().parse(body).asJsonObjectOrNull() ?: return@use ""
                json.string("friendlyName").ifBlank { json.string("username") }.ifBlank { json.string("title") }
            }
        }.getOrDefault("")
    }

    private fun resolvePlexServerToken(accountToken: String, serverId: String): String {
        if (serverId.isBlank()) return ""
        val url = "https://plex.tv/api/resources".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("X-Plex-Token", accountToken)
            ?.build()
            ?.toString()
            ?: return ""
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/xml")
            .header("X-Plex-Token", accountToken)
            .build()
        return runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use ""
                val xml = response.body?.string().orEmpty()
                val devicePattern = Regex("""<Device\b[^>]*>""")
                devicePattern.findAll(xml)
                    .map { it.value }
                    .firstOrNull { it.xmlAttribute("clientIdentifier") == serverId }
                    ?.xmlAttribute("accessToken")
                    .orEmpty()
            }
        }.getOrDefault("")
    }

    private fun refreshConnection(connection: HomeServerConnection): HomeServerConnection {
        require(connection.isUsable) { "${connection.serverName.ifBlank { "Home Server" }} is disabled or incomplete" }
        val info = fetchSystemInfo(connection)
        val kind = info.serverKind.takeUnless { it == HomeServerKind.UNKNOWN }
            ?: detectServerKind(info.productName, info.serverName).takeUnless { it == HomeServerKind.UNKNOWN }
            ?: connection.serverKind
        val shell = connection.copy(
            serverName = info.serverName.ifBlank { connection.serverName },
            serverKind = kind,
            serverId = info.serverId.ifBlank { connection.serverId },
            lastConnectedAt = System.currentTimeMillis()
        )
        return shell.copy(collections = fetchCollections(shell).ifEmpty { connection.collections })
    }

    private fun fetchCollections(connection: HomeServerConnection): List<HomeServerCollection> {
        if (connection.serverKind == HomeServerKind.PLEX) {
            val response = getJson(buildUrl(connection.serverUrl, "/library/sections"), connection)
            return response.array("MediaContainer", "Directory")
                .mapNotNull { it.asJsonObjectOrNull() }
                .mapNotNull { directory ->
                    val id = directory.string("key")
                    if (id.isBlank()) return@mapNotNull null
                    HomeServerCollection(
                        id = id,
                        name = directory.string("title").ifBlank { directory.string("name") }.ifBlank { "Library $id" },
                        type = directory.string("type"),
                        enabled = true
                    )
                }
        }

        val response = getJson(buildUrl(connection.serverUrl, "/Users/${connection.userId}/Views"), connection)
        return response.itemsArray()
            .mapNotNull { it.asJsonObjectOrNull() }
            .mapNotNull { item ->
                val id = item.string("Id")
                if (id.isBlank()) return@mapNotNull null
                HomeServerCollection(
                    id = id,
                    name = item.string("Name").ifBlank { "Library" },
                    type = item.string("CollectionType"),
                    enabled = true
                )
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
        if (connection.serverKind == HomeServerKind.PLEX) {
            val leaves = getJson(
                buildUrl(
                    connection.serverUrl,
                    "/library/metadata/$seriesId/allLeaves",
                    mapOf("includeGuids" to "1")
                ),
                connection
            ).metadataItems(connection.serverKind)
            return leaves.firstOrNull { it.parentIndexNumber == season && it.indexNumber == episode }
        }

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
        if (connection.serverKind == HomeServerKind.PLEX) {
            return queryPlexItems(connection, itemTypes, query)
        }
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

    private fun queryPlexItems(
        connection: HomeServerConnection,
        itemTypes: String,
        query: Map<String, String?>
    ): List<HomeServerItem> {
        if (query.containsKey("AnyProviderIdEquals")) {
            return emptyList()
        }
        val searchTerm = query["SearchTerm"]?.takeIf { it.isNotBlank() } ?: return emptyList()
        val plexType = when (itemTypes.lowercase(Locale.US)) {
            "movie" -> "1"
            "series" -> "2"
            "episode" -> "4"
            else -> null
        }
        val limit = query["Limit"]?.takeIf { it.isNotBlank() } ?: "25"
        val collections = eligibleCollections(connection, itemTypes)
        val sectionResults = if (collections.isNotEmpty()) {
            collections.flatMap { collection ->
                runCatching {
                    getJson(
                        buildUrl(
                            connection.serverUrl,
                            "/library/sections/${collection.id}/all",
                            mapOf(
                                "type" to plexType,
                                "title" to searchTerm,
                                "includeGuids" to "1",
                                "limit" to limit
                            )
                        ),
                        connection
                    ).metadataItems(connection.serverKind)
                }.getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }
        val results = sectionResults.ifEmpty {
            runCatching {
                getJson(
                    buildUrl(
                        connection.serverUrl,
                        "/search",
                        mapOf(
                            "query" to searchTerm,
                            "type" to plexType,
                            "includeGuids" to "1",
                            "limit" to limit
                        )
                    ),
                    connection
                ).metadataItems(connection.serverKind)
            }.getOrDefault(emptyList())
        }

        val requestedSeason = query["ParentIndexNumber"]?.toIntOrNull()
        val requestedEpisode = query["IndexNumber"]?.toIntOrNull()
        return results
            .filter { item ->
                (requestedSeason == null || item.parentIndexNumber == requestedSeason) &&
                    (requestedEpisode == null || item.indexNumber == requestedEpisode)
            }
            .distinctBy { it.id }
    }

    private fun eligibleCollections(connection: HomeServerConnection, itemTypes: String): List<HomeServerCollection> {
        val type = itemTypes.lowercase(Locale.US)
        return connection.collections
            .filter { it.enabled }
            .filter { collection ->
                val collectionType = collection.type.lowercase(Locale.US)
                when (type) {
                    "movie" -> collectionType in setOf("movies", "movie")
                    "series", "episode" -> collectionType in setOf("tvshows", "series", "show")
                    else -> true
                }
            }
    }

    private fun itemFields(): String = "ProviderIds,MediaSources,MediaStreams,Path,PremiereDate,ProductionYear,RunTimeTicks"

    private fun buildStreamSources(
        connection: HomeServerConnection,
        item: HomeServerItem
    ): List<StreamSource> {
        val playbackInfo = if (connection.serverKind == HomeServerKind.PLEX) null else runCatching {
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

        val sources = if (connection.serverKind == HomeServerKind.PLEX) {
            item.mediaSources.ifEmpty {
                runCatching {
                    getJson(
                        buildUrl(
                            connection.serverUrl,
                            "/library/metadata/${item.id}",
                            mapOf("includeGuids" to "1")
                        ),
                        connection
                    ).metadataItems(connection.serverKind).firstOrNull()?.mediaSources.orEmpty()
                }.getOrDefault(emptyList())
            }
        } else {
            playbackInfo?.mediaSources().orEmpty().ifEmpty { item.mediaSources }
        }
        return sources
            .mapNotNull { mediaSource ->
                val url = mediaSource.playbackUrl(connection, item.id) ?: return@mapNotNull null
                val quality = qualityLabel(mediaSource)
                val labelParts = listOfNotNull(
                    ADDON_NAME,
                    homeServerKindLabel(connection.serverKind).takeIf { it.isNotBlank() },
                    connection.serverName.takeIf { it.isNotBlank() },
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
        if (connection.serverKind == HomeServerKind.PLEX) {
            key.takeIf { it.isNotBlank() }?.let { return plexUrlWithToken(connection, absoluteUrl(connection.serverUrl, it)) }
            path.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }?.let {
                return plexUrlWithToken(connection, it)
            }
            id.takeIf { it.isNotBlank() }?.let {
                return buildUrl(
                    connection.serverUrl,
                    "/library/parts/$it/file",
                    mapOf("X-Plex-Token" to connection.accessToken)
                )
            }
            return null
        }
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

    private fun plexUrlWithToken(connection: HomeServerConnection, rawUrl: String): String {
        val parsed = rawUrl.toHttpUrlOrNull() ?: return rawUrl
        if (!parsed.queryParameter("X-Plex-Token").isNullOrBlank()) return rawUrl
        return parsed.newBuilder()
            .addQueryParameter("X-Plex-Token", connection.accessToken)
            .build()
            .toString()
    }

    private fun homeServerKindLabel(kind: HomeServerKind): String {
        return when (kind) {
            HomeServerKind.PLEX -> "Plex"
            HomeServerKind.JELLYFIN -> "Jellyfin"
            HomeServerKind.EMBY -> "Emby"
            HomeServerKind.UNKNOWN -> ""
        }
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
        return itemsArray().mapNotNull { it.asJsonObjectOrNull()?.toHomeServerItem(HomeServerKind.UNKNOWN) }
    }

    private fun JsonObject.itemsArray(): List<JsonElement> = array("Items")

    private fun JsonObject.metadataItems(kind: HomeServerKind): List<HomeServerItem> {
        return array("MediaContainer", "Metadata").mapNotNull { it.asJsonObjectOrNull()?.toHomeServerItem(kind) }
            .ifEmpty { array("Metadata").mapNotNull { it.asJsonObjectOrNull()?.toHomeServerItem(kind) } }
    }

    private fun JsonObject.mediaSources(): List<HomeServerMediaSource> {
        return array("MediaSources").mapNotNull { it.asJsonObjectOrNull()?.toMediaSource(HomeServerKind.UNKNOWN) }
    }

    private fun JsonObject.toHomeServerItem(kind: HomeServerKind): HomeServerItem {
        if (kind == HomeServerKind.PLEX) {
            val providerIds = array("Guid")
                .mapNotNull { it.asJsonObjectOrNull()?.string("id") }
                .mapNotNull { guid ->
                    val provider = guid.substringBefore("://").lowercase(Locale.US)
                    val id = guid.substringAfter("://", missingDelimiterValue = "").substringBefore("?")
                    provider.takeIf { it.isNotBlank() && id.isNotBlank() }?.let { it to id }
                }
                .toMap()
            return HomeServerItem(
                id = string("ratingKey").ifBlank { string("key") },
                name = string("title"),
                type = string("type"),
                productionYear = int("year") ?: string("originallyAvailableAt").take(4).toIntOrNull(),
                providerIds = providerIds,
                indexNumber = int("index"),
                parentIndexNumber = int("parentIndex"),
                mediaSources = array("Media")
                    .mapNotNull { it.asJsonObjectOrNull() }
                    .flatMap { media -> media.array("Part").mapNotNull { it.asJsonObjectOrNull()?.toMediaSource(kind, media) } }
            )
        }

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

    private fun JsonObject.toMediaSource(kind: HomeServerKind, parentMedia: JsonObject? = null): HomeServerMediaSource {
        if (kind == HomeServerKind.PLEX) {
            val width = parentMedia?.int("width") ?: int("width") ?: 0
            val height = parentMedia?.int("height") ?: int("height") ?: 0
            return HomeServerMediaSource(
                id = string("id"),
                key = string("key"),
                name = string("file").substringAfterLast('/').substringAfterLast('\\').ifBlank {
                    parentMedia?.string("title").orEmpty()
                },
                path = string("file"),
                container = parentMedia?.string("container").orEmpty().ifBlank { string("container") },
                eTag = "",
                sizeBytes = long("size") ?: 0L,
                transcodingUrl = "",
                videoWidth = width,
                videoHeight = height
            )
        }

        val streams = array("MediaStreams").mapNotNull { it.asJsonObjectOrNull() }
        val videoStream = streams.firstOrNull { it.string("Type").equals("Video", ignoreCase = true) }
        return HomeServerMediaSource(
            id = string("Id"),
            key = "",
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

    private fun JsonObject.array(parent: String, name: String): List<JsonElement> =
        obj(parent)?.array(name).orEmpty()

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonElement.asStringOrNull(): String? =
        runCatching { takeUnless { it.isJsonNull }?.asString }.getOrNull()

    private fun String.xmlAttribute(name: String): String {
        val pattern = Regex("""\b${Regex.escape(name)}=["']([^"']*)["']""")
        return pattern.find(this)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun parsePlexIdentity(body: String): Pair<String, String> {
        val container = runCatching {
            val json = JsonParser().parse(body).asJsonObjectOrNull()
            json?.obj("MediaContainer") ?: json
        }.getOrNull()
        val name = container?.string("friendlyName").orEmpty()
        val id = container?.string("machineIdentifier").orEmpty()
        if (name.isNotBlank() || id.isNotBlank()) {
            return name to id
        }
        return body.xmlAttribute("friendlyName") to body.xmlAttribute("machineIdentifier")
    }

    private data class ServerInfo(
        val serverName: String,
        val serverId: String,
        val productName: String,
        val serverKind: HomeServerKind = HomeServerKind.UNKNOWN
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
        val key: String,
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
