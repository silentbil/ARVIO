# ARVIO Web

Browser-native ARVIO app for iPad, desktop, and TV browsers. This app lives beside the Android app and mirrors the same product surface without touching Android-only code.

## What It Reuses

- ARVIO Netlify auth and account sync (`auth-login`, `auth-refresh`, `account-sync-pull`, `account-sync-push`).
- Android's TMDB and Trakt proxy model through Next API routes.
- Stremio-style addon manifest and stream response contracts.
- IPTV M3U playlist parsing, channel groups, favorites, and browser HLS playback.
- ARVIO visual language: dark full-screen shell, left TV navigation, hero, rails, details drawer, source selector, player overlay, Live TV, addons, settings.

## Environment

Copy `.env.example` to `.env.local` and fill:

```bash
NEXT_PUBLIC_NETLIFY_BACKEND_URL=https://auth.arvio.tv/.netlify/functions
NEXT_PUBLIC_ARVIO_APP_ANON_KEY=
NEXT_PUBLIC_SUPABASE_URL=
NEXT_PUBLIC_SUPABASE_ANON_KEY=
NEXT_PUBLIC_TRAKT_CLIENT_ID=
TMDB_API_KEY=
TRAKT_CLIENT_SECRET=
```

When `NEXT_PUBLIC_ARVIO_APP_ANON_KEY` is configured, `/api/tmdb/*`, `/api/trakt/*`, cloud login, and account sync use the same Netlify backend as Android. `NEXT_PUBLIC_SUPABASE_ANON_KEY` is kept as a fallback for older deployments; new deployments should provide the app anon key explicitly.

## Run

```bash
npm install
npm run dev
```

Production check:

```bash
npm run build
npm run start
```

## Netlify Git Deploy

The source for `web.arvio.tv` is this `web/` directory. Do not connect Netlify to a repository that only contains generated `_next/` output.

Recommended Netlify settings for the `arvio-web` site:

```text
Repository: ProdigyV21/ARVIO
Branch: main
Base directory: web
Build command: npm run build
Publish directory: .next
```

The matching config is tracked in `web/netlify.toml`. If Netlify is linked from the repository root, set the site base directory to `web`; otherwise Netlify will build the Android/landing workspace instead of the browser app.

## Browser Limits

The browser player can play direct HTTP/HLS URLs. Addon streams that only return torrents, info hashes, or Android-player-only formats still need a web resolver/transcode path before they can play in a browser.
