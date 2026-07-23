"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { getStreams, getStreamsProgressive, installAddon as installAddonManifest, loadLocalAddons, normalizeAddons, saveLocalAddons } from "./addons";
import { AuthClient, SESSION_KEY, decodeJwtPayload } from "./auth";
import { getAuthPortalUrl } from "./config";
import { defaultCatalogs, mergeCatalogs } from "./catalogs";
import { getContinueWatching, pullCloudPayload, pullCloudProfiles, pullCloudTraktToken, pullCloudWatchlist, saveCloudAddons, saveCloudProfiles, saveCloudSettings, saveCloudTraktToken } from "./cloud";
import { cachedDebridDirectUrl, parseDebridStream, resolveDebridDirectUrl, resolveTranscodeStream } from "./debrid";
import { createPendingExternalPlayback } from "./externalPlayback";
import { externalLaunchMode, openExternalPlayer } from "./externalPlayers";
import { streamPlayability } from "./streamCompatibility";
import { loadHomeServerRows } from "./homeserver";
import { buildXtreamCatchupUrl, loadIptvGuideForChannels, loadIptvSnapshot, loadPlaylists, savePlaylists } from "./iptv";
import { dedupeMedia, historyToItem, hydrateTraktItems, traktItemToMedia, traktPlaybackToMedia, traktUpNextToMedia } from "./mappers";
import { loadStored, purgeLegacyStorage, removeStored, saveStored } from "./storage";
import { getDetails, loadCatalog, searchMedia } from "./tmdb";
import { TraktClient, type TraktDeviceCode } from "./trakt";
import type {
  AppSettings,
  AuthSession,
  Category,
  InstalledAddon,
  IptvChannel,
  IptvPlaylistEntry,
  IptvProgram,
  CatalogConfig,
  IptvSnapshot,
  MediaItem,
  NavSection,
  Profile,
  StreamSource
} from "./types";

// Reclaim space from superseded cache keys before anything reads or writes —
// once localStorage is full every save fails silently, which starved the
// Continue Watching seed and Trakt progress caches on long-lived installs.
purgeLegacyStorage();

export const authClient = new AuthClient();
export const traktClient = new TraktClient();

const settingsKey = "arvio.web.settings";
const PROFILES_KEY = "arvio.web.profiles";
const ACTIVE_PROFILE_KEY = "arvio.web.activeProfileId";
const AVATAR_IMAGES_KEY = "arvio.web.avatarImages";
// Which account the locally-cached profiles belong to. Profiles live in a single
// browser-global localStorage key, so without this a second account signing in
// on the same browser would see the FIRST account's profiles (they persist until
// the cloud pull replaces them — and a brand-new account has no cloud profiles to
// replace them with). We stamp the owner email and ignore cached profiles that
// belong to a different account.
const PROFILES_OWNER_KEY = "arvio.web.profilesOwner";

function currentAccountEmail(): string {
  return (authClient.session?.email ?? "").trim().toLowerCase();
}

// Cached profiles are only trusted when they belong to the signed-in account
// (or when signed out, where local-only profiles are expected).
function localProfilesMatchAccount(): boolean {
  const owner = (loadStored<string | null>(PROFILES_OWNER_KEY, null) ?? "").trim().toLowerCase();
  const email = currentAccountEmail();
  if (!email) return true; // signed out: local profiles are fine
  return owner === email;
}

// Instant-paint caches for Continue Watching / Watchlist. The TTL is deliberately
// long: a stale list is strictly better than a blank rail (the fresh fetch
// replaces it seconds later). The old 24h TTL left the rail blank on the first
// open of the day whenever the previous enriched refresh was more than a day ago.
const LIST_CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000;

function cwCacheKeyFor(profileId: string | null | undefined) {
  return `arvio.web.cw.v2:${profileId ?? "no-profile"}`;
}

function watchlistCacheKeyFor(profileId: string | null | undefined) {
  return `arvio.web.watchlist.v1:${profileId ?? "no-profile"}`;
}

function readCachedList(key: string): MediaItem[] {
  const cached = loadStored<{ at: number; items: MediaItem[] } | null>(key, null);
  return cached?.items?.length && Date.now() - cached.at < LIST_CACHE_TTL_MS ? cached.items : [];
}

// Cache-only representation of a media item: cast/related are the bulk of a
// hydrated item (tens of KB each) and the rails never render them — details
// refetches both anyway. Full items once pushed localStorage past its ~5MB
// quota, after which EVERY cache write failed silently.
function slimCacheItem(item: MediaItem): MediaItem {
  const { cast, related, ...slim } = item;
  return slim;
}

function saveCachedList(key: string, items: MediaItem[], max: number) {
  try {
    saveStored(key, { at: Date.now(), items: items.slice(0, max).map(slimCacheItem) });
  } catch {
    // Never let a cache write break the refresh.
  }
}

export type AppView = "profiles" | "login" | "app";

function randomProfileColor() {
  const colors = [0xffe50914, 0xff1db954, 0xff3b82f6, 0xfff59e0b, 0xff8b5cf6, 0xffec4899, 0xff14b8a6, 0xff6366f1];
  return colors[Math.floor(Math.random() * colors.length)];
}

function makeProfile(name: string, avatarColor: number, avatarId = 0): Profile {
  const now = Date.now();
  return {
    id: (globalThis.crypto?.randomUUID?.() ?? `p_${now}_${Math.floor(Math.random() * 1e6)}`),
    name,
    avatarColor,
    avatarId,
    avatarImageVersion: 0,
    isKidsProfile: false,
    pin: null,
    isLocked: false,
    createdAt: now,
    lastUsedAt: now
  };
}

export const defaultSettings: AppSettings = {
  autoPlayNext: true,
  autoPlaySingleSource: false,
  autoPlayMinQuality: "any",
  frameRateMatchingMode: "off",
  trailerAutoPlay: true,
  trailerSound: false,
  trailerDelaySeconds: 2,
  trailerInCards: true,
  volumeBoostDb: 0,
  includeSpecials: false,
  qualityFilterPreset: "off",
  qualityFilters: [],
  language: "en-US",
  defaultSubtitle: "en",
  secondarySubtitle: "",
  audioLanguage: "",
  subtitleSize: 100,
  subtitleColor: "#ffffff",
  subtitleColorName: "White",
  subtitleOffsetMs: 0,
  subtitleOffset: "bottom",
  subtitleStyle: "outline",
  subtitleStylized: false,
  filterSubtitlesByLanguage: false,
  removeHearingImpaired: true,
  aiSubtitlesEnabled: false,
  aiSubtitleModel: "off",
  aiAutoSelect: false,
  aiApiKey: "",
  defaultPlayer: "browser",
  cardLayoutMode: "landscape",
  deviceModeOverride: "auto",
  oledBlack: false,
  clockFormat: "24h",
  showBudget: true,
  smoothScrolling: true,
  spoilerBlur: false,
  accentColor: "arctic",
  dnsProvider: "system",
  showLoadingStats: false,
  customUserAgent: "",
  torrServerBaseUrl: "",
  skipProfileSelection: false,
  cardDensity: "comfortable",
  catalogs: defaultCatalogs,
  hiddenCatalogIds: [],
  disabledAddonIds: [],
  homeServers: [],
  iptvPlaylists: [],
  iptvStalkerUrl: "",
  iptvStalkerMac: "",
  favoriteChannelIds: [],
  favoriteGroupIds: [],
  hiddenGroupIds: [],
  groupOrder: []
};

const emptyIptv: IptvSnapshot = {
  channels: [],
  grouped: {},
  nowNext: {},
  favoriteGroups: [],
  favoriteChannels: [],
  hiddenGroups: [],
  groupOrder: [],
  playlistWarnings: [],
  epgWarning: undefined,
  loadedAt: 0
};

function mediaWatchKey(item: MediaItem, seasonNumber?: number | null, episodeNumber?: number | null) {
  if (item.mediaType === "movie") return `movie:${item.id}`;
  const season = seasonNumber ?? item.seasonNumber ?? null;
  const episode = episodeNumber ?? item.episodeNumber ?? null;
  if (season && episode) return `tv:${item.id}:${season}:${episode}`;
  return `tv:${item.id}`;
}

function traktWatchedKeys(movies: unknown[], shows: unknown[]) {
  const keys = new Set<string>();
  movies.forEach((raw) => {
    const item = raw as { movie?: { ids?: { tmdb?: number } } };
    const tmdb = item.movie?.ids?.tmdb;
    if (tmdb) keys.add(`movie:${tmdb}`);
  });
  shows.forEach((raw) => {
    const item = raw as {
      show?: { ids?: { tmdb?: number }; aired_episodes?: number };
      seasons?: Array<{ number?: number; episodes?: Array<{ number?: number }> }>;
    };
    const tmdb = item.show?.ids?.tmdb;
    if (!tmdb) return;
    let watchedEpisodes = 0;
    item.seasons?.forEach((season) => {
      const seasonNumber = season.number;
      // Specials (season 0) don't count toward aired_episodes.
      if (seasonNumber === undefined || seasonNumber === null) return;
      season.episodes?.forEach((episode) => {
        if (!episode.number) return;
        keys.add(`tv:${tmdb}:${seasonNumber}:${episode.number}`);
        if (seasonNumber > 0) watchedEpisodes += 1;
      });
    });
    // Trakt lists a show here after a single played episode; only badge the
    // whole show as watched when every aired episode has been seen.
    const aired = item.show?.aired_episodes;
    if (typeof aired === "number" && aired > 0 && watchedEpisodes >= aired) {
      keys.add(`tv:${tmdb}`);
    }
  });
  return keys;
}

