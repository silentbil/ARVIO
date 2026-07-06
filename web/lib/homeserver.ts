import { jsonRequest, proxiedUrl } from "./http";
import type { Category, HomeServerCollectionConfig, HomeServerConfig, MediaItem, MediaType, StreamSource } from "./types";

// ── Cloud sync shape mapping (APK parity) ───────────────────────────────────
// The Android app persists home servers as a JSON string:
//   { "connections": [ HomeServerConnection ] }
// with fields: serverKind (UNKNOWN|JELLYFIN|EMBY|PLEX), serverUrl, accessToken,
// accountToken, userId, userName, serverName, serverId, collections, enabled…
// The web app uses HomeServerConfig (type/url/token/…). These helpers translate
// both directions so a server configured on the TV works here and vice-versa.

function apkKindToWebType(kind: unknown): HomeServerConfig["type"] {
  const k = String(kind ?? "").toUpperCase();
  if (k === "PLEX") return "plex";
  if (k === "EMBY") return "emby";
  return "jellyfin";
}

function webTypeToApkKind(type: HomeServerConfig["type"]): string {
  if (type === "plex") return "PLEX";
  if (type === "emby") return "EMBY";
  return "JELLYFIN";
}

function toStr(value: unknown): string {
  return typeof value === "string" ? value : value == null ? "" : String(value);
}

// Parse a cloud homeServerConnectionJson value into web HomeServerConfig[].
// Accepts the APK object shape { connections: [...] }, a bare array (legacy web
// writes), or a single object.
export function parseHomeServerConnectionJson(json: string | null | undefined): HomeServerConfig[] {
  if (!json || !json.trim()) return [];
  let root: unknown;
  try {
    root = JSON.parse(json);
  } catch {
    return [];
  }
  const rawList: unknown[] = Array.isArray(root)
    ? root
    : root && typeof root === "object" && Array.isArray((root as { connections?: unknown[] }).connections)
      ? (root as { connections: unknown[] }).connections
      : root && typeof root === "object"
        ? [root]
        : [];

  return rawList
    .map((raw): HomeServerConfig | null => {
      if (!raw || typeof raw !== "object") return null;
      const r = raw as Record<string, unknown>;
      // Support both APK field names and prior web field names.
      const url = trimUrl(toStr(r.serverUrl ?? r.url));
      const type = "type" in r && !("serverKind" in r)
        ? (String(r.type).toLowerCase() as HomeServerConfig["type"])
        : apkKindToWebType(r.serverKind);
      const token = toStr(r.accessToken ?? r.token);
      if (!url && !token) return null;
      const collections: HomeServerCollectionConfig[] = Array.isArray(r.collections)
        ? (r.collections as unknown[])
            .map((c) => (c && typeof c === "object" ? (c as Record<string, unknown>) : null))
            .filter((c): c is Record<string, unknown> => Boolean(c))
            .map((c) => ({
              id: toStr(c.id),
              name: toStr(c.name),
              type: toStr(c.type),
              enabled: c.enabled !== false
            }))
        : [];
      return {
        id: toStr(r.connectionId ?? r.id) || `${type}:${url}`,
        type,
        name: toStr(r.displayName ?? r.name ?? r.serverName) || "Home Server",
        url,
        token: token || undefined,
        username: toStr(r.userName ?? r.username) || undefined,
        password: toStr(r.password) || undefined,
        enabled: r.enabled !== false,
        serverId: toStr(r.serverId) || undefined,
        userId: toStr(r.userId) || undefined,
        userName: toStr(r.userName) || undefined,
        accountToken: toStr(r.accountToken) || undefined,
        collections: collections.length ? collections : undefined,
        lastConnectedAt: typeof r.lastConnectedAt === "number" ? r.lastConnectedAt : undefined
      };
    })
    .filter((s): s is HomeServerConfig => Boolean(s));
}

