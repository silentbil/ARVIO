export const config = {
  supabaseUrl: process.env.NEXT_PUBLIC_SUPABASE_URL ?? "",
  supabaseAnonKey: process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY ?? "",
  traktClientId: process.env.NEXT_PUBLIC_TRAKT_CLIENT_ID ?? "",
  imageBase: "https://image.tmdb.org/t/p/w780",
  backdropBase: "https://image.tmdb.org/t/p/w1280",
  backdropOriginal: "https://image.tmdb.org/t/p/original"
};

export function hasSupabaseConfig() {
  return config.supabaseUrl.startsWith("https://") && config.supabaseAnonKey.length > 40;
}

export function hasTraktConfig() {
  return config.traktClientId.length > 10 && !config.traktClientId.startsWith("__");
}
