"use client";

import {
  ArrowLeft,
  AudioLines,
  Check,
  Copy,
  ExternalLink,
  Folder,
  Loader2,
  Maximize,
  Minimize,
  Pause,
  Play,
  RotateCcw,
  RotateCw,
  Settings,
  SkipForward,
  Subtitles,
  Volume2,
  VolumeX,
  X
} from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { config } from "@/lib/config";
import { createPendingExternalPlayback } from "@/lib/externalPlayback";
import { saveProgress } from "@/lib/cloud";
import { cachedDebridDirectUrl, isUncachedDebridStream, parseDebridStream, resolveDebridDirectUrl } from "@/lib/debrid";
import type { RemuxAudioTrack } from "@/lib/remux";
import { canOpenInAnyPlayer, copyStreamUrl, externalLaunchMode, openExternalPlayer, openInAnyPlayer } from "@/lib/externalPlayers";
import { proxiedUrl } from "@/lib/http";
import { attachPlayback } from "@/lib/player";
import { resolverMediaUrl, resolverSubtitleUrl } from "@/lib/resolver";
import { sourcePickerScore, streamSizeBytes } from "@/lib/sourceRank";
import { streamPlayability } from "@/lib/streamCompatibility";
import { authClient, traktClient, useApp } from "@/lib/store";
import { SubtitleTranslator, subtitleLanguageName } from "@/lib/subtitleAi";
import { getLogoUrl } from "@/lib/tmdb";
import type { AppSettings, MediaItem, StreamSource } from "@/lib/types";

type PlayerPanel = "sources" | "subtitles" | "audio" | "settings" | null;

function youTubeId(url: string): string | null {
  const match = url.match(/(?:youtube\.com\/(?:watch\?v=|embed\/)|youtu\.be\/)([\w-]{11})/);
  return match?.[1] ?? null;
}

function fmt(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return "0:00";
  const total = Math.floor(seconds);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}` : `${m}:${String(s).padStart(2, "0")}`;
}

function isSameStream(a: StreamSource, b: StreamSource) {
  return (
    a.url === b.url &&
    a.infoHash === b.infoHash &&
    a.fileIdx === b.fileIdx &&
    a.addonId === b.addonId &&
    a.source === b.source
  );
}

function mergeSubtitleTracks(primary: StreamSource, enriched?: StreamSource) {
  if (!enriched?.subtitles?.length) return primary;
  const existing = primary.subtitles ?? [];
  const seen = new Set(existing.map((subtitle) => subtitle.url));
  const subtitles = [...existing, ...enriched.subtitles.filter((subtitle) => !seen.has(subtitle.url))];
  return subtitles.length === existing.length ? primary : { ...primary, subtitles };
}

function streamMeta(stream: StreamSource) {
  return [stream.addonName, stream.quality, stream.size].filter(Boolean).join(" - ");
}

function isLikelyHlsUrl(url?: string | null) {
  if (!url) return false;
  return /\.m3u8(?:[?#]|$)/i.test(url) || url.toLowerCase().includes("mpegurl");
}

// Xtream panels serve the same live stream as HLS at …/id.m3u8. Playlists with
// output=ts hand out raw-TS URLs the browser often can't use directly, so the
// ladder also tries the HLS twin of an Xtream-style live URL.
function xtreamHlsVariant(url?: string | null) {
  if (!url) return null;
  const match = url.match(/^(https?:\/\/[^?#]*\/[^/?#]+\/[^/?#]+\/\d+)\.ts(?=$|[?#])/i);
  return match ? `${match[1]}.m3u8` : null;
}

function liveTvProxyHeaders() {
  return {
    Accept: "*/*",
    "User-Agent": "VLC/3.0.20 LibVLC/3.0.20",
    "Icy-MetaData": "1"
  };
}

function directManifestUrl(url: string) {
  const target = new URL(proxiedUrl(url, liveTvProxyHeaders()));
  target.searchParams.set("rewrite", "direct");
  return target.toString();
}

function workerManifestUrl(url: string) {
  // Manifest via the app backend (reaches hosts that block Cloudflare),
  // segments via the resolver worker (free bandwidth, CORS-clean).
  const target = new URL(proxiedUrl(url, liveTvProxyHeaders()));
  target.searchParams.set("rewrite", "worker");
  return target.toString();
}

function qualityBadges(stream: StreamSource) {
  const text = `${stream.quality ?? ""} ${stream.description ?? ""}`.toUpperCase();
  return [
    stream.quality || "HD",
    /\bDV\b/.test(text) || text.includes("DOLBY VISION") ? "DV" : "",
    /\bHDR/.test(text) ? "HDR" : "",
    text.includes("ATMOS") ? "ATMOS" : ""
  ].filter(Boolean);
}

function defaultSubtitleIndex(stream: StreamSource, language: string) {
  const desired = language.trim().toLowerCase();
  if (!desired || desired === "off") return -1;
  return (stream.subtitles ?? []).findIndex((subtitle) => {
    const lang = subtitle.lang?.toLowerCase() ?? "";
    const label = subtitle.label?.toLowerCase() ?? "";
    return lang.startsWith(desired) || label.includes(desired);
  });
}

// Subtitle list ordered like the app: the user's preferred language first,
// English next, then everything else alphabetically by language name. Each
// entry keeps its original index (the <track> elements are index-addressed).
function orderedSubtitles(stream: StreamSource, preferred: string) {
  const pref = preferred.trim().toLowerCase();
  return (stream.subtitles ?? [])
    .map((subtitle, index) => ({
      subtitle,
      index,
      langName: subtitleLanguageName(subtitle.lang || subtitle.label || "") || (subtitle.label ?? "Subtitle")
    }))
    .sort((a, b) => {
      const rank = (entry: { subtitle: { lang?: string | null } }) => {
        const lang = (entry.subtitle.lang ?? "").toLowerCase();
        if (pref && pref !== "off" && lang.startsWith(pref)) return 0;
        if (lang.startsWith("en")) return 1;
        return 2;
      };
      return rank(a) - rank(b) || a.langName.localeCompare(b.langName);
    });
}

export function PlayerOverlay() {
  const {
    activeStream,
    activeChannel,
    selected,
    selectedEpisode,
    settings,
    updateSettings,
    activeProfile,
    streams,
    playStream,
    advanceEpisode,
    setToast,
    closePlayer
  } = useApp();

  if (!activeStream?.url) return null;

  const ytId = youTubeId(activeStream.url);
  const title = activeChannel?.name ?? selected?.title ?? activeStream.source;

  if (ytId) {
    return (
      <section className="player-overlay">
        <iframe
          className="player-youtube"
          src={`https://www.youtube-nocookie.com/embed/${ytId}?autoplay=1&rel=0&modestbranding=1`}
          title={title}
          allow="autoplay; encrypted-media; fullscreen"
          allowFullScreen
        />
        <div className="player-top show">
          <div className="player-top-left">
            <button type="button" className="player-icon-btn" onClick={closePlayer} aria-label="Back"><ArrowLeft size={24} /></button>
            <div>
              <p className="eyebrow">Trailer</p>
              <h2>{title}</h2>
            </div>
          </div>
          <button type="button" className="player-icon-btn" onClick={closePlayer} aria-label="Close"><X size={24} /></button>
        </div>
      </section>
    );
  }

  const canAdvance = Boolean(selected?.mediaType === "tv" && selectedEpisode && !activeChannel);
  const enrichedStream = mergeSubtitleTracks(
    activeStream,
    streams.find((candidate) => isSameStream(candidate, activeStream) || (candidate.url && candidate.url === activeStream.url))
  );
  return (
    <VideoPlayer
      title={title}
      subtitleLabel={selectedEpisode ? `S${selectedEpisode.season} E${selectedEpisode.episode}` : null}
      stream={enrichedStream}
      streams={streams}
      item={selected}
      selectedEpisode={selectedEpisode}
      settings={settings}
      updateSettings={updateSettings}
      activeProfileId={activeProfile?.id ?? null}
      liveTv={Boolean(activeChannel)}
      canAdvance={canAdvance}
      onSelectStream={playStream}
      onAdvance={advanceEpisode}
      onToast={setToast}
      onClose={closePlayer}
    />
  );
}

