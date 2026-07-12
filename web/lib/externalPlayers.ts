import { resolverDownloadUrl, resolverLaunchUrl, resolverSubtitleUrl } from "./resolver";
import type { StreamSource, SubtitleTrack } from "./types";

export type ExternalPlayer = "vlc" | "infuse";

function cleanFilename(value: string) {
  return value
    .replace(/[\\/:*?"<>|]+/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 140) || "ARVIO stream";
}

function subtitleUrl(subtitle?: SubtitleTrack) {
  if (!subtitle?.url) return "";
  return resolverSubtitleUrl(subtitle.url);
}

// External players (VLC/Infuse) accept a single sidecar subtitle URL. Pick the
// one matching the user's preferred language, else English, else the first —
// so the addon subtitle (e.g. OpenSubtitles) is loaded automatically.
function pickSubtitle(subtitles: SubtitleTrack[] | undefined, preferredLang: string): SubtitleTrack | undefined {
  if (!subtitles?.length) return undefined;
  const pref = preferredLang.trim().toLowerCase();
  const byLang = (prefix: string) => subtitles.find((s) => (s.lang ?? "").toLowerCase().startsWith(prefix) && !s.isForced && !s.isEmbedded);
  if (pref && pref !== "off") {
    const match = byLang(pref);
    if (match) return match;
  }
  return byLang("en") ?? subtitles.find((s) => !s.isEmbedded) ?? subtitles[0];
}

function isTorrentioResolve(url?: string | null) {
  return Boolean(url && /torrentio\.strem\.fun\/(?:resolve\/)?(?:torbox|realdebrid)/i.test(url));
}

export function externalPlayerUrl(player: ExternalPlayer, stream: StreamSource, title: string, preferredSubtitleLang = "") {
  // External players decode everything natively. Prefer the ORIGINAL file over
  // a debrid-transcoded HLS variant — UNLESS the original is a torrentio
  // /resolve/ redirect, in which case the resolved CDN url in `stream.url` is
  // what makes VLC start instantly (following the redirect chain costs ~10-15s).
  const targetUrl = isTorrentioResolve(stream.originalUrl)
    ? stream.url
    : (stream.originalUrl ?? stream.url);
  if (!targetUrl) return "";
  // Give the external player the real container in the filename so it probes
  // ALL streams (a .mp4 hint on an actual MKV can make VLC miss extra audio
  // tracks). Detect from the source text / URL; default to mkv (most debrid
  // remuxes) rather than mp4.
  const containerText = `${targetUrl} ${stream.originalUrl ?? ""} ${stream.source ?? ""} ${stream.description ?? ""}`.toLowerCase();
  const ext = /\.m3u8|mpegurl/.test(containerText)
    ? "m3u8"
    : /\.mp4(?:[?#/]|$)/.test(containerText)
      ? "mp4"
      : /\.(mkv|matroska)|remux|\bweb-?dl\b|bluray|\bhevc\b|x265/.test(containerText)
        ? "mkv"
        : "mp4";
  const filename = `${cleanFilename(title)}.${ext}`;
  // Hand VLC/Infuse the addon subtitle (e.g. OpenSubtitles) in the user's
  // language — their URL schemes accept exactly one sidecar sub.
  const sub = subtitleUrl(pickSubtitle(stream.subtitles, preferredSubtitleLang));
  const params = new URLSearchParams();
  params.set("url", targetUrl);
  params.set("filename", filename);
  if (sub) params.set("sub", sub);

  if (player === "infuse") {
    params.set("download", "0");
    return `infuse://x-callback-url/play?${params.toString()}`;
  }

  return `vlc-x-callback://x-callback-url/stream?${params.toString()}`;
}

// iOS home-screen webapps (standalone PWAs) silently DROP custom-scheme
// navigations — no error, no app launch. Detect that context and route the
// scheme through the resolver's /launch interstitial instead: the PWA opens
// https links in its Safari sheet, which CAN launch app schemes (native
// "Open in VLC?" prompt). Regular browser tabs use a programmatic anchor
// click, the most reliable scheme launch on iOS Safari/Chrome.
// This MUST run synchronously inside the click handler.
function isIosStandalone() {
  return typeof navigator !== "undefined" && (navigator as { standalone?: boolean }).standalone === true;
}

function launchScheme(href: string) {
  if (typeof document === "undefined") return;
  if (isIosStandalone()) {
    const interstitial = resolverLaunchUrl(href);
    if (interstitial) {
      window.location.href = interstitial;
      return;
    }
  }
  // Anchor click — most reliable in real browser tabs for custom schemes.
  try {
    const a = document.createElement("a");
    a.href = href;
    a.rel = "noopener";
    // Keep it out of layout but still clickable.
    a.style.position = "fixed";
    a.style.left = "-9999px";
    document.body.appendChild(a);
    a.click();
    // Remove after the navigation has had a chance to fire.
    setTimeout(() => a.remove(), 1500);
  } catch {
    // Direct assignment fallback (desktop / Android WebView).
    try {
      window.location.href = href;
    } catch {
      /* ignore */
    }
  }
}

function isAppleMobile() {
  if (typeof navigator === "undefined") return false;
  const ua = navigator.userAgent;
  // iPadOS 13+ masquerades as Macintosh but has touch points.
  return /iPad|iPhone|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
}

function isAndroid() {
  return typeof navigator !== "undefined" && /Android/i.test(navigator.userAgent);
}

// How VLC/Infuse are launched on this platform. vlc-x-callback:// is an
// iOS-ONLY scheme — desktop VLC (Windows/macOS/Linux) registers no URL
// protocol at all, so on desktop the only reliable handoff is a tiny .m3u
// playlist download: VLC owns the .m3u association, one click plays it.
export function externalLaunchMode(player: ExternalPlayer): "scheme" | "playlist" {
  if (player === "vlc" && !isAppleMobile() && !isAndroid()) return "playlist";
  return "scheme";
}

function downloadVlcPlaylist(stream: StreamSource, title: string, preferredSubtitleLang: string) {
  const targetUrl = isTorrentioResolve(stream.originalUrl) ? stream.url : (stream.originalUrl ?? stream.url);
  if (!targetUrl || typeof document === "undefined") return false;
  const sub = subtitleUrl(pickSubtitle(stream.subtitles, preferredSubtitleLang));
  const lines = ["#EXTM3U", `#EXTINF:-1,${cleanFilename(title)}`];
  // input-slave attaches the addon subtitle as a second input — VLC loads it
  // automatically alongside the stream.
  if (sub) lines.push(`#EXTVLCOPT:input-slave=${sub}`);
  lines.push(targetUrl);
  const blob = new Blob([`${lines.join("\n")}\n`], { type: "audio/x-mpegurl" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `${cleanFilename(title)}.m3u`;
  document.body.appendChild(a);
  a.click();
  setTimeout(() => {
    a.remove();
    URL.revokeObjectURL(url);
  }, 4000);
  return true;
}

export function openExternalPlayer(player: ExternalPlayer, stream: StreamSource, title: string, preferredSubtitleLang = "") {
  if (externalLaunchMode(player) === "playlist") {
    return downloadVlcPlaylist(stream, title, preferredSubtitleLang);
  }
  const href = externalPlayerUrl(player, stream, title, preferredSubtitleLang);
  if (!href) return false;
  launchScheme(href);
  return true;
}

export async function copyStreamUrl(stream: StreamSource) {
  const targetUrl = stream.originalUrl ?? stream.url;
  if (!targetUrl || typeof navigator === "undefined" || !navigator.clipboard) return false;
  await navigator.clipboard.writeText(targetUrl);
  return true;
}

// Download via the resolver worker, which adds Content-Disposition: attachment.
// CDNs serve video files inline, so a plain link makes iOS Safari/Chrome open a
// preview page instead of downloading — the attachment header hands the file to
// the browser's own download manager (visible progress, Files app on iOS).
export function downloadStreamUrl(stream: StreamSource, title: string) {
  const targetUrl = isTorrentioResolve(stream.originalUrl) ? stream.url : (stream.originalUrl ?? stream.url);
  if (!targetUrl) return null;
  const containerText = `${targetUrl} ${stream.originalUrl ?? ""} ${stream.source ?? ""} ${stream.description ?? ""}`.toLowerCase();
  const ext = /\.mp4(?:[?#/]|$)/.test(containerText) ? "mp4" : "mkv";
  const filename = `${cleanFilename(title)}.${ext}`;
  return resolverDownloadUrl(targetUrl, filename) ?? targetUrl;
}

export function downloadStream(stream: StreamSource, title: string) {
  const href = downloadStreamUrl(stream, title);
  if (!href) return false;
  return triggerDownload(href, cleanFilename(title));
}

// Start a download via a programmatic <a download> click — the reliable path in
// real browser tabs AND installed PWAs. `window.location.href = href` navigated
// the whole tab: a non-200 (rate-limited/expired token) then left a blank page
// with no feedback, and even a 200 attachment could replace the app in some
// engines. The anchor keeps the app in place and lets the browser's own
// download manager own the transfer (the download proxy sets Content-
// Disposition: attachment, so this never opens an inline preview).
export function triggerDownload(href: string, filename: string) {
  if (typeof document === "undefined") return false;
  try {
    const a = document.createElement("a");
    a.href = href;
    a.download = filename || "arvio-download";
    a.rel = "noopener";
    a.style.position = "fixed";
    a.style.left = "-9999px";
    document.body.appendChild(a);
    a.click();
    setTimeout(() => a.remove(), 4000);
    return true;
  } catch {
    try {
      window.location.href = href;
      return true;
    } catch {
      return false;
    }
  }
}
