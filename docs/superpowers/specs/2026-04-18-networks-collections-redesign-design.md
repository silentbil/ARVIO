# Networks Collections Redesign

**Date:** 2026-04-18
**Status:** Design approved, ready for implementation planning

## Goal

Replace the current mix of low-res GIFs and inconsistent imagery on the pre-installed "Services" collection row with clean, uniform branded assets from [mrtxiv/networks-video-collection](https://github.com/mrtxiv/networks-video-collection), and add a play-once-with-sound motion hero on the collection detail screen for the 7 premium services.

## Context

### Current state

- Pre-installed service collections live in `MediaRepository.kt` lines 272–497. 12 services total: Netflix, Prime Video, Apple TV+, Disney+, Paramount+, HBO Max, Hulu, Shudder, JioHotstar, SonyLiv, Sky, Crunchyroll.
- Each collection carries hardcoded URLs for `collectionCoverImageUrl`, `collectionFocusGifUrl`, `collectionHeroImageUrl`, `collectionHeroGifUrl`, `collectionClearLogoUrl`. URLs are scattered across Tenor, postimg, nuvioapp, TMDB, and random CDNs — inconsistent fidelity and reliability.
- Home row service tile (`MediaCard.kt:119-135`) shows the static cover when idle; on focus, swaps to `collectionFocusGifUrl`. The user considers this focus-swap GIF "cheap" inside the poster.
- Collection detail hero (`CollectionDetailsScreen.kt:412-468`) renders a single static `Image` (falls through cover → hero → focusGif candidates). `clearLogo` and `description` fields are stored on `CatalogConfig` but not rendered anywhere on the detail screen today.
- Data sources (addon catalogs + TMDB watch provider fallback) are untouched by this redesign.

### Target assets (mrtxiv, pinned to commit `3486fc9a3d0efe59d1929e75f66021dc4e15bcb7`)

Base URL: `https://raw.githubusercontent.com/mrtxiv/networks-video-collection/3486fc9a3d0efe59d1929e75f66021dc4e15bcb7/`

Per service:
- `networks%20collection/<name>.png` — wide branded wordmark card (high-res PNG)
- `networks%20videos/<name>.mp4` — branded motion intro video with audio

| Service | collection asset | video asset |
|---|---|---|
| Netflix | `netflix.png` | `netflix.mp4` |
| Prime Video | `amazonprime.png` | `amazonprime.mp4` |
| Apple TV+ | `appletvplus.png` | `appletv.mp4` |
| Disney+ | `disneyplus.png` | `disneyplus.mp4` |
| HBO Max | `hbomax.png` | `hbomax.mp4` |
| Hulu | `hulu.png` | `hulu.mp4` |
| Paramount+ | `paramount.png` | `paramount.mp4` |

The mrtxiv repo does not cover the 5 extras (Shudder, JioHotstar, SonyLiv, Sky, Crunchyroll). They are kept with their existing static cover/hero URLs and simply get their GIF fields nulled.

## Scope

In scope:
1. Data model extension for video hero.
2. Asset swap for premium 7 services; GIF-removal for all 12 services; reorder so premium 7 come first.
3. New motion hero behavior on collection detail screen for services with a video URL.

Out of scope (not changing):
- Data-source pipeline (addon catalogs + TMDB watch providers).
- Franchise / Genre / Director collections.
- Pre-existing hidden-catalog / custom-catalog logic.
- Any persisted user ordering (existing installs keep their current order; no migration).

## Design

### 1. Data model

Add one nullable field to `CatalogConfig` in `app/src/main/kotlin/com/arflix/tv/data/model/CatalogModels.kt`:

```kotlin
val collectionHeroVideoUrl: String? = null
```

This field carries the MP4 URL to be played once on the collection detail hero. All existing fields remain unchanged.

Persistence layer in `CatalogRepository.kt` (the row encode/decode at lines ~742–834) must be updated to read and write the new field so custom/imported collections can round-trip through DataStore. Preinstalled collections regenerate from code on every startup, so no schema migration is needed for persisted state — unknown fields decode to `null`.

### 2. Pre-installed service collections (`MediaRepository.kt`)

**Service list order** (single source of truth for default install order):

1. Netflix
2. Prime Video
3. Apple TV+
4. Disney+
5. HBO Max
6. Hulu
7. Paramount+
8. Shudder
9. JioHotstar
10. SonyLiv
11. Sky
12. Crunchyroll

**Premium 7** — for each, replace the existing `cover`, `focusGif`, `hero`, `clearLogo` arguments with:

```
cover    = "<mrtxivBase>networks%20collection/<name>.png"
focusGif = null                                           // no focus-state swap
hero     = null                                           // falls back to cover for static hero
clearLogo= null                                           // not used on detail screen
collectionHeroVideoUrl = "<mrtxivBase>networks%20videos/<name>.mp4"
```

The `collection(...)` helper at lines 237–263 must be updated to accept and pass through a `heroVideo` argument.

**Extras 5** (Shudder, JioHotstar, SonyLiv, Sky, Crunchyroll) — keep existing `cover` and `hero` URLs; set `focusGif = null` and `clearLogo = null`; no `heroVideo`.

Data sources (`addonCollectionSource(...)`, `watchProviderSource(...)`, `requiredAddons`) are untouched for all 12.

### 3. Collection detail hero (`CollectionDetailsScreen.kt`)

Rewrite `CollectionHero` (currently lines 412–468) to branch on `collectionHeroVideoUrl`.

**Hero state machine** (per entry into the detail screen — state is remembered keyed on `catalog.id`, so navigating back and re-entering plays the video again):

```
state = PLAYING if catalog.collectionHeroVideoUrl != null else STATIC
```

- **STATIC branch:** current behavior. Render `Image(collectionCoverImageUrl)` with the existing gradient overlay.
- **PLAYING branch:** render an `AndroidView` wrapping a Media3 `PlayerView` with:
  - `ExoPlayer.Builder(context).build()` constructed inside `remember(catalog.id)`.
  - `MediaItem.fromUri(collectionHeroVideoUrl)`, `prepare()`, `playWhenReady = true`.
  - `volume = 1.0f` (sound on — user explicitly asked for sound).
  - `repeatMode = Player.REPEAT_MODE_OFF`.
  - `PlayerView` configured with `useController = false`, `resizeMode = RESIZE_MODE_ZOOM` to crop-fill the hero box.
  - Listener: `onPlaybackStateChanged(state)` — when `state == Player.STATE_ENDED`, release the player and flip composable state to `STATIC`, which swaps to the cover PNG freeze-frame.
  - `DisposableEffect(catalog.id)` releases the player on composition leave.
  - Lifecycle: observe `LocalLifecycleOwner`; on `ON_STOP` release the player and treat the video as "already played" so returning to the screen shows the static freeze-frame instead of restarting the video mid-session. (Re-entering the screen after a *full* nav-back to home resets state because `remember(catalog.id)` is scoped to the composition.)
  - Existing gradient overlay (`Brush.verticalGradient`, lines 453–466) stays on top of both branches.

**No clearLogo overlay. No description text.** These are already absent from today's `CollectionHero` and `CollectionTitleHeader` — the design confirms that stays the case.

### 4. Home row tile (no code change)

`MediaCard.kt:119-135` already falls through to `baseImageUrl` when `item.backdrop` is blank/equal to `item.image`. Because step 2 nulls every service's `collectionFocusGifUrl`, the `HomeViewModel.toCollectionCategory` mapping at `HomeViewModel.kt:1072-1074` sets `backdrop = collectionCoverImageUrl` (same as `image`). MediaCard's existing guard `item.backdrop?.takeIf { ... && it != item.image }` (line 129) then produces a null `collectionFocusUrl`, and the tile stays on the high-res cover PNG for both idle and focused states. No `MediaCard` code change required.

## Components

- `CatalogConfig` (data class) — +1 field.
- `MediaRepository.kt::services` (val) — URL swap, reorder, `collection()` helper signature update.
- `CollectionHero` (composable in `CollectionDetailsScreen.kt`) — two-branch render with ExoPlayer for video.
- `CatalogRepository.kt` row encode/decode — round-trip the new field.

## Error handling

- **Video load failure / network error:** ExoPlayer listener catches `onPlayerError`; immediately release and flip to STATIC. User sees the cover PNG with no interruption.
- **URL rot / 404 on mrtxiv:** commit-SHA pin (`3486fc9...`) prevents drift. If mrtxiv's raw CDN is unreachable at runtime, Coil's existing error→next-candidate fallback in `CollectionHero` (already used for images) combined with the video-load-failure path both degrade to "show whatever cover resolves." Worst case: hero box stays empty behind the gradient — acceptable, matches current fail-open behavior.
- **Player leak prevention:** `DisposableEffect` + `LifecycleEventObserver` ensure player is released on composition leave *and* on app background.

## Testing

Tests live in `app/src/test/` (unit) and `app/src/androidTest/` (instrumented). This change is mostly UI-rendering and data-config, so verification is primarily manual:

- Unit tests to add:
  - `MediaRepositoryTest` — assert premium 7 appear first in `services` list, all 12 have `collectionFocusGifUrl == null`, premium 7 have non-null `collectionHeroVideoUrl` pointing at the mrtxiv-pinned URL.
  - `CatalogConfigSerializationTest` — round-trip a `CatalogConfig` with `collectionHeroVideoUrl` through the repo's encode/decode path.
- Manual UAT checklist:
  - Fresh install → home Services row shows Netflix first, high-res static PNG, no GIF on focus.
  - Click Netflix tile → detail screen plays video with audio, ends, freezes on Netflix PNG.
  - Back out to home, re-enter Netflix collection → video plays again.
  - Play Netflix video partially → press home (app background) → reopen → detail screen shows static PNG (not restarting video).
  - Open Shudder (extra, no video) → static hero displays, no playback attempt, no crash.
  - Turn off WiFi, open Disney+ → video fails to load, static PNG shows, no crash.

## Open questions

None — all resolved during brainstorm:
- Freeze-frame after video: branded cover PNG (same asset as row tile). ✅ confirmed.
- Premium 7 ordering: first in the list. ✅ confirmed.
- Play cadence: once per detail-screen entry (re-plays on re-entry). ✅ confirmed.
- Migration for existing users' saved order: none — new ordering applies to fresh installs only. ✅ confirmed.
- Description / clearLogo removal: already absent from detail screen, no removal needed. ✅ confirmed.
