"use client";

import { useEffect, useRef, useState } from "react";
import { loadStored, saveStored } from "@/lib/storage";
import { useApp } from "@/lib/store";
import type { CatalogConfig, Category, MediaItem } from "@/lib/types";
import { MediaRail } from "./MediaRail";

// v3: v2 entries were poisoned by collection rails colliding on a cache key
// that omitted collectionSources (all service rows shared one entry).
const CATALOG_ROW_CACHE_KEY = "arvio.web.catalogRows.v3";
const CATALOG_ROW_CACHE_TTL = 12 * 60 * 60 * 1000;
type CatalogRowCache = Record<string, { at: number; category: Category }>;

export function LazyRail({ catalog, eager = false, posterMode, onOpen, onFocus, onLoaded }: {
  catalog: CatalogConfig;
  eager?: boolean;
  posterMode?: boolean;
  onOpen: (item: MediaItem) => void;
  onFocus?: (item: MediaItem) => void;
  onLoaded?: (category: Category) => void;
}) {
  const { loadCatalogRow, settings } = useApp();
  const effectivePosterMode = posterMode ?? (catalog.layout ? catalog.layout === "poster" : settings.cardLayoutMode === "poster");
  const cacheKey = catalogCacheKey(catalog, settings.language);
  const ref = useRef<HTMLDivElement | null>(null);
  const startedRef = useRef(false);
  const [category, setCategory] = useState<Category | null>(() => readCachedCatalog(cacheKey));
  const [loading, setLoading] = useState(false);
  const [done, setDone] = useState(false);

  useEffect(() => {
    const cached = readCachedCatalog(cacheKey);
    if (cached) {
      setCategory(cached);
      onLoaded?.(cached);
    }
    startedRef.current = false;
    setDone(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cacheKey]);

  useEffect(() => {
    if (startedRef.current) return undefined;

    const load = () => {
      if (startedRef.current) return;
      startedRef.current = true;
      setLoading(true);
      void loadCatalogRow(catalog)
        .then((row) => {
          setLoading(false);
          setDone(true);
          if (row?.items.length) {
            setCategory(row);
            writeCachedCatalog(cacheKey, row);
            onLoaded?.(row);
          }
        })
        .catch(() => {
          setLoading(false);
          setDone(true);
        });
    };

    // Eager rows (top of the home) load immediately; the rest load when scrolled
    // near the viewport. setTimeout is used (not rAF/IntersectionObserver) so it
    // still fires in backgrounded/embedded tabs.
    if (eager) {
      const timer = setTimeout(load, 0);
      return () => clearTimeout(timer);
    }

    const check = () => {
      const node = ref.current;
      if (!node || startedRef.current) return;
      const rect = node.getBoundingClientRect();
      if (rect.top < window.innerHeight + 600 && rect.bottom > -600) load();
    };

    const timer = setTimeout(check, 0);
    window.addEventListener("scroll", check, { passive: true });
    window.addEventListener("resize", check);
    return () => {
      clearTimeout(timer);
      window.removeEventListener("scroll", check);
      window.removeEventListener("resize", check);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cacheKey, catalog.id, eager]);

  if (category) {
    return <MediaRail category={category} onOpen={onOpen} onFocus={onFocus} posterMode={effectivePosterMode} />;
  }
  if (done) return null;

  return (
    <section ref={ref} className={`rail rail-skeleton ${effectivePosterMode ? "is-poster" : ""}`} aria-hidden>
      <div className="rail-head">
        <h3>{catalog.name}</h3>
      </div>
      <div className="rail-strip">
        {loading && Array.from({ length: 6 }).map((_, index) => <div key={index} className="card-skeleton" />)}
      </div>
    </section>
  );
}

function catalogCacheKey(catalog: CatalogConfig, language: string) {
  return [
    catalog.id,
    language,
    catalog.sourceType,
    catalog.mediaType ?? "",
    catalog.sourceUrl ?? "",
    catalog.endpoint ?? "",
    JSON.stringify(catalog.params ?? {}),
    catalog.layout ?? "",
    // Collection rails (Prime/HBO/Hulu/Paramount…) are distinguished ONLY by
    // their collectionSources (e.g. the tmdbWatchProviderId) and kind — without
    // these in the key, every service row collides on the same cache entry and
    // renders identical content.
    catalog.kind ?? "",
    catalog.collectionSources?.length ? JSON.stringify(catalog.collectionSources) : ""
  ].join("|");
}

function readCachedCatalog(key: string) {
  const cache = loadStored<CatalogRowCache>(CATALOG_ROW_CACHE_KEY, {});
  const entry = cache[key];
  if (!entry || Date.now() - entry.at > CATALOG_ROW_CACHE_TTL) return null;
  return entry.category?.items?.length ? entry.category : null;
}

function writeCachedCatalog(key: string, category: Category) {
  const cache = loadStored<CatalogRowCache>(CATALOG_ROW_CACHE_KEY, {});
  const next: CatalogRowCache = { ...cache, [key]: { at: Date.now(), category } };
  const entries = Object.entries(next).sort((a, b) => b[1].at - a[1].at).slice(0, 80);
  saveStored(CATALOG_ROW_CACHE_KEY, Object.fromEntries(entries));
}