// Serialize web HomeServerConfig[] back into the APK { connections: [...] }
// shape so the Android app reads it after a web-side change.
export function serializeHomeServerConnectionJson(servers: HomeServerConfig[] | undefined): string {
  const list = (servers ?? []).filter((s) => s && (s.url || s.token));
  if (!list.length) return "";
  return JSON.stringify({
    connections: list.map((s) => ({
      enabled: s.enabled !== false,
      connectionId: s.id || `${webTypeToApkKind(s.type)}:${s.url}`,
      serverUrl: s.url,
      displayName: s.name || "",
      serverName: s.name || "",
      serverKind: webTypeToApkKind(s.type),
      serverId: s.serverId || "",
      userId: s.userId || "",
      userName: s.userName || s.username || "",
      accessToken: s.token || "",
      accountToken: s.accountToken || "",
      collections: (s.collections ?? []).map((c) => ({
        id: c.id,
        name: c.name,
        type: c.type,
        enabled: c.enabled !== false
      })),
      lastConnectedAt: s.lastConnectedAt || 0
    }))
  });
}

/**
 * Home-server clients. Jellyfin / Emby share an API surface. Plex uses its own
 * JSON API but is normalized to the same MediaItem rows.
 *
 * All requests go through /api/proxy so the browser avoids CORS with the user's
 * server and we can attach the auth header.
 */

const AUTH_HEADER = 'MediaBrowser Client="ARVIO Web", Device="Web", DeviceId="arvio-web", Version="1.0.0"';

const sessionCache = new Map<string, { token: string; userId: string }>();

function trimUrl(url: string) {
  return url.replace(/\/+$/, "");
}

function directUrl(url: string) {
  return url;
}

function hashId(value: string): number {
  let hash = 0;
  for (let i = 0; i < value.length; i += 1) {
    hash = (hash * 31 + value.charCodeAt(i)) | 0;
  }
  return Math.abs(hash) || 1;
}

async function proxiedGet<T>(url: string, headers?: Record<string, string>): Promise<T> {
  return jsonRequest<T>(proxiedUrl(url, headers));
}

async function proxiedPost<T>(url: string, body: unknown, headers?: Record<string, string>): Promise<T> {
  const target = new URL("/api/proxy", window.location.origin);
  target.searchParams.set("url", url);
  if (headers && Object.keys(headers).length) target.searchParams.set("headers", btoa(JSON.stringify(headers)));
  return jsonRequest<T>(target.toString(), { method: "POST", body: JSON.stringify(body) });
}

async function ensureSession(server: HomeServerConfig): Promise<{ token: string; userId: string } | null> {
  const cached = sessionCache.get(server.id);
  if (cached) return cached;
  const base = trimUrl(server.url);

  // API-key path: resolve the user id from the token.
  if (server.token) {
    try {
      const me = await proxiedGet<{ Id: string }>(`${base}/Users/Me`, { "X-Emby-Token": server.token });
      if (me?.Id) {
        const session = { token: server.token, userId: me.Id };
        sessionCache.set(server.id, session);
        return session;
      }
    } catch {
      /* fall through to username auth */
    }
  }

  // Username/password path (AuthenticateByName).
  if (server.username) {
    try {
      const auth = await proxiedPost<{ AccessToken: string; User: { Id: string } }>(
        `${base}/Users/AuthenticateByName`,
        { Username: server.username, Pw: server.password ?? "" },
        { "X-Emby-Authorization": AUTH_HEADER }
      );
      if (auth?.AccessToken && auth.User?.Id) {
        const session = { token: auth.AccessToken, userId: auth.User.Id };
        sessionCache.set(server.id, session);
        return session;
      }
    } catch {
      return null;
    }
  }
  return null;
}

interface JellyfinItem {
  Id: string;
  Name: string;
  Type: string;
  ProductionYear?: number;
  Overview?: string;
  CommunityRating?: number;
  ImageTags?: { Primary?: string };
  BackdropImageTags?: string[];
}

interface PlexSection {
  key: string;
  title: string;
  type: string;
}

interface PlexItem {
  ratingKey: string;
  title: string;
  type: string;
  year?: number;
  summary?: string;
  rating?: number;
  thumb?: string;
  art?: string;
  Media?: Array<{ Part?: Array<{ key?: string }> }>;
}

