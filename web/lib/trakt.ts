import { config, hasTraktConfig } from "./config";
import { jsonRequest } from "./http";
import { loadStored, removeStored, saveStored } from "./storage";

const TRAKT_TOKEN_KEY = "arvio.web.trakt.token";
// v2: v1 stored FULL progress payloads (every season/episode — ~740KB across a
// library) and helped exhaust the localStorage quota; v2 entries are slimmed
// to the four fields Continue Watching reads.
const TRAKT_PROGRESS_CACHE_KEY = "arvio.web.trakt.progressCache.v2";
const TRAKT_PROGRESS_TTL_MS = 15 * 60 * 1000;

export interface TraktDeviceCode {
  device_code: string;
  user_code: string;
  verification_url: string;
  expires_in: number;
  interval: number;
}

export interface TraktToken {
  access_token: string;
  refresh_token: string;
  expires_at: number;
}

function normalizeToken(token: TraktToken | null): TraktToken | null {
  if (!token?.access_token) return null;
  const expiresAt = Number(token.expires_at ?? 0);
  return {
    ...token,
    expires_at: expiresAt > 0 && expiresAt < 1_000_000_000_000 ? expiresAt * 1000 : expiresAt
  };
}

export class TraktClient {
  token = normalizeToken(loadStored<TraktToken | null>(TRAKT_TOKEN_KEY, null));

  get isConnected() {
    return Boolean(this.token?.access_token);
  }

  setToken(token: TraktToken | null) {
    this.token = normalizeToken(token);
    if (this.token) saveStored(TRAKT_TOKEN_KEY, this.token);
    else removeStored(TRAKT_TOKEN_KEY);
  }

  async beginDeviceLink() {
    if (!hasTraktConfig()) throw new Error("Trakt is not configured");
    return this.trakt<TraktDeviceCode>("/oauth/device/code", {
      method: "POST",
      body: JSON.stringify({ client_id: config.traktClientId })
    });
  }

  async pollDeviceToken(code: string) {
    const response = await this.exchangeDeviceToken(code);
    this.token = {
      access_token: response.access_token,
      refresh_token: response.refresh_token,
      expires_at: Date.now() + response.expires_in * 1000
    };
    this.setToken(this.token);
  }

  private async exchangeDeviceToken(code: string) {
    type TokenResponse = { access_token: string; refresh_token: string; expires_in: number };
    // Trakt Cloudflare-blocks our Netlify backend's egress IP, so the token
    // exchange goes direct from the browser (which Trakt does not block). This
    // needs the client secret; it is already shipped in the public Android app,
    // so exposing it here is not a new disclosure.
    if (config.traktClientSecret) {
      try {
        return await jsonRequest<TokenResponse>("https://api.trakt.tv/oauth/device/token", {
          method: "POST",
          headers: { "content-type": "application/json", "trakt-api-version": "2" },
          body: JSON.stringify({ code, client_id: config.traktClientId, client_secret: config.traktClientSecret })
        });
      } catch {
        // fall through to the backend proxy
      }
    }
    return this.trakt<TokenResponse>("/oauth/device/token", {
      method: "POST",
      body: JSON.stringify({ code, client_id: config.traktClientId })
    });
  }

  async watchlist() {
    if (!this.token) return [];
    await this.refreshIfNeeded();
    return this.trakt<unknown[]>("/sync/watchlist", {
      headers: { "x-user-token": this.token.access_token }
    });
  }

  // The user's personal collection (owned/available items).
  async collection(type: "movies" | "shows") {
    if (!this.token) return [];
    await this.refreshIfNeeded();
    return this.trakt<unknown[]>(`/sync/collection/${type}`, {
      headers: { "x-user-token": this.token.access_token }
    });
  }

  // Custom Trakt lists owned by the user: [{ ids: { trakt, slug }, name, ... }].
  async userLists() {
    if (!this.token) return [];
    await this.refreshIfNeeded();
    return this.trakt<unknown[]>("/users/me/lists", {
      headers: { "x-user-token": this.token.access_token }
    });
  }

  // Items in a specific custom list (movies + shows).
  async listItems(listId: string | number) {
    if (!this.token) return [];
    await this.refreshIfNeeded();
    return this.trakt<unknown[]>(`/users/me/lists/${listId}/items/movies,shows`, {
      headers: { "x-user-token": this.token.access_token }
    });
  }

