import { buildXtreamPlayerApiUrl, fetchXtreamJson, playlistProxyHeaders, xtreamInfoFromUrl, type XtreamInfo } from "./iptv";
import type { IptvPlaylistEntry, MediaItem, StreamSource } from "./types";

// IPTV VOD as a source (parity with the Android app): when an Xtream playlist
// carries a VOD/series library, match the opened title against it and inject the
// provider's on-demand file as a playable source. Matching order mirrors the
// app: TMDB id → IMDb id → normalized title + year.

type XtreamVodStream = {
  stream_id?: string | number;
  name?: string;
  year?: string | number;
  container_extension?: string;
  imdb?: string;
  imdb_id?: string;
  tmdb?: string | number;
  tmdb_id?: string | number;
  category_id?: string | number;
};

type XtreamSeries = {
  series_id?: string | number;
  name?: string;
  year?: string | number;
  tmdb?: string | number;
  tmdb_id?: string | number;
  imdb?: string;
  imdb_id?: string;
};

type XtreamEpisode = {
  id?: string | number;
  episode_num?: string | number;
  episode_number?: string | number;
  season?: string | number;
  title?: string;
  container_extension?: string;
  info?: { container_extension?: string };
};

type XtreamSeriesInfo = {
  episodes?: Record<string, XtreamEpisode[]>;
};

const VOD_CACHE = new Map<string, { at: number; movies: XtreamVodStream[]; series: XtreamSeries[] }>();
const VOD_TTL_MS = 30 * 60 * 1000;

function normalizeTitle(value: string) {
  return value
    .replace(/[[(][^\])]*[\])]/g, " ")
    .replace(/\b(19|20)\d{2}\b/g, " ")
    .replace(/\bS\d{1,2}\s*E\d{1,3}\b/gi, " ")
    .replace(/\b(1080p|2160p|720p|480p|4k|uhd|hdr|bluray|web-?dl|webrip|x265|x264|hevc|remux|imax|proper|extended|multi|dual)\b/gi, " ")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim()
    .replace(/\s+/g, " ");
}

function parseYear(value?: string | number): number | null {
  const str = String(value ?? "");
  const match = str.match(/\b(19|20)\d{2}\b/);
  return match ? Number(match[0]) : null;
}

function tmdbOf(entry: { tmdb?: string | number; tmdb_id?: string | number }): string {
  const raw = entry.tmdb ?? entry.tmdb_id;
  const str = String(raw ?? "").trim();
  return /^\d+$/.test(str) ? str : "";
}

function imdbOf(entry: { imdb?: string; imdb_id?: string }): string {
  const raw = (entry.imdb ?? entry.imdb_id ?? "").trim().toLowerCase();
  return /^tt\d+$/.test(raw) ? raw : "";
}

function xtreamInfoFromPlaylists(playlists: IptvPlaylistEntry[]): XtreamInfo | null {
  for (const playlist of playlists) {
    if (playlist.enabled === false) continue;
    const info = xtreamInfoFromUrl(playlist.epgUrl ?? "") ?? xtreamInfoFromUrl(playlist.m3uUrl ?? "");
    if (info) return info;
  }
  return null;
}

async function loadVodCatalog(info: XtreamInfo, userAgent?: string) {
  const cacheKey = `${info.baseUrl}|${info.username}`;
  const cached = VOD_CACHE.get(cacheKey);
  if (cached && Date.now() - cached.at < VOD_TTL_MS) return cached;
  const headers = playlistProxyHeaders(userAgent);
  const [movies, series] = await Promise.all([
    fetchXtreamJson<XtreamVodStream[]>(buildXtreamPlayerApiUrl(info.baseUrl, info.username, info.password, "get_vod_streams"), headers).catch(() => []),
    fetchXtreamJson<XtreamSeries[]>(buildXtreamPlayerApiUrl(info.baseUrl, info.username, info.password, "get_series"), headers).catch(() => [])
  ]);
  const entry = {
    at: Date.now(),
    movies: Array.isArray(movies) ? movies : [],
    series: Array.isArray(series) ? series : []
  };
  VOD_CACHE.set(cacheKey, entry);
  return entry;
}

function movieVodUrl(info: XtreamInfo, streamId: string | number, ext?: string) {
  const extension = (ext || "mkv").replace(/^\./, "");
  return `${info.baseUrl}/movie/${encodeURIComponent(info.username)}/${encodeURIComponent(info.password)}/${streamId}.${extension}`;
}

