package com.arflix.tv.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryPayloadSelectionTest {
    @Test
    fun `real multi profile snapshot outranks empty default profile`() {
        val tvSnapshot = """
            {
              "profiles": [
                {"id":"p1","name":"Arvind","avatarId":2},
                {"id":"p2","name":"Kids","avatarId":3}
              ],
              "profileSettingsById": {},
              "addonsByProfile": {"p1":[{"id":"torrentio"}]},
              "iptvByProfile": {"p1":{"playlists":[{"name":"Main","m3uUrl":"https://example.com/list.m3u"}]}},
              "updatedAt": 1000
            }
        """.trimIndent()
        val emptyPhoneSnapshot = """
            {
              "profiles": [
                {"id":"local","name":"Profile 1","avatarId":0,"avatarImageVersion":0}
              ],
              "profileSettingsById": {},
              "addonsByProfile": {"local":[]},
              "iptvByProfile": {"local":{"playlists":[]}},
              "updatedAt": 999999
            }
        """.trimIndent()

        assertTrue(
            accountSyncPayloadRestoreRank(tvSnapshot) >
                accountSyncPayloadRestoreRank(emptyPhoneSnapshot)
        )
    }

    @Test
    fun `single default profile with configured addon is still useful`() {
        val configuredDefaultSnapshot = """
            {
              "profiles": [
                {"id":"default","name":"Profile 1","avatarId":0,"avatarImageVersion":0}
              ],
              "profileSettingsById": {},
              "addonsByProfile": {"default":[{"id":"torrentio"}]},
              "iptvByProfile": {"default":{"playlists":[]}},
              "updatedAt": 1000
            }
        """.trimIndent()
        val emptyPhoneSnapshot = """
            {
              "profiles": [
                {"id":"local","name":"Profile 1","avatarId":0,"avatarImageVersion":0}
              ],
              "profileSettingsById": {},
              "addonsByProfile": {"local":[]},
              "updatedAt": 999999
            }
        """.trimIndent()

        assertTrue(
            accountSyncPayloadRestoreRank(configuredDefaultSnapshot) >
                accountSyncPayloadRestoreRank(emptyPhoneSnapshot)
        )
    }
}
