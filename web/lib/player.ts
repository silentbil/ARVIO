export type PlaybackOptions = {
  onError?: () => void;
  live?: boolean;
};

function canUseMse() {
  return typeof window !== "undefined" && "MediaSource" in window;
}

function isHlsUrl(url: string) {
  return url.includes(".m3u8") || url.includes("application/vnd.apple.mpegurl");
}

function isMpegTsCandidate(url: string) {
  // Raw MPEG-TS: explicit .ts extension, or an extensionless Xtream-style live
  // path (…/live/user/pass/12345). Encoded URLs going through a proxy keep the
  // ".ts" of the target inside the query string, so check the whole URL.
  if (/\.ts(?:$|[?#&])/i.test(url) || url.includes(".ts%3F") || url.toLowerCase().includes(".ts&")) return true;
  try {
    const path = new URL(url).pathname;
    return /\/live\/[^/]+\/[^/]+\/\d+$/.test(path);
  } catch {
    return false;
  }
}

export function attachPlayback(video: HTMLVideoElement, url: string, options: PlaybackOptions = {}) {
  const hlsUrl = isHlsUrl(url);

  // Prefer hls.js over the browser's native HLS: MSE playback transmuxes the
  // MPEG-TS segments common in IPTV reliably, while native implementations
  // outside iOS/Safari stall on them. Native is the fallback when MSE is missing.
  // hls.js is lazy-loaded so its ~quarter-megabyte doesn't sit in the initial
  // bundle for visitors who never press play.
  if (hlsUrl && !canUseMse() && video.canPlayType("application/vnd.apple.mpegurl")) {
    video.src = url;
    return () => {
      video.removeAttribute("src");
      video.load();
    };
  }

  if (hlsUrl && canUseMse()) {
    let hls: { destroy: () => void } | null = null;
    let cancelled = false;
    import("hls.js").then(({ default: Hls }) => {
      if (cancelled) return;
      if (!Hls.isSupported()) {
        if (video.canPlayType("application/vnd.apple.mpegurl")) video.src = url;
        else options.onError?.();
        return;
      }
      const instance = new Hls({
        enableWorker: true,
        lowLatencyMode: true,
        backBufferLength: 90
      });
      instance.on(Hls.Events.ERROR, (_event, data) => {
        if (!data.fatal) return;
        if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
          instance.recoverMediaError();
          return;
        }
        options.onError?.();
      });
      instance.loadSource(url);
      instance.attachMedia(video);
      hls = instance;
    }).catch(() => options.onError?.());
    return () => {
      cancelled = true;
      hls?.destroy();
      video.removeAttribute("src");
      video.load();
    };
  }

  if (options.live && isMpegTsCandidate(url) && typeof window !== "undefined" && "MediaSource" in window) {
    let player: { destroy: () => void } | null = null;
    let cancelled = false;
    import("mpegts.js").then(({ default: mpegts }) => {
      if (cancelled) return;
      if (!mpegts.isSupported()) {
        video.src = url;
        return;
      }
      const instance = mpegts.createPlayer(
        { type: "mpegts", isLive: true, url },
        { enableWorker: true, liveBufferLatencyChasing: true, autoCleanupSourceBuffer: true }
      );
      instance.on(mpegts.Events.ERROR, () => options.onError?.());
      instance.attachMediaElement(video);
      instance.load();
      player = instance;
    }).catch(() => options.onError?.());
    return () => {
      cancelled = true;
      try {
        player?.destroy();
      } catch {
        // mpegts teardown after a fatal error can throw; the element reset below is enough.
      }
      video.removeAttribute("src");
      video.load();
    };
  }

  video.src = url;
  return () => {
    video.removeAttribute("src");
    video.load();
  };
}
