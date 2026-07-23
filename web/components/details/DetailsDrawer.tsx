"use client";

import { BadgeCheck, Bookmark, CalendarDays, Clapperboard, Copy, Download, ExternalLink, Filter, MapPin, Play, Search, Star, Trash2, UserCircle, X } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import { MediaCard } from "@/components/media/MediaCard";
import { RailScroller } from "@/components/media/RailScroller";
import { config } from "@/lib/config";
import { createPendingExternalPlayback } from "@/lib/externalPlayback";
import { saveProgress } from "@/lib/cloud";
import { copyStreamUrl, downloadStreamUrl, downloadToVlc, externalLaunchMode, isAppleMobile, isDesktop, isLinux, isWindows, openExternalPlayer, openInAnyPlayer, setVlcProtocolReady, triggerDownload, vlcProtocolReady, VLC_SETUP_SH_URL, VLC_SETUP_URL } from "@/lib/externalPlayers";
import { fetchSubtitlesForItem } from "@/lib/addons";
import { cachedDebridDirectUrl, isUncachedDebridStream, parseDebridStream, prefetchDebridDirectUrl, resolveDebridDirectUrl } from "@/lib/debrid";
import { canonicalServiceName, IMDB_LOGO, serviceClearLogo } from "@/lib/serviceLogos";
import { sourcePickerScore } from "@/lib/sourceRank";
import { isBrowserPlayableStream, isDirectPlayableStream, streamPlayability } from "@/lib/streamCompatibility";
import { authClient, traktClient, useApp } from "@/lib/store";
import { getDetails, getLogoUrl, getPersonDetails, getReviews, getSeasonEpisodes } from "@/lib/tmdb";
import type { EpisodeInfo, InstalledAddon, MediaItem, PersonCredit, PersonDetails, ReviewInfo, StreamSource, SubtitleTrack } from "@/lib/types";

export function DetailsDrawer() {
  const { selected: item } = useApp();
  if (!item) return null;
  return <DetailsView key={`${item.mediaType}-${item.id}`} item={item} />;
}

function needsDetailsHydration(item: MediaItem) {
  // TV shows ALWAYS need a full details fetch: an item opened from a rail or
  // Continue Watching may carry a partial/stale `seasons` array (or one built
  // from a single episode), so trusting `seasons?.length` skipped hydration and
  // left the details page missing seasons/episodes. getDetails is cached, so
  // re-fetching a fully-hydrated item is cheap.
  if (item.mediaType === "tv") return true;
  return !item.cast?.length && !item.related?.length && !item.trailerUrl;
}

