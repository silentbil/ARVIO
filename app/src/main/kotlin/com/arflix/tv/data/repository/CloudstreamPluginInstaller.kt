package com.arflix.tv.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudstreamPluginInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repositoryService: CloudstreamRepositoryService
) {
    private val installRoot: File
        get() = File(context.filesDir, "cloudstream_plugins")

    fun getPluginFile(internalName: String, repoUrl: String): File {
        val repoFolder = repositoryService.sanitizeFileName(repoUrl)
        val fileName = repositoryService.sanitizeFileName(internalName)
        return File(installRoot, "$repoFolder/$fileName.cs3")
    }

    suspend fun install(pluginUrl: String, internalName: String, repoUrl: String): File {
        val destination = getPluginFile(internalName, repoUrl)
        return repositoryService.downloadPlugin(pluginUrl, destination)
    }

    fun remove(installedPath: String?) {
        val path = installedPath?.trim().orEmpty()
        if (path.isBlank()) return
        runCatching {
            val file = File(path)
            if (file.exists()) {
                file.setWritable(true)
                file.delete()
            }
        }
    }
}