function filterWatchedContinueWatching(items: MediaItem[], watchedKeys: Set<string>) {
  if (!watchedKeys.size) return items;
  return items.filter((item) => {
    const key = mediaWatchKey(item);
    return !key || !watchedKeys.has(key);
  });
}

function isMediaWatched(item: MediaItem, watchedKeys: Set<string>, seasonNumber?: number | null, episodeNumber?: number | null) {
  if (item.isWatched) return true;
  const key = mediaWatchKey(item, seasonNumber, episodeNumber);
  // An episode counts as watched only via its own tv:id:season:episode key —
  // never via the show-level key, which now means "entire show completed".
  return Boolean(key && watchedKeys.has(key));
}

function isPausedPlaybackItem(item: MediaItem) {
  // Match the Android app: in-progress items are 3%–90% watched.
  const progress = item.progress ?? 0;
  return progress >= 3 && progress < 90;
}

function traktActivityTime(raw: unknown) {
  const item = raw as { last_watched_at?: string; last_updated_at?: string; show?: { title?: string } };
  return Date.parse(item.last_watched_at ?? item.last_updated_at ?? "") || 0;
}

// Per-show progress responses cached by show + last activity: a show whose
// last_watched_at hasn't moved has unchanged progress, so refreshes after the
// first cost zero Trakt calls for it. Keeps us far away from Trakt's rate
// limits (their July 2026 API update made bursts much more failure-prone).
const showProgressCache = new Map<string, unknown>();

async function loadTraktUpNext(watchedShowsRows: unknown[], includeSpecials: boolean) {
  // Fetch per-show progress for the whole watched-shows list (newest-activity
  // first) so Continue Watching surfaces every show with an unwatched next
  // episode — not just the most recent handful. The activity-keyed cache means
  // repeat refreshes only hit shows whose last_watched_at actually changed, and
  // concurrency is throttled so this never becomes the old 48-parallel burst.
  const watchedShows = watchedShowsRows
    .slice()
    .sort((a, b) => traktActivityTime(b) - traktActivityTime(a))
    .slice(0, 120);
  const results: Array<MediaItem | null> = new Array(watchedShows.length).fill(null);
  let cursor = 0;
  // Distinguishes "progress fetch failed" (rate-limited/blocked) from "show has
  // no next episode" — an empty result with failures present is a partial
  // outage, not an empty Continue Watching.
  let fetchFailures = 0;
  const workers = Array.from({ length: Math.min(8, watchedShows.length) }, async () => {
    while (cursor < watchedShows.length) {
      const index = cursor;
      cursor += 1;
      const watched = watchedShows[index];
      const row = watched as { show?: { ids?: { trakt?: number } } };
      const traktId = row.show?.ids?.trakt;
      if (!traktId) continue;
      const activityAt = traktActivityTime(watched);
      const cacheKey = `${traktId}:${activityAt}:${includeSpecials}`;
      let progress: unknown = showProgressCache.get(cacheKey) ?? null;
      if (progress === undefined || progress === null) {
        // activityAt keys the persistent cache: unchanged activity = identical
        // progress, so repeat boots read from localStorage instead of firing
        // ~120 Trakt calls — the difference between CW enriching in seconds vs
        // half a minute (and the reason the CW cache never got written on
        // devices where sessions were shorter than the old pipeline).
        progress = await traktClient.showProgress(traktId, includeSpecials, activityAt).catch(() => null);
        if (progress) {
          showProgressCache.set(cacheKey, progress);
          if (showProgressCache.size > 200) {
            const oldest = showProgressCache.keys().next().value;
            if (oldest) showProgressCache.delete(oldest);
          }
        } else {
          fetchFailures += 1;
        }
      }
      results[index] = traktUpNextToMedia(watched, progress);
    }
  });
  await Promise.all(workers);
  const items = results
    .filter((item): item is MediaItem => Boolean(item))
    .filter((item) => includeSpecials || item.seasonNumber !== 0);
  return { items, fetchFailures };
}

function mergeTraktWithLocalResume(traktItems: MediaItem[], localItems: MediaItem[]) {
  if (!localItems.length) return traktItems;
  const localByEpisode = new Map(localItems.map((item) => [
    `${item.mediaType}:${item.id}:${item.seasonNumber ?? ""}:${item.episodeNumber ?? ""}`,
    item
  ]));
  return traktItems.map((item) => {
    const local = localByEpisode.get(`${item.mediaType}:${item.id}:${item.seasonNumber ?? ""}:${item.episodeNumber ?? ""}`);
    if (!local) return item;
    return {
      ...item,
      image: item.image || local.image,
      backdrop: item.backdrop || local.backdrop,
      episodeTitle: item.episodeTitle ?? local.episodeTitle ?? null,
      progress: Math.max(item.progress ?? 0, local.progress ?? 0),
      timeRemainingLabel: local.timeRemainingLabel ?? item.timeRemainingLabel ?? null
    };
  });
}

async function hydrateContinueWatchingItems(items: MediaItem[]) {
  // Throttled pool (not one big Promise.all): 50 parallel TMDB calls at startup
  // starved user-initiated fetches (opening a details page mid-boot timed out
  // and rendered without seasons/cast).
  const source = items.slice(0, 50);
  const hydrated: MediaItem[] = new Array(source.length);
  let cursor = 0;
  const workers = Array.from({ length: Math.min(10, source.length) }, async () => {
    while (cursor < source.length) {
      const index = cursor;
      cursor += 1;
      const item = source[index];
      const details = await getDetails(item).catch(() => item);
      hydrated[index] = {
        ...details,
        ...item,
        image: item.image || details.image,
        backdrop: item.backdrop || details.backdrop,
        overview: details.overview || item.overview,
        rating: details.rating || item.rating,
        duration: details.duration || item.duration
      };
    }
  });
  await Promise.all(workers);
  return hydrated;
}

function sameSettings(a: AppSettings, b: AppSettings) {
  return JSON.stringify(a) === JSON.stringify(b);
}

export interface AppStore {
  view: AppView;
  cloudLoginRequired: boolean;
  profiles: Profile[];
  activeProfile: Profile | null;
  avatarImages: Record<string, string>;
  manageMode: boolean;
  setManageMode: (value: boolean) => void;
  selectProfile: (profile: Profile) => Promise<void>;
  createProfile: (name: string, avatarColor: number, avatarId: number) => Promise<void>;
  updateProfile: (profile: Profile) => Promise<void>;
  deleteProfile: (id: string) => Promise<void>;
  switchProfile: () => void;
  goToLogin: () => void;
  backToProfiles: () => void;

  section: NavSection;
  setSection: (section: NavSection) => void;
  categories: Category[];
  catalogConfigs: CatalogConfig[];
  loadCatalogRow: (catalog: CatalogConfig) => Promise<Category | null>;
  homeServerRows: Category[];
  continueWatching: MediaItem[];
  watchlist: MediaItem[];
  isWatched: (item: MediaItem, seasonNumber?: number | null, episodeNumber?: number | null) => boolean;
  markWatchedLocally: (item: { mediaType: MediaItem["mediaType"]; id: number; season?: number | null; episode?: number | null }, watched?: boolean) => void;
  hero: MediaItem | null;
  setHeroPreview: (item: MediaItem | null) => void;
  selected: MediaItem | null;
  streams: StreamSource[];
  selectedEpisode: { season: number; episode: number } | null;
  loadEpisodeStreams: (item: MediaItem, season: number, episode: number) => Promise<StreamSource[]>;
  advanceEpisode: () => Promise<boolean>;
  activeStream: StreamSource | null;
  activeChannel: IptvChannel | null;
  addons: InstalledAddon[];
  iptvSnapshot: IptvSnapshot;
  query: string;
  setQuery: (value: string) => void;
  results: MediaItem[];
  settings: AppSettings;
  setSettings: (next: AppSettings) => void;
  updateSettings: (patch: Partial<AppSettings>) => void;
  auth: AuthSession | null;
  traktConnected: boolean;
  deviceCode: TraktDeviceCode | null;
  busy: string;
  toast: string | null;
  setToast: (value: string | null) => void;

