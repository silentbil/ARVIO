import { config } from "./config";
import { getDetails } from "./tmdb";
import { tmdbImageUrl } from "./mediaImages";
import type { MediaItem, WatchHistoryEntry } from "./types";

export function historyToItem(entry: WatchHistoryEntry): MediaItem {
  const title = entry.media_type === "tv" && entry.episode_title
    ? `${entry.title ?? "Series"}: ${entry.episode_title}`
    : entry.title ?? "Untitled";
  const progress = Math.round((entry.progress ?? 0) * 100);
  const remaining = Math.max(0, (entry.duration_seconds ?? 0) - (entry.position_seconds ?? 0));
  return {
    id: entry.show_tmdb_id,
    title,
    subtitle: entry.media_type === "tv" ? `S${entry.season ?? 1} E${entry.episode ?? 1}` : "Movie",
    mediaType: entry.media_type,
    image: tmdbImageUrl(config.imageBase, entry.poster_path),
    backdrop: tmdbImageUrl(config.backdropBase, entry.backdrop_path) || null,
    seasonNumber: entry.season ?? null,
    episodeNumber: entry.episode ?? null,
    episodeTitle: entry.episode_title ?? null,
    progress,
    activityAt: Date.parse(entry.updated_at ?? entry.paused_at ?? "") || 0,
    timeRemainingLabel: remaining > 0 ? `${Math.ceil(remaining / 60)}m left` : null
  };
}

export function traktItemToMedia(raw: unknown): MediaItem {
  const item = raw as {
    type?: string;
    listed_at?: string;
    movie?: { title?: string; year?: number; ids?: { tmdb?: number; trakt?: number; imdb?: string } };
    show?: { title?: string; year?: number; ids?: { tmdb?: number; trakt?: number; imdb?: string } };
  };
  const media = item.movie ?? item.show;
  const mediaType = item.type === "show" ? "tv" : "movie";
  return {
    id: media?.ids?.tmdb ?? media?.ids?.trakt ?? Math.floor(Math.random() * 1000000),
    title: media?.title ?? "Untitled",
    year: media?.year ? String(media.year) : "",
    subtitle: mediaType === "tv" ? "TV Series" : "Movie",
    mediaType,
    traktId: media?.ids?.trakt ?? null,
    imdbId: media?.ids?.imdb ?? null,
    // listed_at = when the user added it to their watchlist — the field
    // "Recently added" must sort by.
    activityAt: Date.parse(item.listed_at ?? "") || 0
  };
}

export function traktPlaybackToMedia(raw: unknown): MediaItem {
  const item = raw as {
    progress?: number;
    paused_at?: string;
    movie?: { title?: string; year?: number; ids?: { tmdb?: number; trakt?: number; imdb?: string } };
    show?: { title?: string; year?: number; ids?: { tmdb?: number; trakt?: number; imdb?: string } };
    episode?: { season?: number; number?: number; title?: string };
  };
  const media = item.movie ?? item.show;
  const isShow = Boolean(item.show);
  return {
    activityAt: Date.parse(item.paused_at ?? "") || Date.now(),
    id: media?.ids?.tmdb ?? media?.ids?.trakt ?? Math.floor(Math.random() * 1000000),
    title: isShow && item.episode?.title ? `${media?.title ?? "Series"}: ${item.episode.title}` : media?.title ?? "Untitled",
    year: media?.year ? String(media.year) : "",
    subtitle: isShow ? `S${item.episode?.season ?? 1} E${item.episode?.number ?? 1}` : "Movie",
    mediaType: isShow ? "tv" : "movie",
    traktId: media?.ids?.trakt ?? null,
    imdbId: media?.ids?.imdb ?? null,
    seasonNumber: item.episode?.season ?? null,
    episodeNumber: item.episode?.number ?? null,
    episodeTitle: item.episode?.title ?? null,
    progress: Math.round(item.progress ?? 0)
  };
}