function mapItem(base: string, token: string, item: JellyfinItem): MediaItem {
  const mediaType: MediaType = item.Type === "Series" ? "tv" : "movie";
  const image = item.ImageTags?.Primary ? directUrl(`${base}/Items/${item.Id}/Images/Primary?maxWidth=500&api_key=${token}`) : "";
  const backdrop = item.BackdropImageTags?.length ? directUrl(`${base}/Items/${item.Id}/Images/Backdrop/0?maxWidth=1280&api_key=${token}`) : null;
  return {
    id: hashId(item.Id),
    title: item.Name,
    overview: item.Overview ?? "",
    year: item.ProductionYear ? String(item.ProductionYear) : "",
    rating: item.CommunityRating ? item.CommunityRating.toFixed(1) : "",
    mediaType,
    image,
    backdrop,
    isHomeServer: true,
    // Movies stream directly; series would need episode browsing (future).
    homeServerUrl: mediaType === "movie" ? directUrl(`${base}/Videos/${item.Id}/stream?static=true&api_key=${token}`) : null
  };
}

function plexImage(base: string, token: string, path?: string) {
  return path ? directUrl(`${base}${path}?X-Plex-Token=${encodeURIComponent(token)}`) : "";
}

function plexStreamUrl(base: string, token: string, item: PlexItem) {
  const part = item.Media?.[0]?.Part?.[0]?.key;
  return part ? directUrl(`${base}${part}?X-Plex-Token=${encodeURIComponent(token)}`) : null;
}

function mapPlexItem(base: string, token: string, item: PlexItem): MediaItem {
  const mediaType: MediaType = item.type === "show" ? "tv" : "movie";
  return {
    id: hashId(`plex:${item.ratingKey}`),
    title: item.title,
    overview: item.summary ?? "",
    year: item.year ? String(item.year) : "",
    rating: item.rating ? item.rating.toFixed(1) : "",
    mediaType,
    image: plexImage(base, token, item.thumb),
    backdrop: item.art ? plexImage(base, token, item.art) : null,
    isHomeServer: true,
    homeServerUrl: mediaType === "movie" ? plexStreamUrl(base, token, item) : null
  };
}

async function loadPlexRows(server: HomeServerConfig): Promise<Category[]> {
  if (!server.token) return [];
  const base = trimUrl(server.url);
  const token = server.token;
  const headers = { Accept: "application/json", "X-Plex-Token": token };
  const sections = await proxiedGet<{ MediaContainer?: { Directory?: PlexSection[] } }>(
    `${base}/library/sections?X-Plex-Token=${encodeURIComponent(token)}`,
    headers
  ).catch(() => null);
  const libraries = (sections?.MediaContainer?.Directory ?? [])
    .filter((section) => section.type === "movie" || section.type === "show")
    .slice(0, 4);
  const rows = await Promise.all(libraries.map(async (library) => {
    const payload = await proxiedGet<{ MediaContainer?: { Metadata?: PlexItem[] } }>(
      `${base}/library/sections/${library.key}/all?X-Plex-Token=${encodeURIComponent(token)}&sort=addedAt:desc`,
      headers
    ).catch(() => null);
    const mapped = (payload?.MediaContainer?.Metadata ?? [])
      .slice(0, 24)
      .map((item) => mapPlexItem(base, token, item))
      .filter((item) => item.image || item.backdrop);
    return mapped.length ? { id: `hs_${server.id}_${library.key}`, title: `${server.name} - ${library.title}`, items: mapped } : null;
  }));
  return rows.filter((row): row is Category => Boolean(row));
}

