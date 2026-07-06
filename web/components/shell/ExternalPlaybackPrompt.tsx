"use client";

import { CheckCircle2, Clock3, Play, X } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { saveProgress } from "@/lib/cloud";
import { authClient, traktClient, useApp } from "@/lib/store";
import {
  clearPendingExternalPlayback,
  loadPendingExternalPlayback,
  type PendingExternalPlayback
} from "@/lib/externalPlayback";

type PromptMode = "choice" | "progress";

function labelFor(pending: PendingExternalPlayback) {
  const episode = pending.mediaType === "tv" && pending.season && pending.episode
    ? `S${pending.season} E${pending.episode}${pending.episodeTitle ? ` - ${pending.episodeTitle}` : ""}`
    : "Movie";
  return `${pending.title} - ${episode}`;
}

export function ExternalPlaybackPrompt() {
  const { refreshData, setToast, markWatchedLocally } = useApp();
  const [pending, setPending] = useState<PendingExternalPlayback | null>(null);
  const [mode, setMode] = useState<PromptMode>("choice");
  const [saving, setSaving] = useState(false);

  // `immediate` = the user just returned to the app (focus/visibility), so we
  // show the prompt no matter how briefly they were gone. The age gate only
  // exists to stop the in-page fallback timer from flashing the prompt while
  // the user is still on this page and hasn't actually left for the player.
  const checkPending = useCallback((immediate = false) => {
    const next = loadPendingExternalPlayback();
    if (!next) {
      setPending(null);
      setMode("choice");
      return;
    }
    const age = Date.now() - next.openedAt;
    // Small grace so we don't fire the instant the scheme launch flips
    // visibility; a real trip to VLC is always longer than this.
    if (immediate ? age >= 1_500 : age >= 12_000) setPending(next);
  }, []);

  useEffect(() => {
    const onReturn = () => {
      if (document.visibilityState === "visible") checkPending(true);
    };
    // Fallback for browsers that don't fire focus/visibility reliably.
    const timer = window.setTimeout(() => checkPending(false), 12_500);
    window.addEventListener("focus", onReturn);
    document.addEventListener("visibilitychange", onReturn);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener("focus", onReturn);
      document.removeEventListener("visibilitychange", onReturn);
    };
  }, [checkPending]);

  const syncProgress = async (progress: number, action: "start" | "pause" | "stop") => {
    if (!pending) return;
    setSaving(true);
    const finished = progress >= 100;
    const duration = pending.durationSeconds || 0;
    const position = duration > 0 ? Math.round((duration * progress) / 100) : 0;
    // Close the prompt immediately so the buttons feel instant; the sync
    // requests continue in the background.
    const active = pending;
    clearPendingExternalPlayback(active.id);
    setPending(null);
    setMode("choice");
    // Instant UI: badge it watched + drop from Continue Watching now, before
    // the Trakt round-trip returns.
    if (finished) {
      markWatchedLocally({ mediaType: active.mediaType, id: active.tmdbId, season: active.season, episode: active.episode });
    }
    setToast(finished ? "Marking watched…" : "Syncing progress…");
    try {
      if (traktClient.isConnected) {
        if (finished) {
          // A completed watch must go into Trakt HISTORY (creates the watched
          // badge) — a scrobble alone does not. Mirrors the details "Mark
          // Watched" flow.
          await traktClient.addToHistory({
            mediaType: active.mediaType,
            tmdbId: active.tmdbId,
            season: active.season,
            episode: active.episode
          }).catch(() => undefined);
        } else {
          await traktClient.scrobble(action, {
            mediaType: active.mediaType,
            tmdbId: active.tmdbId,
            season: active.season,
            episode: active.episode,
            progress
          }).catch(() => undefined);
        }
      }
      if (authClient.session) {
        await saveProgress(authClient, {
          media_type: active.mediaType,
          show_tmdb_id: active.tmdbId,
          profile_id: active.profileId,
          season: active.season,
          episode: active.episode,
          episode_title: active.episodeTitle,
          title: active.title,
          progress: Math.max(0, Math.min(1, progress / 100)),
          duration_seconds: duration,
          position_seconds: position,
          backdrop_path: active.backdropPath,
          poster_path: active.posterPath,
          source: active.source,
          stream_addon_id: active.streamAddonId,
          stream_title: active.streamTitle
        }, active.profileId).catch(() => undefined);
      }
      setToast(finished ? "Marked watched and synced." : "Progress synced.");
      await refreshData(active.profileId);
    } finally {
      setSaving(false);
    }
  };

  const dismiss = (message?: string) => {
    if (pending) clearPendingExternalPlayback(pending.id);
    setPending(null);
    setMode("choice");
    if (message) setToast(message);
  };

  if (!pending) return null;

  return (
    <section className="external-playback-modal" role="dialog" aria-modal="true" aria-label="External playback tracking">
      <div className="external-playback-backdrop" />
      <article className="external-playback-card">
        <button type="button" className="external-playback-close" onClick={() => dismiss()} aria-label="Close external playback prompt">
          <X size={18} />
        </button>
        <p className="eyebrow">{pending.player === "vlc" ? "VLC" : "Infuse"} Playback</p>
        <h3>Update your watch progress?</h3>
        <p>{labelFor(pending)}</p>

        {mode === "choice" ? (
          <div className="external-playback-actions">
            <button type="button" className="primary" disabled={saving} onClick={() => void syncProgress(100, "stop")}>
              <CheckCircle2 size={18} /> Finished
            </button>
            <button type="button" className="secondary" disabled={saving} onClick={() => setMode("progress")}>
              <Clock3 size={18} /> Still watching
            </button>
            <button type="button" className="secondary" disabled={saving} onClick={() => void syncProgress(1, "start")}>
              <Play size={18} fill="currentColor" /> Just started
            </button>
            <button type="button" className="ghost" disabled={saving} onClick={() => dismiss("External playback ignored.")}>
              No
            </button>
          </div>
        ) : (
          <>
            <div className="external-progress-grid">
              {[25, 50, 75, 90].map((progress) => (
                <button type="button" key={progress} disabled={saving} onClick={() => void syncProgress(progress, "pause")}>
                  {progress}%
                </button>
              ))}
            </div>
            <button type="button" className="ghost external-back" disabled={saving} onClick={() => setMode("choice")}>
              Back
            </button>
          </>
        )}

        <small>
          ARVIO cannot read playback from external apps, so it only updates Trakt and ARVIO Cloud after you confirm.
        </small>
      </article>
    </section>
  );
}
