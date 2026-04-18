# Networks Collections Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the pre-installed Services collection row with clean mrtxiv-hosted branded assets and add a play-once-with-sound motion hero on the collection detail screen for the 7 premium services.

**Architecture:** Add `collectionHeroVideoUrl` field to `CatalogConfig`; swap image URLs + null GIF fields for all 12 pre-installed services in `MediaRepository`; rewrite `CollectionHero` composable to branch on the video URL and render a Media3/ExoPlayer-backed motion hero that plays once with sound and freezes on the branded cover PNG when complete.

**Tech Stack:** Kotlin, Jetpack Compose, Media3/ExoPlayer 1.3.1 (already present), Gson serialization, JUnit 4 + MockK + Truth for tests.

**Spec:** `docs/superpowers/specs/2026-04-18-networks-collections-redesign-design.md`

---

## File Map

- **Modify** `app/src/main/kotlin/com/arflix/tv/data/model/CatalogModels.kt` — add `collectionHeroVideoUrl` field to `CatalogConfig`.
- **Modify** `app/src/main/kotlin/com/arflix/tv/data/repository/CatalogRepository.kt` — decode/normalize the new field.
- **Modify** `app/src/main/kotlin/com/arflix/tv/data/repository/MediaRepository.kt` — extend `collection()` helper, swap 7 services to mrtxiv URLs with video, null all service GIFs, reorder so premium 7 come first.
- **Create** `app/src/main/kotlin/com/arflix/tv/ui/screens/collections/VideoHero.kt` — ExoPlayer-backed composable, plays once with sound, calls `onEnded`, lifecycle-aware release.
- **Modify** `app/src/main/kotlin/com/arflix/tv/ui/screens/collections/CollectionDetailsScreen.kt` — rewrite `CollectionHero` to branch on `collectionHeroVideoUrl`.
- **Create** `app/src/test/kotlin/com/arflix/tv/data/model/CatalogConfigRoundtripTest.kt` — Gson round-trip test for the new field.
- **Create** `app/src/test/kotlin/com/arflix/tv/data/repository/PreinstalledServicesTest.kt` — assert premium 7 ordering + asset URLs + nulled GIFs.

---

## Constants

Used throughout the plan — quote these verbatim. The SHA pins the mrtxiv assets to a specific repo state.

```
MRTXIV_BASE = "https://raw.githubusercontent.com/mrtxiv/networks-video-collection/3486fc9a3d0efe59d1929e75f66021dc4e15bcb7/"
```

| Service | Cover PNG (wide branded card) | Video MP4 (plays once with sound) |
|---|---|---|
| Netflix | `${MRTXIV_BASE}networks%20collection/netflix.png` | `${MRTXIV_BASE}networks%20videos/netflix.mp4` |
| Prime Video | `${MRTXIV_BASE}networks%20collection/amazonprime.png` | `${MRTXIV_BASE}networks%20videos/amazonprime.mp4` |
| Apple TV+ | `${MRTXIV_BASE}networks%20collection/appletvplus.png` | `${MRTXIV_BASE}networks%20videos/appletv.mp4` |
| Disney+ | `${MRTXIV_BASE}networks%20collection/disneyplus.png` | `${MRTXIV_BASE}networks%20videos/disneyplus.mp4` |
| HBO Max | `${MRTXIV_BASE}networks%20collection/hbomax.png` | `${MRTXIV_BASE}networks%20videos/hbomax.mp4` |
| Hulu | `${MRTXIV_BASE}networks%20collection/hulu.png` | `${MRTXIV_BASE}networks%20videos/hulu.mp4` |
| Paramount+ | `${MRTXIV_BASE}networks%20collection/paramount.png` | `${MRTXIV_BASE}networks%20videos/paramount.mp4` |

Note: Apple TV+ uses `appletvplus.png` for the cover but `appletv.mp4` for the video — that's how the mrtxiv repo is organized.

---

### Task 1: Add `collectionHeroVideoUrl` field to CatalogConfig

**Files:**
- Modify: `app/src/main/kotlin/com/arflix/tv/data/model/CatalogModels.kt:71-92`
- Create: `app/src/test/kotlin/com/arflix/tv/data/model/CatalogConfigRoundtripTest.kt`

- [ ] **Step 1: Write the failing Gson round-trip test**

Create `app/src/test/kotlin/com/arflix/tv/data/model/CatalogConfigRoundtripTest.kt`:

```kotlin
package com.arflix.tv.data.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogConfigRoundtripTest {
    @Test
    fun `collectionHeroVideoUrl survives gson round-trip`() {
        val original = CatalogConfig(
            id = "collection_service_netflix",
            title = "Netflix",
            sourceType = CatalogSourceType.PREINSTALLED,
            isPreinstalled = true,
            kind = CatalogKind.COLLECTION,
            collectionGroup = CollectionGroupKind.SERVICE,
            collectionHeroVideoUrl = "https://example.com/netflix.mp4"
        )
        val gson = Gson()
        val json = gson.toJson(original)
        val restored = gson.fromJson(json, CatalogConfig::class.java)
        assertEquals("https://example.com/netflix.mp4", restored.collectionHeroVideoUrl)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.data.model.CatalogConfigRoundtripTest"`
