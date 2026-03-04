# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

### Added
- (Nothing yet)

## [1.8.3] - 2026-03-04

### Added
- Search suggestions/typeahead flow with D-pad navigation and quick open from suggestion list.
- Player binge-group-aware next-episode preference handoff for more consistent source continuity.
- TMDB watch-provider data support in repository layer (used for details enrichment and future UI extensions).

### Changed
- Home vertical catalog navigation tuned for smoother up/down transitions and lower frame-skip risk.
- Home focus retention now survives category/custom-catalog list updates more reliably.
- Custom catalog incremental load starts earlier to reduce time-to-visible after entering Home.
- Details page layout overhauled for cleaner hierarchy (actions -> seasons -> episodes) with larger, richer episode cards.
- Home and Details metadata/description spacing and typography refined for improved readability.

### Fixed
- Focus could drift off-screen on some pages when navigating back across rows/lists; viewport correction logic now clamps and recenters focus targets.
- Source-switch flow hardened in Player to reduce black/stuck states during stream changes.
- Subtitle switching no longer requires full media-source rebuild in normal track-switch cases.
- Home hero metadata (time/budget/rating) now appears much faster when focus changes.
- Cross-screen focus loss regressions when custom catalogs finished loading on Home.

## [1.8.2] - 2026-03-02

### Changed
- Cross-device cloud sync (IPTV, addons, catalogs, watchlist, settings) now triggers on every profile selection instead of only on first app launch.
- Playback starts significantly faster — removed redundant startup buffer gate and lowered initial buffer threshold.

### Fixed
- Continue Watching no longer shows a 60-second empty gap when auto-playing the next episode.
- "Mark as Watched" from the context menu now correctly removes the item from Continue Watching.
- "Mark as Watched" now automatically adds the next episode to Continue Watching.
- Watched status now loads from ARVIO Cloud for non-Trakt profiles, so badges appear without a Trakt account.
- Continue Watching now syncs across devices for non-Trakt profiles using profile name instead of device-local UUID.
- Legacy Continue Watching entries no longer leak across profiles.
- Fixed duplicate key crash ("Key was already used") in Continue Watching row when the same show appeared twice.
- Watched badges now appear on initial Details page load without needing to navigate away and back.
- ARVIO Cloud watched data queries now paginate correctly for large libraries (previously capped at 1,000 rows).
- Hero clear logo now loads immediately on startup when selecting a profile, instead of requiring a focus change.
- When no streaming addons are configured, the app now shows a friendly setup guide instead of a playback error.
- Source selector shows setup instructions instead of generic "No sources found" when no addons are installed.
- Next auto-played episode no longer starts at 01:00 — correctly starts at 00:01.

## [1.6.0] - 2026-02-22

### Added
- Extended Live TV EPG timeline model to support multiple upcoming programs per channel (beyond now/next).
- Per-profile cloud snapshot payload maps for settings, addons, catalogs, IPTV config/favorites, and watchlist.
- Repository helpers for profile-specific export/import of addons, catalogs, IPTV config, and watchlist state.
- Expanded HTTP/HTTPS playback compatibility path for stream sources and header handling.
- IPTV VOD support for both movies and TV shows integrated into source resolution flows.
- Card layout mode toggle for switching between landscape and poster styles.
- Default audio language option in Settings with profile-scoped persistence.

### Changed
- App version updated to `1.6.0` (`versionCode 160`) and Settings label updated to `ARVIO V1.6`.
- Live TV EPG lane now uses real upcoming program blocks and only shows filler when timeline data is genuinely unavailable.
- IPTV loading/retry strategy tuned to reduce multi-minute startup delays and improve responsiveness.
- Playback startup buffering strategy rebalanced for movie/TV streams (larger startup gate + safer initial buffer thresholds).
- External subtitle injection timing adjusted to avoid immediate post-start media-item rebuilds.
- Profile boot flow now starts IPTV warm/load earlier after profile selection for faster Live TV readiness.
- Live TV and Settings surfaces received additional UI polish and focus/navigation refinements for Android TV remote use.

