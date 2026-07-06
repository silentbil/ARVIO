"use client";

import { Search } from "lucide-react";
import { useApp } from "@/lib/store";
import { MediaCard } from "@/components/media/MediaCard";

export function SearchScreen() {
  const { query, setQuery, results, openDetails, settings } = useApp();
  const posterMode = settings.cardLayoutMode === "poster";
  return (
    <div className={`screen ${posterMode ? "poster-results" : ""}`}>
      <section className="search-hero">
        <span className="search-icon-shell"><Search size={28} /></span>
        <input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          autoFocus
          placeholder="Search movies, series, people, and catalogs"
        />
      </section>
      <div className="grid-results">
        {results.map((item) => <MediaCard key={`${item.mediaType}-${item.id}`} item={item} onOpen={openDetails} posterMode={posterMode} />)}
      </div>
    </div>
  );
}
