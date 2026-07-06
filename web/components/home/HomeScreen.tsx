"use client";

import { Info, Play } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { IMDB_LOGO } from "@/lib/serviceLogos";
import { genreNamesFromIds, getLogoUrl } from "@/lib/tmdb";
import { useApp } from "@/lib/store";
import { LazyRail } from "@/components/media/LazyRail";
import { MediaRail } from "@/components/media/MediaRail";
import type { Category, MediaItem } from "@/lib/types";

export function HomeScreen() {
  const { hero, categories, catalogConfigs, homeServerRows, continueWatching, openDetails, setHeroPreview, settings } = useApp();
  const posterMode = settings.cardLayoutMode === "poster";

  // The eager rails (trending/popular/provider lists) overlap heavily; keep each
  // title in the first rail it appears in and trim repeats from later rails,
  // unless doing so would hollow a rail out.
  const dedupedCategories = useMemo(() => {
    const seen = new Set<string>();
    return categories.map((category) => {
      const kept = category.items.filter((item) => !seen.has(`${item.mediaType}-${item.id}`));
      const items = kept.length >= Math.min(8, category.items.length) ? kept : category.items;
      items.forEach((item) => seen.add(`${item.mediaType}-${item.id}`));
      return items === category.items ? category : { ...category, items };
    });
  }, [categories]);
  const [heroLogo, setHeroLogo] = useState<string | null>(null);
  const hoverTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const seededHero = useRef(false);
  const userInteractedHero = useRef(false);

  // Showcase titles for the rotating hero, collected from the first rails as
  // they load (trending rails arrive lazily, so we accumulate here).
  const [heroPoolRows, setHeroPoolRows] = useState<MediaItem[]>([]);

  const seedHeroFromRow = (row: Category) => {
    // Feed the first couple of catalog rows into the hero rotation pool.
    setHeroPoolRows((prev) => {
      if (prev.length >= 12) return prev;
      const seen = new Set(prev.map((i) => `${i.mediaType}-${i.id}`));
      const additions = row.items
        .filter((item) => item.backdrop && !seen.has(`${item.mediaType}-${item.id}`))
        .slice(0, 8);
      return additions.length ? [...prev, ...additions].slice(0, 12) : prev;
    });
    if (seededHero.current || continueWatching.length) return;
    const first = row.items[0];
    if (first) {
      seededHero.current = true;
      setHeroPreview(first);
    }
  };

  const heroPool = heroPoolRows;

  // Auto-advance the hero every 8s until the user hovers a card (which pins the
  // hero to whatever they're pointing at and stops the carousel).
  useEffect(() => {
    if (heroPool.length < 2) return undefined;
    let index = 0;
    if (!userInteractedHero.current) setHeroPreview(heroPool[0]);
    const timer = window.setInterval(() => {
      if (userInteractedHero.current) return;
      index = (index + 1) % heroPool.length;
      setHeroPreview(heroPool[index]);
    }, 8000);
    return () => window.clearInterval(timer);
  }, [heroPool, setHeroPreview]);

  // Title-treatment logo for the hero.
  useEffect(() => {
    setHeroLogo(null);
    if (!hero || hero.id <= 0) return;
    let active = true;
    void getLogoUrl({ mediaType: hero.mediaType, id: hero.id }).then((url) => {
      if (active) setHeroLogo(url);
    }).catch(() => undefined);
    return () => { active = false; };
  }, [hero?.id, hero?.mediaType]);

  const onCardFocus = (item: MediaItem) => {
    userInteractedHero.current = true;
    if (hoverTimer.current) clearTimeout(hoverTimer.current);
    hoverTimer.current = setTimeout(() => setHeroPreview(item), 220);
  };

  const heroGenres = (hero?.genres?.length ? hero.genres : genreNamesFromIds(hero?.genreIds)).slice(0, 3);
  const metaBits = [
    hero?.mediaType === "tv" ? "Series" : "Movie",
    hero?.releaseDate?.slice(0, 4) || hero?.year || null,
    hero?.duration || null,
    ...heroGenres
  ].filter(Boolean);

  return (
    <div className="screen">
      {hero && (
        <section className="hero" style={{ backgroundImage: hero.backdrop ? `url(${hero.backdrop})` : undefined }}>
          <div className="hero-copy">
            {heroLogo ? (
              <img className="hero-logo" src={heroLogo} alt={hero.title} />
            ) : (
              <h2>{hero.title}</h2>
            )}
            <div className="hero-meta">
              {hero.rating && (
                <span className="hero-imdb">
                  <img src={IMDB_LOGO} alt="IMDb" />
                  <b>{hero.rating}</b>
                </span>
              )}
              {metaBits.map((bit) => <span key={String(bit)}>{bit}</span>)}
            </div>
            <p>{hero.overview || hero.subtitle || "Continue from your ARVIO library."}</p>
            <div className="hero-actions">
              <button type="button" className="primary" onClick={() => openDetails(hero)}><Play size={20} fill="currentColor" /> Play</button>
              <button type="button" className="secondary" onClick={() => openDetails(hero)}><Info size={20} /> More Info</button>
            </div>
          </div>
        </section>
      )}
      {dedupedCategories.map((category) => (
        <MediaRail key={category.id} category={category} onOpen={openDetails} onFocus={onCardFocus} posterMode={posterMode} />
      ))}
      {homeServerRows.map((category) => (
        <MediaRail key={category.id} category={category} onOpen={openDetails} onFocus={onCardFocus} posterMode={posterMode} />
      ))}
      {catalogConfigs.map((catalog, index) => (
        <LazyRail
          key={catalog.id}
          catalog={catalog}
          eager={index < 8}
          onOpen={openDetails}
          onFocus={onCardFocus}
          onLoaded={seedHeroFromRow}
        />
      ))}
    </div>
  );
}