### Fixed
- IPTV Refresh action could fail with cancellation errors (`StandaloneCoroutine was canceled`) and not reload channels.
- Live TV timeline third/fourth blocks incorrectly showing `No EPG data` despite available EPG entries.
- Cross-profile leakage risk where addon sets could appear across profiles due to account-wide startup sync behavior.
- Profile isolation gaps by moving remaining global settings storage (`card layout mode`) to profile scope.
- Multiple IPTV EPG parsing paths now keep consistent upcoming-program selection across pull-parser and SAX fallbacks.
- Improved Dolby Vision startup compatibility with automatic codec fallback path (DV -> HEVC -> AVC) before source failover.

## [1.5.0] - 2026-02-17

### Added
- ARVIO Cloud TV pairing flow via QR sign-in/register and direct account linking.
- VOD sources available inside source selection for playback.
- Skip Intro integration in player with dedicated button and backend wiring.
- QR rendering component for in-app pairing.
- IPTV support now includes Xtream Codes connections.

### Changed
- App version bumped to `1.5.0` (`versionCode 150`).
- Updated Downloader install code to `5955104`.
- Catalog limits increased from `20` to `40` entries for built-in catalogs and added Trakt/MDBList catalogs.
- Improved player startup and stream handling to reduce delays before playback starts.
- Better Android TV keyboard and remote handling in settings/addon/list flows.
- Improved compatibility for Fire TV / Firestick class devices.
- Android 7 (API 24/25) support enabled by lowering app minimum SDK requirement.
- Framerate matching behavior refined in playback flow.

### Fixed
- Source discovery regression where results became very slow or stalled after initial successful loads.
- Autoplay/source fallback behavior that switched too aggressively across sources.
- Playback start issues at `00:00` for some streams and large files.
- Large 4K stream handling and retention so high-size sources are given a fair start window.
- VOD source visibility and matching reliability, including TV-show catalog flow improvements.
- Subtitle menu back-navigation behavior (back now closes subtitle layer correctly instead of exiting playback flow).
- ARVIO Cloud account pairing reliability between app and web sign-in path.
- TV remote navigation issues in settings forms/addon-list sections.
- EPG reliability and parser flow issues affecting guide behavior.

## [1.4.0] - 2026-02-14

### Added
- Optional `ARVIO Cloud` account connection in Settings for syncing profiles, addons, catalogs, and IPTV settings.
- Supabase migration and edge functions for TV device auth flow: `tv-auth-start`, `tv-auth-status`, `tv-auth-complete`.

### Fixed
- Trakt connect now displays activation URL and code while authorization is pending.
- Cloud sign-in/sign-up modal D-pad navigation (Down/Up/Left/Right) is now consistent on Android TV remotes.

## [1.3.0] - 2026-02-11

### Added
- IPTV settings now include a dedicated `Delete M3U Playlist` action to remove configured M3U/EPG and IPTV favorites.
- Updated release screenshots for Catalogs and Live TV (`v1.3`).

### Changed
- Player controls overlay no longer adds a dark background scrim behind play/pause controls.
- Sidebar focus visibility and section handoff behavior improved for clearer TV remote navigation.
- Continue Watching cards show resume timestamp and a subtle progress track.

### Fixed
- Resume metadata flow to keep Continue Watching playback start position aligned with player start.
- Multiple focus/scroll consistency issues across Home/Settings/TV surfaces.

## [1.2.0] - 2026-02-10

### Added
- Live TV page in sidebar with IPTV support.
- M3U playlist configuration in Settings.
- Catalogs tab in Settings for custom Trakt and MDBList URLs.
- Catalog ordering controls (up/down) and deletion for custom catalogs.
- Live TV mini-player flow and expanded TV navigation support.
- New screenshots for Live TV and Catalogs in README.

### Changed
- Home and catalog loading behavior across profiles.
- Focus and scroll behavior improvements across Home, Details, Search, Watchlist, and TV surfaces.
- Player/stream handling refinements for smoother transitions.
- App release version updated to `1.2.0`.

### Fixed
- Continue Watching visibility and persistence regressions.
- Custom catalog rows not appearing on Home in some profile states.
- IPTV and mini-player stability issues including focus restore and state persistence.
- Multiple UI alignment and layout consistency issues in Settings and TV screens.
