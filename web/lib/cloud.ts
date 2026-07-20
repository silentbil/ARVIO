import type { AuthClient } from "./auth";
import { config, hasNetlifyBackendConfig } from "./config";
import { parseHomeServerConnectionJson, serializeHomeServerConnectionJson } from "./homeserver";
import { jsonRequest } from "./http";
import { normalizeIptvPlaylist as normalizeRuntimeIptvPlaylist } from "./iptv";
import { tmdbImageUrl } from "./mediaImages";
import type { TraktToken } from "./trakt";
import type { AppSettings, InstalledAddon, IptvPlaylistEntry, MediaItem, Profile, QualityFilterConfig, WatchHistoryEntry } from "./types";

export interface CloudPayload {
  version: number;
  addons: InstalledAddon[];
  settings?: Partial<AppSettings>;
  updatedAt: number;
}

type RawPayload = Record<string, unknown>;

interface AccountSyncPullResponse {
  payload?: RawPayload | string | null;
  source?: string | null;
  updatedAt?: string | null;
}

interface AndroidContinueWatchingItem {
  id?: number;
  title?: string;
  mediaType?: string;
  progress?: number;
  resumePositionSeconds?: number;
  durationSeconds?: number;
  season?: number | null;
  episode?: number | null;
  episodeTitle?: string | null;
  backdropPath?: string | null;
  posterPath?: string | null;
  streamKey?: string | null;
  streamAddonId?: string | null;
  streamTitle?: string | null;
  updatedAtMs?: number;
}

interface AndroidWatchlistItem {
  addedAt?: number;
  backdropPath?: string | null;
  mediaType?: string;
  posterPath?: string | null;
  sourceOrder?: number;
  title?: string | null;
  tmdbId?: number | null;
}

interface AndroidIptvProfileState {
  m3uUrl?: string;
  epgUrl?: string;
  playlists?: IptvPlaylistEntry[];
  stalkerPortalUrl?: string;
  stalkerMacAddress?: string;
  favoriteChannels?: string[];
  favoriteGroups?: string[];
  hiddenGroups?: string[];
  groupOrder?: string[];
}

function canUseBackendSync(auth: AuthClient) {
  return Boolean(auth.session?.accessToken && auth.isNetlifySession && hasNetlifyBackendConfig());
}

