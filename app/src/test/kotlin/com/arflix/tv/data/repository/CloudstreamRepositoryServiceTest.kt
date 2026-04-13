package com.arflix.tv.data.repository

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CloudstreamRepositoryServiceTest {

    private val service = CloudstreamRepositoryService(OkHttpClient())

    @Test
    fun `normalizeRepositoryUrl expands cloudstream repo scheme`() = runBlocking {
        val normalized = service.normalizeRepositoryUrl("cloudstreamrepo://example.com/repo.json")
        assertEquals("https://example.com/repo.json", normalized)
    }

    @Test
    fun `normalizeRepositoryUrl rejects non https urls`() = runBlocking {
        try {
            service.normalizeRepositoryUrl("http://example.com/repo.json")
            fail("Expected normalizeRepositoryUrl to reject HTTP")
        } catch (expected: IllegalArgumentException) {
            assertEquals("Cloudstream repositories must use HTTPS", expected.message)
        }
    }

    @Test
    fun `sanitizeFileName keeps stable readable prefix`() {
        val sanitized = service.sanitizeFileName("Repo Name/With Spaces")
        assertTrue(sanitized.startsWith("Repo_Name_With_Spaces_"))
    }
}