  refreshData: (profileIdOverride?: string | null) => Promise<void>;
  refreshIptv: () => Promise<void>;
  loadIptvGuide: (channels: IptvChannel[]) => Promise<void>;
  openDetails: (item: MediaItem) => Promise<void>;
  closeDetails: () => void;
  playStream: (stream: StreamSource, options?: { forceTranscode?: boolean; forceRemux?: boolean }) => void;
  playTrailer: (item: MediaItem) => Promise<void>;
  playChannel: (channel: IptvChannel) => void;
  playCatchup: (channel: IptvChannel, program: IptvProgram) => void;
  closePlayer: () => void;
  installAddon: (url: string) => Promise<void>;
  removeAddon: (addon: InstalledAddon) => Promise<void>;
  setAddonsState: (next: InstalledAddon[]) => Promise<void>;
  signIn: (email: string, password: string, mode: "sign-in" | "sign-up") => Promise<void>;
  signOut: () => void;
  beginTrakt: () => Promise<void>;
  pollTrakt: () => Promise<void>;
  disconnectTrakt: () => void;
  // Watchlist list-source switcher (Trakt custom lists / collection).
  loadTraktLists: () => Promise<Array<{ id: string; name: string }>>;
  loadTraktListItems: (source: string) => Promise<MediaItem[]>;
}

const AppContext = createContext<AppStore | null>(null);

export function useApp(): AppStore {
  const store = useContext(AppContext);
  if (!store) throw new Error("useApp must be used within <AppProvider>");
  return store;
}

