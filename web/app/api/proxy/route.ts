import { NextRequest, NextResponse } from "next/server";

const BLOCKED_HOSTS = new Set(["localhost", "127.0.0.1", "::1", "0.0.0.0"]);
const PROXY_TIMEOUT_MS = 18_000;
const ALLOW_MEDIA_PROXY =
  process.env.ALLOW_NETLIFY_MEDIA_PROXY === "true" ||
  process.env.NEXT_PUBLIC_ALLOW_NETLIFY_MEDIA_PROXY === "true";

export async function GET(request: NextRequest) {
  const input = new URL(request.url);
  const raw = input.searchParams.get("url");
  if (!raw) return NextResponse.json({ error: "Missing url" }, { status: 400 });

  let target: URL;
  try {
    target = new URL(raw);
  } catch {
    return NextResponse.json({ error: "Invalid url" }, { status: 400 });
  }

  if (!["http:", "https:"].includes(target.protocol) || BLOCKED_HOSTS.has(target.hostname)) {
    return NextResponse.json({ error: "Blocked proxy target" }, { status: 400 });
  }
  const rewriteMode = input.searchParams.get("rewrite");
  const playlistOnlyRequest =
    (rewriteMode === "0" || rewriteMode === "direct" || rewriteMode === "worker") &&
    !request.headers.has("range") &&
    isLikelyPlaylistTarget(target);
  if (!ALLOW_MEDIA_PROXY && !playlistOnlyRequest && isLikelyMediaRequest(target, request)) {
    return NextResponse.json(
      { error: "Media proxy disabled to protect hosting bandwidth. Use direct playback or an external player." },
      { status: 403 }
    );
  }

  const forwardedHeaders = decodeHeaders(input.searchParams.get("headers")) ?? {};
  const range = request.headers.get("range");
  if (range) forwardedHeaders.range = range;
  const response = await fetchWithTimeout(target, {
    headers: forwardedHeaders,
    cache: "no-store",
    redirect: "follow"
  });

  const contentType = response.headers.get("content-type") ?? "application/octet-stream";
  if (rewriteMode !== "0" && shouldRewritePlaylist(target, contentType)) {
    const text = await response.text();
    const rewritten = rewriteMode === "worker"
      ? rewritePlaylistToWorker(text, target, input.searchParams.get("headers"))
      : rewriteMode === "direct" ? rewritePlaylistToAbsolute(text, target) : rewritePlaylist(text, target, request);
    const headers = new Headers();
    headers.set("content-type", contentType.includes("mpegurl") ? contentType : "application/vnd.apple.mpegurl");
    headers.set("cache-control", rewriteMode === "direct" ? "private, max-age=20" : "private, max-age=30, stale-while-revalidate=120");
    headers.set("access-control-allow-origin", "*");
    return new NextResponse(rewritten, { status: response.status, headers });
  }

  const headers = new Headers();
  headers.set("content-type", contentType);
  headers.set("cache-control", playlistOnlyRequest ? "private, max-age=3600, stale-while-revalidate=86400" : "no-store");
  headers.set("access-control-allow-origin", "*");
  headers.set("accept-ranges", response.headers.get("accept-ranges") ?? "bytes");
  const contentLength = response.headers.get("content-length");
  const contentRange = response.headers.get("content-range");
  if (contentLength) headers.set("content-length", contentLength);
  if (contentRange) headers.set("content-range", contentRange);

  return new NextResponse(response.body, { status: response.status, headers });
}

export async function POST(request: NextRequest) {
  const input = new URL(request.url);
  const raw = input.searchParams.get("url");
  if (!raw) return NextResponse.json({ error: "Missing url" }, { status: 400 });

  let target: URL;
  try {
    target = new URL(raw);
  } catch {
    return NextResponse.json({ error: "Invalid url" }, { status: 400 });
  }
  if (!["http:", "https:"].includes(target.protocol) || BLOCKED_HOSTS.has(target.hostname)) {
    return NextResponse.json({ error: "Blocked proxy target" }, { status: 400 });
  }
  if (!ALLOW_MEDIA_PROXY && isLikelyMediaRequest(target, request)) {
    return NextResponse.json(
      { error: "Media proxy disabled to protect hosting bandwidth. Use direct playback or an external player." },
      { status: 403 }
    );
  }

  const forwardedHeaders = decodeHeaders(input.searchParams.get("headers")) ?? {};
  const body = await request.text();
  const response = await fetchWithTimeout(target, {
    method: "POST",
    headers: { "content-type": "application/json", ...forwardedHeaders },
    body,
    cache: "no-store",
    redirect: "follow"
  });

  const headers = new Headers();
  headers.set("content-type", response.headers.get("content-type") ?? "application/json");
  headers.set("cache-control", "no-store");
  headers.set("access-control-allow-origin", "*");
  return new NextResponse(response.body, { status: response.status, headers });
}

function decodeHeaders(raw: string | null) {
  if (!raw) return undefined;
  try {
    return JSON.parse(Buffer.from(raw, "base64").toString("utf8")) as Record<string, string>;
  } catch {
    return undefined;
  }
}

