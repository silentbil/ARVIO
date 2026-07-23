// Page side of Telegram streaming. Registers the streaming service worker,
// keeps a registry of playable videos keyed by an opaque stream id, and answers
// the worker's byte-range requests by downloading through the authorized GramJS
// client (see downloadRange in client.ts).

import { downloadRange, type TgVideo } from "./client";

// Max bytes served per range request. The player asks for open-ended ranges
// (`bytes=start-`); we cap each response and let it request the next window,
// exactly like Android's 2 MB CHUNK_SIZE proxy loop (a little larger here to cut
// round-trips over the network hop).
const MAX_WINDOW = 4 * 1024 * 1024;

const registry = new Map<string, TgVideo>();
let listenerInstalled = false;
let swReady: Promise<void> | null = null;

export function isStreamingSupported(): boolean {
  return typeof navigator !== "undefined" && "serviceWorker" in navigator && typeof MessageChannel !== "undefined";
}

/** Register the streaming worker and wire the page-side range handler. Idempotent. */
export async function initTelegramStreaming(): Promise<void> {
  if (!isStreamingSupported()) return;
  if (!listenerInstalled) {
    navigator.serviceWorker.addEventListener("message", onWorkerMessage);
    listenerInstalled = true;
  }
  if (!swReady) {
    swReady = navigator.serviceWorker
      .register("/tg-stream-sw.js")
      .then(() => navigator.serviceWorker.ready)
      .then(() => undefined)
      .catch((e) => {
        swReady = null;
        throw e;
      });
  }
  await swReady;
}

/**
 * Register a Telegram video for playback and return the same-origin URL the
 * <video> element should load. Returns null if streaming is unsupported.
 */
export function registerStream(video: TgVideo): string | null {
  if (!isStreamingSupported()) return null;
  const id = crypto.randomUUID();
  registry.set(id, video);
  // Fire-and-forget: ensure the worker is registered by the time the player
  // issues its first range request.
  void initTelegramStreaming();
  return `/tg-stream/${id}`;
}

export function clearStream(streamUrl: string) {
  const id = streamUrl.split("/tg-stream/")[1];
  if (id) registry.delete(decodeURIComponent(id));
}

async function onWorkerMessage(event: MessageEvent) {
  const data = event.data;
  if (!data || data.type !== "ARVIO_TG_RANGE") return;
  const port = event.ports[0];
  if (!port) return;

  const { streamId, start, end } = data as { streamId: string; start: number; end: number | null };
  const video = registry.get(streamId);
  if (!video) {
    port.postMessage({ ok: false, error: "Stream expired" });
    return;
  }

  const total = video.size;
  const from = Math.max(0, Math.min(start ?? 0, total - 1));
  const requestedEnd = end == null ? total - 1 : Math.min(end, total - 1);
  const to = Math.min(requestedEnd, from + MAX_WINDOW - 1);
  const length = to - from + 1;

  try {
    const bytes = await downloadRange(video, from, length);
    const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
    port.postMessage(
      { ok: true, buffer, totalSize: total, mime: video.mime, start: from, end: from + bytes.length - 1 },
      [buffer]
    );
  } catch (e) {
    port.postMessage({ ok: false, error: (e as Error)?.message ?? "Download failed" });
  }
}