async function backendRequest<T>(auth: AuthClient, path: string, init: RequestInit = {}) {
  const token = await auth.accessToken();
  return jsonRequest<T>(`${config.netlifyBackendUrl.replace(/\/+$/, "")}/${path.replace(/^\/+/, "")}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(init.headers ?? {})
    }
  });
}

function parsePayload(payload: RawPayload | string | null | undefined): RawPayload {
  if (!payload) return {};
  if (typeof payload === "string") {
    try {
      return (JSON.parse(payload) as RawPayload) ?? {};
    } catch {
      return {};
    }
  }
  return payload;
}

function parseNestedJson(value: unknown): unknown {
  if (typeof value !== "string") return value;
  const trimmed = value.trim();
  if (!trimmed || (!trimmed.startsWith("{") && !trimmed.startsWith("["))) return value;
  try {
    return JSON.parse(trimmed) as unknown;
  } catch {
    return value;
  }
}

function objectRecord<T = unknown>(value: unknown): Record<string, T> {
  const parsed = parseNestedJson(value);
  return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed as Record<string, T> : {};
}

function arrayValue<T = unknown>(value: unknown): T[] {
  const parsed = parseNestedJson(value);
  return Array.isArray(parsed) ? parsed as T[] : [];
}

function scopedValue<T>(root: RawPayload, key: string, profileId?: string | null): T | undefined {
  if (!profileId) return undefined;
  const byProfile = objectRecord<T>(root[key]);
  return parseNestedJson(byProfile[profileId]) as T | undefined;
}

function setScopedValue<T>(root: RawPayload, key: string, profileId: string, value: T) {
  const byProfile = objectRecord<T>(root[key]);
  byProfile[profileId] = value;
  root[key] = byProfile;
}

// ── Per-field last-writer-wins support (multi-device conflict resolution) ──
// Mirrors the Android CloudSyncRepository scheme: a scalar setting carries a per-field timestamp
// under `fieldUpdatedAt` ("g:accentColor", "p:<profileId>:autoPlayNext"). Only the fields this web
// session actually changed get their timestamp bumped, so the web can't revert a phone's newer
// change and its own changes are respected by the Android merge.
function bumpFieldTs(root: RawPayload, key: string) {
  const map = (root.fieldUpdatedAt && typeof root.fieldUpdatedAt === "object"
    ? root.fieldUpdatedAt
    : {}) as Record<string, number>;
  map[key] = Date.now();
  root.fieldUpdatedAt = map;
}

/** Value equality tolerant of object/array fields (avoids reference-inequality false positives). */
function sameFieldValue(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  try {
    return JSON.stringify(a) === JSON.stringify(b);
  } catch {
    return false;
  }
}

function stringArray(value: unknown): string[] {
  return arrayValue(value).map((item) => String(item ?? "").trim()).filter(Boolean);
}

function stringValue(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function normalizeIptvPlaylist(value: unknown, fallbackId: string): IptvPlaylistEntry | null {
  const raw = objectRecord(value);
  const xtreamTriplet = [raw.host ?? raw.server ?? raw.portal ?? raw.baseUrl, raw.username ?? raw.user, raw.password ?? raw.pass]
    .map((item) => stringValue(item))
    .filter(Boolean);
  const m3uUrl = stringValue(
    raw.m3uUrl ??
    raw.m3u_url ??
    raw.url ??
    raw.uri ??
    raw.playlistUrl ??
    raw.playlist_url ??
    raw.xtreamUrl ??
    raw.xtream_url
  ) || (xtreamTriplet.length >= 3 ? xtreamTriplet.join(" ") : "");
  if (!m3uUrl) return null;
  const id = stringValue(raw.id) || fallbackId;
  const name = stringValue(raw.name) || "Cloud IPTV";
  const epgUrl = stringValue(raw.epgUrl ?? raw.epg_url ?? raw.xmltvUrl ?? raw.xmltv_url);
  return normalizeRuntimeIptvPlaylist({
    id,
    name,
    m3uUrl,
    epgUrl,
    epgUrls: stringArray(raw.epgUrls ?? raw.epg_urls),
    enabled: raw.enabled !== false
  }, Number(fallbackId.match(/\d+$/)?.[0] ?? 1) - 1);
}

function normalizeIptvPlaylists(value: unknown, fallbackPrefix: string): IptvPlaylistEntry[] {
  const values = arrayValue(value);
  return values.length
    ? values
        .map((playlist, index) => normalizeIptvPlaylist(playlist, `${fallbackPrefix}-${index + 1}`))
        .filter((playlist): playlist is IptvPlaylistEntry => Boolean(playlist))
    : [];
}

function dedupeIptvPlaylists(playlists: IptvPlaylistEntry[]) {
  const seen = new Set<string>();
  return playlists.filter((playlist) => {
    const key = `${playlist.m3uUrl.trim()}|${playlist.epgUrl?.trim() ?? ""}`;
    if (!playlist.m3uUrl.trim() || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function androidSubtitleSize(size: number | string | undefined) {
  if (typeof size === "string") return size;
  if (!Number.isFinite(size)) return "Medium";
  if (Number(size) <= 85) return "Small";
  if (Number(size) >= 130) return "Large";
  return "Medium";
}

function webSubtitleSize(size: unknown) {
  const raw = String(size ?? "").toLowerCase();
  if (raw === "small") return 80;
  if (raw === "large") return 140;
  if (raw === "extra large" || raw === "xlarge") return 170;
  const parsed = Number(size);
  return Number.isFinite(parsed) ? parsed : 100;
}

function androidSubtitleColor(settings: AppSettings) {
  return settings.subtitleColorName || webSubtitleColorName(settings.subtitleColor);
}

function webSubtitleColorName(value: unknown): AppSettings["subtitleColorName"] {
  const raw = String(value ?? "").trim().toLowerCase();
  if (raw.includes("yellow") || raw === "#ffff00" || raw === "#ffeb3b") return "Yellow";
  if (raw.includes("green") || raw === "#00ff00" || raw === "#4caf50") return "Green";
  if (raw.includes("cyan") || raw === "#00ffff" || raw === "#00bcd4") return "Cyan";
  if (raw.includes("red") || raw === "#ff0000" || raw === "#f44336") return "Red";
  if (raw.includes("orange") || raw === "#ffa500" || raw === "#ff9800") return "Orange";
  if (raw.includes("blue") || raw === "#0000ff" || raw === "#2196f3") return "Blue";
  if (raw.includes("violet") || raw.includes("purple") || raw === "#8b5cf6") return "Violet";
  return "White";
}

function webSubtitleColor(value: unknown) {
  switch (webSubtitleColorName(value)) {
    case "Yellow": return "#ffeb3b";
    case "Green": return "#4caf50";
    case "Cyan": return "#00bcd4";
    case "Red": return "#f44336";
    case "Orange": return "#ff9800";
    case "Blue": return "#2196f3";
    case "Violet": return "#8b5cf6";
    default: return "#ffffff";
  }
}

function androidSubtitleOffset(value: AppSettings["subtitleOffset"]) {
  switch (value) {
    case "low": return "Low";
    case "medium": return "Medium";
    case "high": return "High";
    default: return "Bottom";
  }
}

function webSubtitleOffset(value: unknown): AppSettings["subtitleOffset"] {
  switch (String(value ?? "").trim().toLowerCase()) {
    case "low": return "low";
    case "medium": return "medium";
    case "high": return "high";
    default: return "bottom";
  }
}

function androidSubtitleStyle(value: AppSettings["subtitleStyle"]) {
  if (value === "background") return "Background";
  if (value === "shadow" || value === "raised") return "Normal";
  return "Bold";
}

function webSubtitleStyle(value: unknown): AppSettings["subtitleStyle"] {
  switch (String(value ?? "").trim().toLowerCase()) {
    case "background": return "background";
    case "normal": return "shadow";
    case "bold":
    case "outline":
    default:
      return "outline";
  }
}

function androidFrameRateMode(value: AppSettings["frameRateMatchingMode"]) {
  switch (value) {
    case "seamless": return "Seamless only";
    case "always": return "Always";
    default: return "Off";
  }
}

function webFrameRateMode(value: unknown): AppSettings["frameRateMatchingMode"] {
  switch (String(value ?? "").trim().toLowerCase()) {
    case "seamless":
    case "seamless only":
    case "only if seamless":
    case "only_if_seamless":
      return "seamless";
    case "always":
      return "always";
    default:
      return "off";
  }
}

function normalizedDns(value: unknown): AppSettings["dnsProvider"] {
  const raw = String(value ?? "").trim().toLowerCase();
  if (raw.includes("cloudflare")) return "cloudflare";
  if (raw.includes("google")) return "google";
  if (raw.includes("adguard") || raw.includes("ad guard")) return "adguard";
  if (raw.includes("quad9")) return "quad9";
  return "system";
}

function validQualityFilters(value: unknown): QualityFilterConfig[] {
  const values = arrayValue(value);
  if (!values.length) return [];
  return values.reduce<QualityFilterConfig[]>((filters, item, index) => {
    const raw = objectRecord(item);
    const regexPattern = stringValue(raw.regexPattern);
    if (!regexPattern) return filters;
    filters.push({
      id: stringValue(raw.id) || `filter-${index + 1}`,
      deviceName: stringValue(raw.deviceName) || "Quality filter",
      regexPattern,
      enabled: raw.enabled !== false,
      createdAt: Number(raw.createdAt) || Date.now()
    });
    return filters;
  }, []);
}

function androidQuality(value: AppSettings["autoPlayMinQuality"]) {
  switch (value) {
    case "hd": return "720p";
    case "fhd": return "1080p";
    case "4k": return "4K";
    default: return "Any";
  }
}

function webQuality(value: unknown): AppSettings["autoPlayMinQuality"] {
  switch (String(value ?? "").toLowerCase()) {
    case "720p":
    case "hd":
      return "hd";
    case "1080p":
    case "fhd":
    case "fullhd":
      return "fhd";
    case "4k":
    case "2160p":
    case "uhd":
      return "4k";
    default:
      return "any";
  }
}

function androidProfileSettings(settings: AppSettings) {
  return {
    defaultSubtitle: settings.defaultSubtitle || "Off",
    defaultAudioLanguage: settings.audioLanguage || "Auto (Original)",
    contentLanguage: settings.language || "en-US",
    trailerAutoPlay: settings.trailerAutoPlay,
    trailerSoundEnabled: settings.trailerSound,
    trailerDelaySeconds: settings.trailerDelaySeconds,
    trailerInCards: settings.trailerInCards,
    clockFormat: settings.clockFormat,
    showBudget: settings.showBudget,
    showLoadingStats: settings.showLoadingStats,
    spoilerBlurEnabled: settings.spoilerBlur,
    volumeBoostDb: Math.max(0, Math.min(15, Number(settings.volumeBoostDb) || 0)),
    dnsProvider: settings.dnsProvider,
    subtitleUsageJson: "",
    subtitleSettingsUpdatedAt: Date.now(),
    subtitleSize: androidSubtitleSize(settings.subtitleSize),
    subtitleColor: androidSubtitleColor(settings),
    subtitleOffset: androidSubtitleOffset(settings.subtitleOffset),
    subtitleStyle: androidSubtitleStyle(settings.subtitleStyle),
    subtitleStylized: settings.subtitleStylized,
    secondarySubtitle: settings.secondarySubtitle || "Off",
    filterSubtitlesByLanguage: settings.filterSubtitlesByLanguage,
    homeServerConnectionJson: serializeHomeServerConnectionJson(settings.homeServers),
    torrServerBaseUrl: settings.torrServerBaseUrl || null,
    catalogueRowLayoutModes: {},
    cardLayoutMode: settings.cardLayoutMode,
    frameRateMatchingMode: androidFrameRateMode(settings.frameRateMatchingMode),
    autoPlayNext: settings.autoPlayNext,
    autoPlaySingleSource: settings.autoPlaySingleSource,
    autoPlayMinQuality: androidQuality(settings.autoPlayMinQuality),
    includeSpecials: settings.includeSpecials,
    qualityFilters: settings.qualityFilters
  };
}

function settingsFromAndroidProfile(value: unknown): Partial<AppSettings> {
  const state = objectRecord(value);
  const partial: Partial<AppSettings> = {};
  if ("defaultSubtitle" in state) partial.defaultSubtitle = String(state.defaultSubtitle || "");
  if ("defaultAudioLanguage" in state) partial.audioLanguage = String(state.defaultAudioLanguage || "");
  if ("contentLanguage" in state) partial.language = String(state.contentLanguage || "en-US");
  if ("trailerAutoPlay" in state) partial.trailerAutoPlay = Boolean(state.trailerAutoPlay);
  if ("trailerSoundEnabled" in state) partial.trailerSound = Boolean(state.trailerSoundEnabled);
  if ("trailerDelaySeconds" in state) partial.trailerDelaySeconds = Number(state.trailerDelaySeconds) || 2;
  if ("trailerInCards" in state) partial.trailerInCards = Boolean(state.trailerInCards);
  if ("clockFormat" in state) partial.clockFormat = String(state.clockFormat) === "12h" ? "12h" : "24h";
  if ("showBudget" in state) partial.showBudget = Boolean(state.showBudget);
  if ("showLoadingStats" in state) partial.showLoadingStats = Boolean(state.showLoadingStats);
  if ("spoilerBlurEnabled" in state) partial.spoilerBlur = Boolean(state.spoilerBlurEnabled);
  if ("volumeBoostDb" in state) partial.volumeBoostDb = Math.max(0, Math.min(15, Number(state.volumeBoostDb) || 0));
  if ("includeSpecials" in state) partial.includeSpecials = Boolean(state.includeSpecials);
  if ("dnsProvider" in state) partial.dnsProvider = normalizedDns(state.dnsProvider);
  if ("subtitleSize" in state) partial.subtitleSize = webSubtitleSize(state.subtitleSize);
  if ("subtitleColor" in state) {
    partial.subtitleColorName = webSubtitleColorName(state.subtitleColor);
    partial.subtitleColor = webSubtitleColor(state.subtitleColor);
  }
  if ("subtitleOffset" in state) partial.subtitleOffset = webSubtitleOffset(state.subtitleOffset);
  if ("subtitleStyle" in state) partial.subtitleStyle = webSubtitleStyle(state.subtitleStyle);
  // AI subtitle translation — same cloud fields the Android app writes, so the
  // key/model configured on the TV work here without re-entry.
  if ("subtitleAiEnabled" in state) partial.aiSubtitlesEnabled = Boolean(state.subtitleAiEnabled);
  if ("subtitleAiAutoSelect" in state) partial.aiAutoSelect = Boolean(state.subtitleAiAutoSelect);
  if ("subtitleAiApiKey" in state) partial.aiApiKey = String(state.subtitleAiApiKey || "");
  if ("subtitleAiModel" in state) {
    const model = String(state.subtitleAiModel || "").toLowerCase();
    partial.aiSubtitleModel = model === "gemini" ? "gemini" : model === "off" ? "off" : "groq";
  }
  if ("subtitleStylized" in state) partial.subtitleStylized = Boolean(state.subtitleStylized);
  if ("secondarySubtitle" in state) partial.secondarySubtitle = String(state.secondarySubtitle || "");
  if ("filterSubtitlesByLanguage" in state) partial.filterSubtitlesByLanguage = Boolean(state.filterSubtitlesByLanguage);
  if ("homeServerConnectionJson" in state && typeof state.homeServerConnectionJson === "string" && state.homeServerConnectionJson.trim()) {
    // The Android app writes { connections: [...] } with its own field names;
    // parseHomeServerConnectionJson handles that shape, a bare array, or a
    // single object, and maps to the web HomeServerConfig.
    const parsed = parseHomeServerConnectionJson(state.homeServerConnectionJson);
    if (parsed.length) partial.homeServers = parsed;
  }
  if ("cardLayoutMode" in state) partial.cardLayoutMode = String(state.cardLayoutMode) === "poster" ? "poster" : "landscape";
  if ("frameRateMatchingMode" in state) partial.frameRateMatchingMode = webFrameRateMode(state.frameRateMatchingMode);
  if ("autoPlayNext" in state) partial.autoPlayNext = Boolean(state.autoPlayNext);
  if ("autoPlaySingleSource" in state) partial.autoPlaySingleSource = Boolean(state.autoPlaySingleSource);
  if ("autoPlayMinQuality" in state) partial.autoPlayMinQuality = webQuality(state.autoPlayMinQuality);
  if ("torrServerBaseUrl" in state) partial.torrServerBaseUrl = stringValue(state.torrServerBaseUrl);
  if ("qualityFilters" in state) {
    const filters = validQualityFilters(state.qualityFilters);
    partial.qualityFilters = filters;
    if (filters.length) partial.qualityFilterPreset = "custom";
  }
  return partial;
}

function iptvFromAndroid(value: unknown, root?: RawPayload): Partial<AppSettings> {
  const state = objectRecord(value) as AndroidIptvProfileState;
  const rootState = root ?? {};
  const rootSettings = objectRecord(rootState.settings);
  const profilePlaylists = normalizeIptvPlaylists(state.playlists, "cloud-profile");
  const rootSettingsPlaylists = normalizeIptvPlaylists(rootSettings.iptvPlaylists, "cloud-settings");
  const rootPlaylists = normalizeIptvPlaylists(rootState.iptvPlaylists ?? rootState.playlists, "cloud-root");
  const legacyM3uUrl = stringValue(state.m3uUrl) || stringValue(rootState.iptvM3uUrl);
  const legacyEpgUrl = stringValue(state.epgUrl) || stringValue(rootState.iptvEpgUrl);
  const legacyPlaylists = legacyM3uUrl
      ? [{
          id: "cloud-primary",
          name: "Cloud IPTV",
          m3uUrl: legacyM3uUrl,
          epgUrl: legacyEpgUrl,
          enabled: true
        }]
      : [];
  const playlists = dedupeIptvPlaylists([
    ...profilePlaylists,
    ...rootSettingsPlaylists,
    ...rootPlaylists,
    ...legacyPlaylists
  ]);
  return {
    iptvPlaylists: playlists,
    favoriteChannelIds: stringArray(state.favoriteChannels).length ? stringArray(state.favoriteChannels) : stringArray(rootState.iptvFavoriteChannels),
    favoriteGroupIds: stringArray(state.favoriteGroups).length ? stringArray(state.favoriteGroups) : stringArray(rootState.iptvFavoriteGroups),
    hiddenGroupIds: stringArray(state.hiddenGroups),
    groupOrder: stringArray(state.groupOrder),
    iptvStalkerUrl: stringValue(state.stalkerPortalUrl ?? rootState.iptvStalkerUrl),
    iptvStalkerMac: stringValue(state.stalkerMacAddress ?? rootState.iptvStalkerMac)
  };
}

function androidCwToHistory(item: AndroidContinueWatchingItem, profileId?: string | null): WatchHistoryEntry | null {
  const tmdbId = Number(item.id ?? 0);
  if (!tmdbId) return null;
  const mediaType = String(item.mediaType ?? "movie").toLowerCase() === "tv" ? "tv" : "movie";
  const duration = Math.max(0, Number(item.durationSeconds ?? 0));
  const position = Math.max(0, Number(item.resumePositionSeconds ?? 0));
  const progress = Math.max(0, Math.min(0.99, Number(item.progress ?? 0) / 100));
  return {
    user_id: "",
    profile_id: profileId ?? null,
    media_type: mediaType,
    show_tmdb_id: tmdbId,
    season: item.season ?? null,
    episode: item.episode ?? null,
    title: item.title ?? null,
    episode_title: item.episodeTitle ?? null,
    progress,
    duration_seconds: duration,
    position_seconds: position,
    backdrop_path: item.backdropPath ?? null,
    poster_path: item.posterPath ?? null,
    stream_key: item.streamKey ?? null,
    stream_addon_id: item.streamAddonId ?? null,
    stream_title: item.streamTitle ?? null,
    updated_at: item.updatedAtMs ? new Date(item.updatedAtMs).toISOString() : null
  };
}

function historyToAndroidCw(entry: Omit<WatchHistoryEntry, "user_id">): AndroidContinueWatchingItem {
  return {
    id: entry.show_tmdb_id,
    title: entry.title ?? "",
    mediaType: entry.media_type,
    progress: Math.round(Math.max(0, Math.min(1, entry.progress)) * 100),
    resumePositionSeconds: entry.position_seconds ?? 0,
    durationSeconds: entry.duration_seconds ?? 0,
    season: entry.season ?? null,
    episode: entry.episode ?? null,
    episodeTitle: entry.episode_title ?? null,
    backdropPath: entry.backdrop_path ?? null,
    posterPath: entry.poster_path ?? null,
    streamKey: entry.stream_key ?? null,
    streamAddonId: entry.stream_addon_id ?? null,
    streamTitle: entry.stream_title ?? entry.source ?? null,
    updatedAtMs: Date.now()
  };
}

function androidContinueWatchingItems(root: RawPayload, profileId?: string | null): AndroidContinueWatchingItem[] {
  const readByProfile = (key: string) => {
    const byProfile = objectRecord<unknown>(root[key]);
    if (!Object.keys(byProfile).length) return [] as AndroidContinueWatchingItem[];
    return profileId
      ? arrayValue<AndroidContinueWatchingItem>(byProfile[profileId])
      : Object.values(byProfile).flatMap((items) => arrayValue<AndroidContinueWatchingItem>(items));
  };
  const scoped = [
    ...readByProfile("localContinueWatchingByProfile"),
    ...readByProfile("continueWatchingByProfile"),
    ...readByProfile("watchHistoryByProfile")
  ];
  const global = [
    ...arrayValue<AndroidContinueWatchingItem>(root.localContinueWatching),
    ...arrayValue<AndroidContinueWatchingItem>(root.continueWatching)
  ];
  const seen = new Set<string>();
  return [...scoped, ...global].filter((item) => {
    const key = `${item.mediaType ?? ""}:${item.id ?? ""}:${item.season ?? ""}:${item.episode ?? ""}`;
    if (!item.id || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

// A single app refresh calls pullRawPayload five times (payload, trakt token,
// continue watching, watchlist, profiles) — all reading the SAME account-sync
// document. Deduplicate: one in-flight pull is shared by all callers, and its
// result is cached briefly so the near-simultaneous reads become ONE backend
// (Netlify function) invocation instead of five. This is the single biggest
// per-user-per-session cut on the auth backend. Writes clear the cache so the
// next read is fresh.
let rawPayloadCache: { userId: string; at: number; payload: RawPayload } | null = null;
let rawPayloadInFlight: { userId: string; promise: Promise<RawPayload> } | null = null;
const RAW_PAYLOAD_TTL_MS = 5_000;

export function invalidateRawPayloadCache() {
  rawPayloadCache = null;
}

async function fetchRawPayload(auth: AuthClient): Promise<RawPayload> {
  if (canUseBackendSync(auth)) {
    const response = await backendRequest<AccountSyncPullResponse>(auth, "account-sync-pull", { method: "GET" });
    return parsePayload(response.payload);
  }
  const rows = await auth.supabase<Array<{ payload?: string | null }>>(
    `/rest/v1/account_sync_state?user_id=eq.${auth.session!.userId}&select=user_id,payload,updated_at`
  );
  const raw = rows[0]?.payload;
  if (!raw) return {};
  try {
    return (JSON.parse(raw) as RawPayload) ?? {};
  } catch {
    return {};
  }
}

/** Read the full account_sync_state payload object (shared with Android). */
export async function pullRawPayload(auth: AuthClient): Promise<RawPayload> {
  if (!auth.session) return {};
  const userId = auth.session.userId;
  if (rawPayloadCache && rawPayloadCache.userId === userId && Date.now() - rawPayloadCache.at < RAW_PAYLOAD_TTL_MS) {
    return rawPayloadCache.payload;
  }
  if (rawPayloadInFlight && rawPayloadInFlight.userId === userId) {
    return rawPayloadInFlight.promise;
  }
  const promise = fetchRawPayload(auth)
    .then((payload) => {
      rawPayloadCache = { userId, at: Date.now(), payload };
      return payload;
    })
    .finally(() => {
      if (rawPayloadInFlight?.promise === promise) rawPayloadInFlight = null;
    });
  rawPayloadInFlight = { userId, promise };
  return promise;
}

async function writeRawPayload(auth: AuthClient, payload: RawPayload) {
  if (!auth.session) return;
  payload.userId = auth.session.userId;
  payload.updatedAt = Date.now();
  // The payload we just wrote is now authoritative — seed the read cache with it
  // so an immediate follow-up read is served locally instead of round-tripping.
  rawPayloadCache = { userId: auth.session.userId, at: Date.now(), payload };
  if (canUseBackendSync(auth)) {
    await backendRequest(auth, "account-sync-push", {
      method: "POST",
      body: JSON.stringify({ payload })
    });
    return;
  }
  await auth.supabase("/rest/v1/account_sync_state", {
    method: "POST",
    headers: { Prefer: "resolution=merge-duplicates" },
    body: JSON.stringify({
      user_id: auth.session.userId,
      payload: JSON.stringify(payload),
      updated_at: new Date().toISOString()
    })
  });
}

/**
 * Read-modify-write the shared payload, preserving keys this app doesn't own
 * (e.g. Android's profiles / avatar images). Mirrors Android's
 * AuthRepository.mutateAccountSyncPayload.
 */
export async function mutateCloudPayload(auth: AuthClient, mutator: (root: RawPayload) => void) {
  if (!auth.session) return;
  const root = await pullRawPayload(auth);
  mutator(root);
  await writeRawPayload(auth, root);
}

export async function pullCloudPayload(auth: AuthClient, profileId?: string | null): Promise<CloudPayload> {
  const root = await pullRawPayload(auth);
  const profileSettings = settingsFromAndroidProfile(scopedValue(root, "profileSettingsById", profileId));
  const iptvSettings = iptvFromAndroid(scopedValue(root, "iptvByProfile", profileId), root);
  const profileCatalogs = scopedValue<AppSettings["catalogs"]>(root, "catalogsByProfile", profileId);
  const hiddenCatalogIds = scopedValue<string[]>(root, "hiddenPreinstalledByProfile", profileId);
  const profileAddons = scopedValue<InstalledAddon[]>(root, "addonsByProfile", profileId);
  const legacySettings = objectRecord<unknown>(root.settings) as Partial<AppSettings>;
  const legacyCatalogs = arrayValue(root.catalogs) as AppSettings["catalogs"];
  const legacyHiddenCatalogIds = arrayValue<string>(root.hiddenPreinstalledCatalogs);
  // Canonical, timestamp-managed GLOBAL settings live at the top level of the payload (written by
  // Android with per-field timestamps), NOT in the legacy `root.settings` blob. Map them so an
  // Android change (accent color, AI subtitles, etc.) is actually visible on web.
  const globalSettings: Partial<AppSettings> = {};
  if ("accentColor" in root) globalSettings.accentColor = String(root.accentColor ?? "");
  if ("oledBlackBackground" in root) globalSettings.oledBlack = Boolean(root.oledBlackBackground);
  if ("customUserAgent" in root) globalSettings.customUserAgent = String(root.customUserAgent ?? "");
  if ("skipProfileSelection" in root) globalSettings.skipProfileSelection = Boolean(root.skipProfileSelection);
  if ("subtitleAiEnabled" in root) globalSettings.aiSubtitlesEnabled = Boolean(root.subtitleAiEnabled);
  if ("subtitleAiAutoSelect" in root) globalSettings.aiAutoSelect = Boolean(root.subtitleAiAutoSelect);
  if ("subtitleAiApiKey" in root) globalSettings.aiApiKey = String(root.subtitleAiApiKey ?? "");
  // The web's model is a small enum ("off"/"groq"/"gemini") while Android stores a full model id —
  // only adopt the value when it's already in the web's vocabulary (i.e. the web wrote it last).
  if ("subtitleAiModel" in root) {
    const model = String(root.subtitleAiModel ?? "");
    if (model === "off" || model === "groq" || model === "gemini") globalSettings.aiSubtitleModel = model;
  }
  if ("subtitleRemoveHearingImpaired" in root) globalSettings.removeHearingImpaired = Boolean(root.subtitleRemoveHearingImpaired);
  return {
    version: typeof root.version === "number" ? root.version : 1,
    // Union the per-profile scope with the global list. An empty/partial profile
    // scope must never hide the shared library (that was a wipe amplifier).
    addons: unionAddons(arrayValue<InstalledAddon>(profileAddons), arrayValue<InstalledAddon>(root.addons)),
    settings: {
      ...legacySettings,
      ...profileSettings,
      ...globalSettings,
      ...iptvSettings,
      ...(arrayValue(profileCatalogs).length ? { catalogs: arrayValue(profileCatalogs) as AppSettings["catalogs"] } : legacyCatalogs.length ? { catalogs: legacyCatalogs } : {}),
      ...(arrayValue(hiddenCatalogIds).length ? { hiddenCatalogIds: arrayValue<string>(hiddenCatalogIds) } : legacyHiddenCatalogIds.length ? { hiddenCatalogIds: legacyHiddenCatalogIds } : {})
    },
    updatedAt: typeof root.updatedAt === "number" ? root.updatedAt : 0
  };
}

function unionAddons(primary: InstalledAddon[], existing: unknown): InstalledAddon[] {
  const byKey = new Map<string, InstalledAddon>();
  for (const addon of [...primary, ...arrayValue<InstalledAddon>(existing)]) {
    const key = addon?.manifestUrl || addon?.id;
    if (key && !byKey.has(key)) byKey.set(key, addon);
  }
  return [...byKey.values()];
}

export async function saveCloudAddons(
  auth: AuthClient,
  addons: InstalledAddon[],
  profileId?: string | null,
  options: { removedIds?: string[] } = {}
) {
  const removed = new Set(options.removedIds ?? []);
  await mutateCloudPayload(auth, (root) => {
    root.version = 2;
    // Addon writes are union-based and can only shrink via an explicit remove
    // list. This makes it impossible for a stale/empty in-memory list — or an
    // empty per-profile scope — to wipe the shared library. A removal drops the
    // id from every scope; everything else is merged in.
    const applyRemovals = (list: InstalledAddon[]) => list.filter((a) => !removed.has(a.id));
    root.addons = applyRemovals(unionAddons(addons, root.addons));
    if (profileId) {
      const scoped = scopedValue<InstalledAddon[]>(root, "addonsByProfile", profileId);
      setScopedValue(root, "addonsByProfile", profileId, applyRemovals(unionAddons(addons, scoped)));
    }
    // Set-level timestamp: lets Android tell an intentional "removed everything" from a blank pull,
    // so removing the last add-on(s) from web actually propagates (reconcileAddonsWithCloud).
    root.addonsUpdatedAt = Date.now();
  });
}

export async function saveCloudSettings(
  auth: AuthClient,
  settings: AppSettings,
  addons: InstalledAddon[],
  profileId?: string | null,
  profiles: Profile[] = [],
  // The web session's last-synced settings (same profile). Used to detect which fields THIS session
  // actually changed, so we only assert (and timestamp) those — never reverting a field a phone
  // changed. Null → treat every field as changed (bootstrap / after a profile switch).
  baseline?: AppSettings | null
) {
  await mutateCloudPayload(auth, (root) => {
    root.version = 2;
    root.updatedAt = Date.now();
    // Settings saves intentionally do NOT touch root.addons: addon writes go
    // exclusively through saveCloudAddons (merge-protected). A stale session's
    // partial in-memory addon list must never leak into the shared payload.
    void addons;
    root.settings = settings;

    // ── Genuine global settings that Android merges by per-field timestamp. Only write + bump the
    //    timestamp when the web actually changed the field vs its baseline; otherwise leave the
    //    (freshly read-modify-written) cloud value so a phone's newer change survives.
    const globalFields: Array<[string, unknown, unknown]> = [
      ["accentColor", settings.accentColor, baseline?.accentColor],
      ["oledBlackBackground", settings.oledBlack, baseline?.oledBlack],
      ["skipProfileSelection", settings.skipProfileSelection, baseline?.skipProfileSelection],
      ["customUserAgent", settings.customUserAgent, baseline?.customUserAgent],
      ["dnsProvider", settings.dnsProvider, baseline?.dnsProvider],
      ["subtitleAiEnabled", settings.aiSubtitlesEnabled, baseline?.aiSubtitlesEnabled],
      ["subtitleAiAutoSelect", settings.aiAutoSelect, baseline?.aiAutoSelect],
      ["subtitleAiApiKey", settings.aiApiKey, baseline?.aiApiKey],
      ["subtitleAiModel", settings.aiSubtitleModel, baseline?.aiSubtitleModel],
      ["subtitleRemoveHearingImpaired", settings.removeHearingImpaired, baseline?.removeHearingImpaired]
    ];
    for (const [rootKey, newVal, baseVal] of globalFields) {
      if (!baseline || !sameFieldValue(newVal, baseVal)) {
        root[rootKey] = newVal;
        bumpFieldTs(root, `g:${rootKey}`);
      }
    }
    root.focusBorderColor = settings.accentColor; // mirror of accentColor (not a merge key)

    // ── Root legacy flat mirrors: only OLD Android clients read these (current clients read
    //    profileSettingsById below). Kept for backward compat; not timestamp-managed.
    root.defaultSubtitle = settings.defaultSubtitle || "Off";
    root.defaultAudioLanguage = settings.audioLanguage || "Auto (Original)";
    root.cardLayoutMode = settings.cardLayoutMode;
    root.frameRateMatchingMode = androidFrameRateMode(settings.frameRateMatchingMode);
    root.autoPlayNext = settings.autoPlayNext;
    root.autoPlaySingleSource = settings.autoPlaySingleSource;
    root.autoPlayMinQuality = androidQuality(settings.autoPlayMinQuality);
    root.includeSpecials = settings.includeSpecials;
    root.torrServerBaseUrl = settings.torrServerBaseUrl;
    root.qualityFilters = settings.qualityFilters;
    root.catalogs = settings.catalogs;
    root.hiddenPreinstalledCatalogs = settings.hiddenCatalogIds;
    root.iptvFavoriteChannels = settings.favoriteChannelIds;
    root.iptvFavoriteGroups = settings.favoriteGroupIds;
    root.iptvStalkerUrl = settings.iptvStalkerUrl;
    root.iptvStalkerMac = settings.iptvStalkerMac;
    if (settings.iptvPlaylists[0]) {
      root.iptvM3uUrl = settings.iptvPlaylists[0].m3uUrl;
      root.iptvEpgUrl = settings.iptvPlaylists[0].epgUrl ?? "";
    }

    if (profiles.length) root.profiles = profiles;
    if (profileId) {
      // ── Per-profile settings: merge field-by-field over the freshly-read cloud object. Only
      //    web-changed fields are overwritten + timestamped; everything else keeps the cloud value.
      const newProfile = androidProfileSettings(settings) as Record<string, unknown>;
      const baseProfile = baseline ? (androidProfileSettings(baseline) as Record<string, unknown>) : null;
      const existing = (scopedValue<Record<string, unknown>>(root, "profileSettingsById", profileId) ?? {}) as Record<string, unknown>;
      const merged: Record<string, unknown> = { ...existing };
      // Fields the web doesn't genuinely own (always sends empty) — never write them, so they can't
      // wipe a phone's value. defaultSubtitle keeps its own timestamp logic (handled below).
      const skip = new Set(["catalogueRowLayoutModes", "subtitleUsageJson", "defaultSubtitle", "subtitleSettingsUpdatedAt"]);
      for (const field of Object.keys(newProfile)) {
        if (skip.has(field)) continue;
        if (!baseProfile || !sameFieldValue(newProfile[field], baseProfile[field])) {
          merged[field] = newProfile[field];
          bumpFieldTs(root, `p:${profileId}:${field}`);
        }
      }
      // defaultSubtitle uses Android's own subtitleSettingsUpdatedAt LWW — assert it only when the
      // web actually changed it, bumping that timestamp so Android adopts it.
      if (!baseline || !sameFieldValue(settings.defaultSubtitle, baseline.defaultSubtitle)) {
        merged.defaultSubtitle = settings.defaultSubtitle || "Off";
        merged.subtitleSettingsUpdatedAt = Date.now();
      }
      setScopedValue(root, "profileSettingsById", profileId, merged);
      // NOTE: settings saves must NOT touch add-ons. Android now reconciles add-ons to the cloud
      // authoritatively, so writing this session's (possibly stale) add-on list here could delete an
      // add-on installed on another device. Add-ons are written exclusively by saveCloudAddons().
      setScopedValue(root, "catalogsByProfile", profileId, settings.catalogs);
      setScopedValue(root, "hiddenPreinstalledByProfile", profileId, settings.hiddenCatalogIds);
      setScopedValue(root, "iptvByProfile", profileId, {
        m3uUrl: settings.iptvPlaylists[0]?.m3uUrl ?? "",
        epgUrl: settings.iptvPlaylists[0]?.epgUrl ?? "",
        playlists: settings.iptvPlaylists,
        stalkerPortalUrl: settings.iptvStalkerUrl,
        stalkerMacAddress: settings.iptvStalkerMac,
        favoriteChannels: settings.favoriteChannelIds,
        favoriteGroups: settings.favoriteGroupIds,
        hiddenGroups: settings.hiddenGroupIds,
        groupOrder: settings.groupOrder
      });
    }
  });
}

export interface CloudProfiles {
  profiles: Profile[];
  activeProfileId: string | null;
  avatarImages: Record<string, string>;
}

export async function pullCloudProfiles(auth: AuthClient): Promise<CloudProfiles> {
  const root = await pullRawPayload(auth);
  return {
    profiles: arrayValue<Profile>(root.profiles),
    activeProfileId: typeof root.activeProfileId === "string" ? root.activeProfileId : null,
    avatarImages: objectRecord<string>(root.profileAvatarImagesById)
  };
}

export async function pullCloudTraktToken(auth: AuthClient, profileId?: string | null): Promise<TraktToken | null> {
  const root = await pullRawPayload(auth);
  const tokensByProfile = objectRecord<{
    accessToken?: string;
    refreshToken?: string;
    expiresAt?: number;
    access_token?: string;
    refresh_token?: string;
    expires_at?: number;
  }>(root.traktTokens);
  const token = profileId ? tokensByProfile?.[profileId] : undefined;
  const accessToken = token?.accessToken ?? token?.access_token;
  const refreshToken = token?.refreshToken ?? token?.refresh_token;
  const rawExpiresAt = token?.expiresAt ?? token?.expires_at ?? 0;
  const expiresAt = rawExpiresAt > 0 && rawExpiresAt < 1_000_000_000_000 ? rawExpiresAt * 1000 : rawExpiresAt;
  if (!accessToken || !refreshToken) return null;
  return {
    access_token: accessToken,
    refresh_token: refreshToken,
    expires_at: expiresAt
  };
}

export async function saveCloudTraktToken(auth: AuthClient, token: TraktToken, profileId?: string | null) {
  if (!profileId) return;
  await mutateCloudPayload(auth, (root) => {
    const tokens = objectRecord<unknown>(root.traktTokens) ?? {};
    // Write both key styles so the Android app and web read it either way.
    tokens[profileId] = {
      accessToken: token.access_token,
      refreshToken: token.refresh_token,
      expiresAt: token.expires_at,
      access_token: token.access_token,
      refresh_token: token.refresh_token,
      expires_at: token.expires_at
    };
    root.traktTokens = tokens;
    root.traktLinked = true;
  });
}

export async function pullCloudWatchlist(auth: AuthClient, profileId?: string | null): Promise<MediaItem[]> {
  const root = await pullRawPayload(auth);
  const byProfile = objectRecord<unknown>(root.watchlistByProfile);
  const items = profileId
    ? arrayValue<AndroidWatchlistItem>(byProfile[profileId])
    : Object.values(byProfile).flatMap((value) => arrayValue<AndroidWatchlistItem>(value));
  return items
    .map((item): MediaItem | null => {
      const id = Number(item.tmdbId ?? 0);
      if (!id) return null;
      const mediaType = String(item.mediaType ?? "movie").toLowerCase() === "tv" ? "tv" : "movie";
      return {
        id,
        title: item.title ?? "Untitled",
        mediaType,
        subtitle: mediaType === "tv" ? "TV Series" : "Movie",
        image: tmdbImageUrl(config.imageBase, item.posterPath),
        backdrop: tmdbImageUrl(config.backdropBase, item.backdropPath) || null
      };
    })
    .filter((item): item is MediaItem => Boolean(item));
}

export async function saveCloudProfiles(auth: AuthClient, profiles: Profile[], activeProfileId: string | null) {
  await mutateCloudPayload(auth, (root) => {
    root.profiles = profiles;
    root.activeProfileId = activeProfileId;
  });
}

export async function getContinueWatching(auth: AuthClient, profileId?: string | null) {
  if (!auth.session) return [];
  if (canUseBackendSync(auth)) {
    const root = await pullRawPayload(auth);
    return androidContinueWatchingItems(root, profileId)
      .map((item) => androidCwToHistory(item, profileId))
      .filter((item): item is WatchHistoryEntry => Boolean(item))
      .filter((item) => (item.progress ?? 0) < 0.9)
      .sort((a, b) => Date.parse(b.updated_at ?? "") - Date.parse(a.updated_at ?? ""))
      .slice(0, 50);
  }
  const profileFilter = profileId ? `&profile_id=eq.${encodeURIComponent(profileId)}` : "";
  return auth.supabase<WatchHistoryEntry[]>(
    `/rest/v1/watch_history?user_id=eq.${auth.session.userId}${profileFilter}&progress=lt.0.9&select=*&order=updated_at.desc&limit=50`
  );
}

export async function saveProgress(auth: AuthClient, entry: Omit<WatchHistoryEntry, "user_id">, profileId?: string | null) {
  if (!auth.session) return;
  if (canUseBackendSync(auth)) {
    await mutateCloudPayload(auth, (root) => {
      const targetProfileId = entry.profile_id ?? profileId ?? "default";
      const byProfile = objectRecord<unknown>(root.localContinueWatchingByProfile);
      const current = arrayValue<AndroidContinueWatchingItem>(byProfile[targetProfileId]);
      const nextItem = historyToAndroidCw({ ...entry, profile_id: targetProfileId });
      const filtered = current.filter((candidate) => {
        const sameTitle = candidate.id === nextItem.id && String(candidate.mediaType ?? "").toLowerCase() === nextItem.mediaType;
        if (!sameTitle) return true;
        if (nextItem.mediaType !== "tv") return false;
        return candidate.season !== nextItem.season || candidate.episode !== nextItem.episode;
      });
      byProfile[targetProfileId] = (nextItem.progress ?? 0) >= 90 ? filtered : [nextItem, ...filtered].slice(0, 50);
      root.localContinueWatchingByProfile = byProfile;
    });
    return;
  }
  await auth.supabase("/rest/v1/watch_history", {
    method: "POST",
    headers: { Prefer: "resolution=merge-duplicates" },
    body: JSON.stringify({
      ...entry,
      user_id: auth.session.userId,
      profile_id: entry.profile_id ?? profileId ?? null,
      paused_at: new Date().toISOString(),
      updated_at: new Date().toISOString()
    })
  });
}

export async function markWatched(auth: AuthClient, entry: Omit<WatchHistoryEntry, "user_id" | "progress" | "position_seconds">, profileId?: string | null) {
  await saveProgress(auth, {
    ...entry,
    progress: 1,
    position_seconds: entry.duration_seconds
  }, profileId);
}