function seriesVodUrl(info: XtreamInfo, streamId: string | number, ext?: string) {
  const extension = (ext || "mkv").replace(/^\./, "");
  return `${info.baseUrl}/series/${encodeURIComponent(info.username)}/${encodeURIComponent(info.password)}/${streamId}.${extension}`;
}

function toSource(name: string, url: string, quality: string, addonName: string): StreamSource {
  return {
    source: name,
    addonName,
    addonId: "iptv_xtream_vod",
    quality,
    size: "",
    url,
    // Honest expectation: these play through the user's own IPTV line. Panels
    // gate streams by IP/region/player and can flip policy at any time — when
    // they refuse the user's network, NO player (browser, VLC, the app) can
    // open the stream, and that is invisible to us up front (the catalog API
    // and the media endpoints have separate access rules; e.g. tx-4kott serves
    // its catalog to datacenter egress while 403'ing media to the same client).
    description: "Streams via your IPTV line — if your provider blocks your network or region, no player can open it"
  };
}

function inferQuality(text: string): string {
  const upper = text.toUpperCase();
  if (upper.includes("2160") || upper.includes("4K") || upper.includes("UHD")) return "4K";
  if (upper.includes("1080")) return "1080p";
  if (upper.includes("720")) return "720p";
  return "HD";
}

export async function findMovieVodSources(
  playlists: IptvPlaylistEntry[],
  item: MediaItem,
  userAgent?: string
): Promise<StreamSource[]> {
  const info = xtreamInfoFromPlaylists(playlists);
  if (!info) return [];
  const { movies } = await loadVodCatalog(info, userAgent);
  if (!movies.length) return [];

  const wantTmdb = String(item.id);
  const wantImdb = item.imdbId?.toLowerCase() ?? "";
  const wantTitle = normalizeTitle(item.title);
  const wantYear = item.year ? Number(item.year) : parseYear(item.releaseDate ?? "");

  const idMatches = movies.filter((movie) =>
    (wantTmdb && tmdbOf(movie) === wantTmdb) || (wantImdb && imdbOf(movie) === wantImdb)
  );

  const matches = idMatches.length
    ? idMatches
    : movies.filter((movie) => {
        const title = normalizeTitle(movie.name ?? "");
        if (!title || title !== wantTitle) return false;
        const movieYear = parseYear(movie.year) ?? parseYear(movie.name);
        // Title match must agree on year (±1) when both are known.
        return !wantYear || !movieYear || Math.abs(movieYear - wantYear) <= 1;
      });

  return matches
    .filter((movie) => movie.stream_id !== undefined)
    .slice(0, 8)
    .map((movie) => {
      const name = movie.name?.trim() || item.title;
      return toSource(name, movieVodUrl(info, movie.stream_id!, movie.container_extension), inferQuality(name), "IPTV VOD");
    });
}

export async function findEpisodeVodSource(
  playlists: IptvPlaylistEntry[],
  item: MediaItem,
  season: number,
  episode: number,
  userAgent?: string
): Promise<StreamSource[]> {
  const info = xtreamInfoFromPlaylists(playlists);
  if (!info) return [];
  const { series } = await loadVodCatalog(info, userAgent);
  if (!series.length) return [];

  const wantTmdb = String(item.id);
  const wantImdb = item.imdbId?.toLowerCase() ?? "";
  const wantTitle = normalizeTitle(item.title);

  const match =
    series.find((entry) => (wantTmdb && tmdbOf(entry) === wantTmdb) || (wantImdb && imdbOf(entry) === wantImdb)) ??
    series.find((entry) => normalizeTitle(entry.name ?? "") === wantTitle);
  if (!match?.series_id) return [];

  const headers = playlistProxyHeaders(userAgent);
  const infoUrl = buildXtreamPlayerApiUrl(info.baseUrl, info.username, info.password, "get_series_info");
  const seriesInfo = await fetchXtreamJson<XtreamSeriesInfo>(`${infoUrl}&series_id=${match.series_id}`, headers).catch(() => null);
  const episodes = seriesInfo?.episodes?.[String(season)] ?? [];
  const found = episodes.find((ep) => Number(ep.episode_num ?? ep.episode_number) === episode);
  if (!found?.id) return [];

  const ext = found.container_extension ?? found.info?.container_extension;
  const name = found.title?.trim() || `${item.title} S${season}E${episode}`;
  return [toSource(name, seriesVodUrl(info, found.id, ext), inferQuality(name), "IPTV Series VOD")];
}