  async playback() {
    if (!this.token) return [];
    await this.refreshIfNeeded();
    return this.trakt<unknown[]>("/sync/playback", {
      headers: { "x-user-token": this.token.access_token }
    });
  }

  async watched(type: "movies" | "shows") {
    if (!this.token) return [];
    await this.refreshIfNeeded();
    const all: unknown[] = [];
    let page = 1;
    // Trakt's July 2026 API update (trakt-api discussion #775): extended=
    // progress responses are server-capped at 100 items/page (others at 250).
    // Requesting above the cap made `rows.length < limit` true on a FULL page,
    // silently stopping pagination after page 1 for large libraries.
    const limit = type === "shows" ? 100 : 250;
    const maxPages = type === "shows" ? 5 : 8;
    while (true) {
      const query = new URLSearchParams({
        page: String(page),
        limit: String(limit)
      });
      // Season/episode data is opt-in now and required for watched badges;
      // it is also the heavy variant, hence the page ceiling.
      if (type === "shows") query.set("extended", "progress");
      const rows = await this.trakt<unknown[]>(`/sync/watched/${type}?${query.toString()}`, {
        headers: { "x-user-token": this.token.access_token }
      });
      if (!rows.length) break;
      all.push(...rows);
      if (rows.length < limit || page >= maxPages) break;
      page += 1;
    }
    return all;
  }

  // `activityKey` (the show's last_watched_at) makes the persistent cache
  // activity-keyed: a show whose activity hasn't moved has IDENTICAL progress,
  // so entries stay valid for days instead of the blanket 15-minute TTL — a
  // repeat app boot then costs ~zero progress calls instead of ~120.
  async showProgress(traktShowId: number, includeSpecials = false, activityKey?: string | number) {
    if (!this.token) return null;
    await this.refreshIfNeeded();
    const cached = readProgressCache(this.token.access_token, traktShowId, includeSpecials, activityKey);
    if (cached !== undefined) return cached;
    const query = new URLSearchParams({
      hidden: "false",
      specials: String(includeSpecials),
      count_specials: String(includeSpecials)
    });
    const progress = await this.trakt<unknown>(`/shows/${traktShowId}/progress/watched?${query.toString()}`, {
      headers: { "x-user-token": this.token.access_token }
    });
    writeProgressCache(this.token.access_token, traktShowId, includeSpecials, progress, activityKey);
    return progress;
  }

  async history(type?: "movies" | "shows" | "episodes") {
    if (!this.token) return [];
    await this.refreshIfNeeded();
    const path = type ? `/sync/history/${type}` : "/sync/history";
    return this.trakt<unknown[]>(path, {
      headers: { "x-user-token": this.token.access_token }
    });
  }

  async addToWatchlist(item: TraktMediaRef) {
    if (!this.token) return;
    await this.refreshIfNeeded();
    await this.trakt("/sync/watchlist", {
      method: "POST",
      headers: { "x-user-token": this.token.access_token },
      body: JSON.stringify(this.mediaBody(item))
    });
  }

  // Mark watched on Trakt (registers in the user's history, same as the app's
  // "Mark watched"). A full-show ref (no season/episode) marks the whole show.
  async addToHistory(item: TraktMediaRef) {
    if (!this.token) return;
    await this.refreshIfNeeded();
    await this.trakt("/sync/history", {
      method: "POST",
      headers: { "x-user-token": this.token.access_token },
      body: JSON.stringify(this.mediaBody(item))
    });
  }

  async removeFromHistory(item: TraktMediaRef) {
    if (!this.token) return;
    await this.refreshIfNeeded();
    await this.trakt("/sync/history/remove", {
      method: "POST",
      headers: { "x-user-token": this.token.access_token },
      body: JSON.stringify(this.mediaBody(item))
    });
  }

  async removeFromWatchlist(item: TraktMediaRef) {
    if (!this.token) return;
    await this.refreshIfNeeded();
    await this.trakt("/sync/watchlist/remove", {
      method: "POST",
      headers: { "x-user-token": this.token.access_token },
      body: JSON.stringify(this.mediaBody(item))
    });
  }

  async scrobble(action: "start" | "pause" | "stop", item: TraktMediaRef & { progress: number }) {
    if (!this.token) return;
    await this.refreshIfNeeded();
    await this.trakt(`/scrobble/${action}`, {
      method: "POST",
      headers: { "x-user-token": this.token.access_token },
      body: JSON.stringify({ ...this.mediaBody(item), progress: Math.round(item.progress) })
    });
  }

