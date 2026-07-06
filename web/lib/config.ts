function envValue(value: string | undefined, fallback = "") {
  return value && !value.startsWith("$") ? value : fallback;
}

export const config = {
  supabaseUrl: process.env.NEXT_PUBLIC_SUPABASE_URL ?? "",
  supabaseAnonKey: process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY ?? "",
  appAnonKey: envValue(process.env.NEXT_PUBLIC_ARVIO_APP_ANON_KEY, process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY ?? ""),
  netlifyBackendUrl: process.env.NEXT_PUBLIC_NETLIFY_BACKEND_URL ?? process.env.NETLIFY_BACKEND_URL ?? "https://auth.arvio.tv/.netlify/functions",
  resolverUrl: envValue(process.env.NEXT_PUBLIC_ARVIO_RESOLVER_URL, ""),
  traktClientId: process.env.NEXT_PUBLIC_TRAKT_CLIENT_ID ?? "",
  traktClientSecret: envValue(process.env.NEXT_PUBLIC_TRAKT_CLIENT_SECRET, ""),
  allowNetlifyMediaProxy: envValue(process.env.NEXT_PUBLIC_ALLOW_NETLIFY_MEDIA_PROXY, "false") === "true",
  imageBase: "https://image.tmdb.org/t/p/w780",
  backdropBase: "https://image.tmdb.org/t/p/w1280",
  backdropOriginal: "https://image.tmdb.org/t/p/original"
};

export function hasSupabaseConfig() {
  return config.supabaseUrl.startsWith("https://") && config.supabaseAnonKey.length > 40;
}

export function hasNetlifyBackendConfig() {
  return config.netlifyBackendUrl.startsWith("https://") && config.appAnonKey.length > 40;
}

export function hasResolverConfig() {
  return config.resolverUrl.startsWith("https://") || config.resolverUrl.startsWith("http://localhost:");
}

export function hasTraktConfig() {
  return config.traktClientId.length > 10 && !config.traktClientId.startsWith("__");
}
