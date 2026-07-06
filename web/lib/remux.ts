// Tier 3 — client-side remux. Opens an MKV/WebM (or any mediabunny-readable
// container) straight from its URL via range requests, passes the video through
// untouched (no re-encode), and muxes it with the best *browser-decodable* audio
// track into fragmented MP4 that we feed to a <video> through MSE. TrueHD/DTS
// tracks are skipped in favour of the AC-3/E-AC-3/AAC compatibility track that
// virtually every remux also ships — no transcoding, no debrid, no server.

import type { Input, InputAudioTrack } from "mediabunny";

export type RemuxAudioTrack = {
  index: number;
  codec: string;
  label: string;
  language?: string;
  channels?: number;
  browserPlayable: boolean;
};

export type RemuxProbe = {
  container: string;
  videoCodec?: string;
  videoPlayable: boolean;
  audioTracks: RemuxAudioTrack[];
  // Index into audioTracks of the track we'll play, or -1 if none are usable.
  chosenAudioIndex: number;
};

export type RemuxHandle = {
  probe: RemuxProbe;
  /** Start remuxing into the given media element. Resolves once playback can begin. */
  start: (video: HTMLVideoElement, audioIndex?: number) => Promise<void>;
  destroy: () => void;
};

const LOSSLESS_AUDIO = /truehd|mlp|dts|dca/i;

// Whether MSE can *mux* this audio codec inside fMP4 on this browser. Distinct
// from WebCodecs decode support: Chrome can decode E-AC-3 via a WebCodecs
// decoder but its MSE demuxer rejects ec-3 in the stsd box, so such tracks must
// be re-encoded to AAC before muxing.
function mseCanMuxAudio(codec: string) {
  const c = codec.toLowerCase();
  const mp4Codec =
    /aac|mp4a/.test(c) ? "mp4a.40.2"
      : /e-?ac-?3|ec-?3/.test(c) ? "ec-3"
        : /ac-?3|ac3/.test(c) ? "ac-3"
          : /opus/.test(c) ? "opus"
            : /flac/.test(c) ? "flac"
              : null;
  if (!mp4Codec) return false;
  try {
    return MediaSource.isTypeSupported(`audio/mp4; codecs="${mp4Codec}"`);
  } catch {
    return false;
  }
}

function audioLabel(codec: string, language: string | undefined, channels: number | undefined, lossless: boolean) {
  const codecName =
    /ac-?3/i.test(codec) && !/e-?ac-?3|ec-?3/i.test(codec) ? "Dolby Digital"
      : /e-?ac-?3|ec-?3/i.test(codec) ? "Dolby Digital+"
        : /aac|mp4a/i.test(codec) ? "AAC"
          : /opus/i.test(codec) ? "Opus"
            : /flac/i.test(codec) ? "FLAC"
              : /truehd|mlp/i.test(codec) ? "TrueHD"
                : /dts|dca/i.test(codec) ? "DTS"
                  : codec.toUpperCase();
  const parts = [codecName];
  if (channels) parts.push(channels >= 7 ? "7.1" : channels >= 6 ? "5.1" : channels === 2 ? "2.0" : `${channels}ch`);
  if (language) parts.push(language.toUpperCase());
  if (lossless) parts.push("lossless");
  return parts.join(" · ");
}

async function describeAudioTrack(track: InputAudioTrack, index: number): Promise<RemuxAudioTrack> {
  const codec = (await track.getCodecParameterString().catch(() => null)) ?? (track.codec ?? "");
  const lossless = LOSSLESS_AUDIO.test(codec);
  // Lossless codecs are never browser-decodable; for the rest, ask mediabunny
  // whether this browser (natively or via a registered decoder) can decode it.
  const browserPlayable = lossless ? false : await track.canDecode().catch(() => false);
  return {
    index,
    codec,
    label: audioLabel(codec, track.languageCode ?? undefined, track.numberOfChannels ?? undefined, lossless),
    language: track.languageCode ?? undefined,
    channels: track.numberOfChannels ?? undefined,
    browserPlayable
  };
}

let ac3Registered = false;