export async function loadHomeServerRows(servers: HomeServerConfig[]): Promise<Category[]> {
  const plexRows = await Promise.all(
    servers
      .filter((server) => server.enabled && server.url && server.type === "plex")
      .map((server) => loadPlexRows(server).catch(() => [] as Category[]))
  );
  const active = servers.filter((server) => server.enabled && server.url && (server.type === "jellyfin" || server.type === "emby"));
  const rowsPerServer = await Promise.all(active.map(async (server) => {
    const session = await ensureSession(server).catch(() => null);
    if (!session) return [] as Category[];
    const base = trimUrl(server.url);
    const { token, userId } = session;
    try {
      const views = await proxiedGet<{ Items?: Array<{ Id: string; Name: string; CollectionType?: string }> }>(
        `${base}/Users/${userId}/Views?api_key=${token}`
      );
      const libraries = (views.Items ?? []).filter((v) => v.CollectionType === "movies" || v.CollectionType === "tvshows").slice(0, 4);
      const rows = await Promise.all(libraries.map(async (library) => {
        const items = await proxiedGet<{ Items?: JellyfinItem[] }>(
          `${base}/Users/${userId}/Items?ParentId=${library.Id}&Recursive=true&IncludeItemTypes=Movie,Series&SortBy=DateCreated&SortOrder=Descending&Limit=24&Fields=Overview&api_key=${token}`
        ).catch(() => ({ Items: [] as JellyfinItem[] }));
        const mapped = (items.Items ?? []).map((item) => mapItem(base, token, item)).filter((m) => m.image || m.backdrop);
        return mapped.length ? { id: `hs_${server.id}_${library.Id}`, title: `${server.name} · ${library.Name}`, items: mapped } : null;
      }));
      return rows.filter((row): row is Category => Boolean(row));
    } catch {
      return [] as Category[];
    }
  }));
  return [...plexRows.flat(), ...rowsPerServer.flat()];
}

export function clearHomeServerSessions() {
  sessionCache.clear();
}

// Verify a home-server config works: authenticate (Jellyfin/Emby) or validate
// the token (Plex), and count libraries. Used by the web settings "Test" button
// so users adding a server directly (no Android app) get confirmation.
export async function testHomeServerConnection(
  server: HomeServerConfig
): Promise<{ ok: boolean; serverName?: string; libraryCount?: number; error?: string }> {
  const base = trimUrl(server.url);
  if (!base) return { ok: false, error: "Missing server URL" };
  try {
    if (server.type === "plex") {
      const token = server.token ?? "";
      if (!token) return { ok: false, error: "Plex needs an access token" };
      const sections = await proxiedGet<{ MediaContainer?: { Directory?: PlexSection[]; friendlyName?: string } }>(
        `${base}/library/sections?X-Plex-Token=${encodeURIComponent(token)}`,
        { Accept: "application/json", "X-Plex-Token": token }
      );
      const libs = sections?.MediaContainer?.Directory ?? [];
      return { ok: true, serverName: sections?.MediaContainer?.friendlyName, libraryCount: libs.length };
    }
    // Reset any cached (possibly stale) session so the test really re-auths.
    sessionCache.delete(server.id);
    const session = await ensureSession(server);
    if (!session) return { ok: false, error: "Authentication failed — check token or username/password" };
    const views = await proxiedGet<{ Items?: unknown[] }>(
      `${base}/Users/${session.userId}/Views?api_key=${session.token}`
    ).catch(() => null);
    const info = await proxiedGet<{ ServerName?: string }>(
      `${base}/System/Info?api_key=${session.token}`
    ).catch(() => null);
    return { ok: true, serverName: info?.ServerName, libraryCount: (views?.Items ?? []).length };
  } catch (error) {
    return { ok: false, error: error instanceof Error ? error.message : "Connection failed" };
  }
}

// ── Source resolution (APK parity) ──────────────────────────────────────────
// Match the opened title on each usable home server and emit playable
// StreamSources (addonId "home_server"), for movies and episodes, across
// Jellyfin/Emby (PlaybackInfo + /Videos/{id}/stream) and Plex (media parts).

export const HOME_SERVER_ADDON_ID = "home_server";

function normalizeTitle(title: string): string {
  return title
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/&/g, " and ")
    .replace(/[^a-z0-9]+/g, " ")
    .replace(/\b(the|a|an)\b/g, " ")
    .trim()
    .replace(/\s+/g, " ");
}

interface MatchTarget {
  title: string;
  year?: number;
  imdbId?: string;
  tmdbId?: number;
}