  disconnect() {
    this.setToken(null);
  }

  private async trakt<T>(path: string, init: RequestInit = {}) {
    const url = new URL(`/api/trakt/${path.replace(/^\/+/, "")}`, window.location.origin);
    const request = {
      ...init,
      // Hard timeout: a single hanging Trakt response (throttling that never
      // answers) used to block one of the up-next workers forever — and
      // Promise.all over the workers then hung the WHOLE enriched Continue
      // Watching pipeline, so the fresh rail never landed and the instant-paint
      // cache never got written.
      signal: init.signal ?? (typeof AbortSignal.timeout === "function" ? AbortSignal.timeout(15_000) : undefined),
      headers: {
        "trakt-api-version": "2",
        "trakt-api-key": config.traktClientId,
        ...(init.headers ?? {})
      }
    } satisfies RequestInit;
    // Direct-first: api.trakt.tv serves CORS to browsers, so most calls never
    // need the Netlify proxy — each proxied call was a paid function
    // invocation (~8 per boot per user, up to ~120 on cold progress caches),
    // and Trakt intermittently blocks Netlify egress anyway. The proxy remains
    // the fallback for networks where Trakt challenges browser preflights, and
    // the primary path for oauth flows that need the backend secret.
    if (this.canUseDirectFallback(path, init)) {
      try {
        return await this.directTrakt<T>(path, request);
      } catch {
        return jsonRequest<T>(url.toString(), request);
      }
    }
    try {
      return await jsonRequest<T>(url.toString(), request);
    } catch (error) {
      if (!this.canUseDirectFallback(path, init)) throw error;
      return this.directTrakt<T>(path, request);
    }
  }

  private canUseDirectFallback(path: string, init: RequestInit) {
    if (!config.traktClientId) return false;
    const normalized = path.replace(/^\/+/, "");
    // The device-code request needs only the public client_id, so it can go
    // straight to Trakt when the Netlify proxy is Cloudflare-blocked. Token
    // exchange/refresh need the client secret (backend-only) — no direct fallback.
    if (normalized === "oauth/device/code") return true;
    if (normalized.startsWith("oauth/")) return false;
    const headers = new Headers(init.headers ?? {});
    return Boolean(headers.get("x-user-token") || headers.get("Authorization"));
  }

  private async directTrakt<T>(path: string, init: RequestInit) {
    const target = new URL(`https://api.trakt.tv/${path.replace(/^\/+/, "")}`);
    const headers = new Headers(init.headers ?? {});
    const userToken = headers.get("x-user-token");
    headers.delete("x-user-token");
    headers.set("trakt-api-version", "2");
    headers.set("trakt-api-key", config.traktClientId);
    headers.set("Accept", "application/json");
    if (userToken && !headers.has("Authorization")) headers.set("Authorization", `Bearer ${userToken}`);
    if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
    const response = await fetch(target.toString(), {
      ...init,
      headers,
      cache: "no-store",
      // Fresh timeout: the signal inherited from the proxy attempt may already
      // be (nearly) expired by the time this fallback runs.
      signal: typeof AbortSignal.timeout === "function" ? AbortSignal.timeout(15_000) : undefined
    });
    if (!response.ok) {
      const raw = await response.text().catch(() => "");
      throw new Error(raw.includes("Cloudflare") || raw.includes("<html")
        ? "Trakt is blocking this network request right now."
        : raw || `Trakt request failed with ${response.status}`);
    }
    if (response.status === 204) return undefined as T;
    return (await response.json()) as T;
  }