Expected: FAIL with `unresolved reference: collectionHeroVideoUrl` (compile error).

- [ ] **Step 3: Add the field to CatalogConfig**

Edit `app/src/main/kotlin/com/arflix/tv/data/model/CatalogModels.kt`. In the `CatalogConfig` data class (lines 71–92), add the new field right after `collectionHeroGifUrl`:

```kotlin
data class CatalogConfig(
    val id: String,
    val title: String,
    val sourceType: CatalogSourceType,
    val sourceUrl: String? = null,
    val sourceRef: String? = null,
    val isPreinstalled: Boolean = false,
    val addonId: String? = null,
    val addonCatalogType: String? = null,
    val addonCatalogId: String? = null,
    val addonName: String? = null,
    val kind: CatalogKind = CatalogKind.STANDARD,
    val collectionGroup: CollectionGroupKind? = null,
    val collectionDescription: String? = null,
    val collectionCoverImageUrl: String? = null,
    val collectionFocusGifUrl: String? = null,
    val collectionHeroImageUrl: String? = null,
    val collectionHeroGifUrl: String? = null,
    val collectionHeroVideoUrl: String? = null,
    val collectionClearLogoUrl: String? = null,
    val collectionSources: List<CollectionSourceConfig> = emptyList(),
    val requiredAddonUrls: List<String> = emptyList()
) : Serializable
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.data.model.CatalogConfigRoundtripTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/data/model/CatalogModels.kt \
        app/src/test/kotlin/com/arflix/tv/data/model/CatalogConfigRoundtripTest.kt
git commit -m "feat(catalog): add collectionHeroVideoUrl field to CatalogConfig"
```

---

### Task 2: Round-trip the new field through CatalogRepository decode/normalize

**Files:**
- Modify: `app/src/main/kotlin/com/arflix/tv/data/repository/CatalogRepository.kt:742-790, 820-836`

The write path uses `gson.toJson(catalogs)` (line 162) and serializes all data-class fields automatically — no code change on write. The read path has explicit field mapping that needs updating, plus `normalizeCatalogConfig` which trims strings.

- [ ] **Step 1: Add a failing assertion to the round-trip test**

Open `app/src/test/kotlin/com/arflix/tv/data/model/CatalogConfigRoundtripTest.kt` and add this test:

```kotlin
@Test
fun `collectionHeroVideoUrl is preserved when serialized to a JSON object map`() {
    // This mirrors how CatalogRepository reads catalogs back from DataStore:
    // gson decodes the stored JSON into List<Map<String, Any?>> first, then
    // reads individual fields by key. The new field must be present in the
    // serialized JSON for that decode path to find it.
    val cfg = CatalogConfig(
        id = "id",
        title = "t",
        sourceType = CatalogSourceType.PREINSTALLED,
        collectionHeroVideoUrl = "https://example.com/v.mp4"
    )
    val json = Gson().toJson(cfg)
    assert(json.contains("collectionHeroVideoUrl")) { "Field missing from JSON: $json" }
    assert(json.contains("https://example.com/v.mp4")) { "Value missing from JSON: $json" }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.data.model.CatalogConfigRoundtripTest"`