interface Candidate {
  title: string;
  year?: number;
  providerIds: Record<string, string>;
}

// Mirrors HomeServerMatcher.score in the APK.
function scoreCandidate(target: MatchTarget, c: Candidate): number {
  let score = 0;
  const providers = Object.fromEntries(Object.entries(c.providerIds).map(([k, v]) => [k.toLowerCase(), v]));
  const imdb = target.imdbId?.trim().toLowerCase();
  if (imdb && providers.imdb?.toLowerCase() === imdb) score += 1000;
  if (target.tmdbId != null && Number(providers.tmdb) === target.tmdbId) score += 900;

  const reqN = normalizeTitle(target.title);
  const candN = normalizeTitle(c.title);
  if (reqN && candN) {
    if (reqN === candN) score += 140;
    else if (candN.includes(reqN) || reqN.includes(candN)) score += 65;
  }
  if (target.year != null && c.year != null) {
    const delta = Math.abs(target.year - c.year);
    if (delta === 0) score += 90;
    else if (delta === 1) score += 45;
    else if (delta <= 2) score += 15;
    else score -= 120;
  }
  return score;
}

function isAcceptable(score: number): boolean {
  return score >= 150 || score >= 900;
}

function formatBytes(bytes: number): string {
  if (!bytes || bytes <= 0) return "";
  const gb = bytes / 1_000_000_000;
  if (gb >= 1) return `${gb.toFixed(1)} GB`;
  return `${Math.round(bytes / 1_000_000)} MB`;
}

function qualityLabel(width?: number, height?: number): string {
  const h = height ?? 0;
  const w = width ?? 0;
  if (h >= 2000 || w >= 3800) return "4K";
  if (h >= 1000 || w >= 1900) return "1080p";
  if (h >= 700 || w >= 1200) return "720p";
  if (h > 0) return `${h}p`;
  return "";
}

// ---- Jellyfin / Emby ----

interface JellyfinFullItem {
  Id: string;
  Name: string;
  ProductionYear?: number;
  ProviderIds?: Record<string, string>;
  MediaSources?: Array<{
    Id?: string;
    Path?: string;
    Container?: string;
    Size?: number;
    ETag?: string;
    MediaStreams?: Array<{ Type?: string; Width?: number; Height?: number }>;
  }>;
}

async function jellyfinFindItem(
  server: HomeServerConfig,
  session: { token: string; userId: string },
  target: MatchTarget,
  itemTypes: string
): Promise<JellyfinFullItem | null> {
  const base = trimUrl(server.url);
  const { token, userId } = session;
  const params = new URLSearchParams({
    Recursive: "true",
    IncludeItemTypes: itemTypes,
    SearchTerm: target.title,
    Fields: "ProviderIds,MediaSources,ProductionYear,Path",
    Limit: "12",
    api_key: token
  });
  const res = await proxiedGet<{ Items?: JellyfinFullItem[] }>(
    `${base}/Users/${userId}/Items?${params.toString()}`
  ).catch(() => null);
  const items = res?.Items ?? [];
  if (!items.length) return null;
  let best: JellyfinFullItem | null = null;
  let bestScore = -Infinity;
  for (const item of items) {
    const score = scoreCandidate(target, {
      title: item.Name,
      year: item.ProductionYear,
      providerIds: item.ProviderIds ?? {}
    });
    if (score > bestScore) {
      bestScore = score;
      best = item;
    }
  }
  return best && isAcceptable(bestScore) ? best : null;
}

