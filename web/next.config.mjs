import { writeFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";

// Build stamp for the in-app update watcher. iOS home-screen webapps cache the
// start page HTML aggressively (days) with no service worker to bust it — the
// client compares its baked-in stamp against /version.json (no-store) and
// reloads itself when a newer deploy exists. The same stamp is inlined via
// NEXT_PUBLIC_BUILD_STAMP and written to public/version.json here so they are
// always generated together.
const buildStamp = String(Date.now());
try {
  mkdirSync(join(process.cwd(), "public"), { recursive: true });
  writeFileSync(join(process.cwd(), "public", "version.json"), JSON.stringify({ v: buildStamp }));
} catch {
  // Non-fatal: the watcher simply stays inactive if the stamp is missing.
}

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  outputFileTracingRoot: process.cwd(),
  env: {
    NEXT_PUBLIC_BUILD_STAMP: buildStamp
  },
  images: {
    remotePatterns: [
      { protocol: "https", hostname: "image.tmdb.org" },
      { protocol: "https", hostname: "**" }
    ]
  }
};

export default nextConfig;