Expected: PASS (Gson auto-serializes the field, so this already passes — it's a safeguard that write-side coverage exists before we touch read-side).

- [ ] **Step 3: Update CatalogRepository decode to read `collectionHeroVideoUrl`**

In `app/src/main/kotlin/com/arflix/tv/data/repository/CatalogRepository.kt`, around line 745 (right after `collectionHeroGifUrl`), add the decode line:

```kotlin
                val collectionHeroGifUrl = asTrimmedString(row["collectionHeroGifUrl"])
                val collectionHeroVideoUrl = asTrimmedString(row["collectionHeroVideoUrl"])
```

Then in the `CatalogConfig(...)` constructor call (around line 770–790), add the field (keep alphabetical/positional consistency with the data class order — right after `collectionHeroGifUrl`):

```kotlin
                        collectionHeroGifUrl = collectionHeroGifUrl,
                        collectionHeroVideoUrl = collectionHeroVideoUrl,
```

In `normalizeCatalogConfig` (around line 834, right after `collectionHeroGifUrl = ...`), add the trim-blank-to-null line:

```kotlin
            collectionHeroGifUrl = config.collectionHeroGifUrl?.trim().takeUnless { it.isNullOrBlank() },
            collectionHeroVideoUrl = config.collectionHeroVideoUrl?.trim().takeUnless { it.isNullOrBlank() },
```

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew :app:compileSideloadDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/data/repository/CatalogRepository.kt \
        app/src/test/kotlin/com/arflix/tv/data/model/CatalogConfigRoundtripTest.kt
git commit -m "feat(catalog): decode and normalize collectionHeroVideoUrl in CatalogRepository"
```

---

### Task 3: Extend `collection()` helper in MediaRepository to accept `heroVideo`

**Files:**
- Modify: `app/src/main/kotlin/com/arflix/tv/data/repository/MediaRepository.kt:237-263`

- [ ] **Step 1: Extend the helper signature and wire the field**

Edit `app/src/main/kotlin/com/arflix/tv/data/repository/MediaRepository.kt`. Replace the `collection(...)` helper (lines 237–263) with:

```kotlin
        fun collection(
            id: String,
            title: String,
            group: CollectionGroupKind,
            description: String,
            cover: String? = null,
            focusGif: String? = null,
            hero: String? = null,
            heroVideo: String? = null,
            clearLogo: String? = null,
            sources: List<CollectionSourceConfig>,
            requiredAddons: List<String> = emptyList()
        ) = CatalogConfig(
            id = id,
            title = title,
            sourceType = CatalogSourceType.PREINSTALLED,
            isPreinstalled = true,
            kind = CatalogKind.COLLECTION,
            collectionGroup = group,
            collectionDescription = description,
            collectionCoverImageUrl = cover,
            collectionFocusGifUrl = focusGif ?: cover,
            collectionHeroImageUrl = hero ?: cover,
            collectionHeroGifUrl = focusGif ?: hero ?: cover,
            collectionHeroVideoUrl = heroVideo,
            collectionClearLogoUrl = clearLogo,
            collectionSources = sources,
            requiredAddonUrls = requiredAddons
        )
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew :app:compileSideloadDebugKotlin`
Expected: BUILD SUCCESSFUL. All existing call sites still compile because `heroVideo` defaults to null.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/data/repository/MediaRepository.kt
git commit -m "feat(catalog): extend collection() helper with heroVideo param"
```

---

### Task 4: Rewrite the pre-installed services list

**Files:**
- Modify: `app/src/main/kotlin/com/arflix/tv/data/repository/MediaRepository.kt:272-497`
- Create: `app/src/test/kotlin/com/arflix/tv/data/repository/PreinstalledServicesTest.kt`

The new list keeps all 12 services but:
- Reorders premium 7 to the top (Netflix, Prime, Apple TV+, Disney+, HBO Max, Hulu, Paramount+).
- Swaps the premium 7's cover to mrtxiv PNG, adds `heroVideo`, nulls `focusGif` + `hero` + `clearLogo`.
- Strips `focusGif` + `clearLogo` from the 5 extras (Shudder, JioHotstar, SonyLiv, Sky, Crunchyroll); keeps existing cover and hero.

- [ ] **Step 1: Write the failing assertions**

Create `app/src/test/kotlin/com/arflix/tv/data/repository/PreinstalledServicesTest.kt`:

```kotlin
package com.arflix.tv.data.repository

import com.arflix.tv.data.model.CatalogConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * Exercises the private `buildPreinstalledDefaults()` companion function in
 * MediaRepository via reflection. That's the entry point used by
 * CatalogRepository.ensurePreinstalled to seed a fresh profile's catalogs.
 */
class PreinstalledServicesTest {

    private val premiumSevenOrder = listOf(
        "collection_service_netflix",
        "collection_service_prime",
        "collection_service_appletv",
        "collection_service_disney",
        "collection_service_hbo",
        "collection_service_hulu",
        "collection_service_paramount"
    )

    private val extraFive = setOf(
        "collection_service_shudder",
        "collection_service_jiohotstar",
        "collection_service_sonyliv",
        "collection_service_sky",
        "collection_service_crunchyroll"
    )

    private fun loadServices(): List<CatalogConfig> {
        // buildPreinstalledDefaults is a companion-object function that returns
        // the full preinstalled catalog list (services + franchises + genres).
        val companion = MediaRepository.Companion::class.java
        val method: Method = companion.getDeclaredMethod("buildPreinstalledDefaults")
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val all = method.invoke(MediaRepository.Companion) as List<CatalogConfig>
        return all.filter { it.id.startsWith("collection_service_") }
    }

    @Test
    fun `premium 7 services appear in order at the top of the list`() {
        val services = loadServices()
        val firstSevenIds = services.take(7).map { it.id }
        assertEquals(premiumSevenOrder, firstSevenIds)
    }

    @Test
    fun `all 12 services have focusGif equal to cover (no distinct GIF)`() {
        // The helper defaults `collectionFocusGifUrl` to `focusGif ?: cover`,
        // so passing focusGif = null resolves to the cover PNG itself. The
        // home-row tile treats `backdrop == image` as "no focus swap", which
        // is the clean static behavior we want. Asserting equality (rather
        // than null) pins the expected post-fallback state.
        val services = loadServices()
        assertEquals(12, services.size)
        services.forEach { cfg ->
            assertEquals(
                "Service ${cfg.id} focusGif must equal cover (no distinct GIF)",
                cfg.collectionCoverImageUrl,
                cfg.collectionFocusGifUrl
            )
        }
    }

    @Test
    fun `all 12 services have null collectionClearLogoUrl`() {
        val services = loadServices()
        services.forEach { cfg ->
            assertNull(
                "Service ${cfg.id} should not have a clearLogo",
                cfg.collectionClearLogoUrl
            )
        }
    }

    @Test
    fun `premium 7 services have mrtxiv cover and heroVideo URLs`() {
        val services = loadServices().filter { it.id in premiumSevenOrder }
        val expectedCovers = mapOf(
            "collection_service_netflix" to "networks%20collection/netflix.png",
            "collection_service_prime" to "networks%20collection/amazonprime.png",
            "collection_service_appletv" to "networks%20collection/appletvplus.png",
            "collection_service_disney" to "networks%20collection/disneyplus.png",
            "collection_service_hbo" to "networks%20collection/hbomax.png",
            "collection_service_hulu" to "networks%20collection/hulu.png",
            "collection_service_paramount" to "networks%20collection/paramount.png"
        )
        val expectedVideos = mapOf(
            "collection_service_netflix" to "networks%20videos/netflix.mp4",
            "collection_service_prime" to "networks%20videos/amazonprime.mp4",
            "collection_service_appletv" to "networks%20videos/appletv.mp4",
            "collection_service_disney" to "networks%20videos/disneyplus.mp4",
            "collection_service_hbo" to "networks%20videos/hbomax.mp4",
            "collection_service_hulu" to "networks%20videos/hulu.mp4",
            "collection_service_paramount" to "networks%20videos/paramount.mp4"
        )
        services.forEach { cfg ->
            val cover = cfg.collectionCoverImageUrl
            val video = cfg.collectionHeroVideoUrl
            assertNotNull("${cfg.id} cover", cover)
            assertNotNull("${cfg.id} heroVideo", video)
            assertTrue(
                "${cfg.id} cover must be mrtxiv asset, was $cover",
                cover!!.contains("raw.githubusercontent.com/mrtxiv/networks-video-collection") &&
                    cover.endsWith(expectedCovers[cfg.id]!!)
            )
            assertTrue(
                "${cfg.id} heroVideo must be mrtxiv asset, was $video",
                video!!.contains("raw.githubusercontent.com/mrtxiv/networks-video-collection") &&
                    video.endsWith(expectedVideos[cfg.id]!!)
            )
        }
    }

    @Test
    fun `5 extra services have no heroVideo`() {
        val services = loadServices().filter { it.id in extraFive }
        assertEquals(5, services.size)
        services.forEach { cfg ->
            assertNull("${cfg.id} should not have heroVideo", cfg.collectionHeroVideoUrl)
        }
    }
}
```

**Note:** This test calls a companion function `buildPreinstalledDefaults` that doesn't exist yet. That function must be extracted in the next step. If the codebase already has a different entry point, substitute it here — the reflection call matches whatever name is used in `MediaRepository.Companion`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.data.repository.PreinstalledServicesTest"`
Expected: FAIL — either `NoSuchMethodException` (function doesn't exist yet) or assertion failures on current URL/ordering.

- [ ] **Step 3: Extract the preinstalled defaults builder into a companion function**

Edit `app/src/main/kotlin/com/arflix/tv/data/repository/MediaRepository.kt`. Find the existing code that constructs `services`, `franchises`, `genres` lists and the final combined list. Refactor that whole block into a `companion object` function so the test can invoke it in isolation. Based on existing file structure, the function block containing `val services = listOf(collection(...), ...)` (line 272 onward) must be moved/wrapped so the resulting list is returned from a function named `buildPreinstalledDefaults()`.

Specifically:
1. Locate the existing `companion object` of `MediaRepository` (or add one if absent).
2. Move all the helper `fun`s (`addonCollectionSource`, `watchProviderSource`, `tmdbCollectionSource`, `curatedSource`, `mdblistSource`, `collection`) and the `services`/`franchises`/etc. list-construction code inside a new companion function:

```kotlin
    companion object {
        // ... existing constants like STREAMING_COLLECTION_ADDON_URL ...

        fun buildPreinstalledDefaults(): List<CatalogConfig> {
            fun addonCollectionSource(/* ... existing signature ... */) = /* ... */
            fun watchProviderSource(/* ... */) = /* ... */
            fun tmdbCollectionSource(/* ... */) = /* ... */
            fun curatedSource(/* ... */) = /* ... */
            fun mdblistSource(/* ... */) = /* ... */
            fun collection(
                id: String, title: String, group: CollectionGroupKind,
                description: String, cover: String? = null,
                focusGif: String? = null, hero: String? = null,
                heroVideo: String? = null, clearLogo: String? = null,
                sources: List<CollectionSourceConfig>,
                requiredAddons: List<String> = emptyList()
            ) = /* ... same body as Task 3 ... */

            val services = listOf( /* ... updated service list from Step 4 ... */ )
            val franchises = listOf( /* ... existing franchises ... */ )
            val genres = listOf( /* ... existing genres ... */ )
            // ... etc.
            return services + franchises + genres /* ... plus any other categories ... */
        }
    }
```

Keep all existing franchises/genres/directors exactly as they are — **only services are being modified** by this plan. Update the existing in-class caller that used to construct this list inline to call `buildPreinstalledDefaults()` instead.

- [ ] **Step 4: Rewrite the `services` list**

Inside `buildPreinstalledDefaults()`, replace the existing `val services = listOf(...)` block (previously at lines 272–497) with the new list below. Notice the order (premium 7 first), nulled `focusGif` / `clearLogo` fields, and `heroVideo` on the premium 7. Replace the entire `services` list with exactly this:

```kotlin
            val mrtxivBase = "https://raw.githubusercontent.com/mrtxiv/networks-video-collection/3486fc9a3d0efe59d1929e75f66021dc4e15bcb7/"
            val services = listOf(
                // ── Premium 7 (mrtxiv assets + motion hero) ──
                collection(
                    id = "collection_service_netflix",
                    title = "Netflix",
                    group = CollectionGroupKind.SERVICE,
                    description = "Trending movies and series on Netflix.",
                    cover = "${mrtxivBase}networks%20collection/netflix.png",
                    focusGif = null,
                    hero = null,
                    heroVideo = "${mrtxivBase}networks%20videos/netflix.mp4",
                    clearLogo = null,
                    sources = listOf(
                        addonCollectionSource("aio-metadata", "movie", "mdblist.88328"),
                        addonCollectionSource("aio-metadata", "series", "mdblist.86751"),
                        addonCollectionSource("org.kris.ultra.max.all.v5", "movie", "netflix_movies"),
                        addonCollectionSource("org.kris.ultra.max.all.v5", "series", "netflix_series"),
                        addonCollectionSource(null, "movie", "nfx"),
                        addonCollectionSource(null, "series", "nfx"),
                        watchProviderSource(MediaType.MOVIE, 8),
                        watchProviderSource(MediaType.TV, 8)
                    ),
                    requiredAddons = listOf(STREAMING_COLLECTION_ADDON_URL)
                ),
                collection(
                    id = "collection_service_prime",
                    title = "Prime Video",
                    group = CollectionGroupKind.SERVICE,
                    description = "Trending movies and series on Prime Video.",
                    cover = "${mrtxivBase}networks%20collection/amazonprime.png",
                    focusGif = null,
                    hero = null,
                    heroVideo = "${mrtxivBase}networks%20videos/amazonprime.mp4",
                    clearLogo = null,
                    sources = listOf(
                        addonCollectionSource("aio-metadata", "movie", "mdblist.86755"),
                        addonCollectionSource("aio-metadata", "series", "mdblist.86753"),
                        addonCollectionSource("org.kris.ultra.max.all.v5", "movie", "amazon_movies"),
                        addonCollectionSource("org.kris.ultra.max.all.v5", "series", "amazon_series"),
                        addonCollectionSource(null, "movie", "amp"),
                        addonCollectionSource(null, "series", "amp"),
                        watchProviderSource(MediaType.MOVIE, 9),
                        watchProviderSource(MediaType.TV, 9)
                    ),
                    requiredAddons = listOf(STREAMING_COLLECTION_ADDON_URL)
                ),
                collection(
                    id = "collection_service_appletv",
                    title = "Apple TV+",
                    group = CollectionGroupKind.SERVICE,
                    description = "Trending movies and series on Apple TV+.",
                    cover = "${mrtxivBase}networks%20collection/appletvplus.png",
                    focusGif = null,
                    hero = null,
                    heroVideo = "${mrtxivBase}networks%20videos/appletv.mp4",
                    clearLogo = null,
                    sources = listOf(
                        addonCollectionSource("org.kris.ultra.max.all.v5", "movie", "apple_movies"),
                        addonCollectionSource("org.kris.ultra.max.all.v5", "series", "apple_series"),
                        watchProviderSource(MediaType.MOVIE, 350),
                        watchProviderSource(MediaType.TV, 350)
                    )
                ),
                collection(
                    id = "collection_service_disney",
                    title = "Disney+",
                    group = CollectionGroupKind.SERVICE,
                    description = "Trending movies and series on Disney+.",
                    cover = "${mrtxivBase}networks%20collection/disneyplus.png",
                    focusGif = null,
                    hero = null,
                    heroVideo = "${mrtxivBase}networks%20videos/disneyplus.mp4",
                    clearLogo = null,
                    sources = listOf(
                        addonCollectionSource("org.kris.ultra.max.all.v5", "movie", "disney_movies"),
                        addonCollectionSource("org.kris.ultra.max.all.v5", "series", "disney_series"),
                        addonCollectionSource("aio-metadata", "movie", "mdblist.86759"),
                        addonCollectionSource("aio-metadata", "series", "mdblist.86758"),
                        addonCollectionSource(null, "movie", "dnp"),
                        addonCollectionSource(null, "series", "dnp"),
                        watchProviderSource(MediaType.MOVIE, 337),
                        watchProviderSource(MediaType.TV, 337)
                    ),
                    requiredAddons = listOf(STREAMING_COLLECTION_ADDON_URL)
                ),
                collection(
                    id = "collection_service_hbo",
                    title = "HBO Max",
                    group = CollectionGroupKind.SERVICE,
                    description = "Trending movies and series on HBO Max.",
                    cover = "${mrtxivBase}networks%20collection/hbomax.png",
                    focusGif = null,
                    hero = null,
                    heroVideo = "${mrtxivBase}networks%20videos/hbomax.mp4",
                    clearLogo = null,
                    sources = listOf(
                        addonCollectionSource("aio-metadata", "movie", "mdblist.89647"),
                        addonCollectionSource("aio-metadata", "series", "mdblist.89649"),
                        addonCollectionSource(null, "movie", "hbm"),
                        addonCollectionSource(null, "series", "hbm"),
                        watchProviderSource(MediaType.MOVIE, 1899),
                        watchProviderSource(MediaType.TV, 1899)
                    ),
                    requiredAddons = listOf(STREAMING_COLLECTION_ADDON_URL)
                ),
                collection(
                    id = "collection_service_hulu",
                    title = "Hulu",
                    group = CollectionGroupKind.SERVICE,
                    description = "Trending movies and series on Hulu.",
                    cover = "${mrtxivBase}networks%20collection/hulu.png",
                    focusGif = null,
                    hero = null,
                    heroVideo = "${mrtxivBase}networks%20videos/hulu.mp4",
                    clearLogo = null,
                    sources = listOf(
                        addonCollectionSource("aio-metadata", "series", "mdblist.88327"),
                        addonCollectionSource("org.kris.ultra.max.all.v5", "movie", "hulu_movies"),
                        addonCollectionSource("org.kris.ultra.max.all.v5", "series", "hulu_series"),
                        watchProviderSource(MediaType.MOVIE, 15),
                        watchProviderSource(MediaType.TV, 15)
                    )
                ),
                collection(
                    id = "collection_service_paramount",
                    title = "Paramount+",
                    group = CollectionGroupKind.SERVICE,
                    description = "Trending movies and series on Paramount+.",
                    cover = "${mrtxivBase}networks%20collection/paramount.png",
                    focusGif = null,
                    hero = null,
                    heroVideo = "${mrtxivBase}networks%20videos/paramount.mp4",
                    clearLogo = null,
                    sources = listOf(
                        addonCollectionSource("aio-metadata", "movie", "mdblist.86762"),
                        addonCollectionSource("aio-metadata", "series", "mdblist.86761"),
                        addonCollectionSource("org.kris.ultra.max.all.v5", "movie", "paramount_movies"),
                        watchProviderSource(MediaType.MOVIE, 531),
                        watchProviderSource(MediaType.TV, 531)
                    )
                ),
                // ── Extras (keep existing cover/hero, strip GIF + clearLogo) ──
                collection(
                    id = "collection_service_shudder",
                    title = "Shudder",
                    group = CollectionGroupKind.SERVICE,
                    description = "Horror & thriller picks from Shudder.",
                    cover = "https://nuvioapp.space/uploads/covers/9a804000-5337-4031-9669-7be45c213f6a.gif",
                    focusGif = null,
                    hero = "https://image.tmdb.org/t/p/original/ecKQlAEG95k62SMGhvX83oEqANK.jpg",
                    clearLogo = null,
                    sources = listOf(
                        addonCollectionSource("org.kris.ultra.max.all.v5", "movie", "shudder_movies"),
                        addonCollectionSource("org.kris.ultra.max.all.v5", "series", "shudder_series"),
                        watchProviderSource(MediaType.MOVIE, 99),
                        watchProviderSource(MediaType.TV, 99)
                    )
                ),
                collection(
                    id = "collection_service_jiohotstar",
                    title = "JioHotstar",
                    group = CollectionGroupKind.SERVICE,
                    description = "Trending movies and series on JioHotstar.",
                    cover = "https://i.postimg.cc/Pr4XcqRq/ezgif-com-video-to-gif-converter.gif",
                    focusGif = null,
                    hero = "https://image.tmdb.org/t/p/original/askg3SMvhqEl4OL52YuvdtY40Yb.jpg",
                    clearLogo = null,
                    sources = listOf(
                        addonCollectionSource("community.bharatbinge", "movie", "flixpatrol-netflix-movies"),
                        addonCollectionSource("community.bharatbinge", "series", "flixpatrol-netflix-series"),
                        addonCollectionSource("org.hilay.tv.maldivesnet", "tv", "hilay_catalog"),
                        watchProviderSource(MediaType.MOVIE, 122),
                        watchProviderSource(MediaType.TV, 122)
                    )
                ),
                collection(
                    id = "collection_service_sonyliv",
                    title = "SonyLiv",
                    group = CollectionGroupKind.SERVICE,
                    description = "Trending movies and series on SonyLiv.",
                    cover = "https://cdn.postimage.me/2026/04/11/1000046089.gif",
                    focusGif = null,
                    hero = "https://image.tmdb.org/t/p/original/uDgy6hyPd82kOHh6I95FLtLnj6p.jpg",
                    clearLogo = null,
                    sources = listOf(
                        addonCollectionSource("community.bharatbinge", "movie", "provider-sonyliv-movies"),
                        addonCollectionSource("community.bharatbinge", "series", "provider-sonyliv-series"),
                        watchProviderSource(MediaType.MOVIE, 237),
                        watchProviderSource(MediaType.TV, 237)
                    )
                ),
                collection(
                    id = "collection_service_sky",
                    title = "Sky",
                    group = CollectionGroupKind.SERVICE,
                    description = "Trending movies and series on Sky.",
                    cover = "https://nuvioapp.space/uploads/covers/be914269-f8c5-4c51-8aa5-86581074c10f.png",
                    focusGif = null,
                    hero = "https://image.tmdb.org/t/p/original/pwGmXVKUgKN13psUjlhC9zBcq1o.jpg",
                    clearLogo = null,
                    sources = listOf(
                        addonCollectionSource("aio-metadata", "movie", "mdblist.38516"),
                        addonCollectionSource("aio-metadata", "series", "mdblist.74627"),
                        watchProviderSource(MediaType.MOVIE, 29),
                        watchProviderSource(MediaType.TV, 29)
                    )
                ),
                collection(
                    id = "collection_service_crunchyroll",
                    title = "Crunchyroll",
                    group = CollectionGroupKind.SERVICE,
                    description = "Anime on Crunchyroll.",
                    cover = "https://ingeniousguru.com/wp-content/uploads/2022/10/creeky-roll.gif",
                    focusGif = null,
                    hero = "https://image.tmdb.org/t/p/original/3GQKYh6Trm8pxd2AypovoYQf4Ay.jpg",
                    clearLogo = null,
                    sources = listOf(
                        addonCollectionSource("aio-metadata", "movie", "streaming.cru_movie"),
                        addonCollectionSource("aio-metadata", "series", "streaming.cru_series"),
                        watchProviderSource(MediaType.MOVIE, 283),
                        watchProviderSource(MediaType.TV, 283)
                    )
                )
            )
```

**Important:** the `collection(...)` helper (Task 3) has `focusGif: String? = null` with fallback `collectionFocusGifUrl = focusGif ?: cover`. Passing `focusGif = null` means `collectionFocusGifUrl` falls through to `cover` — for the premium 7 this is now the static PNG (same as idle state). The home-row tile in `MediaCard.kt` treats `backdrop == image` as "no focus swap", which is the clean static behavior we want. Preserving `focusGif ?: cover` avoids breaking franchises/genres/directors that rely on cover-as-focus fallback. The explicit `focusGif = null` is a signal of intent even though the final stored value equals `cover`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.data.repository.PreinstalledServicesTest"`
Expected: ALL PASS.

- [ ] **Step 6: Full compile to catch any missed callers**

Run: `./gradlew :app:compileSideloadDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/data/repository/MediaRepository.kt \
        app/src/test/kotlin/com/arflix/tv/data/repository/PreinstalledServicesTest.kt
git commit -m "feat(collections): swap 7 services to mrtxiv assets, add motion hero URLs"
```

---

### Task 5: Create the `VideoHero` composable

**Files:**
- Create: `app/src/main/kotlin/com/arflix/tv/ui/screens/collections/VideoHero.kt`

This is a stateful composable that owns an `ExoPlayer` instance, plays the video once with sound, calls `onEnded` when playback completes, and releases the player on composition leave or lifecycle STOP. Broken into its own file so `CollectionDetailsScreen.kt` stays readable.

- [ ] **Step 1: Create the file with imports and composable skeleton**

Create `app/src/main/kotlin/com/arflix/tv/ui/screens/collections/VideoHero.kt`:

```kotlin
package com.arflix.tv.ui.screens.collections

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Plays a collection's hero video once with sound and invokes [onEnded]
 * when playback completes or errors. Lifecycle-aware: releases the player
 * on composition leave and on ON_STOP (which also flips playback to ended
 * so the detail screen falls back to the static cover when the app
 * returns to the foreground).
 */
@Composable
fun VideoHero(
    videoUrl: String,
    modifier: Modifier = Modifier,
    onEnded: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val player = remember(videoUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUrl))
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 1.0f
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(videoUrl, player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Treat errors as "done" so the screen falls back to the
                // static cover PNG rather than hanging on a blank hero.
                onEnded()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                onEnded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            }
        }
    )
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew :app:compileSideloadDebugKotlin`
Expected: BUILD SUCCESSFUL. All Media3 imports resolve because media3-exoplayer + media3-ui are already declared in `app/build.gradle.kts:229-234`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/ui/screens/collections/VideoHero.kt
git commit -m "feat(ui): add VideoHero composable for play-once collection hero"
```

---

### Task 6: Branch `CollectionHero` on `collectionHeroVideoUrl`

**Files:**
- Modify: `app/src/main/kotlin/com/arflix/tv/ui/screens/collections/CollectionDetailsScreen.kt:411-468`

Wrap the existing static hero with a `videoPlayed` flag. When a video URL is present and hasn't ended yet, render `VideoHero`; otherwise render the existing static `Image`.

- [ ] **Step 1: Add `mutableStateOf` import if not already present**

Open `app/src/main/kotlin/com/arflix/tv/ui/screens/collections/CollectionDetailsScreen.kt`. Check the imports block at the top. Ensure the following are present (add any that are missing):

```kotlin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
```

- [ ] **Step 2: Rewrite `CollectionHero` to branch on video URL**

Replace the entire existing `CollectionHero` function (currently lines 411–468) with:

```kotlin
@Composable
private fun CollectionHero(
    catalog: CatalogConfig?,
    heroHeight: androidx.compose.ui.unit.Dp
) {
    val videoUrl = catalog?.collectionHeroVideoUrl?.takeIf { it.isNotBlank() }

    // `videoPlayed` resets when `catalog?.id` changes (new detail screen entry),
    // so re-entering a services collection replays the video. Within one entry,
    // once the video ends (or errors, or the app backgrounds) we flip to STATIC
    // and never re-spawn the player.
    var videoPlayed by remember(catalog?.id) { mutableStateOf(false) }

    // Static fallback candidates — used when there's no video, when the video
    // finishes, and when the video errors. Kept as the existing list so we
    // still fall through to hero/cover/focusGif/heroGif in that order.
    val staticCandidates = remember(catalog) {
        listOfNotNull(
            catalog?.collectionHeroImageUrl,
            catalog?.collectionCoverImageUrl,
            catalog?.collectionHeroGifUrl,
            catalog?.collectionFocusGifUrl
        ).filter { it.isNotBlank() }.distinct()
    }
    var staticIndex by remember(catalog?.id) { mutableStateOf(0) }
    val currentStatic = staticCandidates.getOrNull(staticIndex)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight)
    ) {
        if (videoUrl != null && !videoPlayed) {
            VideoHero(
                videoUrl = videoUrl,
                modifier = Modifier.fillMaxSize(),
                onEnded = { videoPlayed = true }
            )
        } else if (currentStatic != null) {
            val painter = rememberAsyncImagePainter(model = currentStatic)
            val painterState = painter.state
            LaunchedEffect(painterState, currentStatic) {
                if (painterState is AsyncImagePainter.State.Error &&
                    staticIndex < staticCandidates.lastIndex
                ) {
                    staticIndex += 1
                }
            }
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        }
        // Existing gradient overlay — same as before. Stays on top of both
        // video and static branches so topbar + grid readability are preserved.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            BackgroundDark.copy(alpha = 0.65f),
                            BackgroundDark.copy(alpha = 0.25f),
                            BackgroundDark.copy(alpha = 0.85f),
                            BackgroundDark
                        )
                    )
                )
        )
    }
}
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew :app:compileSideloadDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full unit test suite as a regression check**