async function jellyfinItemSources(
  server: HomeServerConfig,
  session: { token: string; userId: string },
  item: JellyfinFullItem
): Promise<StreamSource[]> {
  const base = trimUrl(server.url);
  const { token, userId } = session;
  // PlaybackInfo yields the authoritative MediaSources with container/size.
  const playbackInfo = await proxiedPost<{ MediaSources?: JellyfinFullItem["MediaSources"] }>(
    `${base}/Items/${item.Id}/PlaybackInfo?UserId=${userId}&IsPlayback=true&AutoOpenLiveStream=true&MaxStreamingBitrate=2147483647&api_key=${token}`,
    {},
    { "X-Emby-Token": token }
  ).catch(() => null);
  const mediaSources = (playbackInfo?.MediaSources ?? item.MediaSources ?? []).filter(Boolean);
  const label = server.name || "Home Server";
  const seen = new Set<string>();
  const out: StreamSource[] = [];
  for (const ms of mediaSources) {
    const videoStream = (ms.MediaStreams ?? []).find((s) => s.Type === "Video");
    const quality = qualityLabel(videoStream?.Width, videoStream?.Height) || "Direct";
    const container = (ms.Container ?? "").toLowerCase();
    const ext = container ? `.${container}` : "";
    // Direct static stream — playable in-browser (mp4) or via remux/external.
    const url = `${base}/Videos/${item.Id}/stream${ext}?Static=true&MediaSourceId=${ms.Id ?? ""}&api_key=${token}${ms.ETag ? `&Tag=${ms.ETag}` : ""}`;
    if (seen.has(url)) continue;
    seen.add(url);
    out.push({
      source: [label, quality, container.toUpperCase()].filter(Boolean).join(" "),
      addonName: label,
      addonId: HOME_SERVER_ADDON_ID,
      quality,
      size: formatBytes(ms.Size ?? 0),
      sizeBytes: ms.Size && ms.Size > 0 ? ms.Size : null,
      url,
      behaviorHints: {
        cached: true,
        filename: item.Name,
        videoSize: ms.Size && ms.Size > 0 ? ms.Size : null
      },
      description: `${item.Name} · ${label}`
    });
  }
  return out;
}

// ---- Plex ----

interface PlexMetadata {
  ratingKey: string;
  title: string;
  year?: number;
  Guid?: Array<{ id: string }>;
  Media?: Array<{
    videoResolution?: string;
    Part?: Array<{ key?: string; size?: number; container?: string; file?: string }>;
  }>;
}

function plexProviderIds(meta: PlexMetadata): Record<string, string> {
  const ids: Record<string, string> = {};
  for (const g of meta.Guid ?? []) {
    const m = g.id.match(/^(imdb|tmdb|tvdb):\/\/(.+)$/i);
    if (m) ids[m[1].toLowerCase()] = m[2];
  }
  return ids;
}

async function plexFindMetadata(
  server: HomeServerConfig,
  target: MatchTarget,
  plexTypes: number[]
): Promise<PlexMetadata | null> {
  const base = trimUrl(server.url);
  const token = server.token ?? "";
  const headers = { Accept: "application/json", "X-Plex-Token": token };
  let best: PlexMetadata | null = null;
  let bestScore = -Infinity;
  for (const plexType of plexTypes) {
    const res = await proxiedGet<{ MediaContainer?: { Metadata?: PlexMetadata[] } }>(
      `${base}/library/all?title=${encodeURIComponent(target.title)}&type=${plexType}&includeGuids=1&X-Plex-Token=${encodeURIComponent(token)}`,
      headers
    ).catch(() => null);
    for (const meta of res?.MediaContainer?.Metadata ?? []) {
      const score = scoreCandidate(target, { title: meta.title, year: meta.year, providerIds: plexProviderIds(meta) });
      if (score > bestScore) {
        bestScore = score;
        best = meta;
      }
    }
  }
  return best && isAcceptable(bestScore) ? best : null;
}

async function plexMetadataSources(server: HomeServerConfig, meta: PlexMetadata): Promise<StreamSource[]> {
  const base = trimUrl(server.url);
  const token = server.token ?? "";
  const label = server.name || "Home Server";
  const out: StreamSource[] = [];
  const seen = new Set<string>();
  for (const media of meta.Media ?? []) {
    for (const part of media.Part ?? []) {
      if (!part.key) continue;
      const url = `${base}${part.key}?X-Plex-Token=${encodeURIComponent(token)}`;
      if (seen.has(url)) continue;
      seen.add(url);
      const container = (part.container ?? "").toLowerCase();
      const quality = media.videoResolution
        ? (media.videoResolution === "4k" ? "4K" : `${media.videoResolution}p`.replace("pp", "p"))
        : "Direct";
      out.push({
        source: [label, quality, container.toUpperCase()].filter(Boolean).join(" "),
        addonName: label,
        addonId: HOME_SERVER_ADDON_ID,
        quality,
        size: formatBytes(part.size ?? 0),
        sizeBytes: part.size && part.size > 0 ? part.size : null,
        url,
        behaviorHints: { cached: true, filename: meta.title, videoSize: part.size && part.size > 0 ? part.size : null },
        description: `${meta.title} · ${label}`
      });
    }
  }
  return out;
}

