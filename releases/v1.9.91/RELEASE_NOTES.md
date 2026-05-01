# ARVIO 1.9.91

## IPTV and TV page
- Reworked IPTV category handling so provider playlist groups can stay in the same order users configured in their IPTV list.
- Added the expandable All Channels grouping for automatically matched categories.
- Added category context actions for hiding and restoring IPTV groups.
- Removed extra playlist-name clutter from channel rows.
- Improved mobile and tablet TV playback fullscreen behavior so the bottom navigation bar no longer remains visible.
- Changed the mobile top navigation label from TV Shows to TV.
- Improved IPTV VOD source handling so multiple available qualities can appear instead of only one VOD quality.

## Watchlist and Continue Watching
- Fixed Trakt watchlist order so items better follow the latest-added order.
- Fixed Trakt watchlist matching so the app is less likely to choose the wrong remake or wrong year.
- Fixed a regression where the watchlist could briefly load and then disappear into an empty state.
- Fixed stale local watchlist data on TV after switching accounts or profiles.
- Improved Continue Watching startup so cached items appear faster on the home screen.
- Improved Continue Watching behavior with and without Trakt so profile-specific progress is used more consistently.

## Playback and sources
- Improved source switching reliability in the player. Contributor: EierkopZA.
- Improved source loading from fast Search-to-Details navigation. Contributor: EierkopZA.
- Improved player back behavior and playback navigation. Contributor: Himanth Reddy.
- Improved stream startup behavior for selected sources.
- Improved trailer and service video behavior.
- Fixed loading clearlogo flicker in the player. Contributor: EierkopZA.
- Improved Android TV stability on lower-memory devices by reducing image-cache pressure during catalog scrolling and before stream playback starts.

## Catalogs and discovery
- Added Discover Catalogs search for public Trakt and MDBList lists.
- Improved Discover Catalogs TV focus outlines and navigation.
- Improved Discover Catalogs mobile layout.
- Made catalog list adding a one-click action, with Added state feedback.
- Improved catalog rename and dialog language handling.
- Fixed catalog layout controls and focus behavior. Contributor: Himanth Reddy.
- Improved catalog navigation restoration. Contributor: silentbil.

## Details, anime, and metadata
- Fixed anime episode source matching for multi-season anime.
- Fixed details page metadata behavior. Contributor: EierkopZA.
- Improved details/source reliability by waiting for IMDb ID where needed. Contributor: EierkopZA.
- Fixed several details layout and focus regressions.
- Added and refined Crunchyroll assets. Contributor: Himanth Reddy.

## Settings, language, profiles, and cloud
- Improved app language resources. Contributor: silentbil.
- Added subtitle language filtering UI. Contributor: silentbil.
- Fixed DNS persistence. Contributor: Himanth Reddy.
- Fixed cloud login/startup language restore.
- Improved profile loading and profile creation behavior.
- Improved profile and player focus fixes. Contributor: silentbil.

## Download
- AFTVnews / Downloader code: `8252981`
- Direct APK: `https://gitlab.com/arvio1/ARVIO/-/raw/main/releases/v1.9.91/ARVIO%20V1.9.91.apk?inline=false`
