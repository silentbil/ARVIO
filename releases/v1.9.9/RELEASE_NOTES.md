# ARVIO 1.9.9

## Android TV / IPTV overhaul
- Reworked the TV page for very large IPTV lists, including lists with 50,000+ channels.
- Improved channel loading, first EPG appearance, favorites, recent channels, and startup behavior.
- Fixed major DPAD focus and navigation issues across IPTV rows and channel lists.

## Smoother TV navigation
- Improved rail scrolling, focus behavior, and animation timing across the home, details, watchlist, collections, and TV pages.
- Reduced jank in heavy catalog sections such as genres, franchises, Top 10, and recently added rows.
- Fixed multiple focus cropping and blinking issues without lowering artwork quality or removing video previews.

## Playback and source loading
- Improved source discovery speed and reliability for CloudStream, HTTP, VOD, IPTV VOD, and debrid sources.
- Restored and improved MP4/service video playback behavior.
- Improved autoplay selection so higher-quality and larger sources are preferred while keeping startup faster.
- Added frame-rate matching before playback to reduce stutter.

## CloudStream and addon compatibility
- CloudStream support is still in the early stages and may not work with every extension yet.
- Expanded CloudStream and community addon support.
- Improved extractor and runtime handling so more addons appear correctly in sources and are playable.
- Improved source matching for movies and series.

## Trakt, watchlist, and continue watching
- Reworked Trakt watchlist ordering and matching so items better follow the newest-added order from Trakt.
- Improved matching by title, year, and type to avoid wrong versions, such as older remakes or unrelated entries.
- Fixed continue watching logic so it uses real in-progress Trakt data instead of everything that was ever left unfinished.
- Improved profile isolation for Trakt data, watch history, watchlist, and continue watching.

## Profile isolation and cloud sync
- Profiles now have isolated settings, catalogs, Trakt connections, history, watchlists, and continue watching.
- Addons and IPTV can still be shared where intended.
- Improved real-time cloud sync behavior across profiles and devices.
- Fixed force cloud sync and subtitle preference persistence.
- Added and refined profile PIN support, including fixes for mobile profile creation.

## Mobile and settings improvements
- Reworked the mobile settings layout and naming, including renaming "Stremio" to "Addons".
- Improved profile creation and editing on mobile, including keyboard handling and avatar picker scaling.
- Added app-wide language coverage for the languages listed in app settings.
- Added better catalog and IPTV management controls on mobile.

## Collections, catalogs, and metadata
- Fixed several genre, service, franchise, and Top 10 catalog issues.
- Top 10 Movies and Top 10 Shows are now capped correctly.
- Removed unwanted Favorite TV catalog behavior from the homescreen.
- Improved metadata logos and IMDb SVG rating display on home and details pages.
- Removed MAL score display.
- Added cleaner provider logos, including Netflix, HBO Max, Disney+, Prime Video, Hulu, Paramount+, Peacock, Apple TV+, IMDb, and others.

## Contributors
Thank you to everyone who helped with this release, including:
- Sage Gavin Davids
- Himanth Reddy
- Eier Kop / EierkopZA
- chrishudson918
- mrtxiv
- And many more people who contributed smaller fixes, ideas, testing, and feedback. Thank you.

## Sources
- Metadata and discovery: TMDB, IMDb metadata/logo assets, Trakt.
- Sync/auth: Supabase and ARVIO Cloud.
- Playback/addons: IPTV M3U/Xtream/Stalker sources, Stremio-compatible addons, CloudStream/community HTTP sources.
- Smoothness references: Android TV device traces and public Android TV performance research.

## Download
- AFTVnews / Downloader code: `9383706`
- Direct APK: `https://gitlab.com/arvio1/ARVIO/-/raw/main/releases/v1.9.9/ARVIO%20V1.9.9.apk?inline=false`
