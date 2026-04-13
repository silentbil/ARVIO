package com.arflix.tv.data.repository

import com.arflix.tv.data.model.CloudstreamPluginIndexEntry
import com.arflix.tv.data.model.CloudstreamRepositoryManifest
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudstreamRepositoryService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val gson = Gson()

    private fun requireHttpsUrl(url: String, errorMessage: String): String {
        val parsed = URI(url)
        require(parsed.scheme.equals("https", ignoreCase = true)) { errorMessage }
        return parsed.toString()
    }

    private fun resolvePluginListUrl(baseRepositoryUrl: String, pluginListUrl: String): String {
        val trimmed = pluginListUrl.trim()
        require(trimmed.isNotBlank()) { "Repository contains an empty plugin list URL" }
        val resolved = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            URL(URL(baseRepositoryUrl), trimmed).toString()
        }
        return requireHttpsUrl(resolved, "Plugin list URLs must use HTTPS")
    }

    private fun resolvePluginPackageUrl(basePluginListUrl: String, packageUrl: String): String {
        val trimmed = packageUrl.trim()
        require(trimmed.isNotBlank()) { "Plugin package URL is empty" }
        val resolved = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            URL(URL(basePluginListUrl), trimmed).toString()
        }
        return requireHttpsUrl(resolved, "Plugin packages must use HTTPS")
    }

    suspend fun normalizeRepositoryUrl(rawUrl: String): String = withContext(Dispatchers.Default) {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotBlank()) { "Repository URL is empty" }
        val expanded = when {
            trimmed.startsWith("cloudstreamrepo://", ignoreCase = true) ->
                trimmed.removePrefix("cloudstreamrepo://").let { "https://$it" }
            trimmed.startsWith("https://cs.repo/", ignoreCase = true) ->
                trimmed.removePrefix("https://cs.repo/").let { decoded ->
                    if (decoded.startsWith("http://", ignoreCase = true) || decoded.startsWith("https://", ignoreCase = true)) {
                        decoded
                    } else {
                        "https://$decoded"
                    }
                }
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "https://$trimmed"
        }
        requireHttpsUrl(expanded, "Cloudstream repositories must use HTTPS")
    }

    suspend fun fetchRepositoryManifest(url: String): CloudstreamRepositoryManifest = withContext(Dispatchers.IO) {
        val normalized = normalizeRepositoryUrl(url)
        val request = Request.Builder().url(normalized).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Failed to fetch repository (${response.code})" }
            val body = response.body?.string().orEmpty()
            try {
                val parsed = gson.fromJson(body, CloudstreamRepositoryManifest::class.java)
                require(parsed.name.isNotBlank()) { "Repository name missing" }
                require(parsed.manifestVersion > 0) { "Unsupported repository manifest version" }
                require(parsed.pluginLists.isNotEmpty()) { "Repository has no plugin lists" }
                parsed
            } catch (error: JsonSyntaxException) {
                throw IllegalArgumentException("Invalid repository manifest", error)
            }
        }
    }

    suspend fun fetchRepositoryPlugins(url: String): List<CloudstreamPluginIndexEntry> = withContext(Dispatchers.IO) {
        val normalizedRepoUrl = normalizeRepositoryUrl(url)
        val manifest = fetchRepositoryManifest(normalizedRepoUrl)
        manifest.pluginLists.flatMap { pluginListUrl ->
            val resolvedPluginListUrl = resolvePluginListUrl(normalizedRepoUrl, pluginListUrl)
            val request = Request.Builder().url(resolvedPluginListUrl).get().build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string().orEmpty()
                try {
                    gson.fromJson(body, Array<CloudstreamPluginIndexEntry>::class.java)
                        ?.toList()
                        .orEmpty()
                        .mapNotNull { entry ->
                            if (entry.internalName.isBlank() || entry.name.isBlank()) {
                                return@mapNotNull null
                            }
                            runCatching {
                                entry.copy(url = resolvePluginPackageUrl(resolvedPluginListUrl, entry.url))
                            }.getOrNull()
                        }
                } catch (_: JsonSyntaxException) {
                    emptyList()
                }
            }
        }.distinctBy { entry -> "${entry.internalName}:${entry.url}" }
    }

    suspend fun downloadPlugin(
        pluginUrl: String,
        destination: File
    ): File = withContext(Dispatchers.IO) {
        requireHttpsUrl(pluginUrl.trim(), "Plugin packages must use HTTPS")
        destination.parentFile?.mkdirs()
        val tmpDestination = File(destination.parentFile, "${destination.name}.tmp")
        if (tmpDestination.exists()) {
            tmpDestination.delete()
        }
        val request = Request.Builder().url(pluginUrl).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Failed to download plugin (${response.code})" }
            val body = response.body ?: error("Plugin download returned no body")
            FileOutputStream(tmpDestination).use { output ->
                body.byteStream().copyTo(output)
            }
        }
        if (destination.exists()) {
            destination.setWritable(true)
            destination.delete()
        }
        require(tmpDestination.renameTo(destination)) { "Failed to finalize plugin install" }
        destination.setReadOnly()
        destination
    }

    fun sanitizeFileName(value: String): String {
        val cleaned = value
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "plugin" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .take(4)
            .joinToString("") { "%02x".format(it) }
        return "${cleaned}_$digest"
    }
}
