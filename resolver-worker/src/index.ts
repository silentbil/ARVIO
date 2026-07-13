type Env = {
  RESOLVER_KV?: KVNamespace;
  ALLOWED_ORIGINS?: string;
  SOURCE_CACHE_TTL_SECONDS?: string;
  ADDON_TIMEOUT_MS?: string;
  ADDON_EXTENDED_TIMEOUT_MS?: string;
  TRAKT_CLIENT_ID?: string;
  TRAKT_CLIENT_SECRET?: string;
};

// Trakt device-token exchange from the Worker. The Netlify backend's egress IP
// is Cloudflare-challenged by Trakt; the Worker (Cloudflare-origin) is not. The
// client secret lives here as a Worker secret and never touches the browser.
async function traktDeviceToken(request: Request, env: Env): Promise<Response> {
  const body = await request.json<{ code?: string }>().catch(() => null);
  if (!body?.code) return json({ error: "Missing device code" }, request, env, 400);
  if (!env.TRAKT_CLIENT_ID || !env.TRAKT_CLIENT_SECRET) {
    return json({ error: "Trakt is not configured on the resolver" }, request, env, 500);
  }
  const upstream = await fetch("https://api.trakt.tv/oauth/device/token", {
    method: "POST",
    headers: { "content-type": "application/json", "trakt-api-version": "2" },
    body: JSON.stringify({
      code: body.code,
      client_id: env.TRAKT_CLIENT_ID,
      client_secret: env.TRAKT_CLIENT_SECRET
    })
  });
  const text = await upstream.text();
  return new Response(text, {
    status: upstream.status,
    headers: {
      "content-type": upstream.headers.get("content-type") ?? "application/json",
      "cache-control": "no-store",
      ...corsHeaders(request, env)
    }
  });
}

type MediaType = "movie" | "tv";

type AddonCatalog = {
  type: string;
  id: string;
  name: string;
};

type InstalledAddon = {
  id: string;
  name: string;
  version?: string;
  manifestUrl: string;
  catalogs?: AddonCatalog[];
  resources?: Array<string | { name?: string; types?: string[]; idPrefixes?: string[] }>;
  types?: string[];
  idPrefixes?: string[];
  enabled?: boolean;
};

type MediaItem = {
  id: number;
  title: string;
  mediaType: MediaType;
  imdbId?: string | null;
};

type SubtitleTrack = {
  id?: string;
  url: string;
  lang?: string;
  label?: string;
  provider?: string;
  isEmbedded?: boolean;
  isForced?: boolean;
};

type RawStream = {
  name?: string;
  title?: string;
  url?: string;
  externalUrl?: string;
  ytId?: string;
  infoHash?: string;
  fileIdx?: number;
  behaviorHints?: StreamSource["behaviorHints"];
  subtitles?: SubtitleTrack[];
  sources?: string[];
  description?: string;
  size?: string;
  sizeBytes?: number;
};

type StreamSource = {
  source: string;
  addonName: string;
  addonId?: string;
  quality?: string;
  size?: string;
  sizeBytes?: number | null;
  url?: string | null;
  infoHash?: string | null;
  fileIdx?: number | null;
  behaviorHints?: {
    notWebReady?: boolean;
    cached?: boolean | null;
    bingeGroup?: string | null;
    directUrl?: string | null;
    url?: string | null;
    externalUrl?: string | null;
    videoHash?: string | null;
    videoSize?: number | null;
    proxyHeaders?: {
      request?: Record<string, string>;
      response?: Record<string, string>;
    } | null;
    filename?: string | null;
    browserPlayable?: boolean;
    iosPlayable?: boolean;
    externalPlayerRecommended?: boolean;
  } | null;
  subtitles?: SubtitleTrack[];
  sources?: string[];
  description?: string | null;
};

type SourcesRequest = {
  addons?: InstalledAddon[];
  item?: MediaItem;
  season?: number;
  episode?: number;
};

