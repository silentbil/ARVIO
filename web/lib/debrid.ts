import { jsonRequest, proxiedUrl } from "./http";

// Debrid-side transcoding (Tier 2): when a source can't direct-play (MKV
// container, TrueHD/DTS audio, missing HEVC hardware), ask the user's own
// debrid service for a transcoded HLS stream instead of punting to VLC.
// API calls go through /api/proxy because the debrid APIs lack CORS headers.

export type DebridProvider = "torbox" | "realdebrid" | "premiumize" | "alldebrid";

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
// "/" or "=" (config-in-path style). Provider tokens are the full names
// torrentio emits (verified against live torrentio stream URLs).
const TORRENTIO_RESOLVE = /\/(?:resolve\/)?(torbox|realdebrid|real-debrid|premiumize|alldebrid)(?:=|\/)([^/]+)\/([a-fA-F0-9]{40})(?:\/(\d+))?(?:\/([^/?#]+))?/;

function normalizeProvider(raw: string): DebridProvider {
  if (raw === "torbox") return "torbox";
  if (raw === "premiumize") return "premiumize";
  if (raw === "alldebrid") return "alldebrid";
  return "realdebrid";
}

export function parseDebridStream(url: string | null | undefined): DebridStreamInfo | null {
  if (!url) return null;
  const match = url.match(TORRENTIO_RESOLVE);
  if (!match) return null;
  const [, provider, apiKey, infoHash, , fileName] = match;
  if (!apiKey || apiKey.length < 8) return null;
  return {
    provider: normalizeProvider(provider),
    apiKey,
    infoHash: infoHash.toLowerCase(),
    fileName: fileName ? safeDecode(fileName) : undefined
  };
}

export async function resolveTranscodeStream(info: DebridStreamInfo): Promise<TranscodeResult> {
  try {
    if (info.provider === "torbox") return await torboxTranscode(info);
    if (info.provider === "realdebrid") return await realDebridTranscode(info);
    // Premiumize/AllDebrid have no server-side transcoding endpoint. Their files
    // still direct-play/remux in the browser; if a codec truly can't decode,
    // the player's error screen offers VLC/Infuse (which decode anything).
    return { error: "This debrid service has no web transcoding — use VLC/Infuse for this source." };
  } catch (error) {
    return { error: error instanceof Error ? error.message : "Transcoding failed" };
  }
}

// Dispatch to the provider's direct-CDN-URL resolver. Every provider returns a
// plain https file URL the browser can fetch/remux and the worker can proxy for
// download — so download + remux work identically once resolved.
async function directUrlForProvider(info: DebridStreamInfo): Promise<TranscodeResult> {
  switch (info.provider) {
    case "torbox": return torboxDirectUrl(info);
    case "realdebrid": return realDebridDirectUrl(info);
    case "premiumize": return premiumizeDirectUrl(info);
    case "alldebrid": return allDebridDirectUrl(info);
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
    const result = await directUrlForProvider(info);
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

// ---------- Premiumize ----------
// Docs: POST /transfer/directdl with src=magnet returns the file tree for a
// cached torrent immediately (content[].link is the direct CDN url). Bearer
// auth; all calls ride /api/proxy (no CORS on the debrid API). Verified URL
// token against live torrentio: /resolve/premiumize/<key>/<hash>/<file>.
type PmContentItem = { path?: string; link?: string; size?: number };
type PmDirectDl = { status?: string; message?: string; content?: PmContentItem[] };

async function premiumizeDirectUrl(info: DebridStreamInfo): Promise<TranscodeResult> {
  const magnet = `magnet:?xt=urn:btih:${info.infoHash}`;
  const payload = await jsonRequest<PmDirectDl>(
    proxiedUrl("https://www.premiumize.me/api/transfer/directdl", {
      Authorization: `Bearer ${info.apiKey}`,
      "content-type": "application/x-www-form-urlencoded"
    }),
    { method: "POST", body: new URLSearchParams({ src: magnet }).toString(), cache: "no-store" }
  ).catch(() => null);
  if (!payload || payload.status !== "success") {
    return { error: payload?.message || "Premiumize could not open this torrent (it may not be cached)." };
  }
  const videos = (payload.content ?? []).filter((item) => item.link && VIDEO_FILE.test(item.path ?? ""));
  if (!videos.length) return { error: "Premiumize returned no downloadable video file for this source." };
  const pick = pickDebridFile(videos, info.fileName, (item) => item.path ?? "", (item) => item.size ?? 0);
  return pick?.link ? { url: pick.link } : { error: "Premiumize returned no direct download URL." };
}

// ---------- AllDebrid ----------
// Docs (v4, Bearer auth, agent no longer required): magnet/upload adds/looks up
// by infohash → magnet/status(id) reports ready + gives file links → link/unlock
// turns a locked link into a direct CDN url. Verified URL token against live
// torrentio: /resolve/alldebrid/<key>/<hash>/<file>.
type AdEnvelope<T> = { status?: string; data?: T; error?: { code?: string; message?: string } };
type AdMagnet = { id?: number; hash?: string; ready?: boolean; name?: string };
type AdStatusFile = { n?: string; l?: string; s?: number; e?: AdStatusFile[] };
type AdStatus = { id?: number; status?: string; statusCode?: number; links?: Array<{ link?: string; filename?: string; size?: number }>; files?: AdStatusFile[] };

async function adPost<T>(path: string, apiKey: string, form: Record<string, string>) {
  return jsonRequest<AdEnvelope<T>>(
    proxiedUrl(`https://api.alldebrid.com/v4${path}`, {
      Authorization: `Bearer ${apiKey}`,
      "content-type": "application/x-www-form-urlencoded"
    }),
    { method: "POST", body: new URLSearchParams(form).toString(), cache: "no-store" }
  );
}

async function allDebridDirectUrl(info: DebridStreamInfo): Promise<TranscodeResult> {
  // Upload/look up the magnet — for a cached torrent this returns immediately
  // with an id we can query. (Re-uploading an existing magnet is idempotent.)
  const upload = await adPost<{ magnets?: AdMagnet[] }>("/magnet/upload", info.apiKey, {
    "magnets[]": `magnet:?xt=urn:btih:${info.infoHash}`
  }).catch(() => null);
  if (!upload || upload.status !== "success") {
    return { error: upload?.error?.message || "AllDebrid could not open this torrent." };
  }
  const magnet = (upload.data?.magnets ?? [])[0];
  if (!magnet?.id) return { error: "AllDebrid did not return a magnet id for this source." };

  const status = await adPost<{ magnets?: AdStatus }>("/magnet/status", info.apiKey, { id: String(magnet.id) }).catch(() => null);
  if (!status || status.status !== "success") {
    return { error: status?.error?.message || "AllDebrid could not read this torrent's status." };
  }
  // status.data.magnets is an object (single) when queried by id.
  const detail = status.data?.magnets;
  if (!detail || (detail.statusCode !== undefined && detail.statusCode !== 4)) {
    // statusCode 4 = "Ready". Anything else means it's still downloading/queued.
    return { error: "This file is not cached on AllDebrid yet. Start it once, then try again." };
  }
  // Prefer the flat `links` list; fall back to the nested `files` tree (v4.1).
  const flat = (detail.links ?? [])
    .filter((entry) => entry.link && VIDEO_FILE.test(entry.filename ?? ""))
    .map((entry) => ({ name: entry.filename ?? "", link: entry.link!, size: entry.size ?? 0 }));
  const nested = flat.length ? [] : flattenAdFiles(detail.files ?? []);
  const candidates = flat.length ? flat : nested;
  if (!candidates.length) return { error: "AllDebrid returned no downloadable video file for this source." };
  const pick = pickDebridFile(candidates, info.fileName, (c) => c.name, (c) => c.size);
  if (!pick?.link) return { error: "AllDebrid returned no link for this file." };

  // The link from status is a locked AllDebrid link — unlock it to the CDN url.
  const unlocked = await adPost<{ link?: string }>("/link/unlock", info.apiKey, { link: pick.link }).catch(() => null);
  if (!unlocked || unlocked.status !== "success") {
    return { error: unlocked?.error?.message || "AllDebrid could not unlock this file." };
  }
  return unlocked.data?.link ? { url: unlocked.data.link } : { error: "AllDebrid returned no direct download URL." };
}

function flattenAdFiles(files: AdStatusFile[], prefix = ""): Array<{ name: string; link: string; size: number }> {
  const out: Array<{ name: string; link: string; size: number }> = [];
  for (const file of files) {
    const name = `${prefix}${file.n ?? ""}`;
    if (file.l && VIDEO_FILE.test(name)) out.push({ name, link: file.l, size: file.s ?? 0 });
    if (file.e?.length) out.push(...flattenAdFiles(file.e, `${name}/`));
  }
  return out;
}

// Shared file picker for Premiumize/AllDebrid: prefer the exact filename the
// stream URL named, then a substring match, then the largest video file —
// identical to the TorBox/RD heuristic so multi-file torrents resolve the right
// episode.
function pickDebridFile<T>(items: T[], wantedName: string | undefined, nameOf: (item: T) => string, sizeOf: (item: T) => number): T | undefined {
  const wanted = wantedName?.toLowerCase();
  return (
    items.find((item) => wanted && nameOf(item).toLowerCase().split("/").pop() === wanted) ??
    items.find((item) => wanted && nameOf(item).toLowerCase().includes(wanted)) ??
    items.slice().sort((a, b) => sizeOf(b) - sizeOf(a))[0]
  );
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
