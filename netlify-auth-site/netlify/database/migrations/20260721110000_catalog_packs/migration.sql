CREATE TABLE IF NOT EXISTS public.catalog_packs (
  id uuid PRIMARY KEY DEFAULT (md5(random()::text || clock_timestamp()::text)::uuid),
  name text NOT NULL,
  url text NOT NULL,
  normalized_url text UNIQUE NOT NULL,
  author text,
  version text DEFAULT '1.0.0',
  description text,
  catalogs jsonb NOT NULL,
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_catalog_packs_status ON public.catalog_packs(status);

CREATE TABLE IF NOT EXISTS public.submission_rate_limits (
  ip_hash text PRIMARY KEY,
  count integer NOT NULL DEFAULT 1,
  last_submission_at timestamptz NOT NULL DEFAULT now()
);

-- Seed approved catalog packs if not already present
INSERT INTO public.catalog_packs (id, name, url, normalized_url, author, version, description, catalogs, status)
VALUES
  ('c0a37e5e-5b12-4eb0-a5ea-9d84c1737e51', 'Cinema Essentials', '/packs/cinema-essentials.json', 'https://arvio.app/packs/cinema-essentials.json', 'ARVIO Team', '1.0.0', 'All the trending movies, popular lists, and upcoming releases you need for a perfect movie night.', '["Trending in Movies", "Top 10 Movies Today", "Top Movies This Week", "Coming Soon"]'::jsonb, 'approved'),
  ('c0a37e5e-5b12-4eb0-a5ea-9d84c1737e52', 'TV Show Binge Pack', '/packs/tv-binge.json', 'https://arvio.app/packs/tv-binge.json', 'ARVIO Team', '1.0.0', 'Never miss an episode. Popular, trending, and latest airing series in one convenient bundle.', '["Trending in Shows", "Top 10 Shows Today", "Latest Airing"]'::jsonb, 'approved'),
  ('c0a37e5e-5b12-4eb0-a5ea-9d84c1737e53', 'Otaku & K-Drama Hub', '/packs/anime-kdrama.json', 'https://arvio.app/packs/anime-kdrama.json', 'Community', '1.1.2', 'The ultimate pack for anime lovers and K-Drama fans. Auto-updated lists of trending episodes and releases.', '["Trending in Anime", "New in K-Dramas"]'::jsonb, 'approved'),
  ('c0a37e5e-5b12-4eb0-a5ea-9d84c1737e54', 'Action & Franchise Classics', '/packs/classics-franchises.json', 'https://arvio.app/packs/classics-franchises.json', 'Cinephile', '1.0.5', 'Full box-sets and classic franchise collections, including James Bond, Harry Potter, Lord of the Rings, and Jurassic Park.', '["James Bond Collection", "Harry Potter Collection", "The Matrix Collection", "Lord of the Rings and Hobbit Collection", "Jurassic Park Collection"]'::jsonb, 'approved')
ON CONFLICT (id) DO NOTHING;
