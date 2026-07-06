export type ParityStatus = "complete" | "partial" | "blocked";

export interface ParityFeature {
  id: string;
  area: string;
  androidSource: string;
  webSource: string;
  status: ParityStatus;
  browserDifference?: string;
}

export const parityFeatures: ParityFeature[] = [
  {
    id: "home",
    area: "Home",
    androidSource: "HomeScreen + HomeViewModel + MediaRepository",
    webSource: "HomeScreen + store + tmdb/catalogs",
    status: "partial",
    browserDifference: "Visual shell exists; catalog rows still need full Android ordering and every collection/template source."
  },
  {
    id: "details",
    area: "Details",
    androidSource: "DetailsScreen + DetailsViewModel",
    webSource: "DetailsDrawer + tmdb/addons",
    status: "partial",
    browserDifference: "Details, episodes, cast, reviews, related, and source actions exist; Android context menus and autoplay source planning still need full parity."
  },
  {
    id: "player",
    area: "Player",
    androidSource: "PlayerScreen + PlayerViewModel + ExoPlayer",
    webSource: "PlayerOverlay + hls.js/html5 video",
    status: "partial",
    browserDifference: "Direct/HLS playback works; torrent/infoHash, Android codecs, loudness, exact track APIs, and ExoPlayer-only behavior require browser equivalents or a resolver backend."
  },
  {
    id: "profiles",
    area: "Profiles",
    androidSource: "ProfileSelectionScreen + ProfileRepository",
    webSource: "ProfileSelectionScreen + profiles/cloud",
    status: "partial",
    browserDifference: "Profiles and avatars exist; PIN locking and avatar image upload need full browser implementation."
  },
  {
    id: "cloud",
    area: "ARVIO Cloud",
    androidSource: "AuthRepository + CloudSyncRepository",
    webSource: "auth + cloud + store",
    status: "partial",
    browserDifference: "Shared account_sync_state payload is used; more Android scopes need exact merge conflict behavior."
  },
  {
    id: "trakt",
    area: "Trakt",
    androidSource: "TraktRepository + TraktSyncService",
    webSource: "trakt + store",
    status: "partial",
    browserDifference: "Device auth, watchlist, playback, and scrobble exist; full two-way watched/history/outbox sync is not complete."
  },
  {
    id: "addons",
    area: "Addons",
    androidSource: "StreamRepository + AddonRuntimeAggregator",
    webSource: "addons",
    status: "partial",
    browserDifference: "Stremio-compatible manifests and direct streams exist; Android-only Cloudstream/runtime plugins are blocked in browser."
  },
  {
    id: "livetv",
    area: "Live TV",
    androidSource: "IptvRepository + TvViewModel + LiveTvScreen",
    webSource: "iptv + LiveTvScreen",
    status: "partial",
    browserDifference: "M3U/XMLTV/favorites exist; Xtream, Stalker, catchup, guide grid, and quick zap need more work."
  },
  {
    id: "homeserver",
    area: "Home Server",
    androidSource: "HomeServerRepository",
    webSource: "homeserver",
    status: "partial",
    browserDifference: "Plex/Jellyfin/Emby browsing exists; full episode browsing and direct-play/transcode negotiation still need parity."
  },
  {
    id: "telegram",
    area: "Telegram",
    androidSource: "TelegramRepository + TelegramSourceResolver",
    webSource: "not implemented",
    status: "blocked",
    browserDifference: "Direct Telegram client/runtime cannot safely run in browser; needs server/proxy integration."
  }
];

export function paritySummary() {
  const total = parityFeatures.length;
  const complete = parityFeatures.filter((feature) => feature.status === "complete").length;
  const partial = parityFeatures.filter((feature) => feature.status === "partial").length;
  const blocked = parityFeatures.filter((feature) => feature.status === "blocked").length;
  return { total, complete, partial, blocked };
}
