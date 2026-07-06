import type { ExternalPlayer } from "./externalPlayers";
import type { MediaItem, StreamSource } from "./types";

const PENDING_EXTERNAL_PLAYBACK_KEY = "arvio.web.pendingExternalPlayback";

export interface PendingExternalPlayback {
  id: string;
  player: ExternalPlayer;
  openedAt: number;
  title: string;
  profileId: string | null;
  mediaType: MediaItem["mediaType"];
  tmdbId: number;
  season: number | null;
  episode: number | null;
  episodeTitle: string | null;
  posterPath: string | null;
  backdropPath: string | null;
  durationSeconds: number;
  source: string;
  streamAddonId: string | null;
  streamTitle: string | null;
}

function pathFromImageUrl(url: string | null | undefined, marker: string) {
  if (!url) return null;
  const index = url.indexOf(marker);
  return index >= 0 ? url.slice(index + marker.length) : url;
}

function durationToSeconds(duration: string | undefined) {
  if (!duration) return 0;
  const hourMatch = duration.match(/(\d+)\s*h/i);
  const minuteMatch = duration.match(/(\d+)\s*m/i);
  const hours = hourMatch ? Number(hourMatch[1]) : 0;
  const minutes = minuteMatch ? Number(minuteMatch[1]) : 0;
  const total = (Number.isFinite(hours) ? hours : 0) * 3600 + (Number.isFinite(minutes) ? minutes : 0) * 60;
  return total > 0 ? total : 0;
}

export function createPendingExternalPlayback({
  player,
  item,
  stream,
  title,
  profileId,
  season,
  episode
}: {
  player: ExternalPlayer;
  item: MediaItem | null;
  stream: StreamSource;
  title: string;
  profileId: string | null;
  season?: number | null;
  episode?: number | null;
}) {
  if (!item?.id || item.isHomeServer) return null;
  const pending: PendingExternalPlayback = {
    id: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
    player,
    openedAt: Date.now(),
    title: item.title || title,
    profileId,
    mediaType: item.mediaType,
    tmdbId: item.id,
    season: season ?? item.seasonNumber ?? null,
    episode: episode ?? item.episodeNumber ?? null,
    episodeTitle: item.episodeTitle ?? null,
    posterPath: pathFromImageUrl(item.image, "/w780"),
    backdropPath: pathFromImageUrl(item.backdrop, "/w1280"),
    durationSeconds: durationToSeconds(item.duration),
    source: stream.addonName || stream.source || "",
    streamAddonId: stream.addonId ?? null,
    streamTitle: stream.source ?? null
  };
  savePendingExternalPlayback(pending);
  return pending;
}

export function savePendingExternalPlayback(pending: PendingExternalPlayback) {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(PENDING_EXTERNAL_PLAYBACK_KEY, JSON.stringify(pending));
}

export function loadPendingExternalPlayback() {
  if (typeof window === "undefined") return null;
  try {
    const raw = window.localStorage.getItem(PENDING_EXTERNAL_PLAYBACK_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as PendingExternalPlayback;
    if (!parsed?.id || !parsed.tmdbId || !parsed.mediaType) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function clearPendingExternalPlayback(id?: string) {
  if (typeof window === "undefined") return;
  const pending = loadPendingExternalPlayback();
  if (id && pending?.id !== id) return;
  window.localStorage.removeItem(PENDING_EXTERNAL_PLAYBACK_KEY);
}