const JSON_HEADERS = { "content-type": "application/json; charset=utf-8" };
const NDJSON_HEADERS = { "content-type": "application/x-ndjson; charset=utf-8" };

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { headers: corsHeaders(request, env) });

    try {
      if (url.pathname === "/health") {
        return json({ ok: true, service: "arvio-resolver" }, request, env);
      }
      if (url.pathname === "/sources" && request.method === "POST") {
        return sources(request, env);
      }
      if (url.pathname === "/subtitle" && request.method === "GET") {
        return subtitle(request, env);
      }
      if (url.pathname === "/media" && (request.method === "GET" || request.method === "HEAD")) {
        return media(request, env);
      }
      if (url.pathname === "/launch" && request.method === "GET") {
        return launchPage(request, env);
      }
      if (url.pathname === "/trakt/device/token" && request.method === "POST") {
        return traktDeviceToken(request, env);
      }
      return json({ error: "Not found" }, request, env, 404);
    } catch (error) {
      return json({ error: error instanceof Error ? error.message : "Resolver error" }, request, env, 500);
    }
  }
};

async function sources(request: Request, env: Env) {
  const payload = await request.json<SourcesRequest>().catch(() => null);
  if (!payload?.item) return json({ error: "Missing item" }, request, env, 400);

  const addons = (payload.addons ?? []).filter((addon) =>
    addon.enabled !== false &&
    Boolean(manifestUrlFor(addon)) &&
    supportsResource(addon, "stream")
  );

  const cacheKey = await sourceCacheKey(payload);
  const cached = await env.RESOLVER_KV?.get(cacheKey, "json") as StreamSource[] | null;
  if (cached?.length) {
    return json({ type: "final", cached: true, streams: cached }, request, env);
  }

  const { readable, writable } = new TransformStream<Uint8Array, Uint8Array>();
  const writer = writable.getWriter();
  const encoder = new TextEncoder();
  const write = async (value: unknown) => writer.write(encoder.encode(`${JSON.stringify(value)}\n`));

  void (async () => {
    let aggregate: StreamSource[] = [];
    await write({ type: "started", addonCount: addons.length });
    await Promise.all(addons.map(async (addon) => {
      const batch = await queryAddonStreams(addon, payload.item!, payload.season, payload.episode, env);
      if (!batch.length) return;
      aggregate = sortStreams(dedupeStreams([...aggregate, ...batch])
        .filter((stream) => stream.url || stream.infoHash || stream.description || stream.source));
      await write({ type: "batch", addonId: addon.id, addonName: addon.name, batch, streams: aggregate });
    }));
    if (aggregate.length && env.RESOLVER_KV) {
      await env.RESOLVER_KV.put(cacheKey, JSON.stringify(aggregate), {
        expirationTtl: ttl(env)
      }).catch(() => undefined);
    }
    await write({ type: "final", streams: aggregate });
    await writer.close();
  })().catch(async (error) => {
    await write({ type: "error", error: error instanceof Error ? error.message : "Resolver stream failed" }).catch(() => undefined);
    await writer.close().catch(() => undefined);
  });

  return new Response(readable, {
    headers: {
      ...NDJSON_HEADERS,
      ...corsHeaders(request, env),
      "cache-control": "no-store"
    }
  });
}

async function queryAddonStreams(addon: InstalledAddon, item: MediaItem, season: number | undefined, episode: number | undefined, env: Env) {
  const type = item.mediaType === "tv" ? "series" : "movie";
  const types = streamRequestTypes(addon, type);
  const ids = streamIds(item, season, episode);
  const { base, query } = addonBaseUrl(manifestUrlFor(addon));
  for (const id of ids) {
    if (!addonSupportsId(addon, id)) continue;
    for (const requestType of types) {
      if (!addonSupportsType(addon, requestType)) continue;
      const url = `${base}/stream/${requestType}/${encodeURIComponent(id)}.json${query ? `?${query}` : ""}`;
      const response = await fetchWithTimeout(url, env, addon).catch(() => null);
      if (!response?.ok) continue;
      const payload = await response.json<{ streams?: RawStream[] }>().catch(() => null);
      const streams = (payload?.streams ?? []).map((stream) => normalizeStream(stream, addon));
      if (streams.length > 0) return streams;
    }
  }
  return [] as StreamSource[];
}

