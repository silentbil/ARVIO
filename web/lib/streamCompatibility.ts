import { getMediaCapabilities } from "./capabilities";
import { parseDebridStream } from "./debrid";
import type { StreamSource } from "./types";

const WEB_CONTAINERS = /\.(m3u8|mp4|m4v|mov|webm)(?:[?#/]|$)/i;
const MKV_CONTAINER = /\.mkv(?:[?#/]|$)/i;
const HARD_UNSUPPORTED_CONTAINERS = /\.(avi|wmv|flv|torrent)(?:[?#/]|$)/i;
// Archive/junk sources that are not video at all (e.g. "Movie.mkv.zip"). These
// can never play or remux — keep them out of the browser-playable set entirely.
const ARCHIVE_CONTAINERS = /\.(zip|rar|7z|tar|gz|iso|nfo|exe)(?:[?#/]|$)/i;

export type PlaybackMode = "direct" | "remux" | "transcode" | "external" | "locked";

export type StreamPlayability = {
  mode: PlaybackMode;
  reason: string;
};

type CompatStream = Pick<StreamSource, "url" | "source" | "description" | "behaviorHints">;

function streamText(stream: CompatStream) {
  return `${stream.url ?? ""} ${stream.source ?? ""} ${stream.description ?? ""}`.toLowerCase();
}

function videoBlockReason(text: string): string | null {
  const caps = getMediaCapabilities();
  const explicitHevc = text.includes("x265") || text.includes("h.265") || text.includes("h265") || text.includes("hevc");
  // 2160p/4K/UHD releases are HEVC in practice unless the name explicitly says
  // otherwise (h264/x264/avc). Treating them as H.264 made the browser attempt
  // to direct-play/remux them, which fails silently on devices without HEVC.
  const explicitH264 = text.includes("x264") || text.includes("h.264") || text.includes("h264") || text.includes("avc");
  const impliedHevc = !explicitH264 && (text.includes("2160") || /\b4k\b/.test(text) || text.includes("uhd"));
  const hevc = explicitHevc || impliedHevc;
  const dolbyVision = text.includes("dolby vision") || /\bdovi\b/.test(text) || /\bdv\b/.test(text);
  if ((hevc || dolbyVision) && !caps.hevc10 && !caps.hevc) return "This device has no HEVC decoder";
  // Dolby Vision WITHOUT an HDR(10) fallback layer is profile 5 — browsers
  // decode it but render a black/green picture (sound plays, no image).
  // Remuxes are profile 7 with an HDR10 base layer, which plays fine.
  if (dolbyVision && !text.includes("hdr") && !/\bremux\b/.test(text)) {
    return "Dolby Vision-only video shows a black picture in browsers";
  }
  if (text.includes("av1") && !caps.av1) return "This device has no AV1 decoder";
  return null;
}

function audioBlockReason(text: string): string | null {
  const caps = getMediaCapabilities();
  if (text.includes("truehd") || text.includes("true-hd")) return "TrueHD audio never plays in browsers";
  if (text.includes("dts-hd") || text.includes("dts:x") || text.includes("dts-x") || /\bdts\b/.test(text)) return "DTS audio never plays in browsers";
  if ((text.includes("eac3") || text.includes("e-ac-3") || text.includes("ddp") || text.includes("dd+") || text.includes("digital plus")) && !caps.eac3) {
    return "Dolby Digital Plus audio is not supported by this browser";
  }
  if ((/\bac3\b/.test(text) || /\bdd5\.1\b/.test(text)) && !caps.ac3 && !caps.eac3) {
    return "Dolby Digital audio is not supported by this browser";
  }
  return null;
}

function containerBlockReason(text: string): string | null {
  if (HARD_UNSUPPORTED_CONTAINERS.test(text)) return "This container does not play in browsers";
  if (MKV_CONTAINER.test(text) || /\bremux\b/.test(text)) return "MKV container needs remuxing for browser playback";
  return null;
}

function directBlockReason(stream: CompatStream): string | null {
  if (!stream.url) return "No direct URL";
  // notWebReady = torrent infoHash without a resolved URL. externalPlayerRecommended
  // comes from server-side text sniffing — the runtime capability checks below
  // supersede it, so it is intentionally ignored here.
  if (stream.behaviorHints?.notWebReady) return "Needs a debrid resolver";
  const text = streamText(stream);
  return containerBlockReason(text) ?? videoBlockReason(text) ?? audioBlockReason(text);
}

/** Does the device have the decoder this stream's video needs? */
export function videoDecodableForDevice(stream: CompatStream): boolean {
  return videoBlockReason(streamText(stream)) === null;
}

function videoDecodableForRemux(text: string) {
  const caps = getMediaCapabilities();
  if (text.includes("av1")) return caps.av1;
  if (/x265|h\.?265|hevc|2160p remux|dolby vision|\bdovi\b|\bdv\b/.test(text)) return caps.hevc10 || caps.hevc;
  // No explicit HEVC/AV1 marker → assume H.264, which everything decodes.
  return true;
}

export function streamPlayability(stream: CompatStream): StreamPlayability {
  if (!stream.url) return { mode: "locked", reason: "Needs a debrid resolver" };
  // Archive/junk sources (.zip, .rar, .iso …) are not video and can never play
  // or remux — surface as external so they never sit in the playable set.
  if (ARCHIVE_CONTAINERS.test(streamText(stream))) {
    return { mode: "external", reason: "Archive file — not directly playable" };
  }
  if (stream.behaviorHints?.notWebReady) {
    return parseDebridStream(stream.url)
      ? { mode: "transcode", reason: "Needs a debrid resolver" }
      : { mode: "external", reason: "Needs a debrid resolver" };
  }
  const blocked = directBlockReason(stream);
  if (!blocked) return { mode: "direct", reason: "" };

  const text = streamText(stream);
  const isHttp = /^https?:\/\//i.test(stream.url);
  const debrid = parseDebridStream(stream.url);
  const container = containerBlockReason(text);
  const audio = audioBlockReason(text);
  const video = videoBlockReason(text);

  // Remux path: an HTTP file the browser can't open only because of its MKV
  // container and/or lossless audio track, but whose video codec this device
  // *can* decode. We repackage it in-browser and pick the browser-safe audio
  // track — no server, no debrid Pro plan. Works for plain addon direct links
  // *and* debrid /resolve/ URLs, which redirect to CORS+range-friendly CDN MKVs.
  if (isHttp && !video && (container || audio)) {
    return { mode: "remux", reason: container ? "MKV plays after in-browser remux" : "Lossless audio skipped for browser playback" };
  }

  // Video codec this device can't decode at all → debrid transcode if possible.
  if (debrid) return { mode: "transcode", reason: blocked };
  return { mode: "external", reason: blocked };
}

export function isBrowserPlayableStream(stream: CompatStream) {
  const mode = streamPlayability(stream).mode;
  return mode === "direct" || mode === "remux" || mode === "transcode";
}

// Chromium's <video> demuxes Matroska natively (Chrome/Edge/Opera/Brave, desktop
// and Android) — an MKV whose codecs the device can decode plays DIRECTLY, no
// remux needed. canPlayType lies about this ("" for x-matroska), so detect the
// engine instead. Safari and Firefox stay on the remux path.
function isChromiumEngine() {
  if (typeof navigator === "undefined") return false;
  const ua = navigator.userAgent;
  return /Chrome\/|Chromium\/|CriOS\/|EdgA?\//.test(ua) && !/Firefox\//.test(ua);
}

// A "remux"-classified stream that Chromium can in fact play straight from the
// URL: blocked ONLY by its MKV container, with video AND audio codecs this
// device decodes (AC-3/DTS tracks still need the remux to pick a safe track).
export function canDirectPlayMkvStream(stream: CompatStream): boolean {
  if (!isChromiumEngine()) return false;
  const text = streamText(stream);
  if (!MKV_CONTAINER.test(text) && !/\bremux\b/.test(text)) return false;
  return videoBlockReason(text) === null && audioBlockReason(text) === null;
}

export function isDirectPlayableStream(stream: CompatStream) {
  return streamPlayability(stream).mode === "direct";
}

export function isIosPlayableStream(stream: CompatStream) {
  if (!stream.url) return false;
  if (stream.behaviorHints?.notWebReady) return false;
  const text = streamText(stream);
  if (containerBlockReason(text)) return false;
  if (audioBlockReason(text)) return false;
  return WEB_CONTAINERS.test(text) || stream.behaviorHints?.iosPlayable === true;
}

export function playbackWarning(stream: CompatStream) {
  const { mode, reason } = streamPlayability(stream);
  if (mode === "direct") {
    const text = streamText(stream);
    if (text.includes("dolby vision") && !text.includes("hdr")) return "Dolby Vision plays as HDR10 in this browser";
    return "";
  }
  if (mode === "remux") return reason;
  if (mode === "transcode") return `${reason} - plays via debrid transcoding`;
  if (mode === "locked") return "No direct browser URL";
  return reason;
}
