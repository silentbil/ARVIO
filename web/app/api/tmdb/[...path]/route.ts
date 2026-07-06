import { NextRequest, NextResponse } from "next/server";

function envValue(value: string | undefined, fallback = "") {
  return value && !value.startsWith("$") ? value : fallback;
}

export async function GET(request: NextRequest, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  const netlifyBackendUrl = (
    process.env.NEXT_PUBLIC_NETLIFY_BACKEND_URL ??
    process.env.NETLIFY_BACKEND_URL ??
    "https://auth.arvio.tv/.netlify/functions"
  ).replace(/\/+$/, "");
  const appAnonKey = envValue(process.env.NEXT_PUBLIC_ARVIO_APP_ANON_KEY, process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY ?? "");
  const tmdbKey = process.env.TMDB_API_KEY ?? "";
  const input = new URL(request.url);

  let target: URL;
  const usesNetlifyProxy = netlifyBackendUrl.startsWith("https://") && appAnonKey.length > 40;
  if (usesNetlifyProxy) {
    target = new URL(`${netlifyBackendUrl}/tmdb-proxy`);
    target.searchParams.set("path", `/${path.join("/")}`);
    input.searchParams.forEach((value, key) => target.searchParams.set(key, value));
    // cv busts the Next data cache — v2 invalidates entries poisoned by the
    // query-less CDN cache-key bug (fixed upstream with Netlify-Vary: query).
    target.searchParams.set("cv", "2");
  } else if (tmdbKey) {
    target = new URL(`https://api.themoviedb.org/3/${path.join("/")}`);
    input.searchParams.forEach((value, key) => target.searchParams.set(key, value));
    target.searchParams.set("api_key", tmdbKey);
  } else {
    return NextResponse.json({ error: "TMDB proxy is not configured" }, { status: 500 });
  }

  let response: Response | null = null;
  try {
    response = await fetch(target, {
      headers: usesNetlifyProxy
        ? {
            apikey: appAnonKey,
            Authorization: `Bearer ${appAnonKey}`
          }
        : undefined,
      next: { revalidate: 86400 },
      signal: AbortSignal.timeout(8000)
    });
  } catch {
    response = null;
  }

  if ((!response || !response.ok) && usesNetlifyProxy && tmdbKey) {
    const direct = new URL(`https://api.themoviedb.org/3/${path.join("/")}`);
    input.searchParams.forEach((value, key) => direct.searchParams.set(key, value));
    direct.searchParams.set("api_key", tmdbKey);
    try {
      response = await fetch(direct, { next: { revalidate: 86400 }, signal: AbortSignal.timeout(10000) });
    } catch {
      response = null;
    }
  }

  if (!response) {
    return NextResponse.json({ error: "TMDB upstream unreachable" }, { status: 502 });
  }

  const headers = new Headers();
  headers.set("content-type", response.headers.get("content-type") ?? "application/json");
  // Short browser cache; long CDN cache. `durable` opts this response into
  // Netlify's global cross-region cache so one upstream fetch serves users
  // worldwide (not just per-edge-PoP) — the key scale lever. Without durable,
  // a cold PoP re-invokes the function; with it, function hits stay flat as the
  // user base grows.
  headers.set("cache-control", "public, max-age=300, s-maxage=21600, stale-while-revalidate=21600");
  headers.set("netlify-cdn-cache-control", "public, durable, max-age=21600, stale-while-revalidate=21600");
  headers.set("cdn-cache-control", "public, max-age=21600, stale-while-revalidate=21600");
  // CRITICAL: cache key must vary by query string (else every discover/search
  // variant collapses into one entry) but MUST NOT vary by the request-specific
  // headers Next.js injects (x-nextjs-data|rsc|…) — those forced the durable
  // cache to bypass. Setting query-only Vary is what lets durable engage.
  headers.set("netlify-vary", "query");
  headers.set("vary", "Accept-Encoding");

  return new NextResponse(response.body, {
    status: response.status,
    headers
  });
}