async function subtitle(request: Request, env: Env) {
  const input = new URL(request.url);
  const raw = input.searchParams.get("url");
  if (!raw) return json({ error: "Missing url" }, request, env, 400);
  let target: URL;
  try {
    target = new URL(raw);
  } catch {
    return json({ error: "Invalid url" }, request, env, 400);
  }
  if (!["http:", "https:"].includes(target.protocol)) return json({ error: "Unsupported subtitle protocol" }, request, env, 400);

  const response = await fetch(target);
  const text = await response.text();
  const webvtt = text.trimStart().startsWith("WEBVTT") ? text : srtToVtt(text);
  return new Response(webvtt, {
    status: response.ok ? 200 : response.status,
    headers: {
      "content-type": "text/vtt; charset=utf-8",
      "cache-control": "public, max-age=3600",
      ...corsHeaders(request, env)
    }
  });
}

const BLOCKED_MEDIA_HOSTS = new Set(["localhost", "127.0.0.1", "::1", "0.0.0.0", "169.254.169.254"]);

async function media(request: Request, env: Env) {
  const input = new URL(request.url);
  const raw = input.searchParams.get("url");
  if (!raw) return json({ error: "Missing url" }, request, env, 400);
  let target: URL;
  try {
    target = new URL(raw);
  } catch {
    return json({ error: "Invalid url" }, request, env, 400);
  }
  if (!["http:", "https:"].includes(target.protocol) || BLOCKED_MEDIA_HOSTS.has(target.hostname)) {
    return json({ error: "Blocked media target" }, request, env, 400);
  }
  // hls.js/mpegts.js requests carry an Origin header; enforce the allowlist for
  // those. Native <video> requests have no Origin and pass through.
  const origin = request.headers.get("origin");
  if (origin) {
    const allowed = (env.ALLOWED_ORIGINS ?? "").split(",").map((value) => value.trim()).filter(Boolean);
    if (allowed.length && !allowed.includes(origin)) {
      return json({ error: "Origin not allowed" }, request, env, 403);
    }
  }

  const forwarded = new Headers();
  forwarded.set("accept", "*/*");
  forwarded.set("user-agent", decodeMediaHeaders(input.searchParams.get("h"))["User-Agent"] ?? "VLC/3.0.20 LibVLC/3.0.20");
  for (const [key, value] of Object.entries(decodeMediaHeaders(input.searchParams.get("h")))) {
    if (/^(user-agent|referer|origin|icy-metadata|x-forwarded-for|authorization|cookie)$/i.test(key)) {
      forwarded.set(key, value);
    }
  }
  const range = request.headers.get("range");
  if (range) forwarded.set("range", range);

  let upstream: Response;
  try {
    upstream = await fetch(target.toString(), {
      method: request.method,
      headers: forwarded,
      redirect: "follow",
      signal: AbortSignal.timeout(30_000)
    });
  } catch (error) {
    return json({ error: error instanceof Error ? error.message : "Upstream fetch failed" }, request, env, 502);
  }

  const contentType = upstream.headers.get("content-type") ?? "";
  const finalUrl = safeUrl(upstream.url) ?? target;
  // dl=1 turns the proxy into a download: Content-Disposition attachment makes
  // every browser (incl. iOS Safari/Chrome, which preview inline files instead
  // of downloading) hand the file to its download manager with progress UI.
  const wantsDownload = input.searchParams.get("dl") === "1";
  const looksLikePlaylist =
    !wantsDownload && (
      contentType.toLowerCase().includes("mpegurl") ||
      /\.m3u8?(?:$|[?#])/i.test(finalUrl.pathname) ||
      /\.m3u8?(?:$|[?#])/i.test(target.pathname)
    );

  if (looksLikePlaylist && request.method === "GET") {
    const text = await upstream.text();
    if (text.trimStart().startsWith("#EXTM3U") || text.includes("#EXTINF")) {
      const rewritten = rewriteHlsPlaylist(text, finalUrl, input);
      return new Response(rewritten, {
        status: upstream.status,
        headers: {
          "content-type": "application/vnd.apple.mpegurl",
          "cache-control": "no-store",
          ...corsHeaders(request, env)
        }
      });
    }
    return new Response(text, {
      status: upstream.status,
      headers: {
        "content-type": contentType || "text/plain; charset=utf-8",
        "cache-control": "no-store",
        ...corsHeaders(request, env)
      }
    });
  }

  const headers = new Headers(corsHeaders(request, env));
  headers.set("content-type", contentType || "application/octet-stream");
  headers.set("cache-control", "no-store");
  headers.set("accept-ranges", upstream.headers.get("accept-ranges") ?? "bytes");
  headers.set("access-control-expose-headers", "content-length,content-range,accept-ranges,etag,last-modified");
  const contentLength = upstream.headers.get("content-length");
  const contentRange = upstream.headers.get("content-range");
  if (contentLength) headers.set("content-length", contentLength);
  if (contentRange) headers.set("content-range", contentRange);
  // Resume validators: browsers (and download managers) only auto-resume an
  // interrupted download when the original response carried an ETag or
  // Last-Modified — without one, Chrome can't prove the file is unchanged and
  // abandons the download ("stops halfway, can't continue"). Small files finish
  // before any interruption so the gap never showed; big 4K remuxes (hours-long)
  // always hit a network blip, and had no validator to resume against. The
  // TorBox CDN sends Last-Modified — forward it (and ETag when present) so a
  // dropped multi-GB download can pick up where it left off.
  const etag = upstream.headers.get("etag");
  const lastModified = upstream.headers.get("last-modified");
  if (etag) headers.set("etag", etag);
  if (lastModified) headers.set("last-modified", lastModified);
  if (wantsDownload) {
    const rawName = input.searchParams.get("filename") ?? "";
    const safeName = rawName.replace(/[^\w .,()\[\]&'-]+/g, " ").replace(/\s+/g, " ").trim().slice(0, 150) || "arvio-download.mkv";
    headers.set("content-disposition", `attachment; filename="${safeName}"`);
    // Observable via `wrangler tail` — diagnose client download issues.
    console.log(JSON.stringify({
      dl: true,
      host: target.hostname,
      upstreamStatus: upstream.status,
      contentLength: contentLength ?? null,
      range: range ?? null,
      ua: request.headers.get("user-agent")?.slice(0, 80) ?? null
    }));
  }
  return new Response(request.method === "HEAD" ? null : upstream.body, { status: upstream.status, headers });
}

// Interstitial that opens an external-player URL scheme from a real Safari
// context. iOS home-screen webapps (standalone PWAs) silently drop custom-
// scheme navigations — but the in-app browser sheet they open for https links
// CAN launch app schemes (with the native "Open in …?" prompt). The PWA
// navigates here; this page fires the scheme and offers a manual button.
function launchPage(request: Request, env: Env) {
  const input = new URL(request.url);
  const to = input.searchParams.get("to") ?? "";
  if (!/^(vlc-x-callback|infuse|outplayer):\/\//i.test(to)) {
    return json({ error: "Unsupported launch target" }, request, env, 400);
  }
  const app = /^infuse:/i.test(to) ? "Infuse" : /^outplayer:/i.test(to) ? "Outplayer" : "VLC";
  const href = to.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/"/g, "&quot;");
  const html = `<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Opening ${app}…</title>
<style>
  body{background:#0b0b0f;color:#ececf1;font-family:system-ui,-apple-system,sans-serif;display:grid;place-items:center;min-height:100vh;margin:0;text-align:center}
  a.btn{display:inline-block;margin-top:18px;padding:14px 30px;border-radius:12px;background:#fff;color:#000;font-weight:700;font-size:17px;text-decoration:none}
  p{opacity:.7;font-size:14px;max-width:340px;line-height:1.5}
</style></head>
<body><div>
  <h2 style="font-weight:600">Opening ${app}…</h2>
  <p>If nothing happens, tap the button below. Make sure ${app} is installed.</p>
  <a class="btn" href="${href}">Open in ${app}</a>
</div>
<script>setTimeout(function(){ window.location.href = ${JSON.stringify(to)}; }, 80);</script>
</body></html>`;
  return new Response(html, {
    headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" }
  });
}

function rewriteHlsPlaylist(text: string, baseUrl: URL, workerUrl: URL) {
  const headersParam = workerUrl.searchParams.get("h");
  const proxied = (rawTarget: string) => {
    const trimmed = rawTarget.trim();
    if (!trimmed || trimmed.startsWith("data:") || trimmed.startsWith("blob:")) return rawTarget;
    let absolute: URL;
    try {
      absolute = new URL(trimmed, baseUrl);
    } catch {
      return rawTarget;
    }
    if (!["http:", "https:"].includes(absolute.protocol)) return rawTarget;
    // Root-relative so the player resolves it against whichever origin served
    // this manifest (works for both the production route and wrangler dev).
    const params = new URLSearchParams();
    params.set("url", absolute.toString());
    if (headersParam) params.set("h", headersParam);
    return `/media?${params.toString()}`;
  };

  return text.split(/\r?\n/).map((line) => {
    const trimmed = line.trim();
    if (!trimmed) return line;
    if (!trimmed.startsWith("#")) return proxied(line);
    return line.replace(/URI="([^"]+)"/g, (_match, uri: string) => `URI="${proxied(uri)}"`);
  }).join("\n");
}

function decodeMediaHeaders(raw: string | null): Record<string, string> {
  if (!raw) return {};
  try {
    const decoded = JSON.parse(atob(raw.replace(/-/g, "+").replace(/_/g, "/"))) as Record<string, string>;
    return decoded && typeof decoded === "object" ? decoded : {};
  } catch {
    return {};
  }
}

function safeUrl(value: string) {
  try {
    return new URL(value);
  } catch {
    return null;
  }
}

function streamIds(item: MediaItem, season?: number, episode?: number) {
  const ids: string[] = [];
  const hasEpisode = item.mediaType === "tv" && season && episode;
  if (item.imdbId?.startsWith("tt")) {
    ids.push(hasEpisode ? `${item.imdbId}:${season}:${episode}` : item.imdbId);
  }
  ids.push(hasEpisode ? `tmdb:${item.id}:${season}:${episode}` : `tmdb:${item.id}`);
  return Array.from(new Set(ids));
}

function streamRequestTypes(addon: InstalledAddon, requestedType: "movie" | "series") {
  const aliases = requestedType === "series" ? ["series", "tv", "show"] : ["movie", "film"];
  return aliases.filter((type) => addonSupportsType(addon, type));
}

function normalizeStream(stream: RawStream, addon: InstalledAddon): StreamSource {
  const text = [stream.title, stream.name, stream.description, stream.size].filter(Boolean).join(" ");
  const url = streamUrl(stream);
  const browser = browserCompatibility(url, text);
  return {
    source: stream.name ?? stream.title ?? addon.name,
    addonName: addon.name,
    addonId: addon.id,
    quality: detectQuality(text),
    size: stream.size ?? sizeLabel(stream.sizeBytes ?? stream.behaviorHints?.videoSize ?? null),
    sizeBytes: stream.sizeBytes ?? stream.behaviorHints?.videoSize ?? null,
    url,
    infoHash: stream.infoHash ?? null,
    fileIdx: stream.fileIdx ?? null,
    behaviorHints: {
      ...(stream.behaviorHints ?? {}),
      notWebReady: !url && Boolean(stream.infoHash || stream.ytId || stream.behaviorHints?.videoHash),
      browserPlayable: browser.browserPlayable,
      iosPlayable: browser.iosPlayable,
      externalPlayerRecommended: browser.externalPlayerRecommended
    },
    subtitles: stream.subtitles ?? [],
    sources: stream.sources ?? [],
    description: stream.description ?? stream.title ?? null
  };
}

function streamUrl(stream: RawStream) {
  const candidates = [
    stream.url,
    stream.externalUrl,
    stream.behaviorHints?.directUrl,
    stream.behaviorHints?.url,
    stream.behaviorHints?.externalUrl
  ];
  for (const candidate of candidates) {
    const value = typeof candidate === "string" ? candidate.trim() : "";
    if (!value) continue;
    if (value.startsWith("//")) return `https:${value}`;
    if (/^https?:\/\//i.test(value)) return value;
  }
  return null;
}

function browserCompatibility(url: string | null, text: string) {
  const lower = `${url ?? ""} ${text}`.toLowerCase();
  const hls = lower.includes(".m3u8") || lower.includes("mpegurl");
  const mp4 = /\.(mp4|m4v|mov)(?:[?#]|$)/i.test(url ?? "");
  const unsupportedContainer = /\.(mkv|avi|wmv|flv)(?:[?#]|$)/i.test(url ?? "");
  const unsupportedAudio = /\b(dts|truehd|eac3|e-ac-3|atmos truehd)\b/i.test(text);
  const unsupportedBrowserVideo = /\b(x265|h\.265|h265|hevc|av1|dolby vision|dv)\b/i.test(text);
  return {
    browserPlayable: Boolean(url && (hls || mp4) && !unsupportedContainer && !unsupportedAudio && !unsupportedBrowserVideo),
    iosPlayable: Boolean(url && (hls || mp4) && !unsupportedContainer && !unsupportedAudio),
    externalPlayerRecommended: Boolean(url && (!hls && !mp4 || unsupportedContainer || unsupportedAudio || unsupportedBrowserVideo))
  };
}

function manifestUrlFor(addon: InstalledAddon) {
  const compatible = addon as InstalledAddon & { url?: string; transportUrl?: string };
  const raw = compatible.manifestUrl || compatible.url || compatible.transportUrl || "";
  if (!raw) return "";
  const trimmed = raw.trim();
  try {
    const url = new URL(trimmed);
    if (url.pathname.endsWith("/manifest.json")) return url.toString();
    url.pathname = `${url.pathname.replace(/\/+$/, "")}/manifest.json`;
    return url.toString();
  } catch {
    return trimmed.endsWith("/manifest.json") ? trimmed : `${trimmed.replace(/\/+$/, "")}/manifest.json`;
  }
}

function addonBaseUrl(manifestUrl: string) {
  try {
    const url = new URL(manifestUrl);
    const query = url.searchParams.toString();
    url.search = "";
    url.pathname = url.pathname.replace(/\/manifest\.json$/, "").replace(/\/+$/, "");
    return { base: url.toString().replace(/\/+$/, ""), query };
  } catch {
    const [path, query = ""] = manifestUrl.split("?");
    return { base: path.replace(/\/manifest\.json$/, "").replace(/\/+$/, ""), query };
  }
}

function supportsResource(addon: InstalledAddon, resource: string) {
  if (!Array.isArray(addon.resources) || addon.resources.length === 0) return true;
  return addon.resources
    .map((item) => typeof item === "string" ? item : item?.name)
    .filter(Boolean)
    .includes(resource);
}

function addonSupportsType(addon: InstalledAddon, requestedType: string) {
  const declared = new Set<string>();
  const add = (value: unknown) => {
    if (typeof value === "string" && value.trim()) declared.add(value.trim().toLowerCase());
  };
  addon.types?.forEach(add);
  addon.resources?.forEach((resource) => {
    if (typeof resource !== "string") resource.types?.forEach(add);
  });
  if (!declared.size) return true;
  const aliases = requestedType === "series" || requestedType === "tv" || requestedType === "show"
    ? ["series", "tv", "show"]
    : requestedType === "movie" || requestedType === "film"
      ? ["movie", "film"]
      : [requestedType];
  return aliases.some((alias) => declared.has(alias));
}

function addonSupportsId(addon: InstalledAddon, id: string) {
  const prefixes = [
    ...(addon.idPrefixes ?? []),
    ...(addon.resources ?? []).flatMap((resource) => typeof resource === "string" ? [] : resource.idPrefixes ?? [])
  ].map((prefix) => prefix.trim()).filter(Boolean);
  if (!prefixes.length) return true;
  return prefixes.some((prefix) =>
    id.toLowerCase().startsWith(prefix.toLowerCase()) ||
    (!prefix.endsWith(":") && id.toLowerCase().startsWith(`${prefix.toLowerCase()}:`))
  );
}

async function fetchWithTimeout(url: string, env: Env, addon?: InstalledAddon) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs(env, addon));
  try {
    return await fetch(url, {
      headers: {
        accept: "application/json,text/plain,*/*",
        "user-agent": userAgent()
      },
      signal: controller.signal
    });
  } finally {
    clearTimeout(timeout);
  }
}

function dedupeStreams(streams: StreamSource[]) {
  const seen = new Set<string>();
  return streams.filter((stream) => {
    const key = [
      stream.addonId,
      stream.url ?? "",
      stream.infoHash ?? "",
      stream.fileIdx ?? "",
      stream.source,
      stream.description ?? ""
    ].join("|");
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function sortStreams(streams: StreamSource[]) {
  return streams.sort((a, b) => streamScore(b) - streamScore(a));
}

function streamScore(stream: StreamSource) {
  const text = `${stream.quality ?? ""} ${stream.source} ${stream.description ?? ""}`.toLowerCase();
  let score = 0;
  if (stream.behaviorHints?.iosPlayable) score += 140;
  else if (stream.behaviorHints?.browserPlayable) score += 120;
  else if (stream.url) score += 80;
  if (stream.behaviorHints?.cached) score += 80;
  if (text.includes("2160") || text.includes("4k")) score += 60;
  if (text.includes("1080")) score += 45;
  if (text.includes("720")) score += 25;
  if (text.includes("hdr")) score += 12;
  if (text.includes("cam")) score -= 80;
  if (stream.infoHash && !stream.url) score -= 50;
  if (stream.sizeBytes) score += Math.min(25, stream.sizeBytes / 1_000_000_000);
  return score;
}

function detectQuality(value: string) {
  const text = value.toLowerCase();
  if (text.includes("2160") || text.includes("4k")) return "4K";
  if (text.includes("1080")) return "1080p";
  if (text.includes("720")) return "720p";
  if (text.includes("hdr")) return "HDR";
  return "HD";
}

function sizeLabel(bytes?: number | null) {
  if (!bytes || !Number.isFinite(bytes)) return "";
  if (bytes >= 1_000_000_000) return `${(bytes / 1_000_000_000).toFixed(1)} GB`;
  if (bytes >= 1_000_000) return `${Math.round(bytes / 1_000_000)} MB`;
  return "";
}

function srtToVtt(input: string) {
  return `WEBVTT\n\n${input.replace(/\r/g, "").replace(/(\d\d:\d\d:\d\d),(\d\d\d)/g, "$1.$2")}`;
}

async function sourceCacheKey(payload: SourcesRequest) {
  const normalized = JSON.stringify({
    item: payload.item,
    season: payload.season ?? null,
    episode: payload.episode ?? null,
    addons: (payload.addons ?? []).map((addon) => ({
      id: addon.id,
      manifestUrl: manifestUrlFor(addon),
      enabled: addon.enabled !== false
    }))
  });
  const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(normalized));
  return `sources:v2:${Array.from(new Uint8Array(hash)).map((byte) => byte.toString(16).padStart(2, "0")).join("")}`;
}

function corsHeaders(request: Request, env: Env) {
  const origin = request.headers.get("origin") ?? "";
  const allowed = (env.ALLOWED_ORIGINS ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
  const allowOrigin = allowed.includes(origin) ? origin : allowed[0] ?? "*";
  return {
    "access-control-allow-origin": allowOrigin,
    "access-control-allow-methods": "GET,POST,OPTIONS",
    "access-control-allow-headers": "content-type,authorization,range",
    "vary": "origin"
  };
}

function json(value: unknown, request: Request, env: Env, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      ...JSON_HEADERS,
      ...corsHeaders(request, env),
      "cache-control": "no-store"
    }
  });
}

function ttl(env: Env) {
  const parsed = Number(env.SOURCE_CACHE_TTL_SECONDS);
  return Number.isFinite(parsed) && parsed > 0 ? Math.round(parsed) : 180;
}

function timeoutMs(env: Env, addon?: InstalledAddon) {
  const source = addonNeedsExtendedTimeout(addon) ? env.ADDON_EXTENDED_TIMEOUT_MS : env.ADDON_TIMEOUT_MS;
  const parsed = Number(source);
  if (Number.isFinite(parsed) && parsed > 0) return Math.round(parsed);
  return addonNeedsExtendedTimeout(addon) ? 35000 : 15000;
}

function addonNeedsExtendedTimeout(addon?: InstalledAddon) {
  const text = [
    addon?.id,
    addon?.name,
    addon?.manifestUrl
  ].filter(Boolean).join(" ").toLowerCase();
  return /torrentio|torbox|real-?debrid|premiumize|debrid|mediafusion|comet|aiostream|flix-stream|flixnest|flickystream|pengu/.test(text);
}

function userAgent() {
  return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
}