export function AppProvider({
  children,
  initialView,
  cloudLoginRequired = false
}: {
  children: React.ReactNode;
  initialView?: AppView;
  cloudLoginRequired?: boolean;
}) {
  const [section, setSection] = useState<NavSection>("home");
  // Seed Continue Watching + Watchlist synchronously from the last-known lists
  // for the active profile so both are on screen the instant the app renders —
  // without this CW stays blank until the ~120-call Trakt up-next fetch returns,
  // and only appeared after a navigation round-trip remounted the screen.
  const initialProfileId = loadStored<string | null>(ACTIVE_PROFILE_KEY, null);
  const initialCw = readCachedList(cwCacheKeyFor(initialProfileId));
  const initialWatchlist = readCachedList(watchlistCacheKeyFor(initialProfileId));
  const [categories, setCategories] = useState<Category[]>(
    initialCw.length ? [{ id: "continue_watching", title: "Continue Watching", items: initialCw }] : []
  );
  const [catalogConfigs, setCatalogConfigs] = useState<CatalogConfig[]>([]);
  const [homeServerRows, setHomeServerRows] = useState<Category[]>([]);
  const [continueWatching, setContinueWatching] = useState<MediaItem[]>(initialCw);
  const [watchlist, setWatchlist] = useState<MediaItem[]>(initialWatchlist);
  // Where the on-screen CW list came from, so later paints know whether they may
  // replace it: cache seed < fast paint (playback-only) < fresh enriched list.
  const cwSourceRef = useRef<"none" | "seed" | "fast" | "fresh">(initialCw.length ? "seed" : "none");
  // The profile key of the LATEST refresh request. In-flight refreshes for a
  // previous profile check this before writing state, so switching profiles can
  // never end with the old profile's rows landing after the new profile's.
  const refreshKeyRef = useRef<string | null>(null);
  const [watchedKeys, setWatchedKeys] = useState<Set<string>>(() => new Set());
  const [selected, setSelected] = useState<MediaItem | null>(null);
  const [streams, setStreams] = useState<StreamSource[]>([]);
  const [selectedEpisode, setSelectedEpisode] = useState<{ season: number; episode: number } | null>(null);
  const [activeStream, setActiveStream] = useState<StreamSource | null>(null);
  const [activeChannel, setActiveChannel] = useState<IptvChannel | null>(null);
  const [addons, setAddons] = useState<InstalledAddon[]>([]);
  const [iptvSnapshot, setIptvSnapshot] = useState<IptvSnapshot>(emptyIptv);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<MediaItem[]>([]);
  const [settings, setSettings] = useState<AppSettings>(() => {
    const stored = loadStored<AppSettings>(settingsKey, defaultSettings);
    return {
      ...defaultSettings,
      ...stored,
      iptvPlaylists: loadPlaylists(),
      catalogs: mergeCatalogs(stored.catalogs, stored.hiddenCatalogIds)
    };
  });
  const [auth, setAuth] = useState(() => authClient.session);
  const [traktConnected, setTraktConnected] = useState(() => traktClient.isConnected);
  const [deviceCode, setDeviceCode] = useState<TraktDeviceCode | null>(null);
  const [busy, setBusy] = useState("Loading ARVIO");
  const [toast, setToast] = useState<string | null>(null);
  const [cloudProfilesHydrated, setCloudProfilesHydrated] = useState(() => !authClient.session);
  const refreshInFlightRef = useRef<{ key: string; promise: Promise<void> } | null>(null);
  const iptvGuideInFlightRef = useRef(new Set<string>());
  // Snapshot of the settings last known to match the cloud, so the autosave
  // effect can skip pushing settings that just CAME from the cloud (an echo
  // write every app boot = a wasted account-sync-push per user per session).
  const lastSyncedSettingsRef = useRef<string | null>(null);

  const [profiles, setProfiles] = useState<Profile[]>(() => {
    // Don't surface a previous account's cached profiles when a different account
    // is signed in on this browser — start clean and let the cloud pull fill in.
    const stored = localProfilesMatchAccount() ? loadStored<Profile[]>(PROFILES_KEY, []) : [];
    return stored.length ? stored : [makeProfile("Profile 1", 0xffe50914, 0)];
  });
  const [activeProfileId, setActiveProfileId] = useState<string | null>(() =>
    localProfilesMatchAccount() ? loadStored<string | null>(ACTIVE_PROFILE_KEY, null) : null
  );
  // Hydrate cached custom-avatar images synchronously so profile tiles paint the
  // real avatar on first render instead of flashing the letter fallback.
  const [avatarImages, setAvatarImagesState] = useState<Record<string, string>>(() => loadStored<Record<string, string>>(AVATAR_IMAGES_KEY, {}));
  const setAvatarImages = useCallback((next: Record<string, string>) => {
    setAvatarImagesState(next);
    saveStored(AVATAR_IMAGES_KEY, next);
  }, []);
  const [manageMode, setManageMode] = useState(false);
  const [view, setView] = useState<AppView>(() => {
    if (initialView) return initialView;
    const stored = loadStored<Profile[]>(PROFILES_KEY, []);
    const activeId = loadStored<string | null>(ACTIVE_PROFILE_KEY, null);
    const skip = loadStored<AppSettings>(settingsKey, defaultSettings).skipProfileSelection;
    if (skip && activeId && stored.some((p) => p.id === activeId)) return "app";
    return "profiles";
  });

  const activeProfile = profiles.find((p) => p.id === activeProfileId) ?? null;

  const [heroPreview, setHeroPreview] = useState<MediaItem | null>(null);
  const hero = heroPreview ?? continueWatching[0] ?? categories[0]?.items[0] ?? null;

  // Refs so stable callbacks always read the latest values without re-creating.
  const addonsRef = useRef(addons);
  useEffect(() => {
    addonsRef.current = addons;
  }, [addons]);

  const settingsRef = useRef(settings);
  useEffect(() => {
    settingsRef.current = settings;
  }, [settings]);

  useEffect(() => {
    const effectiveCatalogs = mergeCatalogs(settings.catalogs, settings.hiddenCatalogIds);
    setCatalogConfigs(effectiveCatalogs.filter((catalog) => catalog.enabled));
  }, [settings.catalogs, settings.hiddenCatalogIds]);

  const deviceCodeRef = useRef(deviceCode);
  useEffect(() => {
    deviceCodeRef.current = deviceCode;
  }, [deviceCode]);

  const persistAddons = useCallback(async (next: InstalledAddon[], options: { removedIds?: string[] } = {}) => {
    const normalized = normalizeAddons(next);
    setAddons(normalized);
    saveLocalAddons(normalized);
    // Cloud writes are union-based; a removal must be an explicit id list so the
    // shared library can never be shrunk by a stale/partial in-memory view.
    await saveCloudAddons(authClient, normalized, activeProfileId, { removedIds: options.removedIds }).catch(() => undefined);
  }, [activeProfileId]);

  const refreshData = useCallback((profileIdOverride?: string | null) => {
    const profileId = profileIdOverride ?? activeProfileId;
    const key = profileId ?? "no-profile";
    refreshKeyRef.current = key;
    const existing = refreshInFlightRef.current;
    if (existing?.key === key) return existing.promise;
    const run = (async () => {
      const currentSettings = settingsRef.current;
      setBusy("Syncing catalogs");
      // Paint Continue Watching instantly from the last known list for this
      // profile — the fresh Trakt fetch replaces it seconds later. Without
      // this the rail sits empty while up to ~17 Trakt calls round-trip.
      const cwCacheKey = cwCacheKeyFor(profileId);
      const watchlistCacheKey = watchlistCacheKeyFor(profileId);
      try {
        const cachedCw = readCachedList(cwCacheKey);
        if (cachedCw.length && cwSourceRef.current === "none") {
          cwSourceRef.current = "seed";
          setContinueWatching(cachedCw);
          setCategories((current) => current.some((c) => c.id === "continue_watching")
            ? current
            : [{ id: "continue_watching", title: "Continue Watching", items: cachedCw }, ...current]);
        }
        // Same instant-paint for the watchlist grid — without this it sits blank
        // until ~15 Trakt calls round-trip.
        const cachedWatchlist = readCachedList(watchlistCacheKey);
        if (cachedWatchlist.length) {
          setWatchlist((current) => current.length ? current : cachedWatchlist);
        }
      } catch {
        // Cache read must never block the refresh.
      }
      try {
      const localAddons = loadLocalAddons();
      const cloud = authClient.session ? await pullCloudPayload(authClient, profileId).catch(() => null) : null;
      let effectiveSettings = currentSettings;
      if (authClient.session && profileId) {
        const cloudTraktToken = await pullCloudTraktToken(authClient, profileId).catch(() => null);
        if (cloudTraktToken) {
          traktClient.setToken(cloudTraktToken);
          setTraktConnected(true);
        }
      }
      if (cloud?.settings) {
        effectiveSettings = {
          ...defaultSettings,
          ...currentSettings,
          ...cloud.settings,
          catalogs: mergeCatalogs(cloud.settings?.catalogs ?? currentSettings.catalogs, cloud.settings?.hiddenCatalogIds ?? currentSettings.hiddenCatalogIds),
          iptvPlaylists: cloud.settings?.iptvPlaylists ?? currentSettings.iptvPlaylists,
          favoriteChannelIds: cloud.settings?.favoriteChannelIds ?? currentSettings.favoriteChannelIds,
          favoriteGroupIds: cloud.settings?.favoriteGroupIds ?? currentSettings.favoriteGroupIds,
          hiddenGroupIds: cloud.settings?.hiddenGroupIds ?? currentSettings.hiddenGroupIds,
          groupOrder: cloud.settings?.groupOrder ?? currentSettings.groupOrder
        };
        if (!sameSettings(settingsRef.current, effectiveSettings)) setSettings(effectiveSettings);
        // Record what we just synced FROM the cloud (same shape the autosave
        // effect compares against) so it doesn't push it straight back.
        lastSyncedSettingsRef.current = JSON.stringify({ settings: effectiveSettings, activeProfileId: profileId });
        savePlaylists(effectiveSettings.iptvPlaylists);
      }
      // Addon-wipe protection. Prefer cloud, fall back to local, but NEVER let a
      // failed/empty pull replace a non-empty list. A null `cloud` means the pull
      // errored (Cloudflare-challenged the request, offline, etc.) — treat that as
      // "keep what we have", not "the library is empty".
      const cloudAddons = normalizeAddons(cloud?.addons ?? []);
      const localNormalized = normalizeAddons(localAddons);
      const currentAddons = normalizeAddons(addonsRef.current);
      const source = cloudAddons.length
        ? cloudAddons
        : localNormalized.length
          ? localNormalized
          : currentAddons;
      // If the pull explicitly returned an EMPTY cloud list but we still hold
      // addons locally, the cloud is stale/partial — self-heal by pushing our
      // list back up instead of wiping.
      const cloudReturnedEmpty = cloud !== null && cloudAddons.length === 0;
      const addonState = source.map((addon) => ({
        ...addon,
        enabled: !effectiveSettings.disabledAddonIds.includes(addon.id) && addon.enabled !== false
      }));
      setAddons(addonState);
      // Only persist locally when we actually have addons — an empty list can't
      // overwrite a good one.
      if (addonState.length) saveLocalAddons(addonState);
      if (cloudReturnedEmpty && source.length && authClient.session) {
        void saveCloudAddons(authClient, source, profileId).catch(() => undefined);
      }

      const effectiveCatalogs = mergeCatalogs(effectiveSettings.catalogs, effectiveSettings.hiddenCatalogIds);
      setCatalogConfigs(effectiveCatalogs.filter((catalog) => catalog.enabled));

      void loadHomeServerRows(effectiveSettings.homeServers).then(setHomeServerRows).catch(() => setHomeServerRows([]));

      const traktReady = traktClient.isConnected;
      const [historyRows, traktRows, playbackRows, watchedMoviesRows, watchedShowsRows, cloudWatchlistRows] = await Promise.all([
        authClient.session ? getContinueWatching(authClient, profileId).catch(() => []) : Promise.resolve([]),
        traktReady ? traktClient.watchlist().catch(() => []) : Promise.resolve([]),
        traktReady ? traktClient.playback().catch(() => []) : Promise.resolve([]),
        traktReady ? traktClient.watched("movies").catch(() => []) : Promise.resolve([]),
        traktReady ? traktClient.watched("shows").catch(() => []) : Promise.resolve([]),
        authClient.session ? pullCloudWatchlist(authClient, profileId).catch(() => []) : Promise.resolve([])
      ]);

      const cloudCw = historyRows.map(historyToItem);
      const traktPlaybackCw = playbackRows.map(traktPlaybackToMedia).filter(isPausedPlaybackItem);

      // ── Fast paint ─────────────────────────────────────────────────────────
      // The cloud watchlist + cloud/playback CW are already available now (the
      // cloud pull is ~400ms). Paint them immediately so both rails appear in
      // ~1s, WITHOUT waiting on loadTraktUpNext (~120 per-show progress calls)
      // or the per-item TMDB hydration below. The richer Trakt up-next data
      // enriches CW a moment later.
      const fastWatchlistSource = traktRows.length ? traktRows.map(traktItemToMedia) : cloudWatchlistRows;
      if (fastWatchlistSource.length) {
        void hydrateTraktItems(fastWatchlistSource).then((hydrated) => {
          if (hydrated.length && refreshKeyRef.current === key) {
            setWatchlist((current) => current.length ? current : hydrated);
            saveCachedList(watchlistCacheKey, hydrated, 60);
          }
        }).catch(() => undefined);
      }
      const fastCw = dedupeMedia([...traktPlaybackCw, ...cloudCw]).sort((a, b) => (b.activityAt ?? 0) - (a.activityAt ?? 0));
      if (fastCw.length) {
        void hydrateContinueWatchingItems(fastCw).then((hydrated) => {
          // Only fill an empty rail: replacing a seeded cache list with this
          // playback-only list would visibly shrink the rail for a few seconds
          // until the enriched list lands.
          if (refreshKeyRef.current !== key || cwSourceRef.current !== "none") return;
          cwSourceRef.current = "fast";
          setContinueWatching(hydrated);
          setCategories((current) => current.some((c) => c.id === "continue_watching")
            ? current
            : [{ id: "continue_watching", title: "Continue Watching", items: hydrated }, ...current]);
          // First visit on a device (no cache yet): persist this list so the
          // NEXT open paints instantly even if this session ends before the
          // enriched pipeline (which normally owns the cache) completes. Never
          // overwrite an existing richer cache with this playback-only list.
          if (!readCachedList(cwCacheKey).length) {
            saveCachedList(cwCacheKey, hydrated, 20);
          }
        }).catch(() => undefined);
      }
      // ───────────────────────────────────────────────────────────────────────

      const upNext = traktReady
        ? await loadTraktUpNext(watchedShowsRows, effectiveSettings.includeSpecials).catch(() => ({ items: [] as MediaItem[], fetchFailures: 1 }))
        : { items: [] as MediaItem[], fetchFailures: 0 };
      const upNextRows = upNext.items;
      const playbackShowKeys = new Set(traktPlaybackCw.filter((item) => item.mediaType === "tv").map((item) => `${item.mediaType}:${item.id}`));
      const traktCw = mergeTraktWithLocalResume([
        ...traktPlaybackCw,
        ...upNextRows.filter((item) => !playbackShowKeys.has(`${item.mediaType}:${item.id}`))
      ], cloudCw);
      const watchedKeys = traktWatchedKeys(watchedMoviesRows, watchedShowsRows);
      if (refreshKeyRef.current === key) setWatchedKeys(watchedKeys);
      const cwBase = traktReady ? traktCw : cloudCw.filter(isPausedPlaybackItem);
      // Order newest-activity-first across playback + up-next (matches the app's
      // updatedAt-descending sort) so the row leads with what you last watched.
      const cwSorted = dedupeMedia(cwBase).sort((a, b) => (b.activityAt ?? 0) - (a.activityAt ?? 0));
      const cw = await hydrateContinueWatchingItems(filterWatchedContinueWatching(cwSorted, watchedKeys));
      // Trakt outage guard: when Trakt is connected but every read came back
      // empty, the calls were blocked (Cloudflare challenges the CORS
      // preflight intermittently, especially on VPN/datacenter IPs) — keep
      // showing the cached rail instead of wiping it with an empty list.
      const traktOutage = traktReady && !cw.length &&
        !playbackRows.length && !watchedShowsRows.length && !watchedMoviesRows.length && !traktRows.length;
      // Enriched CW (adds Trakt up-next episodes) replaces the fast paint. When
      // the fresh list is non-empty we swap it in. An empty result with Trakt
      // connected and reads healthy means the rail is GENUINELY empty — clear it
      // and its cache so a finished library can't resurrect a stale rail. During
      // an outage (all reads empty) we keep whatever is painted.
      if (!traktOutage && refreshKeyRef.current === key) {
        if (cw.length) {
          cwSourceRef.current = "fresh";
          setContinueWatching(cw);
          saveCachedList(cwCacheKey, cw, 20);
          setCategories((current) => {
            const others = current.filter((c) => c.id !== "continue_watching");
            return [{ id: "continue_watching", title: "Continue Watching", items: cw }, ...others];
          });
        } else if (traktReady && upNext.fetchFailures === 0) {
          // Only clear on a CLEAN pass: any per-show progress failure means this
          // empty result could be a partial outage, and wiping the cache would
          // recreate the blank-rail-on-startup bug the seed exists to prevent.
          cwSourceRef.current = "fresh";
          setContinueWatching([]);
          setCategories((current) => current.filter((c) => c.id !== "continue_watching"));
          removeStored(cwCacheKey);
        }
      }
      // Refresh the watchlist with the authoritative Trakt list if it differs.
      const watchlistSource = traktRows.length ? traktRows.map(traktItemToMedia) : cloudWatchlistRows;
      const hydratedWatchlist = await hydrateTraktItems(watchlistSource);
      if (hydratedWatchlist.length && refreshKeyRef.current === key) {
        setWatchlist(hydratedWatchlist);
        saveCachedList(watchlistCacheKey, hydratedWatchlist, 60);
      }
      } catch (error) {
        setToast(error instanceof Error ? error.message : "Failed to load ARVIO");
      } finally {
        setBusy("");
      }
    })();
    refreshInFlightRef.current = { key, promise: run };
    return run.finally(() => {
      if (refreshInFlightRef.current?.promise === run) refreshInFlightRef.current = null;
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeProfileId]);

  const refreshIptv = useCallback(async () => {
    const currentSettings = settingsRef.current;
    setBusy("Loading TV");
    try {
      const loadedIptv = await loadIptvSnapshot(
        currentSettings.iptvPlaylists,
        currentSettings.favoriteChannelIds,
        currentSettings.favoriteGroupIds,
        currentSettings.hiddenGroupIds,
        currentSettings.groupOrder,
        { userAgent: currentSettings.customUserAgent }
      );
      setIptvSnapshot(loadedIptv);
    } catch (error) {
      setToast(error instanceof Error ? error.message : "Failed to load Live TV");
    } finally {
      setBusy("");
    }
  }, []);

  const loadIptvGuide = useCallback(async (channels: IptvChannel[]) => {
    if (!channels.length) return;
    const currentSettings = settingsRef.current;
    // A guide entry whose "now" programme already ended is stale — refetch it so
    // the rows keep showing what is actually on air.
    const isFresh = (channelId: string) => {
      const entry = iptvSnapshot.nowNext[channelId];
      if (!entry) return false;
      if (entry.now) return entry.now.endUtcMillis > Date.now();
      return true;
    };
    const missing = channels.filter((channel) => !isFresh(channel.id) && !iptvGuideInFlightRef.current.has(channel.id));
    if (!missing.length) return;
    missing.forEach((channel) => iptvGuideInFlightRef.current.add(channel.id));
    try {
      const guide = await loadIptvGuideForChannels(currentSettings.iptvPlaylists, missing);
      if (!Object.keys(guide).length) return;
      setIptvSnapshot((current) => ({
        ...current,
        nowNext: {
          ...current.nowNext,
          ...guide
        }
      }));
    } catch {
      // Guide is helpful but should never block channel browsing/playback.
    } finally {
      missing.forEach((channel) => iptvGuideInFlightRef.current.delete(channel.id));
    }
  }, [iptvSnapshot.nowNext]);

  useEffect(() => {
    if (typeof window === "undefined") return;
    const hash = window.location.hash || "";
    if (hash.includes("access_token=") && hash.includes("refresh_token=")) {
      try {
        const params = new URLSearchParams(hash.replace(/^#/, ""));
        const access_token = params.get("access_token");
        const refresh_token = params.get("refresh_token");
        const email = params.get("email") || "";
        const expires_in = Number(params.get("expires_in") || "3600");
        if (access_token && refresh_token) {
          const payload = decodeJwtPayload(access_token);
          const userId = (payload.sub as string | undefined) ?? "";
          const provider = ((payload.iss as string | undefined) === "arvio-netlify" ? "netlify" : "supabase") as "netlify" | "supabase";
          const session = {
            accessToken: access_token,
            refreshToken: refresh_token,
            userId,
            email,
            expiresAt: Date.now() + expires_in * 1000,
            provider
          };

          saveStored(SESSION_KEY, session);
          authClient.session = session;
          setAuth(session);
          setCloudProfilesHydrated(false);

          // Clear hash parameters from URL without a page reload
          const cleanUrl = window.location.pathname + window.location.search;
          window.history.replaceState({}, document.title, cleanUrl);

          // Redirect to appropriate view
          const stored = loadStored<Profile[]>(PROFILES_KEY, []);
          const activeId = loadStored<string | null>(ACTIVE_PROFILE_KEY, null);
          const skip = settings.skipProfileSelection;
          if (skip && activeId && stored.some((p) => p.id === activeId)) {
            setView("app");
          } else {
            setView("profiles");
          }
        }
      } catch (err) {
        console.error("Failed to parse callback auth parameters", err);
      }
    }
  }, [settings.skipProfileSelection]);

  useEffect(() => {
    // Also runs on the profile-selection screen ("profiles" view): the last-used
    // profile is almost always the one picked, so Continue Watching and the
    // rails are already loading (or loaded) by the time Home first mounts.
    if (view === "login") return;
    if (authClient.session && !cloudProfilesHydrated) return;
    void refreshData();
  }, [cloudProfilesHydrated, refreshData, view]);

  useEffect(() => {
    saveStored(settingsKey, settings);
    savePlaylists(settings.iptvPlaylists);
    if (authClient.session && !cloudProfilesHydrated) return;
    // Skip the cloud push when settings + active profile still match what we
    // last synced from the cloud — otherwise every boot echoes the just-pulled
    // settings straight back, a wasted account-sync-push per user per session. A
    // genuine change (user toggled a setting, switched profile) differs from the
    // snapshot and still saves.
    const snapshot = JSON.stringify({ settings, activeProfileId });
    if (lastSyncedSettingsRef.current !== null && snapshot === lastSyncedSettingsRef.current) {
      return;
    }
    const handle = setTimeout(() => {
      // The settings we last synced for THIS profile — lets saveCloudSettings detect exactly which
      // fields this session changed so it only asserts (and timestamps) those, never reverting a
      // field another device changed. Ignore the baseline if the active profile has since changed.
      let baseline: AppSettings | null = null;
      try {
        if (lastSyncedSettingsRef.current) {
          const parsed = JSON.parse(lastSyncedSettingsRef.current) as { settings?: AppSettings; activeProfileId?: string | null };
          if (parsed.activeProfileId === activeProfileId && parsed.settings) baseline = parsed.settings;
        }
      } catch {
        baseline = null;
      }
      lastSyncedSettingsRef.current = JSON.stringify({ settings: settingsRef.current, activeProfileId });
      void saveCloudSettings(authClient, settingsRef.current, addonsRef.current, activeProfileId, profiles, baseline).catch(() => undefined);
    }, 1200);
    return () => clearTimeout(handle);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settings, activeProfileId, profiles, cloudProfilesHydrated]);

  useEffect(() => {
    saveStored(PROFILES_KEY, profiles);
    saveStored(ACTIVE_PROFILE_KEY, activeProfileId);
    // Stamp which account these cached profiles belong to (empty when signed
    // out), so another account on this browser won't inherit them.
    saveStored(PROFILES_OWNER_KEY, currentAccountEmail());
  }, [profiles, activeProfileId, auth]);

  // When signed in, pull the shared profiles from the same account_sync_state
  // payload Android writes to (cloud wins, matching replaceProfilesFromCloud).
  useEffect(() => {
    if (!authClient.session) {
      setCloudProfilesHydrated(true);
      return;
    }
    let cancelled = false;
    void pullCloudProfiles(authClient)
      .then((cloud) => {
        if (cancelled) return;
        if (cloud.profiles.length) {
          setProfiles(cloud.profiles);
          setAvatarImages(cloud.avatarImages);
          if (cloud.activeProfileId) {
            setActiveProfileId(cloud.activeProfileId);
            void refreshData(cloud.activeProfileId);
          } else if (cloud.profiles[0]) {
            setActiveProfileId(cloud.profiles[0].id);
            void refreshData(cloud.profiles[0].id);
          }
        } else {
          // New account with no cloud profiles yet. If the local profiles were
          // stamped for a DIFFERENT account, they leaked from a previous
          // session — replace them with one clean profile.
          if (!localProfilesMatchAccount()) {
            const fresh = [makeProfile("Profile 1", randomProfileColor(), 0)];
            setProfiles(fresh);
            setActiveProfileId(fresh[0].id);
            saveStored(PROFILES_OWNER_KEY, currentAccountEmail());
            void refreshData(fresh[0].id);
          } else {
            void refreshData(activeProfileId);
          }
        }
        setCloudProfilesHydrated(true);
      })
      .catch(() => {
        if (!cancelled) setCloudProfilesHydrated(true);
      });
    return () => {
      cancelled = true;
    };
  }, [activeProfileId, auth, refreshData]);

  useEffect(() => {
    const handle = setTimeout(async () => {
      if (!query.trim()) {
        setResults([]);
        return;
      }
      setResults(await searchMedia(query, settings.language).catch(() => []));
    }, 260);
    return () => clearTimeout(handle);
  }, [query, settings.language]);

  const updateSettings = useCallback((patch: Partial<AppSettings>) => {
    setSettings((prev) => ({ ...prev, ...patch }));
  }, []);

  const isWatched = useCallback((item: MediaItem, seasonNumber?: number | null, episodeNumber?: number | null) => (
    isMediaWatched(item, watchedKeys, seasonNumber, episodeNumber)
  ), [watchedKeys]);

  // Optimistic watched-mark: badge + drop from Continue Watching instantly,
  // before the Trakt round-trip returns (used by the external-playback prompt
  // and the details Mark Watched flow so the UI responds immediately).
  const markWatchedLocally = useCallback((item: { mediaType: MediaItem["mediaType"]; id: number; season?: number | null; episode?: number | null }, watched = true) => {
    const target = { ...item, seasonNumber: item.season ?? null, episodeNumber: item.episode ?? null } as unknown as MediaItem;
    const key = mediaWatchKey(target, item.season, item.episode);
    setWatchedKeys((prev) => {
      const next = new Set(prev);
      if (watched) next.add(key); else next.delete(key);
      return next;
    });
    if (watched) {
      setContinueWatching((prev) => prev.filter((entry) => mediaWatchKey(entry) !== key && mediaWatchKey(entry) !== `${item.mediaType}:${item.id}`));
      setCategories((prev) => prev.map((cat) => cat.id === "continue_watching"
        ? { ...cat, items: cat.items.filter((entry) => mediaWatchKey(entry) !== key && mediaWatchKey(entry) !== `${item.mediaType}:${item.id}`) }
        : cat).filter((cat) => cat.id !== "continue_watching" || cat.items.length));
    }
  }, []);

  // Merge a fresh addon-stream list in without dropping IPTV VOD sources that
  // were appended asynchronously (they carry addonId "iptv_xtream_vod").
  const mergeStreams = useCallback((incoming: StreamSource[]) => {
    setStreams((prev) => {
      // Preserve the supplemental sources injected in parallel (Xtream VOD and
      // home-server files) when the addon/debrid streams resolve.
      const extras = prev.filter(
        (source) => source.addonId === "iptv_xtream_vod" || source.addonId === "home_server"
      );
      const seen = new Set(incoming.map((s) => s.url ?? s.source));
      return [...incoming, ...extras.filter((s) => !seen.has(s.url ?? s.source))];
    });
  }, []);

  // Look up the opened title in the Xtream VOD/series catalog and append any
  // match as a supplemental source (parity with the Android app).
  const appendVodSources = useCallback((item: MediaItem, season?: number, episode?: number) => {
    const playlists = settingsRef.current.iptvPlaylists;
    if (!playlists?.length) return;
    void (async () => {
      try {
        const { findMovieVodSources, findEpisodeVodSource } = await import("./xtreamVod");
        const ua = settingsRef.current.customUserAgent;
        const sources = season && episode
          ? await findEpisodeVodSource(playlists, item, season, episode, ua)
          : await findMovieVodSources(playlists, item, ua);
        if (!sources.length) return;
        setStreams((prev) => {
          const seen = new Set(prev.map((s) => s.url ?? s.source));
          const fresh = sources.filter((s) => s.url && !seen.has(s.url));
          return fresh.length ? [...prev, ...fresh] : prev;
        });
      } catch {
        // VOD is best-effort; addon sources are unaffected on failure.
      }
    })();
  }, []);

  // Match the opened title on the user's home servers (Jellyfin/Emby/Plex) and
  // append any files found as sources — parity with the Android app, which
  // surfaces home-server media in the same source list as addons.
  const appendHomeServerSources = useCallback((item: MediaItem, season?: number, episode?: number) => {
    const servers = settingsRef.current.homeServers;
    if (!servers?.length || item.isHomeServer) return;
    void (async () => {
      try {
        const { resolveHomeServerMovieSources, resolveHomeServerEpisodeSources } = await import("./homeserver");
        const target = {
          title: item.title,
          year: item.year ? Number(item.year) || undefined : undefined,
          imdbId: item.imdbId ?? undefined,
          tmdbId: item.id > 0 ? item.id : undefined
        };
        const sources = season && episode
          ? await resolveHomeServerEpisodeSources(servers, target, season, episode)
          : await resolveHomeServerMovieSources(servers, target);
        if (!sources.length) return;
        setStreams((prev) => {
          const seen = new Set(prev.map((s) => s.url ?? s.source));
          const fresh = sources.filter((s) => s.url && !seen.has(s.url));
          return fresh.length ? [...prev, ...fresh] : prev;
        });
      } catch {
        // Best-effort; addon/debrid sources are unaffected on failure.
      }
    })();
  }, []);

  const openDetails = useCallback(async (item: MediaItem) => {
    setSelectedEpisode(null);
    // Home-server items carry their own metadata + a direct stream URL — no TMDB.
    if (item.isHomeServer) {
      setSelected(item);
      setStreams(item.homeServerUrl
        ? [{ source: item.title, addonName: "Home Server", quality: "Direct", size: "", url: item.homeServerUrl }]
        : []);
      setBusy("");
      return;
    }
    setBusy("Opening details");
    setStreams([]);
    const detailed = await getDetails(item).catch(() => item);
    const withResumeEpisode = {
      ...detailed,
      seasonNumber: item.seasonNumber ?? detailed.seasonNumber ?? null,
      episodeNumber: item.episodeNumber ?? detailed.episodeNumber ?? null,
      episodeTitle: item.episodeTitle ?? detailed.episodeTitle ?? null
    };
    setSelected(withResumeEpisode);
    // Movies fetch sources immediately. Continue-watching TV entries already
    // know their episode, so load that episode's sources without an extra tap.
    if (item.mediaType === "movie") {
      setBusy("Finding sources");
      appendVodSources(withResumeEpisode);
      appendHomeServerSources(withResumeEpisode);
      const found = await getStreamsProgressive(addonsRef.current, withResumeEpisode, undefined, undefined, setStreams).catch(() => []);
      mergeStreams(found);
    } else if (withResumeEpisode.seasonNumber && withResumeEpisode.episodeNumber) {
      setSelectedEpisode({ season: withResumeEpisode.seasonNumber, episode: withResumeEpisode.episodeNumber });
      setBusy("Finding sources");
      appendVodSources(withResumeEpisode, withResumeEpisode.seasonNumber, withResumeEpisode.episodeNumber);
      appendHomeServerSources(withResumeEpisode, withResumeEpisode.seasonNumber, withResumeEpisode.episodeNumber);
      const found = await getStreamsProgressive(
        addonsRef.current,
        withResumeEpisode,
        withResumeEpisode.seasonNumber,
        withResumeEpisode.episodeNumber,
        setStreams
      ).catch(() => []);
      mergeStreams(found);
    }
    setBusy("");
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadEpisodeStreams = useCallback(async (item: MediaItem, season: number, episode: number) => {
    setSelectedEpisode({ season, episode });
    setStreams([]);
    setBusy("Finding sources");
    appendVodSources(item, season, episode);
    appendHomeServerSources(item, season, episode);
    const found = await getStreamsProgressive(addonsRef.current, item, season, episode, setStreams).catch(() => []);
    mergeStreams(found);
    setBusy("");
    return found;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const advanceEpisode = useCallback(async (): Promise<boolean> => {
    if (!selected || selected.mediaType !== "tv" || !selectedEpisode) return false;
    const nextEpisode = selectedEpisode.episode + 1;
    setSelectedEpisode({ season: selectedEpisode.season, episode: nextEpisode });
    const found = await getStreams(addonsRef.current, selected, selectedEpisode.season, nextEpisode).catch(() => []);
    setStreams(found);
    const best = found.find((stream) => stream.url);
    setActiveStream(best ?? null);
    return Boolean(best);
  }, [selected, selectedEpisode]);

  const closeDetails = useCallback(() => {
    setSelected(null);
    setSelectedEpisode(null);
    setStreams([]);
  }, []);

  const playStream = useCallback((stream: StreamSource, options: { forceTranscode?: boolean; forceRemux?: boolean; forceBrowser?: boolean } = {}) => {
    if (!stream.url) {
      setToast("This source is not web-playable yet. Browser playback needs a direct HTTP/HLS URL.");
      return;
    }
    // Default external player (VLC / Infuse): hand the source straight to it,
    // resolving the debrid CDN URL first (external players can't follow the
    // torrentio redirect chain). A pending record lets ARVIO scrobble to Trakt
    // + save progress when the user returns. forceBrowser overrides (e.g. the
    // in-player source panel, which is already in the browser player).
    const preferredPlayer = settingsRef.current.defaultPlayer;
    if (!options.forceBrowser && !options.forceRemux && !options.forceTranscode && (preferredPlayer === "vlc" || preferredPlayer === "infuse")) {
      const externalItem = selected;
      const externalTitle = selected?.title ?? stream.source ?? "ARVIO stream";
      const preferredSub = settingsRef.current.defaultSubtitle;
      // The deep link MUST fire synchronously in the Play click — custom-scheme
      // navigation (vlc-x-callback://) is blocked after an await. Use the
      // prefetch-cached CDN url if present, otherwise the raw source (VLC
      // follows the redirect itself). Subtitles attach only if we already have
      // them from the panel prefetch on the stream.
      const cached = parseDebridStream(stream.url) ? cachedDebridDirectUrl(stream.url) : null;
      const target = cached ? { ...stream, url: cached, originalUrl: stream.url } : stream;
      setToast(
        preferredPlayer === "vlc" && externalLaunchMode("vlc") === "playlist"
          ? "VLC playlist saved — open it from your downloads to play."
          : `Opening in ${preferredPlayer.toUpperCase()}...`
      );
      createPendingExternalPlayback({
        player: preferredPlayer,
        item: externalItem,
        stream: target,
        title: externalTitle,
        profileId: activeProfile?.id ?? null,
        season: selectedEpisode?.season ?? null,
        episode: selectedEpisode?.episode ?? null
      });
      openExternalPlayer(preferredPlayer, target, externalTitle, preferredSub);
      return;
    }
    // Explicit escalations (from the player's fallback buttons) resolve the
    // heavier paths. These are opt-in, never the default click.
    if (options.forceTranscode) {
      const debrid = parseDebridStream(stream.url);
      if (debrid) {
        setToast("Preparing transcoded stream...");
        void resolveTranscodeStream(debrid).then((result) => {
          if (result.url) setActiveStream({ ...stream, url: result.url, originalUrl: stream.url, transcoded: true });
          else setToast(result.error ?? "Transcoding is unavailable for this source.");
        });
        return;
      }
    }
    if (options.forceRemux) {
      const debrid = parseDebridStream(stream.url);
      if (debrid) {
        // Remux reads via fetch/UrlSource, which the torrentio /resolve/ redirect
        // blocks with CORS — so resolve to the direct CDN URL first for remux only.
        setToast("Preparing stream...");
        void resolveDebridDirectUrl(debrid).then((result) => {
          if (result.url) {
            setActiveStream({ ...stream, url: result.url, originalUrl: stream.url, remux: true });
          } else {
            // Resolution failed (uncached, quota, provider hiccup) — hand the raw
            // stream back to the player so its ladder fails fast and auto-hops to
            // the next source instead of dead-ending on a toast.
            setToast(result.error ?? "Source not ready — trying the next one.");
            setActiveStream({ ...stream, remux: true });
          }
        });
        return;
      }
      setActiveStream({ ...stream, remux: true });
      return;
    }
    // A debrid source the browser can only play after remuxing (MKV / lossless
    // audio): go STRAIGHT to remux with the resolved CDN URL instead of first
    // handing the raw torrentio link to <video>, which can only fail and burn a
    // ~13s stall-timeout before escalating. This is the biggest "not instant"
    // win — the top pick is almost always an MKV.
    const debrid = parseDebridStream(stream.url);
    if (debrid && streamPlayability(stream).mode === "remux") {
      const cached = cachedDebridDirectUrl(stream.url);
      if (cached) {
        setActiveStream({ ...stream, url: cached, originalUrl: stream.url, remux: true });
        return;
      }
      void resolveDebridDirectUrl(debrid).then((result) => {
        if (result.url) setActiveStream({ ...stream, url: result.url, originalUrl: stream.url, remux: true });
        else { setToast(result.error ?? "Source not ready — trying the next one."); setActiveStream({ ...stream, remux: true }); }
      });
      return;
    }
    // Otherwise hand the URL straight to the player for instant playback (like
    // the Android app — the <video>/hls element follows redirects itself). If
    // the source-list prefetch already resolved the debrid CDN URL, start on
    // that directly and skip the torrentio redirect chain. The player escalates
    // to remux/transcode only if playback actually fails to decode.
    const resolved = cachedDebridDirectUrl(stream.url);
    setActiveStream(resolved ? { ...stream, url: resolved, originalUrl: stream.url } : stream);
  }, [selected, activeProfile, selectedEpisode]);

  const playTrailer = useCallback(async (item: MediaItem) => {
    let url = item.trailerUrl ?? null;
    if (!url) {
      const detailed = await getDetails(item).catch(() => item);
      url = detailed.trailerUrl ?? null;
      setSelected((current) => current ?? detailed);
    }
    if (!url) {
      setToast("No trailer available for this title.");
      return;
    }
    // Trailers are YouTube page URLs — the built-in <video> player can't play
    // those. Open YouTube directly: the YouTube app on mobile (youtube.com/
    // youtu.be deep-links into it) or a new browser tab on desktop.
    if (typeof window !== "undefined") {
      const videoId = url.match(/(?:v=|youtu\.be\/|embed\/)([\w-]{11})/)?.[1];
      const target = videoId ? `https://www.youtube.com/watch?v=${videoId}` : url;
      const opened = window.open(target, "_blank", "noopener,noreferrer");
      if (!opened) window.location.href = target;
    }
  }, [setToast]);

  // Hand a live/catch-up stream to VLC/Infuse. Most IPTV is plain-HTTP (mixed
  // content in a secure page) and many providers block anything but a real
  // player, so an external player is the reliable path — it plays from the
  // user's own connection with no browser restrictions, at zero server cost.
  // Returns true when the handoff fired (so the caller skips the browser player).
  const openLiveExternally = useCallback((stream: StreamSource, title: string): boolean => {
    const player = settingsRef.current.defaultPlayer;
    if (player !== "vlc" && player !== "infuse") return false;
    setToast(
      player === "infuse"
        ? "Opening in Infuse..."
        : externalLaunchMode("vlc") === "playlist"
          ? "VLC playlist saved — open it from your downloads to play."
          : "Opening in VLC..."
    );
    openExternalPlayer(player, stream, title, settingsRef.current.defaultSubtitle);
    return true;
  }, []);

  const playChannel = useCallback((channel: IptvChannel) => {
    const stream: StreamSource = {
      source: channel.name,
      addonName: "Live TV",
      quality: "Live",
      size: "",
      url: channel.streamUrl,
      description: channel.group
    };
    if (openLiveExternally(stream, channel.name)) return;
    setActiveChannel(channel);
    setActiveStream(stream);
  }, [openLiveExternally]);

  // Catch-up plays a finished programme from the panel's archive. It is a
  // seekable VOD stream (no activeChannel → scrubber works), but the player
  // still gives it the IPTV proxy ladder via the "Catch-up" addonName marker.
  const playCatchup = useCallback((channel: IptvChannel, program: IptvProgram) => {
    const url = buildXtreamCatchupUrl(settingsRef.current.iptvPlaylists, channel, program);
    if (!url) {
      setToast("Catch-up is not available for this channel.");
      return;
    }
    const title = `${channel.name} · ${program.title}`;
    const stream: StreamSource = {
      source: title,
      addonName: "Catch-up",
      quality: "Catch-up",
      size: "",
      url,
      description: channel.group
    };
    if (openLiveExternally(stream, title)) return;
    setActiveChannel(null);
    setActiveStream(stream);
  }, [setToast, openLiveExternally]);

  const closePlayer = useCallback(() => {
    setActiveStream(null);
    setActiveChannel(null);
  }, []);

  const loadCatalogRow = useCallback((catalog: CatalogConfig) => loadCatalog(catalog, settings.language, addonsRef.current), [settings.language]);

  const installAddon = useCallback(async (url: string) => {
    const addon = await installAddonManifest(url);
    const next = [addon, ...addonsRef.current.filter((candidate) => candidate.id !== addon.id)];
    await persistAddons(next);
  }, [persistAddons]);

  const removeAddon = useCallback(async (addon: InstalledAddon) => {
    const next = addonsRef.current.filter((candidate) => candidate.id !== addon.id);
    await persistAddons(next, { removedIds: [addon.id] });
  }, [persistAddons]);

  const setAddonsState = useCallback(async (next: InstalledAddon[]) => {
    await persistAddons(next);
    setSettings((prev) => ({
      ...prev,
      disabledAddonIds: next.filter((addon) => addon.enabled === false).map((addon) => addon.id)
    }));
  }, [persistAddons]);

  const signIn = useCallback(async (email: string, password: string, mode: "sign-in" | "sign-up") => {
    const trimmedEmail = email.trim();
    if (!trimmedEmail || !password) throw new Error("Enter your email and password.");
    setBusy(mode === "sign-up" ? "Creating account" : "Signing in");
    setToast(null);
    try {
      const previousEmail = currentAccountEmail();
      const session = mode === "sign-up" ? await authClient.signUp(trimmedEmail, password) : await authClient.signIn(trimmedEmail, password);
      // Signing into a DIFFERENT account on this browser: drop the previous
      // account's in-memory + cached profiles so they don't show through while
      // the new account's cloud profiles load. A brand-new account then starts
      // with one clean profile instead of inheriting the old ones.
      if (previousEmail && previousEmail !== trimmedEmail.toLowerCase()) {
        const fresh = [makeProfile("Profile 1", randomProfileColor(), 0)];
        setProfiles(fresh);
        setActiveProfileId(fresh[0].id);
        setContinueWatching([]);
        setWatchlist([]);
        setCategories([]);
        setWatchedKeys(new Set());
        cwSourceRef.current = "none";
      }
      setCloudProfilesHydrated(false);
      setAuth(session);
    } catch (error) {
      const message = error instanceof Error ? error.message : "Authentication failed.";
      setToast(message);
      throw error;
    } finally {
      setBusy("");
    }
  }, [refreshData]);

  const signOut = useCallback(() => {
    authClient.signOut();
    setAuth(null);
    setCloudProfilesHydrated(true);
    setToast("Signed out of ARVIO Cloud.");
  }, []);

  const beginTrakt = useCallback(async () => {
    setDeviceCode(await traktClient.beginDeviceLink());
  }, []);

  const pollTrakt = useCallback(async () => {
    const code = deviceCodeRef.current;
    if (!code) return;
    await traktClient.pollDeviceToken(code.device_code);
    setTraktConnected(true);
    setDeviceCode(null);
    // Persist the token to cloud so other devices (and future sessions) see the
    // connection — parity with the Android app's traktTokens payload.
    if (traktClient.token && activeProfileId) {
      void saveCloudTraktToken(authClient, traktClient.token, activeProfileId).catch(() => undefined);
    }
    await refreshData();
  }, [refreshData, activeProfileId]);

  const disconnectTrakt = useCallback(() => {
    traktClient.disconnect();
    setTraktConnected(false);
  }, []);

  // Watchlist list-source switcher. Returns the user's custom Trakt lists to
  // populate the dropdown (built-in Watchlist/Collection are added by the UI).
  const loadTraktLists = useCallback(async (): Promise<Array<{ id: string; name: string }>> => {
    if (!traktClient.isConnected) return [];
    const lists = await traktClient.userLists().catch(() => []);
    return (lists as Array<Record<string, unknown>>)
      .map((list) => {
        const ids = (list.ids ?? {}) as Record<string, unknown>;
        const id = String(ids.trakt ?? ids.slug ?? "");
        const name = String(list.name ?? "").trim();
        return id && name ? { id, name } : null;
      })
      .filter((v): v is { id: string; name: string } => Boolean(v));
  }, []);

  // Fetch + hydrate a chosen list source into MediaItems. `source` is one of:
  // "watchlist", "collection", or "list:<id>".
  const fetchTraktListItems = useCallback(async (source: string, cacheKey: string): Promise<MediaItem[]> => {
    let rows: unknown[] = [];
    if (source === "collection") {
      const [movies, shows] = await Promise.all([
        traktClient.collection("movies").catch(() => []),
        traktClient.collection("shows").catch(() => [])
      ]);
      rows = [...(movies as unknown[]), ...(shows as unknown[])];
    } else if (source.startsWith("list:")) {
      rows = await traktClient.listItems(source.slice(5)).catch(() => []);
    } else {
      rows = await traktClient.watchlist().catch(() => []);
    }
    const hydrated = await hydrateTraktItems(rows.map(traktItemToMedia));
    if (hydrated.length) {
      saveCachedList(cacheKey, hydrated, 200);
    }
    return hydrated;
  }, []);

  // Returns cached items instantly (with a background refresh) when fresh, else
  // fetches. Cached per-source for instant re-selection.
  const loadTraktListItems = useCallback(async (source: string): Promise<MediaItem[]> => {
    if (!traktClient.isConnected) return [];
    const cacheKey = `arvio.web.traktlist.v1:${source}`;
    try {
      const cached = loadStored<{ at: number; items: MediaItem[] } | null>(cacheKey, null);
      if (cached?.items?.length && Date.now() - cached.at < 30 * 60 * 1000) {
        void fetchTraktListItems(source, cacheKey).catch(() => undefined);
        return cached.items;
      }
    } catch {
      // ignore cache errors
    }
    return fetchTraktListItems(source, cacheKey);
  }, [fetchTraktListItems]);

  const persistProfiles = useCallback((next: Profile[], activeId: string | null) => {
    setProfiles(next);
    setActiveProfileId(activeId);
    saveStored(PROFILES_KEY, next);
    saveStored(ACTIVE_PROFILE_KEY, activeId);
    void saveCloudProfiles(authClient, next, activeId).catch(() => undefined);
  }, []);

  const selectProfile = useCallback(async (profile: Profile) => {
    const updated = profiles.map((p) => (p.id === profile.id ? { ...p, lastUsedAt: Date.now() } : p));
    const switching = profile.id !== activeProfileId;
    persistProfiles(updated, profile.id);
    setManageMode(false);
    if (switching) {
      // Drop the previous profile's rows and seed from the new profile's cache
      // so its Continue Watching paints instantly. refreshKeyRef (updated by the
      // refreshData call below) invalidates any in-flight refresh for the old
      // profile before it can write its rows over these.
      const seededCw = readCachedList(cwCacheKeyFor(profile.id));
      cwSourceRef.current = seededCw.length ? "seed" : "none";
      setContinueWatching(seededCw);
      setCategories(seededCw.length ? [{ id: "continue_watching", title: "Continue Watching", items: seededCw }] : []);
      setWatchlist(readCachedList(watchlistCacheKeyFor(profile.id)));
      setWatchedKeys(new Set());
    }
    setView("app");
    setSection("home");
    void refreshData(profile.id);
  }, [profiles, activeProfileId, persistProfiles, refreshData]);

  const createProfile = useCallback(async (name: string, avatarColor: number, avatarId: number) => {
    const profile = makeProfile(name || "Profile", avatarColor || randomProfileColor(), avatarId);
    persistProfiles([...profiles, profile], activeProfileId);
  }, [profiles, activeProfileId, persistProfiles]);

  const updateProfileAction = useCallback(async (profile: Profile) => {
    persistProfiles(profiles.map((p) => (p.id === profile.id ? profile : p)), activeProfileId);
  }, [profiles, activeProfileId, persistProfiles]);

  const deleteProfileAction = useCallback(async (id: string) => {
    const next = profiles.filter((p) => p.id !== id);
    persistProfiles(next, activeProfileId === id ? null : activeProfileId);
  }, [profiles, activeProfileId, persistProfiles]);

  const switchProfile = useCallback(() => {
    setManageMode(false);
    setView("profiles");
  }, []);

  const goToLogin = useCallback(() => {
    if (typeof window !== "undefined") {
      const redirectUri = window.location.origin + "/";
      const portalUrl = getAuthPortalUrl();
      window.location.href = `${portalUrl}?redirect_uri=${encodeURIComponent(redirectUri)}`;
    }
  }, []);
  const backToProfiles = useCallback(() => setView("profiles"), []);

  const value = useMemo<AppStore>(() => ({
    view,
    cloudLoginRequired,
    profiles,
    activeProfile,
    avatarImages,
    manageMode,
    setManageMode,
    selectProfile,
    createProfile,
    updateProfile: updateProfileAction,
    deleteProfile: deleteProfileAction,
    switchProfile,
    goToLogin,
    backToProfiles,
    section,
    setSection,
    categories,
    catalogConfigs,
    loadCatalogRow,
    homeServerRows,
    continueWatching,
    watchlist,
    isWatched,
    markWatchedLocally,
    hero,
    setHeroPreview,
    selected,
    streams,
    selectedEpisode,
    loadEpisodeStreams,
    advanceEpisode,
    activeStream,
    activeChannel,
    addons,
    iptvSnapshot,
    query,
    setQuery,
    results,
    settings,
    setSettings,
    updateSettings,
    auth,
    traktConnected,
    deviceCode,
    busy,
    toast,
    setToast,
    refreshData,
    refreshIptv,
    loadIptvGuide,
    openDetails,
    closeDetails,
    playStream,
    playTrailer,
    playChannel,
    playCatchup,
    closePlayer,
    installAddon,
    removeAddon,
    setAddonsState,
    signIn,
    signOut,
    beginTrakt,
    pollTrakt,
    disconnectTrakt,
    loadTraktLists,
    loadTraktListItems
  }), [
    view, cloudLoginRequired, profiles, activeProfile, avatarImages, manageMode,
    selectProfile, createProfile, updateProfileAction, deleteProfileAction, switchProfile, goToLogin, backToProfiles,
    section, categories, catalogConfigs, loadCatalogRow, homeServerRows, continueWatching, watchlist, isWatched, hero, heroPreview, selected, streams, selectedEpisode, loadEpisodeStreams, advanceEpisode, activeStream, activeChannel,
    addons, iptvSnapshot, query, results, settings, auth, traktConnected, deviceCode, busy, toast,
    updateSettings, refreshData, openDetails, closeDetails, playStream, playTrailer, playChannel, playCatchup, closePlayer,
    refreshIptv, loadIptvGuide,
    installAddon, removeAddon, setAddonsState, signIn, signOut, beginTrakt, pollTrakt, disconnectTrakt,
    loadTraktLists, loadTraktListItems
  ]);

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}
