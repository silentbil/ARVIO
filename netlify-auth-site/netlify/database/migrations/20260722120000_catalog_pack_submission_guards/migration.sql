ALTER TABLE public.catalog_packs
  ADD COLUMN IF NOT EXISTS normalized_url text;

UPDATE public.catalog_packs
SET normalized_url = lower(
  rtrim(
    CASE
      WHEN url LIKE '/%' THEN 'https://arvio.app' || url
      ELSE url
    END,
    '/'
  )
)
WHERE normalized_url IS NULL OR btrim(normalized_url) = '';

ALTER TABLE public.catalog_packs
  ALTER COLUMN normalized_url SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_catalog_packs_normalized_url
  ON public.catalog_packs(normalized_url);

CREATE TABLE IF NOT EXISTS public.submission_rate_limits (
  ip_hash text PRIMARY KEY,
  count integer NOT NULL DEFAULT 1,
  last_submission_at timestamptz NOT NULL DEFAULT now()
);
