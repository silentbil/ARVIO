"use client";

import { useMemo, useState } from "react";
import { useApp } from "@/lib/store";
import { MediaCard } from "@/components/media/MediaCard";

type WatchlistSort = "added" | "rating" | "title";
type WatchlistFilter = "all" | "movie" | "tv";

export function WatchlistScreen() {
  const { watchlist, traktConnected, openDetails, settings } = useApp();
  const [sort, setSort] = useState<WatchlistSort>("added");
  const [filter, setFilter] = useState<WatchlistFilter>("all");
  const posterMode = settings.cardLayoutMode === "poster";

  const items = useMemo(() => {
    const filtered = filter === "all" ? watchlist : watchlist.filter((item) => item.mediaType === filter);
    return [...filtered].sort((a, b) => {
      if (sort === "rating") return (Number(b.rating) || 0) - (Number(a.rating) || 0);
      if (sort === "title") return a.title.localeCompare(b.title);
      // "Recently added" — newest listed_at (added date) first, like Trakt.
      return (b.activityAt ?? 0) - (a.activityAt ?? 0);
    });
  }, [watchlist, sort, filter]);

  return (
    <div className={`screen ${posterMode ? "poster-results" : ""}`}>
      <section className="section-heading watchlist-heading">
        <div>
          <p className="eyebrow">{traktConnected ? "Synced with your Trakt account" : "Connect Trakt in Settings to sync"}</p>
          <h2>Watchlist</h2>
        </div>
        <div className="watchlist-toolbar">
          <div className="watchlist-pills" role="group" aria-label="Filter watchlist">
            {([["all", "All"], ["movie", "Movies"], ["tv", "Series"]] as const).map(([value, label]) => (
              <button
                key={value}
                type="button"
                className={`watchlist-pill ${filter === value ? "is-active" : ""}`}
                onClick={() => setFilter(value)}
              >
                {label}
              </button>
            ))}
          </div>
          <select
            className="watchlist-sort"
            value={sort}
            onChange={(event) => setSort(event.target.value as WatchlistSort)}
            aria-label="Sort watchlist"
          >
            <option value="added">Recently added</option>
            <option value="rating">Highest rated</option>
            <option value="title">Title A–Z</option>
          </select>
        </div>
      </section>
      {items.length === 0 ? (
        <div className="watchlist-empty">
          <p>Nothing saved{filter === "all" ? " yet" : " in this category"}.</p>
          <span>Add movies and series from their detail pages and they will show up here on every device.</span>
        </div>
      ) : (
        <div className="grid-results">
          {items.map((item) => <MediaCard key={`${item.mediaType}-${item.id}`} item={item} onOpen={openDetails} posterMode={posterMode} />)}
        </div>
      )}
    </div>
  );
}
