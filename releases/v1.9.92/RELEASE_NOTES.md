# ARVIO v1.9.92

## Home server sources and catalogs
- Added Home Server source support for user-owned Jellyfin, Emby, and Plex libraries.
- Added Home Server catalog import so personal server collections can appear as ARVIO catalogs.
- Added distinct server labels in sources for users with multiple connected servers.
- Improved Home Server matching speed, source labels, playback readiness, and autoplay behavior.
- Improved Plex authentication discovery and matching reliability.

## TV, IPTV, and VOD
- Improved full IPTV EPG backfill coverage so more channels receive guide data.
- Improved live TV category context actions, category reorder behavior, and left-navigation focus.
- Improved channel logo loading performance in the TV page.
- Fixed South Africa country labeling in TV categories.
- Improved IPTV VOD quality handling for episodes and sources.

## Details, search, and navigation
- Added TMDB movie collections to details pages and moved collection rows above More Like This.
- Fixed duplicate "Collection" naming in details pages.
- Fixed several details-page spacing, cast-row, collection-row, and poster-clipping issues.
- Fixed details page cast focus jumps and vertical focus skips.
- Fixed search genre filters, search keyboard activation, and search/filter focus indicators.
- Improved home hero syncing so focused cards drive the displayed metadata more reliably.
- Improved focus border behavior and added focus-border color support.

## Continue Watching, profiles, and cloud
- Added continue-watching card enhancements, including clearer season/episode progress badges.
- Fixed false "continue at" resume times on new or unwatched upcoming episodes.
- Refreshed Continue Watching after cloud restore so cloud login restores visible progress sooner.
- Added synced custom profile avatars and fixed avatar preservation during cloud sync.
- Fixed season unwatch and batch season-watch behavior to avoid unnecessary duplicate Trakt writes.
- Fixed Trakt-connected watchlist add/remove failures after token refresh by using the secured Trakt auth proxy.
- Restored optional release-secret wiring for Trakt auth while keeping the secured proxy fallback for builds without a local secret.
- Fixed stale/revoked Trakt tokens blocking reconnect, watchlist updates, and Continue Watching refresh.
- Fixed cloud sync re-saving stale Trakt tokens after they were cleared locally.
- Fixed Trakt proxy allowlist coverage for sync-state and scrobble endpoints.

## Player, subtitles, and accessibility
- Fixed remote selection for the next-episode prompt and routed up-next remote keys correctly.
- Added AI subtitles support and upgraded Media3/ExoPlayer to 1.9.0.
- Added AI subtitle settings on mobile.
- Fixed manual subtitle selection being overwritten by default subtitle rules.
- Fixed subtitle language filtering and subtitle sorting behavior.
- Added subtitle offset and subtitle style settings.
- Added spoiler blur support and Android TV 10 fallback behavior.
- Added trailer sound controls and improved trailer setting behavior on mobile.

## Policy and cleanup
- Removed the non-working CloudStream integration path from the app for this build.
- Tightened Play/GitHub policy wording, README content, and source disclosure.
- Removed Advertising ID usage from the Play build path and clarified privacy/account deletion documentation.

## Contributors
- Sage Gavin Davids: search focus/filter fixes, details collection visibility, continue-watching cards, poster episode badges, subtitle/trailer/spoiler settings, and TV layout fixes.
- EierkopZA: spoiler blur fallback, focus border color support, TV details poster clipping, search filter focus borders, and collection/watchlist focus fixes.
- Himanth Reddy: regex/performance optimization work, codebase optimization, README maintenance, and catalog/settings stability work.
- silentbil: AI subtitles, subtitle scoring/sorting, subtitle settings fixes, and mobile AI settings visibility.