function DetailsView({ item }: { item: MediaItem }) {
  const { streams, selectedEpisode, activeProfile, addons: installedAddons, loadEpisodeStreams, openDetails, playStream, playTrailer, setToast, settings, watchlist, refreshData, busy, isWatched, markWatchedLocally } = useApp();
  const [detailsItem, setDetailsItem] = useState<MediaItem>(item);
  const [detailsLoading, setDetailsLoading] = useState(false);
  const [reviews, setReviews] = useState<ReviewInfo[]>([]);
  const [person, setPerson] = useState<PersonDetails | null>(null);
  const [personLoading, setPersonLoading] = useState(false);
  const [personVisible, setPersonVisible] = useState(false);
  const [sourcePickerVisible, setSourcePickerVisible] = useState(false);
  const [logo, setLogo] = useState<string | null>(null);
  const displayItem = detailsItem ?? item;

  useEffect(() => {
    window.scrollTo({ top: 0, left: 0, behavior: "auto" });
  }, [item.id, item.mediaType]);

  useEffect(() => {
    let active = true;
    setDetailsItem(item);
    if (!needsDetailsHydration(item)) {
      setDetailsLoading(false);
      return () => { active = false; };
    }
    setDetailsLoading(true);
    // getDetails swallows fetch errors and returns the bare item, so a proxy
    // hiccup (common mid-startup-burst) used to leave the page permanently
    // without seasons/cast. Retry until the payload actually looks hydrated.
    const looksHydrated = (details: MediaItem) => details.mediaType === "tv"
      ? Boolean(details.seasons?.length)
      : Boolean(details.cast?.length || details.related?.length || details.trailerUrl);
    void (async () => {
      for (let attempt = 0; attempt < 3 && active; attempt += 1) {
        const details = await getDetails(item).catch(() => null);
        if (!active) return;
        if (details) setDetailsItem(details);
        if (details && looksHydrated(details)) break;
        if (attempt < 2) await new Promise((resolve) => setTimeout(resolve, 900 * (attempt + 1)));
      }
      if (active) setDetailsLoading(false);
    })();
    return () => { active = false; };
  }, [item.id, item.mediaType]);

  useEffect(() => {
    let active = true;
    void getReviews(item).then((r) => active && setReviews(r)).catch(() => undefined);
    return () => { active = false; };
  }, [item.id, item.mediaType]);

  useEffect(() => {
    let active = true;
    setLogo(null);
    void getLogoUrl({ mediaType: item.mediaType, id: item.id }).then((url) => {
      if (active) setLogo(url);
    }).catch(() => undefined);
    return () => { active = false; };
  }, [item.id, item.mediaType]);

  const playableCount = streams.filter(isBrowserPlayableStream).length;
  const isTv = displayItem.mediaType === "tv";
  const inWatchlist = watchlist.some((entry) => entry.mediaType === item.mediaType && entry.id === item.id);
  const canPlayBest = streams.length > 0;
  const detailWatched = isWatched(displayItem, selectedEpisode?.season, selectedEpisode?.episode);
  const continueLabel = buildContinueLabel(displayItem, selectedEpisode);
  const detailMeta = buildDetailMeta(displayItem, settings.showBudget);
  // Clearlogos (bundled from the app, no background tiles). Providers we have
  // no clearlogo for are skipped rather than shown as white TMDB tiles.
  const serviceLogos = [...(displayItem.providerLogos ?? []), ...(displayItem.networkLogos ?? [])]
    .map((logo) => ({ name: canonicalServiceName(logo.name), logo: serviceClearLogo(logo.name) }))
    .filter((logo): logo is { name: string; logo: string } => Boolean(logo.logo))
    .filter((logo, index, arr) => arr.findIndex((candidate) => candidate.name === logo.name) === index)
    .slice(0, 4);

  const playBest = () => {
    if (isTv && !selectedEpisode) {
      setToast(isTv ? "Pick an episode to find sources first." : "No sources found yet.");
      return;
    }
    setSourcePickerVisible(true);
  };

  const openEpisodeSources = async (season: number, episode: number) => {
    setSourcePickerVisible(true);
    const found = await loadEpisodeStreams(displayItem, season, episode);
    if (!found.length) setToast("No sources found for this episode yet.");
  };

  const addToWatchlist = async () => {
    if (!traktClient.isConnected) {
      setToast("Connect Trakt in Settings to use Watchlist.");
      return;
    }
    try {
      await traktClient.addToWatchlist({ mediaType: item.mediaType, tmdbId: item.id });
      setToast("Added to Trakt watchlist.");
      void refreshData();
    } catch (error) {
      setToast(error instanceof Error ? error.message : "Could not add to watchlist.");
    }
  };

  const removeFromWatchlist = async () => {
    if (!traktClient.isConnected) {
      setToast("Connect Trakt in Settings to remove watchlist items.");
      return;
    }
    try {
      await traktClient.removeFromWatchlist({ mediaType: item.mediaType, tmdbId: item.id });
      setToast("Removed from Trakt watchlist.");
      void refreshData();
    } catch (error) {
      setToast(error instanceof Error ? error.message : "Could not remove watchlist item.");
    }
  };

  const markWatched = async () => {
    const season = selectedEpisode?.season ?? item.seasonNumber ?? null;
    const episode = selectedEpisode?.episode ?? item.episodeNumber ?? null;
    const alreadyWatched = detailWatched;
    // Update the badge + Continue Watching instantly, before Trakt round-trips.
    markWatchedLocally({ mediaType: displayItem.mediaType, id: displayItem.id, season, episode }, !alreadyWatched);
    let saved = false;
    try {
      if (authClient.session) {
        await saveProgress(authClient, {
          media_type: displayItem.mediaType,
          show_tmdb_id: displayItem.id,
          profile_id: activeProfile?.id ?? null,
          season,
          episode,
          episode_title: displayItem.episodeTitle ?? null,
          title: displayItem.title,
          progress: alreadyWatched ? 0 : 1,
          duration_seconds: 1,
          position_seconds: alreadyWatched ? 0 : 1,
          backdrop_path: displayItem.backdrop?.replace(config.backdropBase, "") ?? null,
          poster_path: displayItem.image?.replace(config.imageBase, "") ?? null
        }, activeProfile?.id ?? null);
        saved = true;
      }
      if (traktClient.isConnected) {
        // Register in Trakt history so the watched badge syncs everywhere (parity
        // with the app). Toggle removes it from history.
        const ref = { mediaType: displayItem.mediaType, tmdbId: displayItem.id, season, episode };
        if (alreadyWatched) await traktClient.removeFromHistory(ref);
        else await traktClient.addToHistory(ref);
        saved = true;
      }
      setToast(saved ? (alreadyWatched ? "Removed from watched." : "Marked as watched.") : "Connect ARVIO Cloud or Trakt to sync watched state.");
      if (saved) void refreshData();
    } catch (error) {
      setToast(error instanceof Error ? error.message : "Could not update watched state.");
    }
  };

  const openPerson = async (castMember: PersonCredit) => {
    setPersonVisible(true);
    setPersonLoading(true);
    setPerson(null);
    try {
      const details = await getPersonDetails(castMember.id);
      if (!details) {
        setToast("Could not load cast details.");
        setPersonVisible(false);
        return;
      }
      setPerson(details);
    } catch (error) {
      setToast(error instanceof Error ? error.message : "Could not load cast details.");
      setPersonVisible(false);
    } finally {
      setPersonLoading(false);
    }
  };

  return (
    <article className="details-drawer">
      <div className="detail-backdrop" style={{ backgroundImage: displayItem.backdrop ? `url(${displayItem.backdrop})` : undefined }} />
      <div className="detail-body">
        <section className="detail-hero-content">
          <div className="detail-title-line">
            {logo ? <img className="detail-clearlogo" src={logo} alt={displayItem.title} /> : <h2>{displayItem.title}</h2>}
          </div>
          <p>{displayItem.overview || "No overview available."}</p>
          <div className="detail-rating-line">
            {displayItem.rating ? (
              <span className="imdb-lockup">
                <img src={IMDB_LOGO} alt="IMDb" />
                <b>{displayItem.rating}</b>
              </span>
            ) : null}
            {detailWatched && <span className="detail-watched-chip"><BadgeCheck size={13} /> Watched</span>}
            {displayItem.genres?.slice(0, 3).map((genre) => <span key={genre}>{genre}</span>)}
          </div>
          <div className="chips detail-metadata">
            {detailMeta.map((meta) => <span key={meta}>{meta}</span>)}
            {streams.length > 0 && <span>{playableCount}/{streams.length} web playable</span>}
          </div>
          {serviceLogos.length ? (
            <div className="detail-service-logos" aria-label="Streaming and network availability">
              {serviceLogos.map((service) => (
                <span key={service.name} title={service.name}>
                  <img src={service.logo} alt={service.name} />
                </span>
              ))}
            </div>
          ) : null}
          <div className="detail-actions">
            <button type="button" className="primary" onClick={playBest}>
              <Play size={18} fill="currentColor" /> {continueLabel}
            </button>
            {inWatchlist ? (
              <button type="button" className="secondary text-button" onClick={() => void removeFromWatchlist()}><Trash2 size={18} /> Remove</button>
            ) : (
              <button type="button" className="secondary text-button" onClick={() => void addToWatchlist()}><Bookmark size={18} /> Watchlist</button>
            )}
            <button type="button" className={`secondary text-button ${detailWatched ? "is-active" : ""}`} onClick={() => void markWatched()}><BadgeCheck size={18} /> {detailWatched ? "Watched" : "Mark Watched"}</button>
            {displayItem.trailerUrl && (
              <button type="button" className="secondary text-button" onClick={() => void playTrailer(displayItem)}>
                <Play size={18} fill="currentColor" /> Trailer
              </button>
            )}
          </div>
          {!canPlayBest && !isTv && (
            <p className="detail-action-hint">
              {streams.length ? "The installed addons returned sources, but none have a direct browser-playable URL yet." : "Sources will appear here when an installed addon returns results."}
            </p>
          )}
        </section>

        <div className="detail-layout">
          {isTv ? (
            <SeasonEpisodes item={displayItem} loadingDetails={detailsLoading} selectedEpisode={selectedEpisode} isWatched={isWatched} onPlayEpisode={(s, e) => void openEpisodeSources(s, e)} />
          ) : null}

          {displayItem.cast?.length ? (
            <section className="detail-section detail-wide">
              <h3>Cast</h3>
              <RailScroller className="mini-strip" ariaLabel="cast">
                {displayItem.cast.map((person) => (
                  <button type="button" className="mini-card person cast-card" key={person.id} onClick={() => void openPerson(person)}>
                    {person.image ? <img src={person.image} alt="" /> : <UserCircle size={30} />}
                    <strong>{person.name}</strong>
                    <span>{person.character || "Cast"}</span>
                  </button>
                ))}
              </RailScroller>
            </section>
          ) : null}

          {reviews.length > 0 && (
            <section className="detail-section detail-wide">
              <h3>Reviews</h3>
              <div className="review-list">
                {reviews.map((review) => (
                  <article className="review-card" key={review.id}>
                    <div className="review-head">
                      {review.avatar ? <img src={review.avatar} alt="" /> : <UserCircle size={26} />}
                      <strong>{review.author}</strong>
                      {review.rating != null && <span className="review-rating"><Star size={13} fill="currentColor" /> {review.rating}</span>}
                    </div>
                    <p>{review.content.length > 600 ? `${review.content.slice(0, 600)}...` : review.content}</p>
                  </article>
                ))}
              </div>
            </section>
          )}

          {displayItem.related?.length ? (
            <section className="detail-section related detail-wide">
              <h3>More Like This</h3>
              <RailScroller className="rail-strip compact" ariaLabel="more like this">
                {displayItem.related.map((related) => (
                  <MediaCard
                    key={`related-${related.mediaType}-${related.id}`}
                    item={related}
                    onOpen={openDetails}
                    posterMode={settings.cardLayoutMode === "poster"}
                  />
                ))}
              </RailScroller>
            </section>
          ) : null}
        </div>
      </div>
      <PersonModal
        visible={personVisible}
        loading={personLoading}
        person={person}
        onClose={() => setPersonVisible(false)}
        onOpenMedia={(media) => {
          setPersonVisible(false);
          void openDetails(media);
        }}
        posterMode={settings.cardLayoutMode === "poster"}
      />
      <SourcePickerModal
        visible={sourcePickerVisible}
        item={displayItem}
        streams={streams}
        installedAddons={installedAddons}
        selectedEpisode={selectedEpisode}
        activeProfileId={activeProfile?.id ?? null}
        onClose={() => setSourcePickerVisible(false)}
        onPlay={(stream) => {
          playStream(stream);
          if (stream.url) setSourcePickerVisible(false);
        }}
        onToast={setToast}
        loading={busy === "Finding sources"}
      />
    </article>
  );
}

