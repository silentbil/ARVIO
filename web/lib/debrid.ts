import { jsonRequest, proxiedUrl } from "./http";

// Debrid-side transcoding (Tier 2): when a source can't direct-play (MKV
// container, TrueHD/DTS audio, missing HEVC hardware), ask the user's own
// debrid service for a transcoded HLS stream instead of punting to VLC.
// API calls go through /api/proxy because the debrid APIs lack CORS headers.

export type DebridProvider = "torbox" | "realdebrid";

export type DebridStreamInfo = {
  provider: DebridProvider;
  apiKey: string;
  infoHash: string;
  fileName?: string;
};

export type TranscodeResult =
  | { url: string; error?: undefined }
  | { url?: undefined; error: string };

// Matches torrentio-style debrid stream URLs, both the newer
// /resolve/<provider>/<key>/<hash>[/idx][/file] shape and the older
// /<provider>/<key>/<hash>[/idx][/file] shape. Provider may be separated by
// "/" or "=" (config-in-path style).
const TORRENTIO_RESOLVE = /\/(?:resolve\/)?(torbox|realdebrid|real-debrid)(?:=|\/)([^/]+)\/([a-fA-F0-9]{40})(?:\/(\d+))?(?:\/([^/?#]+))?/;

export function parseDebridStream(url: string | null | undefined): DebridStreamInfo | null {
  if (!url) return null;
  const match = url.match(TORRENTIO_RESOLVE);
  if (!match) return null;
  const [, provider, apiKey, infoHash, , fileName] = match;
  if (!apiKey || apiKey.length < 8) return null;
  return {
    provider: provider === "torbox" ? "torbox" : "realdebrid",
    apiKey,
    infoHash: infoHash.toLowerCase(),
    fileName: fileName ? safeDecode(fileName) : undefined
  };
}

export async function resolveTranscodeStream(info: DebridStreamInfo): Promise<TranscodeResult> {
  try {
    return info.provider === "torbox" ? await torboxTranscode(info) : await realDebridTranscode(info);
  } catch (error) {
    return { error: error instanceof Error ? error.message : "Transcoding failed" };
  }
}

// Torrentio marks debrid sources that are NOT in the provider's cache with a
// "download" tag (e.g. "[TB download]") or a ⏳ marker — playing those means
// waiting for the whole torrent to download server-side first.
export function isUncachedDebridStream(stream: { url?: string | null; source?: string | null; addonName?: string | null; description?: string | null }) {
  if (!stream.url || !parseDebridStream(stream.url)) return false;
  const text = `${stream.source ?? ""} ${stream.addonName ?? ""} ${stream.description ?? ""}`.toLowerCase();
  return /\[\s*(?:tb|rd|ad|pm|dl|oc)\s+download\s*\]|⏳|\bnot cached\b|\buncached\b/i.test(text);
}

// Resolve a debrid stream to its final, browser-fetchable direct file URL (the
// CDN URL). Used by the in-browser remux path — the torrentio /resolve/ redirect
// chain isn't CORS-traversable by the browser, but the CDN file itself is.
// Results are cached briefly so a prefetch at details-open makes the actual
// Play press instant (no mylist/requestdl round-trips at click time).
const directUrlCache = new Map<string, { at: number; result: TranscodeResult }>();
const DIRECT_URL_TTL_MS = 8 * 60 * 1000;

function directUrlCacheKey(info: DebridStreamInfo) {
  return `${info.provider}:${info.infoHash}:${info.fileName ?? ""}`;
}

export async function resolveDebridDirectUrl(info: DebridStreamInfo): Promise<TranscodeResult> {
  const key = directUrlCacheKey(info);
  const cached = directUrlCache.get(key);
  if (cached && cached.result.url && Date.now() - cached.at < DIRECT_URL_TTL_MS) return cached.result;
  try {
    const result = info.provider === "torbox" ? await torboxDirectUrl(info) : await realDebridDirectUrl(info);
    if (result.url) directUrlCache.set(key, { at: Date.now(), result });
    return result;
  } catch (error) {
    return { error: error instanceof Error ? error.message : "Could not resolve direct URL" };
  }
}

/** Fire-and-forget warm-up of the direct CDN URL for a debrid stream. */
export function prefetchDebridDirectUrl(url: string | null | undefined) {
  const info = parseDebridStream(url);
  if (!info) return;
  const cached = directUrlCache.get(directUrlCacheKey(info));
  if (cached && cached.result.url && Date.now() - cached.at < DIRECT_URL_TTL_MS) return;
  void resolveDebridDirectUrl(info).catch(() => undefined);
}

/** Synchronous cache lookup — lets playback start on the final CDN URL and skip
 * the torrentio redirect chain entirely when the prefetch already resolved it. */
export function cachedDebridDirectUrl(url: string | null | undefined): string | null {
  const info = parseDebridStream(url);
  if (!info) return null;
  const cached = directUrlCache.get(directUrlCacheKey(info));
  return cached && cached.result.url && Date.now() - cached.at < DIRECT_URL_TTL_MS ? cached.result.url : null;
}

// ---------- TorBox ----------

type TorboxEnvelope<T> = { success: boolean; error?: string | null; detail?: string; data?: T };

type TorboxTorrent = {
  id: number;
  hash?: string;
  files?: Array<{ id: number; name?: string; short_name?: string; size?: number }>;
};

const VIDEO_FILE = /\.(mkv|mp4|m4v|mov|avi|ts|webm|wmv)$/i;

async function torboxApi<T>(path: string, apiKey: string, form?: Record<string, string>): Promise<TorboxEnvelope<T>> {
  return jsonRequest<TorboxEnvelope<T>>(
    proxiedUrl(`https://api.torbox.app/v1/api${path}`, {
      Authorization: `Bearer ${apiKey}`,
      ...(form ? { "content-type": "application/x-www-form-urlencoded" } : {})
    }),
    form ? { method: "POST", body: new URLSearchParams(form).toString(), cache: "no-store" } : { cache: "no-store" }
  );
}

// One retry on transport failures: on production every TorBox call rides the
// Netlify proxy, whose cold starts / hiccups otherwise surface as bogus
// "not cached" errors (locally the proxy is instant, hiding the problem).
async function torboxApiRetry<T>(path: string, apiKey: string, form?: Record<string, string>): Promise<TorboxEnvelope<T>> {
  try {
    return await torboxApi<T>(path, apiKey, form);
  } catch {
    await new Promise((resolve) => setTimeout(resolve, 600));
    return torboxApi<T>(path, apiKey, form);
  }
}

async function findTorboxTorrent(info: DebridStreamInfo): Promise<TorboxTorrent | null> {
  const list = await torboxApiRetry<TorboxTorrent[]>("/torrents/mylist?bypass_cache=true&limit=1000", info.apiKey);
  return (list.data ?? []).find((entry) => entry.hash?.toLowerCase() === info.infoHash) ?? null;
}

type TorboxFile = { id: number; name?: string; short_name?: string; size?: number };
type TorboxFileResolution =
  | { ok: false; error: string }
  | { ok: true; torrent: TorboxTorrent; file?: TorboxFile };

async function resolveTorboxTorrentAndFile(info: DebridStreamInfo): Promise<TorboxFileResolution> {
  let torrent = await findTorboxTorrent(info);
  let sawTransportFailure = false;
  if (!torrent) {
    // Cached torrents attach instantly, so add it on the user's behalf — they
    // just clicked Play on this exact source.
    const added = await torboxApiRetry<{ torrent_id?: number }>("/torrents/createtorrent", info.apiKey, {
      magnet: `magnet:?xt=urn:btih:${info.infoHash}`,
      add_only_if_cached: "true"
    }).catch(() => { sawTransportFailure = true; return null; });
    if (added?.success) torrent = await findTorboxTorrent(info).catch(() => { sawTransportFailure = true; return null; });
  }
  if (!torrent) {
    // Only claim "not cached" when TorBox actually said so — a failed proxy
    // round-trip is a connectivity problem, not a caching problem.
    return {
      ok: false,
      error: sawTransportFailure
        ? "TorBox did not respond — trying the next source."
        : "This file is not cached on TorBox yet. Start the download once, then try again."
    };
  }

  const files = (torrent.files ?? []).filter((file) => VIDEO_FILE.test(file.short_name ?? file.name ?? ""));
  const wanted = info.fileName?.toLowerCase();
  const file =
    files.find((candidate) => wanted && (candidate.short_name ?? candidate.name ?? "").toLowerCase() === wanted) ??
    files.find((candidate) => wanted && (candidate.name ?? "").toLowerCase().includes(wanted)) ??
    files.sort((a, b) => (b.size ?? 0) - (a.size ?? 0))[0];
  return { ok: true, torrent, file };
}

async function torboxDirectUrl(info: DebridStreamInfo): Promise<TranscodeResult> {
  const resolved = await resolveTorboxTorrentAndFile(info);
  if (!resolved.ok) return { error: resolved.error };
  const params = new URLSearchParams({ token: info.apiKey, torrent_id: String(resolved.torrent.id), redirect: "false" });
  if (resolved.file) params.set("file_id", String(resolved.file.id));
  const dl = await torboxApiRetry<string>(`/torrents/requestdl?${params.toString()}`, info.apiKey);
  if (!dl.success || typeof dl.data !== "string" || !/^https?:\/\//i.test(dl.data)) {
    return { error: torboxError(dl) };
  }
  return { url: dl.data };
}

async function torboxTranscode(info: DebridStreamInfo): Promise<TranscodeResult> {
  const resolved = await resolveTorboxTorrentAndFile(info);
  if (!resolved.ok) return { error: resolved.error };
  const { torrent, file } = resolved;

  const params = new URLSearchParams({ id: String(torrent.id), type: "torrent" });
  if (file) params.set("file_id", String(file.id));
  const created = await torboxApi<Record<string, unknown>>(`/stream/createstream?${params.toString()}`, info.apiKey);
  if (!created.success) return { error: torboxError(created) };

  const direct = findStreamUrl(created.data);
  if (direct) return { url: direct };

  const token = stringField(created.data, "token") ?? stringField(created.data, "file_token");
  const presigned = stringField(created.data, "presigned_token");
  if (token && presigned) {
    const data = await torboxApi<Record<string, unknown>>(
      `/stream/getstreamdata?token=${encodeURIComponent(token)}&presigned_token=${encodeURIComponent(presigned)}`,
      info.apiKey
    );
    if (!data.success) return { error: torboxError(data) };
    const url = findStreamUrl(data.data);
    if (url) return { url };
  }

  return { error: "TorBox did not return a playable stream URL." };
}

function torboxError(envelope: TorboxEnvelope<unknown>): string {
  if (envelope.error === "PLAN_RESTRICTED_FEATURE") {
    return "Web transcoding requires the TorBox Pro plan. Upgrade at torbox.app, or use VLC/Infuse.";
  }
  return envelope.detail || envelope.error || "TorBox stream request failed";
}

// ---------- Real-Debrid ----------

type RdTorrent = { id: string; hash?: string };
type RdTorrentInfo = {
  id: string;
  files?: Array<{ id: number; path?: string; bytes?: number; selected?: number }>;
  links?: string[];
};
type RdUnrestrict = { id?: string; download?: string; streamable?: number };

const RD_BASE = "https://api.real-debrid.com/rest/1.0";

async function rdGet<T>(path: string, apiKey: string): Promise<T> {
  return jsonRequest<T>(proxiedUrl(`${RD_BASE}${path}`, { Authorization: `Bearer ${apiKey}` }), { cache: "no-store" });
}

async function rdPost<T>(path: string, apiKey: string, form: Record<string, string>): Promise<T> {
  return jsonRequest<T>(proxiedUrl(`${RD_BASE}${path}`, {
    Authorization: `Bearer ${apiKey}`,
    "content-type": "application/x-www-form-urlencoded"
  }), {
    method: "POST",
    body: new URLSearchParams(form).toString()
  });
}

async function rdUnrestrict(info: DebridStreamInfo): Promise<RdUnrestrict | { error: string }> {
  const torrents = await rdGet<RdTorrent[]>("/torrents?limit=100", info.apiKey);
  const torrent = torrents.find((entry) => entry.hash?.toLowerCase() === info.infoHash);
  if (!torrent) {
    return { error: "This file is not in your Real-Debrid account yet. Start it once, then try again." };
  }
  const detail = await rdGet<RdTorrentInfo>(`/torrents/info/${torrent.id}`, info.apiKey);
  const selected = (detail.files ?? []).filter((file) => file.selected === 1);
  const wanted = info.fileName?.toLowerCase();
  let linkIndex = selected.findIndex((file) => wanted && (file.path ?? "").toLowerCase().includes(wanted));
  if (linkIndex < 0) {
    let largest = -1;
    selected.forEach((file, index) => {
      if ((file.bytes ?? 0) > largest) {
        largest = file.bytes ?? 0;
        linkIndex = index;
      }
    });
  }
  const link = detail.links?.[Math.max(0, linkIndex)];
  if (!link) return { error: "Real-Debrid did not expose a link for this file." };
  const unrestricted = await rdPost<RdUnrestrict>("/unrestrict/link", info.apiKey, { link });
  if (!unrestricted.id) return { error: "Real-Debrid could not unrestrict this file." };
  return unrestricted;
}

async function realDebridDirectUrl(info: DebridStreamInfo): Promise<TranscodeResult> {
  const unrestricted = await rdUnrestrict(info);
  if ("error" in unrestricted) return { error: unrestricted.error };
  return unrestricted.download ? { url: unrestricted.download } : { error: "Real-Debrid returned no direct download URL." };
}

async function realDebridTranscode(info: DebridStreamInfo): Promise<TranscodeResult> {
  const unrestricted = await rdUnrestrict(info);
  if ("error" in unrestricted) return { error: unrestricted.error };

  const transcoded = await rdGet<Record<string, unknown>>(`/streaming/transcode/${unrestricted.id}`, info.apiKey);
  const apple = transcoded.apple;
  const appleUrl =
    typeof apple === "string" ? apple : apple && typeof apple === "object" ? firstStringValue(apple as Record<string, unknown>, ["full"]) : null;
  if (appleUrl) return { url: appleUrl };
  const any = findStreamUrl(transcoded);
  if (any) return { url: any };
  return { error: "Real-Debrid has no transcode for this file (it may not be streamable)." };
}

// ---------- helpers ----------

function safeDecode(value: string) {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

function stringField(data: unknown, key: string): string | null {
  if (!data || typeof data !== "object") return null;
  const value = (data as Record<string, unknown>)[key];
  return typeof value === "string" && value ? value : null;
}

function firstStringValue(record: Record<string, unknown>, preferredKeys: string[] = []): string | null {
  for (const key of preferredKeys) {
    if (typeof record[key] === "string") return record[key] as string;
  }
  for (const value of Object.values(record)) {
    if (typeof value === "string" && /^https?:\/\//i.test(value)) return value;
  }
  return null;
}

function findStreamUrl(data: unknown, depth = 0): string | null {
  if (depth > 4 || data == null) return null;
  if (typeof data === "string") {
    return /^https?:\/\//i.test(data) && (data.includes(".m3u8") || data.includes("/stream")) ? data : null;
  }
  if (Array.isArray(data)) {
    for (const item of data) {
      const found = findStreamUrl(item, depth + 1);
      if (found) return found;
    }
    return null;
  }
  if (typeof data === "object") {
    const record = data as Record<string, unknown>;
    for (const key of ["stream_url", "hls_url", "playback_url", "url", "link"]) {
      const value = record[key];
      if (typeof value === "string" && /^https?:\/\//i.test(value)) return value;
    }
    for (const value of Object.values(record)) {
      const found = findStreamUrl(value, depth + 1);
      if (found) return found;
    }
  }
  return null;
}
