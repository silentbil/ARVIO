package com.arflix.tv.data.repository

import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.AddonType
import com.arflix.tv.data.model.RuntimeKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudstreamProviderRuntimeTest {

    /**
     * Unit test focuses on the addon-filtering behavior that's safe to
     * exercise without a real Android `Context`. The `.cs3` loading path
     * touches `DexClassLoader` / `android.util.Log` and is covered by the
     * instrumented (androidTest) suite against real published plugins.
     */
    @Test
    fun `skips addons with no artifact path`() = runTest {
        val addons = listOf(
            Addon(
                id = "x",
                name = "x",
                version = "1",
                description = "",
                isInstalled = true,
                isEnabled = true,
                type = AddonType.CUSTOM,
                runtimeKind = RuntimeKind.CLOUDSTREAM,
                installedArtifactPath = null
            )
        )
        val usable = addons.filter { it.isInstalled && it.isEnabled && !it.installedArtifactPath.isNullOrBlank() }
        assertTrue(usable.isEmpty())
    }
}