async function ensureAudioDecoders() {
  // AC-3 / E-AC-3 aren't in WebCodecs; register mediabunny's WASM decoder so
  // canDecode() reports true and playback works everywhere (one call covers both
  // AC-3 and E-AC-3). Idempotent — only register once per page.
  if (ac3Registered) return;
  try {
    const { registerAc3Decoder } = await import("@mediabunny/ac3");
    registerAc3Decoder();
    ac3Registered = true;
  } catch {
    // Decoder package failed to load — AC-3/E-AC-3 still work where the browser
    // decodes them natively (Safari/Edge). AAC/Opus tracks play regardless.
  }
}

function normalizeLangPref(pref?: string): string {
  const p = (pref ?? "").trim().toLowerCase();
  if (!p || p === "auto" || p.startsWith("auto")) return "";
  // Map common display values to ISO 639 prefixes.
  const map: Record<string, string> = {
    english: "en", dutch: "nl", nederlands: "nl", german: "de", deutsch: "de",
    french: "fr", spanish: "es", italian: "it", portuguese: "pt", russian: "ru",
    japanese: "ja", korean: "ko", chinese: "zh", hindi: "hi", arabic: "ar", turkish: "tr"
  };
  return map[p] ?? p.slice(0, 3);
}

export async function probeAndPrepareRemux(
  url: string,
  requestHeaders?: Record<string, string>,
  preferredAudioLang?: string
): Promise<RemuxHandle | null> {
  const { Input, UrlSource, ALL_FORMATS } = await import("mediabunny");
  await ensureAudioDecoders();

  const input = new Input({
    formats: ALL_FORMATS,
    source: new UrlSource(url, requestHeaders ? { requestInit: { headers: requestHeaders } } : undefined)
  });

  const format = await input.getFormat().catch(() => null);
  const videoTrack = await input.getPrimaryVideoTrack().catch(() => null);
  const audioInputTracks = await input.getAudioTracks().catch(() => []);
  if (!videoTrack) {
    input.dispose?.();
    return null;
  }

  const videoCodecParam = (await videoTrack.getCodecParameterString().catch(() => null)) ?? undefined;
  const videoPlayable = await videoTrack.canDecode().catch(() => false);
  const audioTracks = await Promise.all(audioInputTracks.map((track, i) => describeAudioTrack(track, i)));
  const fileDuration = await input.computeDuration().catch(() => 0);

  // Prefer a browser-playable track. Honor the user's preferred audio language
  // first; among the remaining candidates prefer more channels, then the first
  // declared (usually the primary/default language).
  const langPref = normalizeLangPref(preferredAudioLang);
  const playable = audioTracks.filter((track) => track.browserPlayable);
  const inPreferred = langPref
    ? playable.filter((track) => (track.language ?? "").toLowerCase().startsWith(langPref))
    : [];
  const pool = inPreferred.length ? inPreferred : playable;
  const chosen = pool.sort((a, b) => (b.channels ?? 0) - (a.channels ?? 0))[0];
  const chosenAudioIndex = chosen ? audioTracks.indexOf(chosen) : -1;

  const probe: RemuxProbe = {
    container: format?.name ?? "unknown",
    videoCodec: videoCodecParam,
    videoPlayable,
    audioTracks,
    chosenAudioIndex
  };

  let conversion: Awaited<ReturnType<typeof import("mediabunny").Conversion.init>> | null = null;
  let mediaSource: MediaSource | null = null;
  let objectUrl: string | null = null;

  const destroy = () => {
    void conversion?.cancel().catch(() => undefined);
    if (objectUrl) URL.revokeObjectURL(objectUrl);
    try {
      if (mediaSource && mediaSource.readyState === "open") mediaSource.endOfStream();
    } catch {
      // endOfStream can throw if already ended.
    }
    input.dispose?.();
  };

  const start = async (video: HTMLVideoElement, audioIndex = chosenAudioIndex) => {
    const { Output, Mp4OutputFormat, StreamTarget, Conversion } = await import("mediabunny");

    mediaSource = new MediaSource();
    objectUrl = URL.createObjectURL(mediaSource);
    video.src = objectUrl;

    await new Promise<void>((resolve) => {
      mediaSource!.addEventListener("sourceopen", () => resolve(), { once: true });
    });

    // Pin the real file duration up front — otherwise the player's total time
    // starts near zero and counts upward as fragments append, which reads as
    // "it's loading the whole file" in the UI.
    if (fileDuration && Number.isFinite(fileDuration)) {
      try {
        mediaSource!.duration = fileDuration;
      } catch {
        // Non-fatal: duration then grows with appended fragments as before.
      }
    }

    // fMP4 with hvc1/mp4a; MSE mime is set once we see the init segment's codecs.
    // Buffer appends are serialized through a queue because SourceBuffer.appendBuffer
    // is asynchronous and single-flight.
    let sourceBuffer: SourceBuffer | null = null;
    const queue: BufferSource[] = [];
    let ended = false;
    const pump = () => {
      if (!sourceBuffer || sourceBuffer.updating || queue.length === 0) return;
      const chunk = queue.shift()!;
      try {
        sourceBuffer.appendBuffer(chunk);
      } catch {
        // QuotaExceeded on very long buffers — drop; live seek/rebuffer handles it.
      }
    };

    // If MSE can't mux the chosen audio codec (e.g. E-AC-3 in Chrome), re-encode
    // just that track to AAC — cheap, video still passes through untouched.
    const chosenCodec = audioTracks[audioIndex]?.codec ?? "";
    const audioNeedsAac = audioIndex >= 0 && !mseCanMuxAudio(chosenCodec);
    const outputAudioCodec = audioNeedsAac ? "mp4a.40.2" : chosenCodec;

    const mimeFor = (codecs: string[]) => `video/mp4; codecs="${codecs.join(",")}"`;
    let initialised = false;

    const writable = new WritableStream<{ data: Uint8Array; type: string; position: number }>({
      write(chunk) {
        if (!initialised) {
          initialised = true;
          const codecs = [probe.videoCodec, outputAudioCodec].filter(Boolean) as string[];
          const mime = mimeFor(codecs.length ? codecs : ["hvc1"]);
          try {
            sourceBuffer = mediaSource!.addSourceBuffer(MediaSource.isTypeSupported(mime) ? mime : 'video/mp4; codecs="hvc1.1.6.L153.B0,mp4a.40.2"');
          } catch {
            sourceBuffer = mediaSource!.addSourceBuffer('video/mp4; codecs="avc1.640028,mp4a.40.2"');
          }
          sourceBuffer.addEventListener("updateend", pump);
        }
        // fastStart:'fragmented' emits append-only, in-order chunks, so we can
        // stream them straight into the SourceBuffer. Copy into a fresh
        // ArrayBuffer so the typed-array's backing store is a plain BufferSource.
        queue.push(chunk.data.slice().buffer);
        pump();
      },
      close() {
        ended = true;
        const finish = () => {
          if (queue.length || sourceBuffer?.updating) {
            setTimeout(finish, 50);
            return;
          }
          try {
            if (mediaSource!.readyState === "open") mediaSource!.endOfStream();
          } catch {
            // already ended
          }
        };
        finish();
      }
    });

    const output = new Output({
      format: new Mp4OutputFormat({ fastStart: "fragmented" }),
      target: new StreamTarget(writable as WritableStream)
    });

    conversion = await Conversion.init({
      input,
      output,
      video: () => ({}), // passthrough copy — no re-encode
      audio: (track, n) => {
        if (n - 1 !== audioIndex) return { discard: true };
        // Passthrough when MSE can mux it; otherwise transcode audio-only to AAC.
        return audioNeedsAac ? { codec: "aac" as const } : {};
      }
    });

    // Run in the background; playback starts as soon as MSE has enough buffered.
    void conversion.execute().catch(() => {
      if (!ended) destroy();
    });

    // Resolve once the element can actually start playing.
    await new Promise<void>((resolve) => {
      const onReady = () => resolve();
      video.addEventListener("loadeddata", onReady, { once: true });
      video.addEventListener("canplay", onReady, { once: true });
      setTimeout(resolve, 12000);
    });
  };

  return { probe, start, destroy };
}

export function remuxWorthTrying(url: string, text: string) {
  // Only attempt for container/codec combos a remux can actually rescue, and only
  // for direct http(s) file URLs (addon direct links, not torrents/magnets).
  if (!/^https?:\/\//i.test(url)) return false;
  const hay = `${url} ${text}`.toLowerCase();
  const mkv = /\.mkv(?:[?#/]|$)/.test(hay) || hay.includes("matroska") || hay.includes("remux") || hay.includes("x-matroska");
  return mkv;
}
