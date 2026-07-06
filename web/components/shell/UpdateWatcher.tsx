"use client";

import { useEffect } from "react";

// iOS home-screen webapps cache the start page HTML for days and there is no
// service worker to invalidate it — users end up running old bundles long
// after a deploy. This watcher compares the bundle's baked-in build stamp with
// the server's /version.json (no-store) on boot, on return-to-foreground and
// every 10 minutes, and reloads the app when a newer deploy exists.
const RELOAD_GUARD_KEY = "arvio.web.lastUpdateReload";
const RELOAD_GUARD_MS = 4 * 60 * 1000;

export function UpdateWatcher() {
  useEffect(() => {
    const baked = process.env.NEXT_PUBLIC_BUILD_STAMP;
    if (!baked) return undefined;
    let disposed = false;

    const check = async () => {
      try {
        const response = await fetch("/version.json", { cache: "no-store" });
        if (!response.ok) return;
        const payload = (await response.json()) as { v?: string | number };
        if (disposed || !payload?.v || String(payload.v) === baked) return;
        // Never interrupt active playback — try again on the next check.
        const video = document.querySelector("video");
        if (video && !video.paused && !video.ended) return;
        // Guard against reload loops if an intermediary keeps serving stale
        // HTML: at most one automatic reload per window.
        const last = Number(window.localStorage.getItem(RELOAD_GUARD_KEY) ?? 0);
        if (Date.now() - last < RELOAD_GUARD_MS) return;
        window.localStorage.setItem(RELOAD_GUARD_KEY, String(Date.now()));
        window.location.reload();
      } catch {
        // Offline or blocked — retry on the next trigger.
      }
    };

    const onVisible = () => {
      if (document.visibilityState === "visible") void check();
    };
    void check();
    document.addEventListener("visibilitychange", onVisible);
    const timer = window.setInterval(() => void check(), 10 * 60 * 1000);
    return () => {
      disposed = true;
      document.removeEventListener("visibilitychange", onVisible);
      window.clearInterval(timer);
    };
  }, []);

  return null;
}