function VideoPlayer({
  title,
  subtitleLabel,
  stream,
  streams,
  item,
  selectedEpisode,
  settings,
  updateSettings,
  activeProfileId,
  liveTv,
  canAdvance,
  onSelectStream,
  onAdvance,
  onToast,
  onClose
}: {
  title: string;
  subtitleLabel: string | null;
  stream: StreamSource;
  streams: StreamSource[];
  item: MediaItem | null;
  selectedEpisode: { season: number; episode: number } | null;
  settings: AppSettings;
  updateSettings: (patch: Partial<AppSettings>) => void;
  activeProfileId: string | null;
  liveTv: boolean;
  canAdvance: boolean;
  onSelectStream: (stream: StreamSource, options?: { forceTranscode?: boolean; forceRemux?: boolean; forceBrowser?: boolean }) => void;
  onAdvance: () => Promise<boolean>;
  onToast: (message: string) => void;
  onClose: () => void;
}) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const lastSavedRef = useRef(0);
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const skipTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const [playing, setPlaying] = useState(false);
  // Boot screen (like the app): backdrop + pulsing clearlogo covers the player
  // and blocks input until first frames are ready.
  const [booted, setBooted] = useState(false);
  const [bootLogo, setBootLogo] = useState<string | null>(null);
  // Guards a one-time hop when a source plays audio but renders a black frame.
  const switchedForBlackRef = useRef(false);
  // AI subtitle translation (same providers/prompt as the Android app).
  const [aiSubsActive, setAiSubsActive] = useState(false);
  const [aiTranslating, setAiTranslating] = useState(false);
  const aiTargetName = subtitleLanguageName(settings.defaultSubtitle);
  const aiAvailable = settings.aiSubtitlesEnabled && settings.aiSubtitleModel !== "off" && Boolean(settings.aiApiKey) && Boolean(aiTargetName) && !aiTargetName.toLowerCase().startsWith("engl");
  const translatorRef = useRef<SubtitleTranslator | null>(null);
  const [playbackRate, setPlaybackRateState] = useState(1);
  const setPlaybackRate = useCallback((rate: number) => {
    setPlaybackRateState(rate);
    if (videoRef.current) videoRef.current.playbackRate = rate;
  }, []);
  const [current, setCurrent] = useState(0);
  const [duration, setDuration] = useState(0);
  const [buffered, setBuffered] = useState(0);
  const [volume, setVolume] = useState(1);
  const [muted, setMuted] = useState(false);
  const [buffering, setBuffering] = useState(true);
  const [showControls, setShowControls] = useState(true);
  const [fullscreen, setFullscreen] = useState(false);
  const [error, setError] = useState(false);
  const [activePanel, setActivePanel] = useState<PlayerPanel>(null);
  const [activeSubtitle, setActiveSubtitle] = useState(-1);
  const [skipOverlay, setSkipOverlay] = useState<number | null>(null);
  const [remuxTracks, setRemuxTracks] = useState<RemuxAudioTrack[]>([]);
  const [remuxAudioIndex, setRemuxAudioIndex] = useState(-1);
  // Desired audio index survives remux restarts (switching audio re-runs the
  // effect); -1 means "use the probe's automatic choice".
  const remuxAudioIndexRef = useRef(-1);
  const [remuxRestartKey, setRemuxRestartKey] = useState(0);
  const switchRemuxAudio = useCallback((index: number) => {
    remuxAudioIndexRef.current = index;
    setRemuxAudioIndex(index);
    // Picking a track on a direct-played source switches into the remux path,
    // which is what actually lets us choose the audio stream.
    if (!stream.remux) {
      onSelectStream(stream, { forceRemux: true });
      return;
    }
    setRemuxRestartKey((key) => key + 1);
  }, [stream, onSelectStream]);

  // Direct-played MKVs expose no track APIs. Probing during playback would
  // open extra range connections to the same CDN link and starve the video
  // (TorBox limits connections per link) — so the container is probed ONLY
  // when the user opens the Audio panel, on demand.
  const [audioProbeState, setAudioProbeState] = useState<"idle" | "probing" | "done">("idle");
  useEffect(() => {
    setAudioProbeState("idle");
    if (!stream.remux) {
      setRemuxTracks([]);
      setRemuxAudioIndex(-1);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stream.url]);
  const probeAudioTracks = useCallback(() => {
    const current = currentStreamRef.current;
    if (liveTv || current.remux || !current.url) return;
    const text = `${current.url} ${current.originalUrl ?? ""} ${current.source ?? ""} ${current.description ?? ""}`.toLowerCase();
    if (!/\.mkv|matroska|remux/.test(text)) return;
    setAudioProbeState("probing");
    void (async () => {
      try {
        const { probeAndPrepareRemux } = await import("@/lib/remux");
        const probeUrl = cachedDebridDirectUrl(current.url) ?? current.url!;
        const prepared = await probeAndPrepareRemux(probeUrl, undefined, settings.audioLanguage);
        if (prepared && prepared.probe.audioTracks.length > 1) {
          setRemuxTracks(prepared.probe.audioTracks);
        }
        prepared?.destroy();
      } catch {
        // Probe is best-effort; the source keeps direct-playing either way.
      } finally {
        setAudioProbeState("done");
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [liveTv, settings.audioLanguage]);

  useEffect(() => {
    setBooted(false);
    const video = videoRef.current;
    if (!video) return undefined;
    const onReady = () => setBooted(true);
    video.addEventListener("playing", onReady);
    video.addEventListener("loadeddata", onReady);
    return () => {
      video.removeEventListener("playing", onReady);
      video.removeEventListener("loadeddata", onReady);
    };
  }, [stream.url, remuxRestartKey]);

  // Black-frame guard: some Dolby Vision sources decode audio fine but render an
  // all-black picture in browsers that can't handle the DV enhancement layer
  // (the "sound but no video" case). A few seconds in, sample the actual frame;
  // if it's essentially black while playback advances, hop to another source.
  useEffect(() => {
    if (!booted || liveTv) return undefined;
    const video = videoRef.current;
    if (!video || switchedForBlackRef.current) return undefined;
    let timer = 0;
    const startedAt = Date.now();
    const check = () => {
      // Audio-only fallback: a source that plays audio but never delivers a
      // video frame (videoWidth stays 0 well into playback — some Dolby Vision
      // streams) is broken here. Treat a long frameless stretch as black.
      if (!switchedForBlackRef.current && !video.paused && video.currentTime > 6 && !video.videoWidth && Date.now() - startedAt > 12000) {
        switchedForBlackRef.current = true;
        onToast("This version's video won't render in the browser — switching source.");
        if (!tryNextSource()) setError(true);
        return;
      }
      if (switchedForBlackRef.current || video.paused || video.currentTime < 4 || !video.videoWidth) {
        timer = window.setTimeout(check, 1500);
        return;
      }
      try {
        const canvas = document.createElement("canvas");
        canvas.width = 32;
        canvas.height = 18;
        const ctx = canvas.getContext("2d", { willReadFrequently: true });
        if (!ctx) return;
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        const { data } = ctx.getImageData(0, 0, canvas.width, canvas.height);
        let bright = 0;
        for (let i = 0; i < data.length; i += 4) {
          if (data[i] > 16 || data[i + 1] > 16 || data[i + 2] > 16) bright += 1;
        }
        // Fewer than 1% non-black pixels over a real playing frame = broken video.
        if (bright / (data.length / 4) < 0.01) {
          switchedForBlackRef.current = true;
          onToast("This version's video won't render in the browser — switching source.");
          if (!tryNextSource()) { setError(true); }
        }
      } catch {
        // Canvas can taint on cross-origin frames without CORS; skip silently.
      }
    };
    timer = window.setTimeout(check, 3500);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [booted, stream.url, liveTv]);

  // Live cue translation: when AI subs are active, the selected (English
  // source) track's cues are translated in small batches and swapped in place;
  // upcoming cues are pre-warmed so swaps land before display. Mirrors the
  // app's SubtitleTranslationManager.
  useEffect(() => {
    if (!aiSubsActive || !aiAvailable || activeSubtitle < 0) return undefined;
    const video = videoRef.current;
    const track = video?.textTracks?.[activeSubtitle];
    if (!video || !track) return undefined;
    const translator = new SubtitleTranslator(settings.aiApiKey, settings.aiSubtitleModel === "gemini" ? "gemini" : "groq", aiTargetName);
    translator.onTranslatingChanged = setAiTranslating;
    translatorRef.current = translator;
    const originals = new WeakMap<VTTCue, string>();
    const applyToCue = (cue: VTTCue) => {
      const original = originals.get(cue) ?? cue.text;
      originals.set(cue, original);
      void translator.translate(original).then((translated) => {
        if (translatorRef.current === translator && cue.text !== translated) cue.text = translated;
      });
    };
    const onCueChange = () => {
      const active = [...(track.activeCues ?? [])] as VTTCue[];
      active.forEach(applyToCue);
      // Pre-warm the next handful of cues.
      const all = [...(track.cues ?? [])] as VTTCue[];
      const now = video.currentTime;
      translator.prefetch(
        all.filter((cue) => cue.startTime > now && cue.startTime < now + 30).slice(0, 8)
          .map((cue) => originals.get(cue) ?? cue.text)
      );
    };
    track.addEventListener("cuechange", onCueChange);
    onCueChange();
    return () => {
      track.removeEventListener("cuechange", onCueChange);
      if (translatorRef.current === translator) translatorRef.current = null;
      setAiTranslating(false);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [aiSubsActive, aiAvailable, activeSubtitle, stream.url, settings.aiApiKey, settings.aiSubtitleModel, aiTargetName]);

  // Auto-activate AI (app parity): preferred-language subtitle missing but an
  // English source exists → translate it automatically.
  useEffect(() => {
    if (!aiAvailable || !settings.aiAutoSelect || liveTv) return;
    const subtitles = stream.subtitles ?? [];
    if (!subtitles.length) return;
    const pref = settings.defaultSubtitle.trim().toLowerCase();
    if (!pref || pref === "off") return;
    const hasPreferred = subtitles.some((subtitle) => (subtitle.lang ?? "").toLowerCase().startsWith(pref));
    const englishIndex = subtitles.findIndex((subtitle) => (subtitle.lang ?? "").toLowerCase().startsWith("en"));
    if (!hasPreferred && englishIndex >= 0) {
      setActiveSubtitle(englishIndex);
      setAiSubsActive(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stream.url, stream.subtitles?.length, aiAvailable, settings.aiAutoSelect, liveTv]);

  // Adaptive downswitch: repeated mid-play buffering means the connection
  // can't sustain this file's bitrate (common with huge remuxes over VPN) —
  // switch once to a substantially lighter playable version of the same title
  // instead of letting the viewer stutter through it.
  useEffect(() => {
    if (!booted || liveTv) return undefined;
    const video = videoRef.current;
    if (!video) return undefined;
    let stalls: number[] = [];
    let switched = false;
    const onWaiting = () => {
      if (switched || video.currentTime < 8) return;
      const now = Date.now();
      stalls = [...stalls.filter((t) => now - t < 90_000), now];
      if (stalls.length < 3) return;
      const current = currentStreamRef.current;
      const currentSize = streamSizeBytes(current);
      const lighter = sourceListRef.current.find((candidate) => {
        if (!candidate.url || candidate.url === current.url || candidate.url === current.originalUrl) return false;
        if (isUncachedDebridStream(candidate)) return false;
        const mode = streamPlayability(candidate).mode;
        if (mode !== "direct" && mode !== "remux") return false;
        const size = streamSizeBytes(candidate);
        return size > 0 && (!currentSize || size < currentSize * 0.55);
      });
      if (!lighter) return;
      switched = true;
      onToast("Your connection can't keep up with this version — switching to a lighter one.");
      onSelectStream(lighter, { forceBrowser: true });
    };
    video.addEventListener("waiting", onWaiting);
    return () => video.removeEventListener("waiting", onWaiting);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [booted, stream.url, liveTv]);

  useEffect(() => {
    setBootLogo(null);
    if (!item || item.id <= 0 || item.isHomeServer) return undefined;
    let active = true;
    void getLogoUrl({ mediaType: item.mediaType, id: item.id }).then((url) => {
      if (active) setBootLogo(url);
    }).catch(() => undefined);
    return () => { active = false; };
  }, [item?.id, item?.mediaType, item?.isHomeServer, item]);

  // Same ranked order as the details source picker — the panel and the
  // auto-hop must both walk sources best-first, not raw addon order.
  const sourceList = useMemo(() => {
    const playable = streams.filter((candidate) => Boolean(candidate.url));
    const base = playable.length ? playable : [stream];
    return [...base].sort((a, b) => sourcePickerScore(b) - sourcePickerScore(a));
  }, [streams, stream]);

  // When a source is truly dead (URL ladder + remux exhausted), hop to the next
  // browser-playable source automatically instead of stranding the viewer on an
  // error screen. Capped, and each failed URL is remembered to prevent loops.
  // Reads through refs so its identity never changes — the playback effect must
  // not restart when the progressive source list grows.
  const failedSourceUrlsRef = useRef(new Set<string>());
  const failedAddonStrikesRef = useRef(new Map<string, number>());
  const autoSourceHopsRef = useRef(0);
  const sourceListRef = useRef(sourceList);
  sourceListRef.current = sourceList;
  const currentStreamRef = useRef(stream);
  currentStreamRef.current = stream;
  useEffect(() => {
    failedSourceUrlsRef.current = new Set();
    failedAddonStrikesRef.current = new Map();
    autoSourceHopsRef.current = 0;
    switchedForBlackRef.current = false;
  }, [item?.id, selectedEpisode?.season, selectedEpisode?.episode]);
  const tryNextSource = useCallback(() => {
    if (liveTv || autoSourceHopsRef.current >= 6) return false;
    const current = currentStreamRef.current;
    if (current.url) failedSourceUrlsRef.current.add(current.url);
    if (current.originalUrl) failedSourceUrlsRef.current.add(current.originalUrl);
    // Two failures from the same addon usually means its host is down/CORS-
    // blocked for the whole list (e.g. 40 dead file-host links in a row) —
    // skip the rest of that addon and jump to the next provider.
    const addonKey = current.addonId || current.addonName || "";
    if (addonKey) failedAddonStrikesRef.current.set(addonKey, (failedAddonStrikesRef.current.get(addonKey) ?? 0) + 1);
    const pick = (skipStruckAddons: boolean) => sourceListRef.current.find((candidate) => {
      if (!candidate.url || failedSourceUrlsRef.current.has(candidate.url)) return false;
      // Uncached debrid torrents would stall on a server-side download.
      if (isUncachedDebridStream(candidate)) return false;
      if (skipStruckAddons) {
        const key = candidate.addonId || candidate.addonName || "";
        if (key && (failedAddonStrikesRef.current.get(key) ?? 0) >= 2) return false;
      }
      const mode = streamPlayability(candidate).mode;
      return mode === "direct" || mode === "remux";
    });
    const next = pick(true) ?? pick(false);
    if (!next) return false;
    autoSourceHopsRef.current += 1;
    onToast(`Source failed — trying ${next.source || next.addonName || "the next source"}`);
    onSelectStream(next, { forceBrowser: true });
    return true;
  }, [liveTv, onSelectStream, onToast]);
  const badges = useMemo(() => {
    if (liveTv) return ["LIVE"];
    const base = qualityBadges(stream);
    if (stream.remux) return ["REMUX", ...base];
    return stream.transcoded ? ["TRANSCODE", ...base] : base;
  }, [stream, liveTv]);
  const mediaMeta = [subtitleLabel, stream.quality, stream.size, stream.addonName].filter(Boolean).join(" - ");

  useEffect(() => {
    const video = videoRef.current;
    if (!video || !stream.url) return undefined;

    // In-browser remux path (Tier 3): repackage an MKV direct link and play the
    // browser-safe audio track. Takes over the element entirely for this source.
    if (stream.remux) {
      let cancelled = false;
      let handle: { destroy: () => void } | null = null;
      setError(false);
      setBuffering(true);
      setActiveSubtitle(-1);
      setRemuxTracks([]);
      lastSavedRef.current = 0;
      void (async () => {
        try {
          const { probeAndPrepareRemux } = await import("@/lib/remux");
          const prepared = await probeAndPrepareRemux(stream.url!, undefined, settings.audioLanguage);
          if (cancelled) { prepared?.destroy(); return; }
          if (!prepared || prepared.probe.chosenAudioIndex < 0 || !prepared.probe.videoPlayable) {
            if (tryNextSource()) return;
            setBuffering(false);
            setError(true);
            return;
          }
          handle = prepared;
          setRemuxTracks(prepared.probe.audioTracks);
          const startIndex = remuxAudioIndexRef.current >= 0 ? remuxAudioIndexRef.current : prepared.probe.chosenAudioIndex;
          setRemuxAudioIndex(startIndex);
          await prepared.start(video, startIndex);
          if (cancelled) return;
          void video.play().catch(() => undefined);
        } catch {
          if (!cancelled && !tryNextSource()) { setBuffering(false); setError(true); }
        }
      })();
      return () => {
        cancelled = true;
        handle?.destroy();
        video.removeAttribute("src");
        video.load();
      };
    }

    setError(false);
    setBuffering(true);
    setActiveSubtitle(defaultSubtitleIndex(stream, settings.defaultSubtitle));
    lastSavedRef.current = 0;
    const headers = config.allowNetlifyMediaProxy ? stream.behaviorHints?.proxyHeaders?.request : undefined;
    let handlingError = false;
    let cancelled = false;
    let detach: (() => void) | undefined;
    // Playback ladder: direct first (free for CORS-friendly providers), then the
    // Cloudflare resolver media proxy for live TV (fixes CORS/ORB without Netlify
    // bandwidth), then the legacy Netlify fallbacks.
    const attempts: string[] = [headers ? proxiedUrl(stream.url, headers) : stream.url];
    // Catch-up recordings come from the same IPTV panels as live channels, so
    // they get the live relay hops — but keep VOD controls (seekable).
    const iptvRelay = liveTv || stream.addonName === "Catch-up";
    if (iptvRelay) {
      const hlsTwin = xtreamHlsVariant(stream.url);
      if (hlsTwin) attempts.push(hlsTwin);
      const workerUrl = resolverMediaUrl(stream.url, liveTvProxyHeaders());
      if (workerUrl) attempts.push(workerUrl);
      if (hlsTwin) {
        const workerTwin = resolverMediaUrl(hlsTwin, liveTvProxyHeaders());
        if (workerTwin) attempts.push(workerTwin);
        attempts.push(workerManifestUrl(hlsTwin));
      }
      if (isLikelyHlsUrl(stream.url)) {
        if (workerUrl) attempts.push(workerManifestUrl(stream.url));
        attempts.push(directManifestUrl(stream.url));
      }
      if (config.allowNetlifyMediaProxy) {
        attempts.push(proxiedUrl(hlsTwin ?? stream.url, liveTvProxyHeaders()));
      }
    }
    if (config.allowNetlifyMediaProxy && !headers && /^https?:\/\//i.test(stream.url)) {
      attempts.push(proxiedUrl(stream.url));
    }
    const uniqueAttempts = [...new Set(attempts)];
    let attemptIndex = 0;
    // Some sources hang forever without ever firing an "error" event (the CDN
    // accepts the connection but never delivers a playable moov/metadata). A
    // plain error-based ladder can't recover from that. Arm a per-attempt stall
    // timeout: if the element hasn't reached at least metadata within the window,
    // treat it as a failure and escalate down the ladder (next URL, then remux).
    let stallTimer: number | undefined;
    const armStallTimer = () => {
      window.clearTimeout(stallTimer);
      // Live channels that hang (provider accepts the connection but never sends
      // data) need a shorter leash than VOD so the ladder keeps moving.
      stallTimer = window.setTimeout(() => {
        if (cancelled) return;
        if (video.readyState < 1) handlePlaybackError();
      }, liveTv ? 10000 : 13000);
    };
    const requestPlayback = () => {
      if (cancelled || !video.paused) return;
      setError(false);
      setBuffering(true);
      const attempt = video.play();
      if (attempt && typeof attempt.catch === "function") {
        attempt.catch(() => {
          if (cancelled) return;
          setBuffering(false);
          if (video.error) setError(true);
          setShowControls(true);
        });
      }
    };
    const handlePlaybackError = () => {
      if (cancelled || handlingError) return;
      handlingError = true;
      attemptIndex += 1;
      const nextUrl = uniqueAttempts[attemptIndex];
      if (nextUrl) {
        setError(false);
        setBuffering(true);
        detach?.();
        detach = attachPlayback(video, nextUrl, { onError: handlePlaybackError, live: liveTv });
        armStallTimer();
        requestPlayback();
        handlingError = false;
        return;
      }
      // Direct attempts exhausted. For a VOD source the browser couldn't decode
      // (MKV container / lossless audio), auto-escalate to the in-browser remux
      // path instead of surfacing an error — this is the instant-first ladder.
      if (!liveTv && !stream.remux && streamPlayability(stream).mode === "remux") {
        cancelled = true;
        detach?.();
        onSelectStream(stream, { forceRemux: true });
        return;
      }
      // This source is dead — hop to the next playable one before giving up.
      if (tryNextSource()) {
        cancelled = true;
        detach?.();
        return;
      }
      setBuffering(false);
      setError(true);
      handlingError = false;
    };
    detach = attachPlayback(video, uniqueAttempts[0], { onError: handlePlaybackError, live: liveTv });
    armStallTimer();
    const onReadyToStart = () => { window.clearTimeout(stallTimer); if (video.playbackRate !== playbackRate) video.playbackRate = playbackRate; requestPlayback(); };
    const startTimer = window.setTimeout(requestPlayback, 0);
    video.addEventListener("loadedmetadata", onReadyToStart, { once: true });
    video.addEventListener("canplay", onReadyToStart, { once: true });
    const slowTimer = window.setTimeout(() => {
      if (liveTv && video.readyState === 0 && video.paused) setBuffering(false);
    }, 6500);
    const onErr = () => handlePlaybackError();
    video.addEventListener("error", onErr);
    return () => {
      cancelled = true;
      window.clearTimeout(startTimer);
      window.clearTimeout(slowTimer);
      window.clearTimeout(stallTimer);
      video.removeEventListener("loadedmetadata", onReadyToStart);
      video.removeEventListener("canplay", onReadyToStart);
      video.removeEventListener("error", onErr);
      detach?.();
    };
  }, [stream, stream.remux, remuxRestartKey, settings.defaultSubtitle, liveTv]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return undefined;
    const onTime = () => setCurrent(video.currentTime);
    const onDur = () => setDuration(video.duration || 0);
    const onPlay = () => setPlaying(true);
    const onPause = () => setPlaying(false);
    const onWaiting = () => setBuffering(true);
    const onPlaying = () => setBuffering(false);
    const onProgress = () => {
      if (video.buffered.length) setBuffered(video.buffered.end(video.buffered.length - 1));
    };
    const onVol = () => { setVolume(video.volume); setMuted(video.muted); };
    video.addEventListener("timeupdate", onTime);
    video.addEventListener("durationchange", onDur);
    video.addEventListener("loadedmetadata", onDur);
    video.addEventListener("play", onPlay);
    video.addEventListener("pause", onPause);
    video.addEventListener("waiting", onWaiting);
    video.addEventListener("playing", onPlaying);
    video.addEventListener("canplay", onPlaying);
    video.addEventListener("progress", onProgress);
    video.addEventListener("volumechange", onVol);
    return () => {
      video.removeEventListener("timeupdate", onTime);
      video.removeEventListener("durationchange", onDur);
      video.removeEventListener("loadedmetadata", onDur);
      video.removeEventListener("play", onPlay);
      video.removeEventListener("pause", onPause);
      video.removeEventListener("waiting", onWaiting);
      video.removeEventListener("playing", onPlaying);
      video.removeEventListener("canplay", onPlaying);
      video.removeEventListener("progress", onProgress);
      video.removeEventListener("volumechange", onVol);
    };
  }, [stream]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    const tracks = Array.from(video.textTracks);
    tracks.forEach((track, index) => {
      track.mode = activeSubtitle === index ? "showing" : "disabled";
    });
  }, [activeSubtitle, stream]);

  // Reset the manual remux audio override when the source changes so each new
  // source starts from its own automatic best-track choice.
  useEffect(() => {
    remuxAudioIndexRef.current = -1;
  }, [stream.url]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video || !item) return undefined;
    const season = selectedEpisode?.season ?? item.seasonNumber ?? null;
    const episode = selectedEpisode?.episode ?? item.episodeNumber ?? null;
    void traktClient.scrobble("start", { mediaType: item.mediaType, tmdbId: item.id, season, episode, progress: item.progress ?? 0 }).catch(() => undefined);
    const save = () => {
      if (!authClient.session || !Number.isFinite(video.duration) || video.duration <= 0) return;
      const now = Date.now();
      if (now - lastSavedRef.current < 15_000) return;
      lastSavedRef.current = now;
      const progress = Math.min(1, Math.max(0, video.currentTime / video.duration));
      void saveProgress(authClient, {
        media_type: item.mediaType,
        show_tmdb_id: item.id,
        profile_id: activeProfileId,
        season,
        episode,
        episode_title: item.episodeTitle ?? null,
        title: item.title,
        progress,
        duration_seconds: Math.round(video.duration),
        position_seconds: Math.round(video.currentTime),
        backdrop_path: item.backdrop?.replace(config.backdropBase, "") ?? null,
        poster_path: item.image?.replace(config.imageBase, "") ?? null,
        source: stream.addonName,
        stream_addon_id: stream.addonId ?? null,
        stream_title: stream.source
      }, activeProfileId).catch(() => undefined);
    };
    const onEnded = () => {
      void traktClient.scrobble("stop", { mediaType: item.mediaType, tmdbId: item.id, season, episode, progress: 100 }).catch(() => undefined);
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
  }, [item, stream, selectedEpisode, settings.autoPlayNext, activeProfileId, canAdvance, onAdvance]);

  const flashControls = useCallback(() => {
    setShowControls(true);
    if (hideTimer.current) clearTimeout(hideTimer.current);
    hideTimer.current = setTimeout(() => {
      if (videoRef.current && !videoRef.current.paused && !activePanel) setShowControls(false);
    }, 3000);
  }, [activePanel]);

  const togglePlay = useCallback(() => {
    const video = videoRef.current;
    if (!video) return;
    if (video.paused) {
      setError(false);
      setBuffering(true);
      const attempt = video.play();
      void attempt?.catch(() => {
        setBuffering(false);
        if (video.error) setError(true);
        setShowControls(true);
      });
    }
    else video.pause();
    flashControls();
  }, [flashControls]);

  const seekBy = useCallback((delta: number) => {
    const video = videoRef.current;
    if (!video) return;
    video.currentTime = Math.max(0, Math.min((video.duration || 0), video.currentTime + delta));
    setSkipOverlay(delta);
    if (skipTimer.current) clearTimeout(skipTimer.current);
    skipTimer.current = setTimeout(() => setSkipOverlay(null), 780);
    flashControls();
  }, [flashControls]);

  const toggleFullscreen = useCallback(() => {
    const el = containerRef.current;
    if (!el) return;
    if (!document.fullscreenElement) void el.requestFullscreen?.().catch(() => undefined);
    else void document.exitFullscreen?.().catch(() => undefined);
  }, []);

  const openPanel = useCallback((panel: Exclude<PlayerPanel, null>) => {
    setActivePanel((currentPanel) => {
      const next = currentPanel === panel ? null : panel;
      // Opening the Audio panel on a direct-played source triggers the
      // on-demand track probe (never during unattended playback).
      if (next === "audio" && audioProbeState === "idle" && remuxTracks.length === 0) probeAudioTracks();
      return next;
    });
    setShowControls(true);
  }, [audioProbeState, remuxTracks.length, probeAudioTracks]);

  const openExternal = useCallback((player: "vlc" | "infuse", selectedStream: StreamSource) => {
    if (!selectedStream.url) {
      onToast("This source has no direct URL for an external player.");
      return;
    }
    onToast(
      player === "infuse"
        ? "Opening in Infuse..."
        : externalLaunchMode("vlc") === "playlist"
          ? "VLC playlist saved — open it from your downloads to play."
          : "Opening in VLC..."
    );
    // The deep link must fire synchronously — custom-scheme navigation is
    // blocked after an await. Use the prefetch-cached CDN url when present,
    // else the raw stream (VLC follows the redirect itself).
    const cached = parseDebridStream(selectedStream.url) ? cachedDebridDirectUrl(selectedStream.url) : null;
    const target = cached ? { ...selectedStream, url: cached, originalUrl: selectedStream.url } : selectedStream;
    createPendingExternalPlayback({
      player,
      item,
      stream: target,
      title,
      profileId: activeProfileId,
      season: selectedEpisode?.season ?? item?.seasonNumber ?? null,
      episode: selectedEpisode?.episode ?? item?.episodeNumber ?? null
    });
    // The in-player stream already carries fetched subtitles; pass the user's
    // preferred language so VLC/Infuse pick the right one.
    openExternalPlayer(player, target, title, settings.defaultSubtitle);
  }, [activeProfileId, item, onToast, selectedEpisode, title, settings.defaultSubtitle]);

  // Android: open in whichever installed player the user picks (system chooser).
  const openAnyPlayer = useCallback((selectedStream: StreamSource) => {
    if (!selectedStream.url) {
      onToast("This source has no direct URL for an external player.");
      return;
    }
    onToast("Opening in your player…");
    const cached = parseDebridStream(selectedStream.url) ? cachedDebridDirectUrl(selectedStream.url) : null;
    const target = cached ? { ...selectedStream, url: cached, originalUrl: selectedStream.url } : selectedStream;
    createPendingExternalPlayback({
      player: "vlc",
      item,
      stream: target,
      title,
      profileId: activeProfileId,
      season: selectedEpisode?.season ?? item?.seasonNumber ?? null,
      episode: selectedEpisode?.episode ?? item?.episodeNumber ?? null
    });
    openInAnyPlayer(target, title, settings.defaultSubtitle);
  }, [activeProfileId, item, onToast, selectedEpisode, title, settings.defaultSubtitle]);

  const copyUrl = useCallback(async (selectedStream: StreamSource) => {
    const copied = await copyStreamUrl(selectedStream).catch(() => false);
    onToast(copied ? "Stream URL copied." : "Could not copy this stream URL.");
  }, [onToast]);

  useEffect(() => {
    const onFs = () => setFullscreen(Boolean(document.fullscreenElement));
    document.addEventListener("fullscreenchange", onFs);
    return () => document.removeEventListener("fullscreenchange", onFs);
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      switch (e.key) {
        case " ":
        case "k":
          e.preventDefault();
          togglePlay();
          break;
        case "ArrowLeft":
        case "j":
          seekBy(-10);
          break;
        case "ArrowRight":
        case "l":
          seekBy(10);
          break;
        case "ArrowUp": {
          const v = videoRef.current;
          if (v) {
            v.volume = Math.min(1, v.volume + 0.1);
            flashControls();
          }
          break;
        }
        case "ArrowDown": {
          const v = videoRef.current;
          if (v) {
            v.volume = Math.max(0, v.volume - 0.1);
            flashControls();
          }
          break;
        }
        case "m": {
          const v = videoRef.current;
          if (v) v.muted = !v.muted;
          break;
        }
        case "c":
          openPanel("subtitles");
          break;
        case "s":
          openPanel("sources");
          break;
        case "f":
          toggleFullscreen();
          break;
        case "Escape":
          if (activePanel) setActivePanel(null);
          else if (!document.fullscreenElement) onClose();
          break;
        default:
          break;
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [togglePlay, seekBy, toggleFullscreen, flashControls, onClose, openPanel, activePanel]);

  // Subtitle rendering honours the user's style setting: boxed, outlined,
  // drop-shadowed, or raised — same options as the Android app.
  const cueStyle = {
    background: "background: rgba(0,0,0,0.65);",
    outline: "background: transparent; text-shadow: -1px -1px 2px #000, 1px -1px 2px #000, -1px 1px 2px #000, 1px 1px 2px #000;",
    shadow: "background: transparent; text-shadow: 2px 2px 5px rgba(0,0,0,0.95);",
    raised: "background: transparent; text-shadow: 0 1px 0 #000, 0 2px 4px rgba(0,0,0,0.75);"
  }[settings.subtitleStyle] ?? "background: rgba(0,0,0,0.65);";
  const cueCss = `.player-overlay video::cue { color: ${settings.subtitleColor}; font-size: ${Math.max(60, Math.min(300, settings.subtitleSize))}%; ${cueStyle} }`;

  // Vertical placement: browsers render captions near the very bottom by
  // default (often behind the control bar). `line` is a percentage from the top
  // — lower number = higher on screen. Applied per cue on the active track.
  const subtitleLinePercent = { bottom: 84, low: 78, medium: 68, high: 56 }[settings.subtitleOffset] ?? 84;
  useEffect(() => {
    const video = videoRef.current;
    if (!video || activeSubtitle < 0) return undefined;
    const track = video.textTracks?.[activeSubtitle];
    if (!track) return undefined;
    const applyLine = () => {
      for (const cue of Array.from(track.cues ?? []) as VTTCue[]) {
        cue.snapToLines = false;
        cue.line = subtitleLinePercent;
      }
    };
    applyLine();
    track.addEventListener("cuechange", applyLine);
    return () => track.removeEventListener("cuechange", applyLine);
  }, [activeSubtitle, subtitleLinePercent, stream.url]);
  const pct = duration > 0 ? (current / duration) * 100 : 0;
  const bufPct = duration > 0 ? (buffered / duration) * 100 : 0;

  return (
    <section
      ref={containerRef}
      className={`player-overlay ${showControls || activePanel ? "controls-on" : "controls-off"}`}
      onMouseMove={flashControls}
    >
      <style>{cueCss}</style>
      <video ref={videoRef} autoPlay playsInline preload="auto" onClick={togglePlay} poster={item?.backdrop ?? undefined}>
        {(stream.subtitles ?? []).map((subtitle, index) => (
          <track
            key={subtitle.id || subtitle.url}
            kind="subtitles"
            srcLang={subtitle.lang || "en"}
            label={subtitle.label || subtitle.lang || "Subtitle"}
            src={resolverSubtitleUrl(subtitle.url)}
            default={activeSubtitle === index}
          />
        ))}
      </video>

      {!booted && !error && (
        <div className="player-boot" style={{ backgroundImage: item?.backdrop ? `url(${item.backdrop})` : undefined }} aria-label="Loading playback">
          {bootLogo
            ? <img className="player-boot-logo" src={bootLogo} alt={title} />
            : <h2 className="player-boot-title">{title}</h2>}
        </div>
      )}
      {buffering && !error && booted && <div className="player-spinner"><Loader2 size={56} /></div>}
      {error && (
        <div className="player-error">
          <p>{liveTv ? "This channel could not be played right now." : "This source could not be played in the browser."}</p>
          <span>
            {liveTv
              ? "Most IPTV providers serve live channels over plain HTTP, which a secure web page can't play (mixed content), and many also block anything that isn't a real player app. Open it in VLC — it plays the exact same stream from your own connection, with no browser restrictions."
              : "This title's versions use a codec (often Dolby Vision or HEVC) your browser can't render. Open it in an external player, which decodes anything — ARVIO still tracks your progress on Trakt when you come back."}
          </span>
          <div className="player-error-actions">
            <button type="button" className="player-error-external" onClick={() => openExternal("vlc", stream)}>
              <ExternalLink size={15} /> Open in VLC
            </button>
            {canOpenInAnyPlayer() ? (
              <button type="button" className="player-error-external" onClick={() => openAnyPlayer(stream)}>
                <ExternalLink size={15} /> Open in player
              </button>
            ) : (
              <button type="button" className="player-error-external" onClick={() => openExternal("infuse", stream)}>
                <ExternalLink size={15} /> Open in Infuse
              </button>
            )}
            {!liveTv && !stream.transcoded && parseDebridStream(stream.url) && (
              <button type="button" className="player-error-transcode" onClick={() => onSelectStream(stream, { forceTranscode: true })}>
                <Play size={15} fill="currentColor" /> Transcode
              </button>
            )}
          </div>
        </div>
      )}
      {skipOverlay !== null && (
        <div className={`player-skip-overlay ${skipOverlay > 0 ? "forward" : "back"}`}>
          {skipOverlay > 0 ? <RotateCw size={34} /> : <RotateCcw size={34} />}
          <strong>{skipOverlay > 0 ? "+" : ""}{skipOverlay}s</strong>
        </div>
      )}
      {!playing && !buffering && !error && (
        <button type="button" className="player-bigplay" onClick={togglePlay} aria-label="Play"><Play size={48} fill="currentColor" /></button>
      )}

      <div className="player-top">
        <div className="player-top-left player-metadata">
          <button type="button" className="player-icon-btn" onClick={onClose} aria-label="Back"><ArrowLeft size={24} /></button>
          <span className="player-accent-rail" />
          <div>
            <p className="eyebrow">{mediaMeta || stream.source}</p>
            <h2>{title}</h2>
            {!playing && item?.overview && <p className="player-overview">{item.overview}</p>}
          </div>
        </div>
        <div className="player-top-actions">
          {canAdvance && <button type="button" className="player-next" onClick={() => void onAdvance()}><SkipForward size={18} /> Next episode</button>}
          <button type="button" className="player-icon-btn" onClick={onClose} aria-label="Close"><X size={22} /></button>
        </div>
      </div>

      {activePanel && (
        <aside className="player-side-panel">
          <div className="player-panel-head">
            <div>
              <p className="eyebrow">{activePanel}</p>
              <h3>{activePanel === "sources" ? "Choose Source" : activePanel === "subtitles" ? "Subtitles" : activePanel === "audio" ? "Audio" : "Playback Settings"}</h3>
            </div>
            <button type="button" className="player-icon-btn" onClick={() => setActivePanel(null)} aria-label="Close panel"><X size={18} /></button>
          </div>

          {activePanel === "sources" && (
            <div className="player-panel-list">
              {sourceList.map((candidate, index) => {
                const active = isSameStream(candidate, stream);
                return (
                  <article
                    key={`${candidate.addonId ?? candidate.addonName}-${candidate.source}-${index}`}
                    className={`player-panel-row ${active ? "is-active" : ""}`}
                  >
                    <span className="player-row-icon">{active ? <Check size={17} /> : index + 1}</span>
                    <span>
                      <strong>{candidate.source || candidate.addonName}</strong>
                      <em>{streamMeta(candidate) || "Direct stream"}</em>
                      <span className="player-row-actions">
                        <button
                          type="button"
                          onClick={() => {
                            onSelectStream(candidate, { forceBrowser: true });
                            setActivePanel(null);
                          }}
                        >
                          <Play size={13} fill="currentColor" /> Play
                        </button>
                        <button type="button" onClick={() => openExternal("vlc", candidate)}>
                          <ExternalLink size={13} /> VLC
                        </button>
                        {canOpenInAnyPlayer() ? (
                          <button type="button" onClick={() => openAnyPlayer(candidate)}>
                            <ExternalLink size={13} /> Player
                          </button>
                        ) : (
                          <button type="button" onClick={() => openExternal("infuse", candidate)}>
                            <ExternalLink size={13} /> Infuse
                          </button>
                        )}
                        <button type="button" onClick={() => void copyUrl(candidate)} aria-label="Copy stream URL">
                          <Copy size={13} />
                        </button>
                      </span>
                    </span>
                  </article>
                );
              })}
            </div>
          )}

          {activePanel === "audio" && (
            <div className="player-panel-list">
              {remuxTracks.length > 0 ? (
                remuxTracks.map((track) => (
                  <button
                    type="button"
                    key={track.index}
                    className={`player-panel-row ${remuxAudioIndex === track.index ? "is-active" : ""} ${track.browserPlayable ? "" : "is-disabled"}`}
                    disabled={!track.browserPlayable}
                    onClick={() => track.browserPlayable && switchRemuxAudio(track.index)}
                  >
                    <span className="player-row-icon">{remuxAudioIndex === track.index ? <Check size={17} /> : ""}</span>
                    <span><strong>{track.label}</strong><em>{track.browserPlayable ? track.codec : "Lossless — external player only"}</em></span>
                  </button>
                ))
              ) : audioProbeState === "probing" ? (
                <p className="player-panel-empty">Reading audio tracks from this source…</p>
              ) : (
                <p className="player-panel-empty">This source plays its default audio track — no other selectable tracks were found.</p>
              )}
            </div>
          )}

          {activePanel === "subtitles" && (
            <div className="player-panel-list">
              <button type="button" className={`player-panel-row ${activeSubtitle < 0 ? "is-active" : ""}`} onClick={() => { setActiveSubtitle(-1); setAiSubsActive(false); }}>
                <span className="player-row-icon">{activeSubtitle < 0 ? <Check size={17} /> : ""}</span>
                <span><strong>Off</strong><em>No subtitle track</em></span>
              </button>
              {aiAvailable && (
                <button
                  type="button"
                  className={`player-panel-row ${aiSubsActive ? "is-active" : ""}`}
                  onClick={() => {
                    if (aiSubsActive) {
                      setAiSubsActive(false);
                      return;
                    }
                    const englishIndex = (stream.subtitles ?? []).findIndex((subtitle) => (subtitle.lang ?? "").toLowerCase().startsWith("en"));
                    if (englishIndex < 0) {
                      onToast("AI subtitles need an English source subtitle for this title.");
                      return;
                    }
                    setActiveSubtitle(englishIndex);
                    setAiSubsActive(true);
                  }}
                >
                  <span className="player-row-icon">{aiSubsActive ? <Check size={17} /> : "AI"}</span>
                  <span>
                    <strong>AI · {aiTargetName}</strong>
                    <em>{aiSubsActive ? (aiTranslating ? "Translating…" : "Live-translating English subtitles") : `Translate English subtitles to ${aiTargetName}`}</em>
                  </span>
                </button>
              )}
              {orderedSubtitles(stream, settings.defaultSubtitle).map(({ subtitle, index, langName }) => (
                <button
                  type="button"
                  key={subtitle.id || subtitle.url}
                  className={`player-panel-row ${activeSubtitle === index && !aiSubsActive ? "is-active" : ""}`}
                  onClick={() => { setActiveSubtitle(index); setAiSubsActive(false); }}
                >
                  <span className="player-row-icon">{activeSubtitle === index && !aiSubsActive ? <Check size={17} /> : ""}</span>
                  <span>
                    <strong>{langName}</strong>
                    <em>{[subtitle.label && subtitle.label !== langName ? subtitle.label : "", subtitle.provider, subtitle.isForced ? "Forced" : ""].filter(Boolean).join(" - ") || "External subtitle"}</em>
                  </span>
                </button>
              ))}
              {(stream.subtitles?.length ?? 0) === 0 && <p className="player-panel-empty">No external subtitles were returned for this source.</p>}
            </div>
          )}

          {activePanel === "settings" && (
            <div className="player-settings-panel">
              <button type="button" className="player-setting-toggle" onClick={() => updateSettings({ autoPlayNext: !settings.autoPlayNext })}>
                <span><strong>Auto-play next episode</strong><em>Play the next episode automatically</em></span>
                <span className={`player-switch ${settings.autoPlayNext ? "is-on" : ""}`} />
              </button>
              <button type="button" className="player-setting-toggle" onClick={() => updateSettings({ autoPlaySingleSource: !settings.autoPlaySingleSource })}>
                <span><strong>Auto-play single source</strong><em>Start playing when only one source is found</em></span>
                <span className={`player-switch ${settings.autoPlaySingleSource ? "is-on" : ""}`} />
              </button>
              <div className="player-setting-row">
                <span><strong>Subtitle size</strong></span>
                <div className="player-setting-stepper">
                  <button type="button" onClick={() => updateSettings({ subtitleSize: Math.max(60, settings.subtitleSize - 10) })} aria-label="Decrease subtitle size">−</button>
                  <b>{settings.subtitleSize}%</b>
                  <button type="button" onClick={() => updateSettings({ subtitleSize: Math.min(300, settings.subtitleSize + 10) })} aria-label="Increase subtitle size">+</button>
                </div>
              </div>
              <div className="player-setting-row">
                <span><strong>Subtitle position</strong></span>
                <div className="player-setting-choices">
                  {([["bottom", "Bottom"], ["low", "Low"], ["medium", "Middle"], ["high", "High"]] as const).map(([value, label]) => (
                    <button
                      type="button"
                      key={value}
                      className={settings.subtitleOffset === value ? "is-active" : ""}
                      onClick={() => updateSettings({ subtitleOffset: value })}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </div>
              <div className="player-setting-row">
                <span><strong>Playback speed</strong></span>
                <div className="player-setting-choices">
                  {[0.75, 1, 1.25, 1.5, 2].map((rate) => (
                    <button
                      type="button"
                      key={rate}
                      className={playbackRate === rate ? "is-active" : ""}
                      onClick={() => setPlaybackRate(rate)}
                    >
                      {rate}×
                    </button>
                  ))}
                </div>
              </div>
              <div className="player-setting-row">
                <span><strong>Subtitle color</strong></span>
                <div className="player-setting-choices player-subtitle-colors">
                  {([["White", "#ffffff"], ["Yellow", "#ffeb3b"], ["Green", "#4caf50"], ["Cyan", "#00e5ff"]] as const).map(([name, hex]) => (
                    <button
                      type="button"
                      key={name}
                      className={settings.subtitleColorName === name ? "is-active" : ""}
                      style={{ ["--dot" as string]: hex }}
                      onClick={() => updateSettings({ subtitleColorName: name, subtitleColor: hex })}
                      aria-label={`${name} subtitles`}
                    >
                      <i />
                    </button>
                  ))}
                </div>
              </div>
              <div className="player-setting-row">
                <span><strong>Subtitle style</strong></span>
                <div className="player-setting-choices">
                  {(["background", "outline", "shadow", "raised"] as const).map((style) => (
                    <button
                      type="button"
                      key={style}
                      className={settings.subtitleStyle === style ? "is-active" : ""}
                      onClick={() => updateSettings({ subtitleStyle: style })}
                    >
                      {style === "background" ? "Boxed" : style.charAt(0).toUpperCase() + style.slice(1)}
                    </button>
                  ))}
                </div>
              </div>
            </div>
          )}
        </aside>
      )}

      <div className="player-controls">
        {!liveTv && (
          <input
            className="scrubber"
            type="range"
            min={0}
            max={duration || 0}
            step={0.1}
            value={current}
            style={{ ["--pct" as string]: `${pct}%`, ["--buf" as string]: `${bufPct}%` }}
            onChange={(e) => {
              const v = videoRef.current;
              if (v) v.currentTime = Number(e.target.value);
            }}
          />
        )}
        <div className="player-controls-row">
          <div className="player-controls-left">
            <button type="button" className="player-icon-btn player-play-btn" onClick={togglePlay} aria-label={playing ? "Pause" : "Play"}>
              {playing ? <Pause size={24} fill="currentColor" /> : <Play size={24} fill="currentColor" />}
            </button>
            {!liveTv && (
              <>
                <button type="button" className="player-icon-btn player-seek-btn" onClick={() => seekBy(-30)} aria-label="Back 30 seconds">
                  <RotateCcw size={22} /><span className="player-seek-label">30</span>
                </button>
                <button type="button" className="player-icon-btn player-seek-btn" onClick={() => seekBy(30)} aria-label="Forward 30 seconds">
                  <RotateCw size={22} /><span className="player-seek-label">30</span>
                </button>
              </>
            )}
            <div className="player-volume">
              <button type="button" className="player-icon-btn" onClick={() => { const v = videoRef.current; if (v) v.muted = !v.muted; }} aria-label="Mute">
                {muted || volume === 0 ? <VolumeX size={20} /> : <Volume2 size={20} />}
              </button>
              <input
                className="volume-slider"
                type="range"
                min={0}
                max={1}
                step={0.05}
                value={muted ? 0 : volume}
                style={{ ["--pct" as string]: `${(muted ? 0 : volume) * 100}%` }}
                onChange={(e) => {
                  const v = videoRef.current;
                  if (v) {
                    v.volume = Number(e.target.value);
                    v.muted = Number(e.target.value) === 0;
                  }
                }}
              />
            </div>
            {liveTv
              ? <span className="player-time player-live-indicator"><span className="live-dot" /> LIVE</span>
              : <span className="player-time">{fmt(current)} <em>/</em> {fmt(duration)}</span>}
          </div>
          <div className="player-controls-right">
            <div className="player-badges">
              {badges.map((badge) => <span key={badge} className="player-quality">{badge}</span>)}
            </div>
            {!liveTv && (
              <>
                <button type="button" className={`player-icon-btn ${activePanel === "subtitles" ? "is-active" : ""}`} onClick={() => openPanel("subtitles")} aria-label="Subtitles"><Subtitles size={20} /></button>
                <button type="button" className={`player-icon-btn ${activePanel === "audio" ? "is-active" : ""}`} onClick={() => openPanel("audio")} aria-label="Audio"><AudioLines size={20} /></button>
              </>
            )}
            <button type="button" className={`player-icon-btn ${activePanel === "sources" ? "is-active" : ""}`} onClick={() => openPanel("sources")} aria-label="Sources"><Folder size={20} /></button>
            <button type="button" className={`player-icon-btn ${activePanel === "settings" ? "is-active" : ""}`} onClick={() => openPanel("settings")} aria-label="Player settings"><Settings size={20} /></button>
            <button type="button" className="player-icon-btn" onClick={toggleFullscreen} aria-label="Fullscreen">
              {fullscreen ? <Minimize size={20} /> : <Maximize size={20} />}
            </button>
          </div>
        </div>
      </div>
    </section>
  );
}