function SourcePickerModal({
  visible,
  item,
  streams,
  installedAddons,
  selectedEpisode,
  activeProfileId,
  onClose,
  onPlay,
  onToast,
  loading
}: {
  visible: boolean;
  item: MediaItem;
  streams: StreamSource[];
  installedAddons: InstalledAddon[];
  selectedEpisode: { season: number; episode: number } | null;
  activeProfileId: string | null;
  onClose: () => void;
  onPlay: (stream: StreamSource) => void;
  onToast: (message: string) => void;
  loading: boolean;
}) {
  const { settings } = useApp();
  const [addonFilter, setAddonFilter] = useState("all");
  const [mode, setMode] = useState<"all" | "playable">("all");
  const [query, setQuery] = useState("");
  // Windows-only: offer the one-time vlc:// setup so "Open in VLC" launches VLC
  // directly instead of downloading a .m3u. Hidden once the user has set it up.
  // macOS is excluded — VLC self-registers vlc:// there, so no installer is
  // needed (and the .bat wouldn't run on a Mac anyway).
  const [vlcReady, setVlcReady] = useState<boolean>(() => vlcProtocolReady());
  const showVlcSetup = (isWindows() || isLinux()) && !vlcReady;
  const enableVlcProtocol = () => {
    // Download the tiny installer script for the platform, then remember the user set it up
    if (isLinux()) {
      triggerDownload(VLC_SETUP_SH_URL, "vlc-setup.sh");
      setVlcProtocolReady(true);
      setVlcReady(true);
      onToast("Run `bash vlc-setup.sh` once in terminal — then Open in VLC works instantly.");
    } else {
      triggerDownload(VLC_SETUP_URL, "vlc-setup.bat");
      setVlcProtocolReady(true);
      setVlcReady(true);
      onToast("Run the downloaded vlc-setup.bat once — then Open in VLC works instantly.");
    }
  };
  // Addon subtitles for this title, fetched in the background when the panel
  // opens so the VLC/Infuse buttons can attach them synchronously on click.
  const [panelSubtitles, setPanelSubtitles] = useState<SubtitleTrack[]>([]);

  useEffect(() => {
    if (!visible) return undefined;
    setAddonFilter("all");
    setMode("all");
    setQuery("");
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose, visible]);

  useEffect(() => {
    setPanelSubtitles([]);
    if (!visible || !item) return undefined;
    let active = true;
    void fetchSubtitlesForItem(
      installedAddons,
      item,
      selectedEpisode?.season ?? item.seasonNumber ?? undefined,
      selectedEpisode?.episode ?? item.episodeNumber ?? undefined
    ).then((subs) => { if (active) setPanelSubtitles(subs); }).catch(() => undefined);
    return () => { active = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, item?.id, selectedEpisode?.season, selectedEpisode?.episode]);

  const addons = useMemo(() => {
    // Only surface addons that actually returned sources for this title. Seeding a
    // chip for every installed stream-capable addon meant subtitle providers
    // (OpenSubtitles, Ktuvit, Wizdom) — whose manifests also declare a "stream"
    // resource — showed up as empty source filters. A name lookup from the
    // installed list keeps the pretty addon names.
    const nameById = new Map(installedAddons.map((addon) => [addon.id, addon.name]));
    const unique = new Map<string, { id: string; name: string; count: number }>();
    streams.forEach((stream) => {
      const id = stream.addonId || stream.addonName;
      const existing = unique.get(id);
      unique.set(id, {
        id,
        name: existing?.name || nameById.get(id) || stream.addonName,
        count: (existing?.count ?? 0) + 1
      });
    });
    return Array.from(unique.values());
  }, [installedAddons, streams]);

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return streams.filter((stream) => {
      if (addonFilter !== "all" && (stream.addonId || stream.addonName) !== addonFilter) return false;
      if (mode === "playable" && !isBrowserPlayableStream(stream)) return false;
      if (!needle) return true;
      return `${stream.source} ${stream.addonName} ${stream.description ?? ""} ${stream.quality ?? ""} ${stream.size ?? ""}`.toLowerCase().includes(needle);
    }).sort((a, b) => sourcePickerScore(b) - sourcePickerScore(a));
  }, [addonFilter, mode, query, streams]);

  // Warm the direct CDN URLs of the top debrid picks while the user is still
  // looking at the list — pressing Play then skips the resolver round-trips
  // (which ride the Netlify proxy in production and cost 1-3s each).
  const prefetchSignature = filtered.slice(0, 3).map((stream) => stream.url ?? "").join("|");
  useEffect(() => {
    filtered.slice(0, 3).forEach((stream) => {
      if (!isUncachedDebridStream(stream)) prefetchDebridDirectUrl(stream.url);
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [prefetchSignature]);

  const playable = streams.filter(isBrowserPlayableStream).length;
  const title = selectedEpisode ? `${item.title} - S${selectedEpisode.season} E${selectedEpisode.episode}` : item.title;
  const openExternal = (player: "vlc" | "infuse", stream: StreamSource) => {
    if (!stream.url) {
      onToast("This source has no direct URL for an external player.");
      return;
    }
    // Build the launch target. Use the prefetch-cached CDN url if we already
    // have it; otherwise hand VLC the raw source (it follows the redirect
    // itself). Subtitles are attached only when synchronously present.
    const cachedCdn = parseDebridStream(stream.url) ? cachedDebridDirectUrl(stream.url) : null;
    let target = cachedCdn ? { ...stream, url: cachedCdn, originalUrl: stream.url } : stream;
    if (!target.subtitles?.length && panelSubtitles.length) target = { ...target, subtitles: panelSubtitles };
    // Persist the pending record and toast BEFORE launching. Both are
    // synchronous (localStorage / React state) so they don't consume the user
    // gesture, and writing first guarantees the return-to-app "mark finished"
    // prompt has its record even if the scheme navigation freezes/unloads the
    // page the instant it fires.
    onToast(
      player === "infuse"
        ? "Opening in Infuse..."
        : externalLaunchMode("vlc") === "playlist"
          ? "VLC playlist saved — open it from your downloads to play."
          : "Opening in VLC..."
    );
    createPendingExternalPlayback({
      player,
      item,
      stream: target,
      title,
      profileId: activeProfileId,
      season: selectedEpisode?.season ?? item.seasonNumber ?? null,
      episode: selectedEpisode?.episode ?? item.episodeNumber ?? null
    });
    // The deep link MUST fire synchronously inside the click — iOS Safari blocks
    // navigation to custom schemes (vlc-x-callback://) once the user gesture is
    // lost, so the app would appear to do nothing. Launch last.
    openExternalPlayer(player, target, title, settings.defaultSubtitle);
  };
  // Android: open in whichever player the user picks (VLC, MX Player, …) via the
  // system chooser — the equivalent of the iOS-only Infuse button.
  const openAnyPlayer = (stream: StreamSource) => {
    if (!stream.url) {
      onToast("This source has no direct URL for an external player.");
      return;
    }
    const cachedCdn = parseDebridStream(stream.url) ? cachedDebridDirectUrl(stream.url) : null;
    let target = cachedCdn ? { ...stream, url: cachedCdn, originalUrl: stream.url } : stream;
    if (!target.subtitles?.length && panelSubtitles.length) target = { ...target, subtitles: panelSubtitles };
    onToast("Opening in your player…");
    createPendingExternalPlayback({
      player: "vlc",
      item,
      stream: target,
      title,
      profileId: activeProfileId,
      season: selectedEpisode?.season ?? item.seasonNumber ?? null,
      episode: selectedEpisode?.episode ?? item.episodeNumber ?? null
    });
    openInAnyPlayer(target, title, settings.defaultSubtitle);
  };
  const copyUrl = async (stream: StreamSource) => {
    const copied = await copyStreamUrl(stream).catch(() => false);
    onToast(copied ? "Stream URL copied." : "Could not copy this stream URL.");
  };
  const downloadSource = async (stream: StreamSource) => {
    if (!stream.url) {
      onToast("This source has no direct URL to download.");
      return;
    }
    // Resolve debrid streams to the final CDN file URL first (the download
    // proxy must get the real file, not the torrentio redirect chain, which
    // 403s server egress). Hard 20s timeout — jsonRequest has none, and a
    // stalled proxy call would leave "Preparing" up forever.
    let target = stream;
    const debridInfo = parseDebridStream(stream.url);
    if (debridInfo) {
      onToast("Preparing download...");
      const direct = cachedDebridDirectUrl(stream.url) ?? await Promise.race([
        resolveDebridDirectUrl(debridInfo).then((result) => result?.url ?? null).catch(() => null),
        new Promise<null>((resolve) => setTimeout(() => resolve(null), 20_000))
      ]);
      if (!direct) {
        onToast("Could not prepare this download — the source may not be cached. Try a [TB+] source.");
        return;
      }
      target = { ...stream, url: direct, originalUrl: stream.url };
    }
    // iOS/iPadOS can't reliably download a multi-GB file in a browser tab —
    // WebKit buffers the whole response in memory and hits the per-tab limit at a
    // random point, with no resume (every browser on iOS is WebKit, incl.
    // Chrome). Hand the resolved direct URL to VLC's `download` action instead:
    // it saves the file to VLC's own storage (outside the browser memory limit)
    // for offline playback on the device. Desktop keeps the normal file download.
    if (isAppleMobile()) {
      const ok = downloadToVlc(target, title, settings.defaultSubtitle);
      onToast(ok
        ? "Downloading to VLC for offline playback. If VLC doesn't open, install it from the App Store."
        : "Could not hand this download to VLC.");
      return;
    }
    const href = downloadStreamUrl(target, title);
    if (!href) {
      onToast("Could not start this download.");
      return;
    }
    // Programmatic <a download> click — reliable in real browser tabs AND
    // installed PWAs, and keeps the app in place (the old window.location.href
    // navigated the whole tab, so a rate-limited/expired download left a blank
    // page with no feedback). The download proxy sets Content-Disposition:
    // attachment, so the browser's download manager owns the transfer.
    const started = triggerDownload(href, `${title}.${/\.mp4(?:[?#/]|$)/i.test(`${target.url} ${target.source ?? ""}`) ? "mp4" : "mkv"}`);
    onToast(started ? "Download started — check your browser downloads." : "Could not start this download.");
  };

  if (!visible || typeof document === "undefined") return null;
  return createPortal(
    <section className="source-modal" role="dialog" aria-modal="true" aria-label="Choose source">
      <div className="source-modal-bg" onClick={onClose} />
      <div className="source-panel">
        <header className="source-panel-head">
          <div>
            <p className="eyebrow">sources</p>
            <h2>{title}</h2>
            <span>{playable}/{streams.length} browser playable. Highest quality and largest files are shown first.</span>
          </div>
          <button type="button" className="person-close" onClick={onClose} aria-label="Close source picker"><X size={24} /></button>
        </header>

        {showVlcSetup && (
          <div className="vlc-setup-hint">
            <span>Windows/desktop: enable one-click "Open in VLC" (no more .m3u download).</span>
            <button type="button" onClick={enableVlcProtocol}>Set up VLC integration</button>
          </div>
        )}

        <div className="source-toolbar">
          <label className="source-search">
            <Search size={18} />
            <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search quality, release, provider" />
          </label>
          <div className="source-filter-group" aria-label="Source mode">
            <button type="button" className={mode === "all" ? "is-active" : ""} onClick={() => setMode("all")}><Filter size={16} /> All sources</button>
            <button type="button" className={mode === "playable" ? "is-active" : ""} onClick={() => setMode("playable")}>Browser playable</button>
          </div>
        </div>

        <div className="source-addon-tabs">
          <button type="button" className={addonFilter === "all" ? "is-active" : ""} onClick={() => setAddonFilter("all")}>All Addons</button>
          <button
            type="button"
            className={mode === "playable" ? "is-active" : ""}
            onClick={() => setMode(mode === "playable" ? "all" : "playable")}
          >
            Browser playable{playable > 0 ? ` ${playable}` : ""}
          </button>
          {addons.map((addon) => (
            <button type="button" key={addon.id} className={addonFilter === addon.id ? "is-active" : ""} onClick={() => setAddonFilter(addon.id)}>
              {addon.name}{addon.count > 0 ? ` ${addon.count}` : ""}
            </button>
          ))}
        </div>

        <div className="source-picker-list">
          {filtered.length === 0 && (
            <p className="source-empty">
              {loading
                ? "Searching addons..."
                : addonFilter !== "all"
                  ? `${addons.find((addon) => addon.id === addonFilter)?.name ?? "This addon"} returned no sources for this title.`
                  : "No sources match this filter."}
            </p>
          )}
          {filtered.map((stream, index) => {
            const locked = !stream.url;
            const playability = streamPlayability(stream);
            const playable = playability.mode === "direct" || playability.mode === "remux" || playability.mode === "transcode";
            const uncached = isUncachedDebridStream(stream);
            const statusLabel = uncached
              ? "Not cached — downloads first, slow start"
              : "Use of external player is recommended";
            const statusClass = uncached ? "needs-vlc" : "recommend-external";
            return (
              <article key={`${stream.addonId}-${stream.source}-${index}`} className={`source-picker-row ${locked ? "is-locked" : ""}`}>
                <span className="source-rank">{index + 1}</span>
                <span className="source-main">
                  <strong>{stream.source || stream.addonName}</strong>
                  <em>{stream.addonName}{stream.description ? ` - ${stream.description}` : ""}</em>
                  <span className="source-status-line">
                    <span className={`source-playback-status ${statusClass}`}>
                      {statusLabel}
                    </span>
                  </span>
                  <span className="stream-badges">
                    {streamBadges(stream).map((badge) => (
                      <span key={badge.label} className={`stream-badge ${badge.tone ?? ""}`}>{badge.label}</span>
                    ))}
                  </span>
                </span>
                <span className="source-side">
                  <b>{stream.quality || "HD"}</b>
                  <small>{locked ? "Needs resolver" : playability.mode === "direct" ? "Browser" : playability.mode === "remux" ? "Remux" : playability.mode === "transcode" ? "Transcode" : "External"}</small>
                  <span className="source-row-actions">
                    <button type="button" className="source-action primary-action" disabled={locked || !playable} onClick={() => onPlay(stream)}>
                      <Play size={13} fill="currentColor" /> Play
                    </button>
                    <button type="button" className="source-action" disabled={locked} onClick={() => openExternal("vlc", stream)}>
                      <ExternalLink size={13} /> VLC
                    </button>
                    <button type="button" className="source-action" disabled={locked} onClick={() => openAnyPlayer(stream)}>
                      <ExternalLink size={13} /> Player
                    </button>
                    <button type="button" className="source-action icon-only" disabled={locked} onClick={() => void downloadSource(stream)} aria-label="Download this source">
                      <Download size={13} />
                    </button>
                    <button type="button" className="source-action icon-only" disabled={locked} onClick={() => void copyUrl(stream)} aria-label="Copy stream URL">
                      <Copy size={13} />
                    </button>
                  </span>
                </span>
              </article>
            );
          })}
        </div>
      </div>
    </section>,
    document.body
  );
}

function PersonModal({
  visible,
  loading,
  person,
  onClose,
  onOpenMedia,
  posterMode
}: {
  visible: boolean;
  loading: boolean;
  person: PersonDetails | null;
  onClose: () => void;
  onOpenMedia: (item: MediaItem) => void;
  posterMode: boolean;
}) {
  useEffect(() => {
    if (!visible) return undefined;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose, visible]);

  if (!visible) return null;
  if (typeof document === "undefined") return null;
  return createPortal(
    <section className="person-modal" role="dialog" aria-modal="true" aria-label={person?.name ?? "Cast details"}>
      <div className="person-modal-bg" onClick={onClose} />
      <div className="person-panel">
        <button type="button" className="person-close" onClick={onClose} aria-label="Close cast details"><X size={24} /></button>
        {loading ? (
          <div className="person-loading">Loading cast details...</div>
        ) : person ? (
          <>
            <aside className="person-sidebar">
              <div className="person-photo">
                {person.profilePath ? <img src={person.profilePath} alt="" /> : <UserCircle size={74} />}
              </div>
              <h2>{person.name}</h2>
              <div className="person-facts">
                {person.birthday ? <span><CalendarDays size={15} /> {formatDate(person.birthday)}</span> : null}
                {person.placeOfBirth ? <span><MapPin size={15} /> {person.placeOfBirth}</span> : null}
              </div>
            </aside>
            <div className="person-content">
              {person.biography ? (
                <section>
                  <p className="eyebrow">Biography</p>
                  <p className="person-bio">{person.biography}</p>
                </section>
              ) : null}
              {person.knownFor.length ? (
                <section>
                  <p className="eyebrow">Known For</p>
                  <RailScroller className={`person-known-rail ${posterMode ? "is-poster" : ""}`} ariaLabel={`${person.name} known for`}>
                    {person.knownFor.map((item) => (
                      <button type="button" className="person-known-card" key={`${item.mediaType}-${item.id}`} onClick={() => onOpenMedia(item)}>
                        <div>{item.backdrop || item.image ? <img src={item.backdrop || item.image} alt="" /> : <Clapperboard size={32} />}</div>
                        <strong>{item.title}</strong>
                        <span>{item.subtitle || item.year || (item.mediaType === "tv" ? "Series" : "Movie")}</span>
                      </button>
                    ))}
                  </RailScroller>
                </section>
              ) : null}
            </div>
          </>
        ) : null}
      </div>
    </section>,
    document.body
  );
}

function formatDate(date: string) {
  const parsed = new Date(`${date}T00:00:00`);
  if (Number.isNaN(parsed.getTime())) return date;
  return parsed.toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}

function streamBadges(stream: StreamSource) {
  const text = `${stream.source} ${stream.description ?? ""} ${stream.size ?? ""}`.toLowerCase();
  const labels: Array<{ label: string; tone?: string }> = [];
  const quality = stream.quality || detectSourceBadge(text);
  if (quality) labels.push({ label: quality, tone: quality === "4K" ? "gold" : "" });
  if (text.includes("hdr10+") || text.includes("hdr")) labels.push({ label: "HDR" });
  if (text.includes("dolby vision") || /\bdv\b/i.test(text)) labels.push({ label: "DV" });
  if (text.includes("atmos")) labels.push({ label: "ATMOS" });
  else if (text.includes("7.1")) labels.push({ label: "7.1" });
  else if (text.includes("5.1")) labels.push({ label: "5.1" });
  const size = stream.size || text.match(/\b\d+(?:\.\d+)?\s?(?:gb|mb|tb)\b/i)?.[0]?.toUpperCase();
  if (size) labels.push({ label: size });
  if (stream.behaviorHints?.cached) labels.push({ label: "CACHED", tone: "ok" });
  if (parseDebridStream(stream.url) || /real-?debrid|premiumize|alldebrid|torbox|\brd\b|\bpm\b|\bad\b|\bdebrid\b/i.test(text)) labels.push({ label: "DEBRID", tone: "ok" });
  if (stream.url) labels.push({ label: "DIRECT", tone: "ok" });
  const mode = streamPlayability(stream).mode;
  if (mode === "direct") labels.push({ label: "WEB", tone: "ok" });
  else if (mode === "remux") labels.push({ label: "REMUX", tone: "ok" });
  else if (mode === "transcode") labels.push({ label: "TRANSCODE", tone: "ok" });
  else if (stream.url) labels.push({ label: "TRY", tone: "warn" });
  if (!stream.url) labels.push({ label: "ANDROID", tone: "warn" });
  const seen = new Set<string>();
  return labels.filter((badge) => {
    if (seen.has(badge.label)) return false;
    seen.add(badge.label);
    return true;
  }).slice(0, 7);
}

function detectSourceBadge(text: string) {
  if (text.includes("2160") || text.includes("4k")) return "4K";
  if (text.includes("1080")) return "1080p";
  if (text.includes("720")) return "720p";
  if (text.includes("480")) return "480p";
  return "";
}

function buildDetailMeta(item: MediaItem, showBudget: boolean) {
  const meta = [
    item.releaseDate || item.year || null,
    item.duration || null,
    item.mediaType === "tv" && item.numberOfSeasons ? `${item.numberOfSeasons} season${item.numberOfSeasons === 1 ? "" : "s"}` : null,
    item.mediaType === "tv" && item.numberOfEpisodes ? `${item.numberOfEpisodes} episodes` : null,
    item.mediaType === "tv" && item.lastAirDate ? `Last aired ${item.lastAirDate}` : null,
    item.status || null,
    item.originalLanguage ? item.originalLanguage.toUpperCase() : null
  ];
  if (showBudget && item.mediaType === "movie") {
    meta.push(formatMoney(item.budget, "Budget"));
    meta.push(formatMoney(item.revenue, "Box office"));
  }
  return meta.filter((value): value is string => Boolean(value));
}

function buildContinueLabel(item: MediaItem, selectedEpisode: { season: number; episode: number } | null) {
  const season = selectedEpisode?.season ?? item.seasonNumber ?? null;
  const episode = selectedEpisode?.episode ?? item.episodeNumber ?? null;
  if (item.mediaType === "tv") {
    return season && episode ? `Continue S${season} E${episode}` : "Choose episode";
  }
  const progress = item.progress ?? 0;
  return progress >= 1 && progress <= 94 ? `Continue ${Math.round(progress)}%` : "Play";
}

function formatMoney(value: number | null | undefined, label: string) {
  if (!value || value <= 0) return null;
  const compact = new Intl.NumberFormat("en-US", {
    notation: "compact",
    maximumFractionDigits: 1,
    style: "currency",
    currency: "USD"
  }).format(value);
  return `${label} ${compact}`;
}

function SeasonEpisodes({ item, loadingDetails, selectedEpisode, isWatched, onPlayEpisode }: {
  item: MediaItem;
  loadingDetails: boolean;
  selectedEpisode: { season: number; episode: number } | null;
  isWatched: (item: MediaItem, seasonNumber?: number | null, episodeNumber?: number | null) => boolean;
  onPlayEpisode: (season: number, episode: number) => void;
}) {
  const seasons = item.seasons ?? [];
  const [season, setSeason] = useState(seasons[0]?.seasonNumber ?? 1);
  const [episodes, setEpisodes] = useState<EpisodeInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [retryNonce, setRetryNonce] = useState(0);

  useEffect(() => {
    if (seasons.length && !seasons.some((entry) => entry.seasonNumber === season)) {
      setSeason(seasons[0]?.seasonNumber ?? 1);
    }
  }, [season, seasons]);

  useEffect(() => {
    let active = true;
    setLoading(true);
    void getSeasonEpisodes(item.id, season)
      .then((eps) => { if (active) setEpisodes(eps); })
      .catch(() => undefined)
      .finally(() => active && setLoading(false));
    return () => { active = false; };
  }, [item.id, season, retryNonce]);

  return (
    <section className="detail-section episodes-section detail-wide">
      <h3>Episodes</h3>
      <div className="season-tabs">
        {seasons.map((s) => (
          <button
            type="button"
            key={s.id}
            className={`season-tab ${s.seasonNumber === season ? "is-active" : ""}`}
            onClick={() => setSeason(s.seasonNumber)}
          >
            {s.name || `Season ${s.seasonNumber}`}
          </button>
        ))}
        {!seasons.length && loadingDetails ? <span className="season-tab is-loading">Loading seasons...</span> : null}
      </div>
      <RailScroller className="episode-list" ariaLabel={`season ${season} episodes`}>
        {(loading || loadingDetails) && <p className="empty">Loading episodes...</p>}
        {!loading && !loadingDetails && !episodes.length ? (
          <p className="empty">
            No episodes found.{" "}
            <button type="button" className="episode-retry" onClick={() => setRetryNonce((n) => n + 1)}>Retry</button>
          </p>
        ) : null}
        {!loading && episodes.map((episode) => {
          const active = selectedEpisode?.season === season && selectedEpisode?.episode === episode.episodeNumber;
          const episodeRating = episode.imdbRating || (episode.voteAverage && episode.voteAverage > 0 ? episode.voteAverage.toFixed(1) : "");
          const watched = isWatched(item, season, episode.episodeNumber);
          return (
            <button
              type="button"
              key={episode.id}
              className={`episode-row ${active ? "is-active" : ""} ${watched ? "is-watched" : ""}`}
              onClick={() => onPlayEpisode(season, episode.episodeNumber)}
            >
              <div className="episode-still">
                {episode.still ? <img src={episode.still} alt="" /> : <Clapperboard size={24} />}
                <span className="episode-chip episode-chip-left">S{season} E{episode.episodeNumber.toString().padStart(2, "0")}</span>
                {episode.airDate && <span className="episode-chip episode-chip-center">{episode.airDate}</span>}
                {watched && <span className="watched-badge episode-watched-badge" aria-label="Watched"><BadgeCheck size={12} /></span>}
                <span className="episode-play"><Play size={18} fill="currentColor" /></span>
              </div>
              <div className="episode-info">
                <strong>{episode.name}</strong>
                <span className="episode-subline">
                  {episode.runtime ? `${episode.runtime}m` : `Episode ${episode.episodeNumber}`}
                  {episodeRating && (
                    <em className="episode-imdb">
                      <img src={IMDB_LOGO} alt="IMDb" loading="lazy" />
                      {episodeRating}
                    </em>
                  )}
                </span>
                <p>{episode.overview || ""}</p>
              </div>
            </button>
          );
        })}
      </RailScroller>
    </section>
  );
}
