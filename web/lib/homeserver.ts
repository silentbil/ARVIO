import { jsonRequest, proxiedUrl } from "./http";
import type { Category, HomeServerConfig, MediaItem, MediaType } from "./types";

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
