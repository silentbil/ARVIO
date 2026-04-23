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
    fun `normalizeRepositoryUrl expands uppercase cloudstream repo scheme`() = runBlocking {
        val normalized = service.normalizeRepositoryUrl("CLOUDSTREAMREPO://example.com/repo.json")
        assertEquals("https://example.com/repo.json", normalized)
    }

    @Test
    fun `normalizeRepositoryUrl decodes uppercase cs repo prefix`() = runBlocking {
        val normalized = service.normalizeRepositoryUrl("HTTPS://CS.REPO/https://example.com/repo.json")
        assertEquals("https://example.com/repo.json", normalized)
    }

    @Test
    fun `normalizeRepositoryUrl decodes mixed case cs repo prefix with implicit https`() = runBlocking {
        val normalized = service.normalizeRepositoryUrl("hTTps://cS.RePo/example.com/repo.json")
        assertEquals("https://example.com/repo.json", normalized)
    }

    @Test
    fun `normalizeRepositoryUrl keeps already normalized lowercase https url unchanged`() = runBlocking {
        val input = "https://example.com/repo.json"
        val normalized = service.normalizeRepositoryUrl(input)
        assertEquals(input, normalized)
    }

    @Test
    fun `normalizeRepositoryUrl repairs legacy raw github repo urls with merged branch`() = runBlocking {
        val normalized = service.normalizeRepositoryUrl(
            "https://raw.githubusercontent.com/SaurabhKaperwan/CSX_builds/CS.json"
        )
        assertEquals(
            "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json",
            normalized
        )
    }

    @Test
    fun `normalizeStoredRepositoryUrl repairs legacy raw github repo urls leniently`() {
        val normalized = service.normalizeStoredRepositoryUrl(
            " https://raw.githubusercontent.com/SaurabhKaperwan/CSX_builds/CS.json "
        )
        assertEquals(
            "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json",
            normalized
        )
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