function usableServers(servers: HomeServerConfig[]): HomeServerConfig[] {
  return servers.filter((s) => s.enabled && s.url && (s.type === "plex" ? Boolean(s.token) : true));
}

export async function resolveHomeServerMovieSources(
  servers: HomeServerConfig[],
  target: MatchTarget
): Promise<StreamSource[]> {
  const active = usableServers(servers);
  if (!active.length) return [];
  const perServer = await Promise.all(active.map(async (server) => {
    try {
      if (server.type === "plex") {
        const meta = await plexFindMetadata(server, target, [1]); // 1 = movie
        return meta ? plexMetadataSources(server, meta) : [];
      }
      const session = await ensureSession(server);
      if (!session) return [];
      const item = await jellyfinFindItem(server, session, target, "Movie");
      return item ? jellyfinItemSources(server, session, item) : [];
    } catch {
      return [];
    }
  }));
  return dedupeSources(perServer.flat());
}

export async function resolveHomeServerEpisodeSources(
  servers: HomeServerConfig[],
  target: MatchTarget,
  season: number,
  episode: number
): Promise<StreamSource[]> {
  const active = usableServers(servers);
  if (!active.length) return [];
  const perServer = await Promise.all(active.map(async (server) => {
    try {
      if (server.type === "plex") {
        return plexEpisodeSources(server, target, season, episode);
      }
      const session = await ensureSession(server);
      if (!session) return [];
      const series = await jellyfinFindItem(server, session, target, "Series");
      if (!series) return [];
      const base = trimUrl(server.url);
      const { token, userId } = session;
      // Jellyfin episode lookup by series + season/episode indices.
      const epRes = await proxiedGet<{ Items?: Array<JellyfinFullItem & { IndexNumber?: number; ParentIndexNumber?: number }> }>(
        `${base}/Shows/${series.Id}/Episodes?userId=${userId}&Fields=MediaSources,Path&api_key=${token}`
      ).catch(() => null);
      const ep = (epRes?.Items ?? []).find((e) => e.ParentIndexNumber === season && e.IndexNumber === episode);
      return ep ? jellyfinItemSources(server, session, ep) : [];
    } catch {
      return [];
    }
  }));
  return dedupeSources(perServer.flat());
}

async function plexEpisodeSources(
  server: HomeServerConfig,
  target: MatchTarget,
  season: number,
  episode: number
): Promise<StreamSource[]> {
  const base = trimUrl(server.url);
  const token = server.token ?? "";
  const headers = { Accept: "application/json", "X-Plex-Token": token };
  const series = await plexFindMetadata(server, target, [2]); // 2 = show
  if (!series) return [];
  // Grandchildren query gets episodes directly with season/episode indices.
  const res = await proxiedGet<{ MediaContainer?: { Metadata?: Array<PlexMetadata & { index?: number; parentIndex?: number }> } }>(
    `${base}/library/metadata/${series.ratingKey}/allLeaves?X-Plex-Token=${encodeURIComponent(token)}`,
    headers
  ).catch(() => null);
  const ep = (res?.MediaContainer?.Metadata ?? []).find((m) => m.parentIndex === season && m.index === episode);
  return ep ? plexMetadataSources(server, ep) : [];
}

function dedupeSources(sources: StreamSource[]): StreamSource[] {
  const seen = new Set<string>();
  return sources.filter((s) => {
    const key = `${s.url ?? ""}|${s.source}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}
