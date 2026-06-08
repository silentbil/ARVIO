"use client";

import { SkipForward, X } from "lucide-react";
import { useEffect, useRef } from "react";
import { config } from "@/lib/config";
import { saveProgress } from "@/lib/cloud";
import { proxiedUrl } from "@/lib/http";
import { attachPlayback } from "@/lib/player";
import { authClient, traktClient, useApp } from "@/lib/store";
import type { AppSettings, MediaItem, StreamSource } from "@/lib/types";

export function PlayerOverlay() {
  const { activeStream, activeChannel, selected, selectedEpisode, settings, advanceEpisode, closePlayer } = useApp();
  if (!activeStream?.url) return null;
  const canAdvance = Boolean(selected?.mediaType === "tv" && selectedEpisode && !activeChannel);
  return (
    <PlayerOverlayView
      title={activeChannel?.name ?? selected?.title ?? activeStream.source}
      subtitleLabel={selectedEpisode ? `S${selectedEpisode.season} E${selectedEpisode.episode}` : null}
      stream={activeStream}
      item={selected}
      settings={settings}
      canAdvance={canAdvance}
      onAdvance={advanceEpisode}
      onClose={closePlayer}
    />
  );
}

function PlayerOverlayView({ title, subtitleLabel, stream, item, settings, canAdvance, onAdvance, onClose }: {
  title: string;
  subtitleLabel: string | null;
  stream: StreamSource;
  item: MediaItem | null;
  settings: AppSettings;
  canAdvance: boolean;
  onAdvance: () => Promise<boolean>;
  onClose: () => void;
}) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const lastSavedRef = useRef(0);

  useEffect(() => {
    if (!videoRef.current || !stream.url) return undefined;
    const headers = stream.behaviorHints?.proxyHeaders?.request;
    const playbackUrl = headers ? proxiedUrl(stream.url, headers) : stream.url;
    return attachPlayback(videoRef.current, playbackUrl);
  }, [stream]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video || !item) return undefined;
    void traktClient.scrobble("start", { mediaType: item.mediaType, tmdbId: item.id, progress: item.progress ?? 0 }).catch(() => undefined);
    const save = () => {
      if (!authClient.session || !Number.isFinite(video.duration) || video.duration <= 0) return;
      const now = Date.now();
      if (now - lastSavedRef.current < 15_000) return;
      lastSavedRef.current = now;
      const progress = Math.min(1, Math.max(0, video.currentTime / video.duration));
      void saveProgress(authClient, {
        media_type: item.mediaType,
        show_tmdb_id: item.id,
        title: item.title,
        progress,
        duration_seconds: Math.round(video.duration),
        position_seconds: Math.round(video.currentTime),
        backdrop_path: item.backdrop?.replace(config.backdropBase, "") ?? null,
        poster_path: item.image?.replace(config.imageBase, "") ?? null,
        source: stream.addonName,
        stream_addon_id: stream.addonId ?? null,
        stream_title: stream.source
      }).catch(() => undefined);
      void traktClient.scrobble(video.paused ? "pause" : "start", { mediaType: item.mediaType, tmdbId: item.id, progress: progress * 100 }).catch(() => undefined);
    };
    const onEnded = () => {
      void traktClient.scrobble("stop", { mediaType: item.mediaType, tmdbId: item.id, progress: 100 }).catch(() => undefined);
      if (settings.autoPlayNext && canAdvance) void onAdvance();
    };
    video.addEventListener("timeupdate", save);
    video.addEventListener("pause", save);
    video.addEventListener("ended", onEnded);
    return () => {
      save();
      video.removeEventListener("timeupdate", save);
      video.removeEventListener("pause", save);
      video.removeEventListener("ended", onEnded);
    };
  }, [item, stream, settings.autoPlayNext, canAdvance, onAdvance]);

  // Subtitle cue styling from settings (size %, color).
  const cueCss = `.player-overlay video::cue { color: ${settings.subtitleColor}; font-size: ${Math.max(60, Math.min(200, settings.subtitleSize))}%; background: rgba(0,0,0,0.5); }`;

  return (
    <section className="player-overlay">
      <style>{cueCss}</style>
      <video ref={videoRef} controls autoPlay playsInline poster={item?.backdrop ?? undefined}>
        {(stream.subtitles ?? []).map((subtitle) => (
          <track
            key={subtitle.id || subtitle.url}
            kind="subtitles"
            srcLang={subtitle.lang || "en"}
            label={subtitle.label || subtitle.lang || "Subtitle"}
            src={`/api/subtitle?url=${encodeURIComponent(subtitle.url)}`}
            default={Boolean(settings.defaultSubtitle && subtitle.lang?.toLowerCase().startsWith(settings.defaultSubtitle.toLowerCase()))}
          />
        ))}
      </video>
      <div className="player-top">
        <div>
          <p className="eyebrow">{stream.addonName}</p>
          <h2>{title}</h2>
          <span className="player-meta">
            {subtitleLabel ? `${subtitleLabel} • ` : ""}{stream.quality || "HD"} {stream.subtitles?.length ? `• ${stream.subtitles.length} subtitles` : ""}
          </span>
        </div>
        <div className="player-top-actions">
          {canAdvance && (
            <button className="player-next" onClick={() => void onAdvance()}><SkipForward size={20} /> Next episode</button>
          )}
          <button className="close light" onClick={onClose}><X size={24} /></button>
        </div>
      </div>
    </section>
  );
}