function shouldRewritePlaylist(target: URL, contentType: string) {
  const lowerType = contentType.toLowerCase();
  return (
    lowerType.includes("mpegurl") ||
    lowerType.includes("x-mpegurl") ||
    isLikelyPlaylistTarget(target)
  );
}

function isLikelyPlaylistTarget(target: URL) {
  const lowerPath = target.pathname.toLowerCase();
  const type = target.searchParams.get("type")?.toLowerCase();
  return (
    lowerPath.endsWith(".m3u") ||
    lowerPath.endsWith(".m3u8") ||
    lowerPath.endsWith("/get.php") ||
    type === "m3u" ||
    type === "m3u_plus"
  );
}

function isLikelyMediaRequest(target: URL, request: NextRequest) {
  if (request.headers.has("range")) return true;
  const lowerPath = target.pathname.toLowerCase();
  return /\.(m3u8|mpd|mp4|m4v|mov|mkv|webm|avi|ts|m2ts|m4s|mp3|aac|ac3|eac3|flac|wav)(?:$|[?#])/i.test(lowerPath);
}

function rewritePlaylist(text: string, baseUrl: URL, request: NextRequest) {
  const rewriteUrl = (raw: string) => {
    const trimmed = raw.trim();
    if (!trimmed || trimmed.startsWith("data:") || trimmed.startsWith("blob:")) return raw;
    let absolute: URL;
    try {
      absolute = new URL(trimmed, baseUrl);
    } catch {
      return raw;
    }
    if (!["http:", "https:"].includes(absolute.protocol)) return raw;
    const proxied = new URL("/api/proxy", request.url);
    proxied.searchParams.set("url", absolute.toString());
    return proxied.toString();
  };

  return text.split(/\r?\n/).map((line) => {
    const trimmed = line.trim();
    if (!trimmed) return line;
    if (!trimmed.startsWith("#")) return rewriteUrl(line);
    return line.replace(/URI="([^"]+)"/g, (_match, uri: string) => `URI="${rewriteUrl(uri)}"`);
  }).join("\n");
}

function rewritePlaylistToWorker(text: string, baseUrl: URL, headersParam: string | null) {
  // Hybrid hop for providers that block Cloudflare-originated fetches (e.g. CF
  // error 1003 on the playlist host): this Netlify function fetches the small
  // manifest, while heavy segment traffic is rewritten to the resolver worker.
  const resolverUrl = (process.env.NEXT_PUBLIC_ARVIO_RESOLVER_URL ?? "").replace(/\/+$/, "");
  if (!resolverUrl.startsWith("http")) return rewritePlaylistToAbsolute(text, baseUrl);
  const workerTarget = (raw: string) => {
    const trimmed = raw.trim();
    if (!trimmed || trimmed.startsWith("data:") || trimmed.startsWith("blob:")) return raw;
    let absolute: URL;
    try {
      absolute = new URL(trimmed, baseUrl);
    } catch {
      return raw;
    }
    if (!["http:", "https:"].includes(absolute.protocol)) return raw;
    if (/\.m3u8?(?:$|[?#])/i.test(absolute.pathname)) {
      // Child playlists may live on the same CF-blocked host as the master;
      // keep them on this route (root-relative resolves against the app origin).
      const params = new URLSearchParams();
      params.set("url", absolute.toString());
      if (headersParam) params.set("headers", headersParam);
      params.set("rewrite", "worker");
      return `/api/proxy?${params.toString()}`;
    }
    const proxied = new URL(`${resolverUrl}/media`);
    proxied.searchParams.set("url", absolute.toString());
    if (headersParam) proxied.searchParams.set("h", headersParam);
    return proxied.toString();
  };

  return text.split(/\r?\n/).map((line) => {
    const trimmed = line.trim();
    if (!trimmed) return line;
    if (!trimmed.startsWith("#")) return workerTarget(line);
    return line.replace(/URI="([^"]+)"/g, (_match, uri: string) => `URI="${workerTarget(uri)}"`);
  }).join("\n");
}

function rewritePlaylistToAbsolute(text: string, baseUrl: URL) {
  const absoluteUrl = (raw: string) => {
    const trimmed = raw.trim();
    if (!trimmed || trimmed.startsWith("data:") || trimmed.startsWith("blob:")) return raw;
    try {
      const absolute = new URL(trimmed, baseUrl);
      return ["http:", "https:"].includes(absolute.protocol) ? absolute.toString() : raw;
    } catch {
      return raw;
    }
  };

  return text.split(/\r?\n/).map((line) => {
    const trimmed = line.trim();
    if (!trimmed) return line;
    if (!trimmed.startsWith("#")) return absoluteUrl(line);
    return line.replace(/URI="([^"]+)"/g, (_match, uri: string) => `URI="${absoluteUrl(uri)}"`);
  }).join("\n");
}

async function fetchWithTimeout(target: URL, init: RequestInit) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), PROXY_TIMEOUT_MS);
  try {
    return await fetch(target, { ...init, signal: controller.signal });
  } catch (error) {
    if (error instanceof Error && error.name === "AbortError") {
      return NextResponse.json({ error: "Proxy target timed out" }, { status: 504 });
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}