export function traktUpNextToMedia(watchedRaw: unknown, progressRaw: unknown): MediaItem | null {
  const watched = watchedRaw as {
    last_watched_at?: string;
    last_updated_at?: string;
    show?: { title?: string; year?: number; ids?: { tmdb?: number; trakt?: number; imdb?: string } };
  };
  const progress = progressRaw as {
    aired?: number;
    completed?: number;
    last_watched_at?: string;
    next_episode?: { season?: number; number?: number; title?: string };
  } | null;
  const show = watched.show;
  const tmdbId = show?.ids?.tmdb;
  const nextEpisode = progress?.next_episode;
  if (!tmdbId || !nextEpisode?.season || !nextEpisode?.number) return null;
  const aired = Math.max(0, Number(progress?.aired ?? 0));
  const completed = Math.max(0, Number(progress?.completed ?? 0));
  if (aired > 0 && completed >= aired) return null;
  return {
    id: tmdbId,
    title: show?.title ?? "Untitled",
    year: show?.year ? String(show.year) : "",
    subtitle: `S${nextEpisode.season} E${nextEpisode.number}`,
    mediaType: "tv",
    traktId: show?.ids?.trakt ?? null,
    imdbId: show?.ids?.imdb ?? null,
    seasonNumber: nextEpisode.season,
    episodeNumber: nextEpisode.number,
    episodeTitle: nextEpisode.title ?? null,
    progress: aired > 0 ? Math.round((Math.min(completed, aired) / aired) * 100) : 0,
    badge: "Up Next",
    timeRemainingLabel: "Up next",
    activityAt: Date.parse(progress?.last_watched_at ?? watched.last_watched_at ?? watched.last_updated_at ?? "") || 0,
    releaseDate: progress?.last_watched_at ?? watched.last_watched_at ?? watched.last_updated_at ?? null
  };
}

export function traktHistoryToMedia(raw: unknown): MediaItem {
  const item = raw as {
    watched_at?: string;
    movie?: { title?: string; year?: number; ids?: { tmdb?: number; trakt?: number; imdb?: string } };
    show?: { title?: string; year?: number; ids?: { tmdb?: number; trakt?: number; imdb?: string } };
    episode?: { season?: number; number?: number; title?: string };
  };
  const media = item.movie ?? item.show;
  const isShow = Boolean(item.show);
  return {
    id: media?.ids?.tmdb ?? media?.ids?.trakt ?? Math.floor(Math.random() * 1000000),
    title: isShow && item.episode?.title ? `${media?.title ?? "Series"}: ${item.episode.title}` : media?.title ?? "Untitled",
    year: media?.year ? String(media.year) : "",
    subtitle: isShow ? `Watched S${item.episode?.season ?? 1} E${item.episode?.number ?? 1}` : "Watched movie",
    mediaType: isShow ? "tv" : "movie",
    traktId: media?.ids?.trakt ?? null,
    imdbId: media?.ids?.imdb ?? null,
    seasonNumber: item.episode?.season ?? null,
    episodeNumber: item.episode?.number ?? null,
    episodeTitle: item.episode?.title ?? null,
    badge: item.watched_at ? "Trakt" : undefined
  };
}

export function dedupeMedia(items: MediaItem[]) {
  const seen = new Set<string>();
  return items.filter((item) => {
    const key = `${item.mediaType}:${item.id}:${item.subtitle ?? ""}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export async function hydrateTraktItems(items: MediaItem[]) {
  // Hydrate the whole watchlist so nothing is silently dropped. getDetails is
  // cached, so re-renders don't re-fetch.
  const hydrated = await Promise.all(items.map((item) => getDetails(item).catch(() => item)));
  // getDetails merges TMDB data over the item but keeps activityAt (added date)
  // from the Trakt mapping via the spread; make sure it survives explicitly.
  return hydrated.map((item, index) => ({ ...item, activityAt: items[index]?.activityAt ?? item.activityAt, badge: index < 10 ? `#${index + 1}` : item.badge }));
}