  private async refreshIfNeeded() {
    if (!this.token?.refresh_token) return;
    if (this.token.expires_at && this.token.expires_at - Date.now() > 300000) return;
    const body = {
      grant_type: "refresh_token",
      refresh_token: this.token.refresh_token,
      client_id: config.traktClientId,
      client_secret: config.traktClientSecret,
      redirect_uri: "urn:ietf:wg:oauth:2.0:oob"
    };
    type RefreshResponse = { access_token: string; refresh_token?: string; expires_in: number };
    let response: RefreshResponse;
    // Direct to Trakt (Netlify egress is Cloudflare-blocked); backend proxy is
    // the fallback for environments without the client secret configured.
    if (config.traktClientSecret) {
      response = await jsonRequest<RefreshResponse>("https://api.trakt.tv/oauth/token", {
        method: "POST",
        headers: { "content-type": "application/json", "trakt-api-version": "2" },
        body: JSON.stringify(body)
      }).catch(() => this.trakt<RefreshResponse>("/oauth/token", {
        method: "POST",
        body: JSON.stringify({ grant_type: "refresh_token", refresh_token: this.token!.refresh_token })
      }));
    } else {
      response = await this.trakt<RefreshResponse>("/oauth/token", {
        method: "POST",
        body: JSON.stringify({ grant_type: "refresh_token", refresh_token: this.token.refresh_token })
      });
    }
    this.setToken({
      access_token: response.access_token,
      refresh_token: response.refresh_token ?? this.token.refresh_token,
      expires_at: Date.now() + response.expires_in * 1000
    });
  }

  private mediaBody(item: TraktMediaRef) {
    if (item.mediaType === "tv") {
      const show = { ids: { tmdb: item.tmdbId } };
      if (item.season && item.episode) {
        return { shows: [{ ...show, seasons: [{ number: item.season, episodes: [{ number: item.episode }] }] }] };
      }
      return { shows: [show] };
    }
    return { movies: [{ ids: { tmdb: item.tmdbId } }] };
  }
}

interface TraktMediaRef {
  mediaType: "movie" | "tv";
  tmdbId: number;
  season?: number | null;
  episode?: number | null;
}

type ProgressCacheEntry = { at: number; value: unknown };

// Activity-keyed entries (key includes the show's last_watched_at) stay valid
// for a week — progress can only change when activity changes, and a changed
// activity produces a NEW key so stale entries are never read, just evicted by
// the size cap. Legacy keys without an activity component keep the short TTL.
const TRAKT_PROGRESS_ACTIVITY_TTL_MS = 7 * 24 * 60 * 60 * 1000;

function progressCacheKey(token: string, traktShowId: number, includeSpecials: boolean, activityKey?: string | number) {
  const activity = activityKey !== undefined && activityKey !== null && activityKey !== "" ? `:${activityKey}` : "";
  return `${token.slice(0, 12)}:${traktShowId}:${includeSpecials ? "specials" : "regular"}${activity}`;
}

function readProgressCache(token: string, traktShowId: number, includeSpecials: boolean, activityKey?: string | number) {
  const cache = loadStored<Record<string, ProgressCacheEntry>>(TRAKT_PROGRESS_CACHE_KEY, {});
  const entry = cache[progressCacheKey(token, traktShowId, includeSpecials, activityKey)];
  const ttl = activityKey !== undefined ? TRAKT_PROGRESS_ACTIVITY_TTL_MS : TRAKT_PROGRESS_TTL_MS;
  if (!entry || Date.now() - entry.at > ttl) return undefined;
  return entry.value;
}

// Persist only the fields Continue Watching actually reads
// (traktUpNextToMedia): a full progress payload lists every season+episode —
// hundreds of KB across a library, which once filled the ~5MB localStorage
// quota and silently broke every cache write in the app.
function slimProgress(value: unknown): unknown {
  if (!value || typeof value !== "object") return value;
  const progress = value as {
    aired?: number;
    completed?: number;
    last_watched_at?: string;
    next_episode?: { season?: number; number?: number; title?: string } | null;
  };
  return {
    aired: progress.aired,
    completed: progress.completed,
    last_watched_at: progress.last_watched_at,
    next_episode: progress.next_episode
      ? { season: progress.next_episode.season, number: progress.next_episode.number, title: progress.next_episode.title }
      : null
  };
}

function writeProgressCache(token: string, traktShowId: number, includeSpecials: boolean, value: unknown, activityKey?: string | number) {
  const cache = loadStored<Record<string, ProgressCacheEntry>>(TRAKT_PROGRESS_CACHE_KEY, {});
  const next = {
    ...cache,
    [progressCacheKey(token, traktShowId, includeSpecials, activityKey)]: { at: Date.now(), value: slimProgress(value) }
  };
  // Cap must exceed the up-next scan width (120 shows) or entries churn out
  // before the next boot can reuse them.
  const entries = Object.entries(next)
    .sort((a, b) => b[1].at - a[1].at)
    .slice(0, 240);
  saveStored(TRAKT_PROGRESS_CACHE_KEY, Object.fromEntries(entries));
}