Run: `./gradlew :app:testSideloadDebugUnitTest`
Expected: ALL PASS (existing tests unchanged, new tests from Tasks 1–4 pass).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/ui/screens/collections/CollectionDetailsScreen.kt
git commit -m "feat(ui): play-once video hero on collection detail for services"
```

---

### Task 7: Manual UAT on emulator or device

**Files:** None (verification only).

- [ ] **Step 1: Install a fresh-profile build**

Run: `./gradlew :app:installSideloadDebug`

If testing ordering, uninstall first so DataStore is clean:
```bash
adb uninstall com.arflix.tv.sideload || true
./gradlew :app:installSideloadDebug
```

- [ ] **Step 2: Home row ordering check**

Open the app → home → locate the "Services" row. Expected tile order left to right: **Netflix → Prime Video → Apple TV+ → Disney+ → HBO Max → Hulu → Paramount+ → Shudder → JioHotstar → SonyLiv → Sky → Crunchyroll**.

- [ ] **Step 3: Row tile appearance check**

Each premium-7 tile shows a crisp high-res PNG logo card. Focus a tile (D-pad right/left) and observe: the tile does **not** animate or swap to a different image. The card stays static.

- [ ] **Step 4: Motion hero happy path — Netflix**

Press Enter/OK on the Netflix tile. Expected: the detail screen opens and the hero area plays the Netflix motion video with audio. When the video ends, the hero freezes on the Netflix branded PNG (same asset as the tile). The grid of Movies/Series loads below.

- [ ] **Step 5: Re-entry replays the video**

Press Back to return to home. Click Netflix again. Expected: video plays from the start with sound.

- [ ] **Step 6: Lifecycle check**

Enter Netflix detail screen, let the video play for ~2 seconds, then press Home on the device/remote to background the app. Return to the app. Expected: detail screen now shows the static Netflix PNG (video is "already played" — no restart).

- [ ] **Step 7: Extras (no video) — Shudder**

From home, scroll to Shudder, open detail. Expected: hero shows the static backdrop (no playback attempt, no crash). Grid loads as usual.

- [ ] **Step 8: Offline / error path**

Enable airplane mode on the emulator/device. Open Disney+ detail. Expected: video fails to load, hero falls back to the Disney+ PNG within ~1–2 seconds, no crash. Disable airplane mode after the check.

- [ ] **Step 9: Record any issues found**

If any step fails, capture a screenshot (`adb exec-out screencap -p > step_N.png`) and logcat (`adb logcat -d -t 500 > step_N.log`), then file a bug rather than patching inline — the plan is done when these steps either pass or have documented bugs.

- [ ] **Step 10: Commit nothing (verification only)**

No code changes at this stage. If Steps 1–8 all pass, the feature is ready to ship.

---

## Self-Review

- **Spec coverage:** Every spec section maps to a task. Section 1 data model → Task 1. Section 2 pre-installed service list → Tasks 3–4. Section 3 detail hero → Tasks 5–6. Section 4 home row tile cleanup → handled implicitly by Task 4 (nulled `focusGif`) — no code change required per spec. Error handling → covered in `VideoHero` (onPlayerError→onEnded) and fallback candidate chain in `CollectionHero`. Testing → Tasks 1, 2, 4 unit tests + Task 7 UAT.
- **Placeholders:** None. Every code block is complete. Every command has expected output.
- **Type consistency:** `collectionHeroVideoUrl` field name is consistent across `CatalogConfig` (Task 1), decode (Task 2), helper (Task 3), and call sites (Task 4). `VideoHero(videoUrl, modifier, onEnded)` signature (Task 5) matches call site (Task 6). `buildPreinstalledDefaults()` name is used consistently in Tasks 4 test and implementation.

---

## Plan complete.

Two execution options:

**1. Subagent-Driven (recommended)** — Fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session with checkpoints between tasks.

Which approach?
