// ARVIO Telegram streaming service worker.
//
// The <video> element plays from same-origin URLs like /tg-stream/<id>. Telegram
// files can only be fetched through the authorized GramJS client that lives in
// the PAGE, so this worker forwards every byte-range request to the page over a
// MessageChannel and turns the page's reply into a 206 Partial Content response.
// This is the browser equivalent of Android's local TelegramStreamingProxy.

const PREFIX = "/tg-stream/";

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin || !url.pathname.startsWith(PREFIX)) return;
  event.respondWith(handleStream(event));
});

async function handleStream(event) {
  const url = new URL(event.request.url);
  const streamId = decodeURIComponent(url.pathname.slice(PREFIX.length));

  const range = parseRange(event.request.headers.get("Range"));

  const client =
    (await self.clients.get(event.clientId)) ||
    (await self.clients.matchAll({ type: "window" }))[0];
  if (!client) return new Response("No client", { status: 500 });

  let data;
  try {
    data = await requestRange(client, streamId, range.start, range.end);
  } catch (err) {
    return new Response(String(err && err.message ? err.message : err), { status: 502 });
  }
  if (!data || !data.ok) {
    return new Response(data && data.error ? data.error : "Not found", { status: 404 });
  }

  const { buffer, totalSize, mime, start, end } = data;
  const headers = new Headers({
    "Content-Type": mime || "video/mp4",
    "Accept-Ranges": "bytes",
    "Content-Length": String(end - start + 1),
    "Content-Range": `bytes ${start}-${end}/${totalSize}`,
    "Cache-Control": "no-store",
  });
  return new Response(buffer, { status: 206, headers });
}

function requestRange(client, streamId, start, end) {
  return new Promise((resolve, reject) => {
    const channel = new MessageChannel();
    const timeout = setTimeout(() => reject(new Error("Telegram byte range timed out")), 45000);
    channel.port1.onmessage = (e) => {
      clearTimeout(timeout);
      resolve(e.data);
    };
    client.postMessage({ type: "ARVIO_TG_RANGE", streamId, start, end }, [channel.port2]);
  });
}

function parseRange(header) {
  if (!header) return { start: 0, end: null };
  const m = /bytes=(\d*)-(\d*)/.exec(header);
  if (!m) return { start: 0, end: null };
  const start = m[1] ? Number(m[1]) : 0;
  const end = m[2] ? Number(m[2]) : null;
  return { start, end };
}
