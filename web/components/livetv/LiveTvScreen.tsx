"use client";

import { CalendarClock, ChevronDown, Eye, EyeOff, History, LayoutGrid, List, ListVideo, Play, Plus, RefreshCw, Search, Star, Tv, X } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { groupKey, loadXtreamCatchup, type CatchupProgram } from "@/lib/iptv";
import { useApp } from "@/lib/store";
import type { IptvChannel, IptvSnapshot } from "@/lib/types";

const CHANNEL_PAGE_SIZE = 300;
const GUIDE_BATCH_DELAY_MS = 500;
const GUIDE_WINDOW_HOURS = 4;
const GUIDE_PX_PER_MIN = 6;

function fmtTime(ms: number): string {
  try {
    return new Intl.DateTimeFormat([], { hour: "2-digit", minute: "2-digit" }).format(new Date(ms));
  } catch {
    return "";
  }
}

function groupLabel(group: string) {
  return group.trim() || "Uncategorized";
}

export function LiveTvScreen() {
  const { iptvSnapshot, settings, setSettings, playChannel, playCatchup, setToast, refreshIptv, loadIptvGuide, busy, auth } = useApp();
  const playlists = settings.iptvPlaylists;
  const favorites = settings.favoriteChannelIds;
  const favoriteGroups = settings.favoriteGroupIds;
  const hiddenGroups = settings.hiddenGroupIds;

  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [epgUrl, setEpgUrl] = useState("");
  const [activeCategory, setActiveCategory] = useState("all");
  const [query, setQuery] = useState("");
  const [selectedChannelId, setSelectedChannelId] = useState<string | null>(null);
  const [managing, setManaging] = useState(false);
  const [view, setView] = useState<"list" | "guide">("list");
  const [visibleCount, setVisibleCount] = useState(CHANNEL_PAGE_SIZE);
  const [catchup, setCatchup] = useState<{ channelId: string; programs: CatchupProgram[]; loading: boolean } | null>(null);

  const channels = iptvSnapshot.channels;
  const groups = iptvSnapshot.grouped;
  const enabledPlaylists = playlists.filter((playlist) => playlist.enabled && playlist.m3uUrl.trim());
  const favoriteChannels = channels.filter((channel) => favorites.includes(channel.id));
  const isLoadingTv = Boolean(busy && (busy.toLowerCase().includes("syncing") || busy.toLowerCase().includes("loading tv")));
  const hasWarnings = Boolean(iptvSnapshot.playlistWarnings?.length);
  const playlistSignature = playlists
    .map((playlist) => `${playlist.id}:${playlist.enabled}:${playlist.m3uUrl}:${playlist.epgUrl ?? ""}:${playlist.epgUrls?.join("|") ?? ""}`)
    .join("||");

  useEffect(() => {
    if (!playlists.length) return;
    void refreshIptv();
  }, [playlistSignature, refreshIptv, playlists.length]);

  const categories = useMemo(() => {
    const orderMap = new Map(iptvSnapshot.groupOrder.map((id, index) => [id, index]));
    const groupRank = (group: string, items: IptvChannel[]) => {
      const keyed = items[0] ? groupKey(items[0]) : group;
      return orderMap.get(keyed) ?? orderMap.get(group) ?? Number.MAX_SAFE_INTEGER;
    };
    const groupRows = Object.entries(groups)
      .map(([group, items]) => ({
        id: `group:${group}`,
        label: groupLabel(group),
        count: items.length,
        favorite: favoriteGroups.includes(group) || Boolean(items[0] && favoriteGroups.includes(groupKey(items[0]))),
        hidden: hiddenGroups.includes(group) || Boolean(items[0] && hiddenGroups.includes(groupKey(items[0]))),
        rank: groupRank(group, items)
      }))
      .filter((group) => !group.hidden)
      .sort((a, b) => Number(b.favorite) - Number(a.favorite) || a.rank - b.rank || b.count - a.count || a.label.localeCompare(b.label));
    return [
      { id: "all", label: "All Channels", count: channels.length, favorite: false, hidden: false },
      { id: "favorites", label: "Favorites", count: favoriteChannels.length, favorite: true, hidden: false },
      ...groupRows
    ];
  }, [channels.length, favoriteChannels.length, favoriteGroups, groups, hiddenGroups, iptvSnapshot.groupOrder]);

  const visibleChannels = useMemo(() => {
    const base = activeCategory === "favorites"
      ? favoriteChannels
      : activeCategory.startsWith("group:")
        ? groups[activeCategory.slice(6)] ?? []
        : channels;
    const needle = query.trim().toLowerCase();
    return needle
      ? base.filter((channel) =>
          channel.name.toLowerCase().includes(needle) ||
          channel.group.toLowerCase().includes(needle) ||
          channel.tvgId?.toLowerCase().includes(needle)
        )
      : base;
  }, [activeCategory, channels, favoriteChannels, groups, query]);

  useEffect(() => {
    setVisibleCount(CHANNEL_PAGE_SIZE);
  }, [activeCategory, query]);
  const renderedChannels = useMemo(() => visibleChannels.slice(0, visibleCount), [visibleChannels, visibleCount]);
  const selectedChannel = channels.find((channel) => channel.id === selectedChannelId) ?? renderedChannels[0] ?? null;
  const selectedGuide = selectedChannel ? iptvSnapshot.nowNext[selectedChannel.id] : undefined;

  // Catch-up listings for the selected channel (channels the panel archives).
  useEffect(() => {
    if (!selectedChannel?.catchupDays || selectedChannel.catchupType !== "xtream") {
      setCatchup(null);
      return undefined;
    }
    let active = true;
    setCatchup({ channelId: selectedChannel.id, programs: [], loading: true });
    void loadXtreamCatchup(settings.iptvPlaylists, selectedChannel)
      .then((programs) => { if (active) setCatchup({ channelId: selectedChannel.id, programs, loading: false }); })
      .catch(() => { if (active) setCatchup({ channelId: selectedChannel.id, programs: [], loading: false }); });
    return () => { active = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedChannel?.id]);

  // Guide loads lazily for rows as they scroll into view, batched so a fast
  // scroll doesn't fire hundreds of EPG requests.
  const guideQueueRef = useRef(new Map<string, IptvChannel>());
  const guideTimerRef = useRef<number | null>(null);
  const requestGuide = useCallback((channel: IptvChannel) => {
    if (iptvSnapshot.nowNext[channel.id]?.now && iptvSnapshot.nowNext[channel.id]!.now!.endUtcMillis > Date.now()) return;
    guideQueueRef.current.set(channel.id, channel);
    if (guideTimerRef.current) return;
    guideTimerRef.current = window.setTimeout(() => {
      guideTimerRef.current = null;
      const batch = Array.from(guideQueueRef.current.values());
      guideQueueRef.current.clear();
      if (batch.length) void loadIptvGuide(batch);
    }, GUIDE_BATCH_DELAY_MS);
  }, [iptvSnapshot.nowNext, loadIptvGuide]);

  useEffect(() => () => {
    if (guideTimerRef.current) window.clearTimeout(guideTimerRef.current);
  }, []);

  useEffect(() => {
    if (selectedChannel) requestGuide(selectedChannel);
  }, [selectedChannel, requestGuide]);

  const toggleFavorite = (channelId: string) =>
    setSettings({
      ...settings,
      favoriteChannelIds: favorites.includes(channelId)
        ? favorites.filter((id) => id !== channelId)
        : [channelId, ...favorites]
    });

  const toggleGroupFavorite = (group: string) => {
    const first = groups[group]?.[0];
    const id = first ? groupKey(first) : group;
    setSettings({
      ...settings,
      favoriteGroupIds: favoriteGroups.includes(id) ? favoriteGroups.filter((item) => item !== id) : [id, ...favoriteGroups]
    });
  };

  const toggleHiddenGroup = (group: string) => {
    const first = groups[group]?.[0];
    const id = first ? groupKey(first) : group;
    setSettings({
      ...settings,
      hiddenGroupIds: hiddenGroups.includes(id) ? hiddenGroups.filter((item) => item !== id) : [id, ...hiddenGroups]
    });
  };

  const addPlaylist = () => {
    if (!url.trim()) {
      setToast("Enter an M3U or Xtream URL first.");
      return;
    }
    setSettings({
      ...settings,
      iptvPlaylists: [{
        id: crypto.randomUUID(),
        name: name.trim() || "Playlist",
        m3uUrl: url.trim(),
        epgUrl: epgUrl.trim(),
        enabled: true
      }, ...playlists]
    });
    setName("");
    setUrl("");
    setEpgUrl("");
    setManaging(false);
  };

  const activeCategoryLabel = categories.find((category) => category.id === activeCategory)?.label ?? "All Channels";

  return (
    <div className="screen livetv-shell">
      <header className="livetv-topbar">
        <div className="livetv-heading">
          <h2>Live TV</h2>
          <span>{channels.length ? `${channels.length.toLocaleString()} channels · ${enabledPlaylists.length} playlist${enabledPlaylists.length === 1 ? "" : "s"}` : "No channels loaded"}</span>
        </div>
        <div className="livetv-search">
          <Search size={17} />
          <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search channels" aria-label="Search channels" />
          {query && (
            <button type="button" onClick={() => setQuery("")} aria-label="Clear search"><X size={15} /></button>
          )}
        </div>
        <div className="livetv-topbar-actions">
          <button type="button" className="livetv-chipbtn" onClick={() => setManaging((value) => !value)} aria-expanded={managing}>
            <ListVideo size={17} /> Playlists
          </button>
          <button type="button" className="livetv-chipbtn" onClick={() => void refreshIptv()} disabled={isLoadingTv} aria-label="Refresh channels">
            <RefreshCw size={17} className={isLoadingTv ? "is-spinning" : ""} /> {isLoadingTv ? "Refreshing" : "Refresh"}
          </button>
        </div>
      </header>

      {managing && (
        <section className="livetv-manage">
          {playlists.map((playlist) => (
            <div className="livetv-manage-row" key={playlist.id}>
              <button
                type="button"
                className={`livetv-switch ${playlist.enabled ? "is-on" : ""}`}
                onClick={() => setSettings({
                  ...settings,
                  iptvPlaylists: playlists.map((p) => p.id === playlist.id ? { ...p, enabled: !p.enabled } : p)
                })}
                aria-label={playlist.enabled ? `Disable ${playlist.name}` : `Enable ${playlist.name}`}
              />
              <span className="livetv-manage-name">
                <strong>{playlist.name}</strong>
                <em>{playlist.epgUrl || playlist.epgUrls?.length ? "Playlist + EPG" : "Playlist"}</em>
              </span>
              <button
                type="button"
                className="livetv-manage-remove"
                onClick={() => setSettings({ ...settings, iptvPlaylists: playlists.filter((p) => p.id !== playlist.id) })}
                aria-label={`Remove ${playlist.name}`}
              >
                <X size={16} />
              </button>
            </div>
          ))}
          <div className="livetv-manage-add">
            <input value={name} onChange={(event) => setName(event.target.value)} placeholder="Name" aria-label="Playlist name" />
            <input value={url} onChange={(event) => setUrl(event.target.value)} placeholder="M3U / Xtream URL (or host user pass)" aria-label="Playlist URL" />
            <input value={epgUrl} onChange={(event) => setEpgUrl(event.target.value)} placeholder="EPG URL (optional)" aria-label="EPG URL" />
            <button type="button" className="primary" onClick={addPlaylist}><Plus size={17} /> Add</button>
          </div>
        </section>
      )}

      {hasWarnings && (
        <div className="livetv-warning">
          <strong>Playlist problem</strong>
          <span>{iptvSnapshot.playlistWarnings?.[0]}</span>
        </div>
      )}

      {!playlists.length && !managing && (
        <section className="livetv-empty">
          <Tv size={44} />
          <h3>Add your IPTV playlist</h3>
          <p>Paste an M3U link or Xtream login. Playlists sync through ARVIO Cloud{auth ? "" : " when you sign in"}.</p>
          <button type="button" className="primary" onClick={() => setManaging(true)}><Plus size={18} /> Add playlist</button>
        </section>
      )}

      {channels.length > 0 && (
        <div className="livetv-columns">
          <nav className="livetv-cats" aria-label="Channel categories">
            {categories.map((category) => (
              <button
                type="button"
                key={category.id}
                className={activeCategory === category.id ? "is-active" : ""}
                title={category.label}
                onClick={() => {
                  setActiveCategory(category.id);
                  setSelectedChannelId(null);
                }}
              >
                {category.id === "favorites" && <Star size={13} fill="currentColor" />}
                <span>{category.label}</span>
                <em>{category.count.toLocaleString()}</em>
              </button>
            ))}
          </nav>

          <main className="livetv-list" aria-label={activeCategoryLabel}>
            <div className="livetv-list-head">
              <h3>{activeCategoryLabel}</h3>
              <span>
                {renderedChannels.length < visibleChannels.length
                  ? `${renderedChannels.length.toLocaleString()} of ${visibleChannels.length.toLocaleString()}`
                  : visibleChannels.length.toLocaleString()}
              </span>
              <div className="livetv-view-toggle" role="tablist" aria-label="Channel view">
                <button type="button" className={view === "list" ? "is-active" : ""} onClick={() => setView("list")} aria-label="List view"><List size={15} /> List</button>
                <button type="button" className={view === "guide" ? "is-active" : ""} onClick={() => setView("guide")} aria-label="Guide view"><LayoutGrid size={15} /> Guide</button>
              </div>
              {activeCategory.startsWith("group:") && (
                <div className="livetv-group-actions">
                  <button type="button" onClick={() => toggleGroupFavorite(activeCategory.slice(6))} aria-label="Favorite this category"><Star size={15} /></button>
                  <button type="button" onClick={() => toggleHiddenGroup(activeCategory.slice(6))} aria-label="Hide this category">
                    {hiddenGroups.includes(activeCategory.slice(6)) ? <Eye size={15} /> : <EyeOff size={15} />}
                  </button>
                </div>
              )}
            </div>
            {renderedChannels.length === 0 && (
              <div className="livetv-list-empty">
                <Search size={28} />
                <p>No channels match {query.trim() ? `"${query.trim()}"` : "this category"}.</p>
                {query.trim() && <button type="button" className="secondary" onClick={() => setQuery("")}>Clear search</button>}
              </div>
            )}
            {view === "list" ? (
              <div className="livetv-rows">
                {renderedChannels.map((channel) => (
                  <ChannelRow
                    key={channel.id}
                    channel={channel}
                    guide={iptvSnapshot.nowNext[channel.id]}
                    favorite={favorites.includes(channel.id)}
                    selected={selectedChannel?.id === channel.id}
                    onFocus={() => setSelectedChannelId(channel.id)}
                    onVisible={() => requestGuide(channel)}
                    onPlay={() => playChannel(channel)}
                    onToggleFavorite={() => toggleFavorite(channel.id)}
                  />
                ))}
              </div>
            ) : (
              <GuideGrid
                channels={renderedChannels}
                nowNext={iptvSnapshot.nowNext}
                selectedId={selectedChannel?.id ?? null}
                onFocus={(channel) => setSelectedChannelId(channel.id)}
                onVisible={requestGuide}
                onPlay={playChannel}
              />
            )}
            {visibleChannels.length > renderedChannels.length && (
              <LoadMoreSentinel onLoadMore={() => setVisibleCount((count) => count + CHANNEL_PAGE_SIZE)} />
            )}
          </main>

          <aside className="livetv-detail" aria-label="Channel details">
            {selectedChannel ? (
              <>
                <div className="livetv-detail-art">
                  {selectedChannel.logo ? <img src={selectedChannel.logo} alt="" loading="lazy" /> : <Tv size={48} />}
                </div>
                <p className="livetv-detail-group">{selectedChannel.group || "Live TV"}</p>
                <h2>{selectedChannel.name}</h2>
                {selectedGuide?.now?.title ? (
                  <div className="livetv-program">
                    <div className="livetv-program-head">
                      <span>ON NOW</span>
                      <em>{fmtTime(selectedGuide.now.startUtcMillis)} – {fmtTime(selectedGuide.now.endUtcMillis)}</em>
                    </div>
                    <strong>{selectedGuide.now.title}</strong>
                    <span className="livetv-progress">
                      <span style={{ width: `${Math.min(100, Math.max(0, ((Date.now() - selectedGuide.now.startUtcMillis) / (selectedGuide.now.endUtcMillis - selectedGuide.now.startUtcMillis)) * 100))}%` }} />
                    </span>
                    {selectedGuide.now.description && <p>{selectedGuide.now.description}</p>}
                  </div>
                ) : (
                  <p className="livetv-detail-empty">No guide data for this channel.</p>
                )}
                {selectedGuide?.next?.title && (
                  <div className="livetv-program is-next">
                    <div className="livetv-program-head">
                      <span>NEXT</span>
                      <em>{fmtTime(selectedGuide.next.startUtcMillis)}</em>
                    </div>
                    <strong>{selectedGuide.next.title}</strong>
                  </div>
                )}
                <div className="livetv-detail-actions">
                  <button type="button" className="primary" onClick={() => playChannel(selectedChannel)}><Play size={17} fill="currentColor" /> Watch</button>
                  <button
                    type="button"
                    className={favorites.includes(selectedChannel.id) ? "secondary is-active" : "secondary"}
                    onClick={() => toggleFavorite(selectedChannel.id)}
                  >
                    <Star size={17} fill={favorites.includes(selectedChannel.id) ? "currentColor" : "none"} /> Favorite
                  </button>
                </div>
                {catchup?.channelId === selectedChannel.id && (catchup.loading || catchup.programs.length > 0) && (
                  <div className="livetv-catchup">
                    <p className="livetv-catchup-head"><History size={14} /> Catch-up{selectedChannel.catchupDays ? ` · ${selectedChannel.catchupDays}d archive` : ""}</p>
                    {catchup.loading && <p className="livetv-detail-empty">Loading archive…</p>}
                    {catchup.programs.slice(0, 12).map((program) => (
                      <button
                        type="button"
                        key={`${program.startUtcMillis}`}
                        className="livetv-catchup-row"
                        onClick={() => playCatchup(selectedChannel, program)}
                      >
                        <CalendarClock size={14} />
                        <span>
                          <strong>{program.title}</strong>
                          <em>{new Intl.DateTimeFormat([], { weekday: "short", hour: "2-digit", minute: "2-digit" }).format(new Date(program.startUtcMillis))}</em>
                        </span>
                        <Play size={13} fill="currentColor" />
                      </button>
                    ))}
                  </div>
                )}
              </>
            ) : (
              <div className="livetv-detail-empty-state">
                <Tv size={44} />
                <p>Select a channel to see the guide.</p>
              </div>
            )}
          </aside>
        </div>
      )}

      {playlists.length > 0 && !channels.length && !hasWarnings && (
        <section className="livetv-empty">
          <ChevronDown size={36} className={isLoadingTv ? "is-spinning" : ""} />
          <h3>{isLoadingTv ? "Loading channels…" : "No channels yet"}</h3>
          <p>{isLoadingTv ? "Big playlists can take a few seconds." : "Refresh, or double-check the playlist details with your provider."}</p>
        </section>
      )}
    </div>
  );
}

function LoadMoreSentinel({ onLoadMore }: { onLoadMore: () => void }) {
  const ref = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof IntersectionObserver === "undefined") return undefined;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) onLoadMore();
    }, { rootMargin: "600px" });
    observer.observe(el);
    return () => observer.disconnect();
  }, [onLoadMore]);
  return <div ref={ref} className="livetv-sentinel" aria-hidden="true" />;
}

// Timeline guide: channels down, time across (now → +4h), programme blocks
// positioned by their real start/end times. EPG loads lazily per visible row.
function GuideGrid({ channels, nowNext, selectedId, onFocus, onVisible, onPlay }: {
  channels: IptvChannel[];
  nowNext: IptvSnapshot["nowNext"];
  selectedId: string | null;
  onFocus: (channel: IptvChannel) => void;
  onVisible: (channel: IptvChannel) => void;
  onPlay: (channel: IptvChannel) => void;
}) {
  // The window starts at the previous half-hour so the "on now" block has context.
  const [windowStart] = useState(() => {
    const now = new Date();
    now.setMinutes(now.getMinutes() < 30 ? 0 : 30, 0, 0);
    return now.getTime();
  });
  const windowEnd = windowStart + GUIDE_WINDOW_HOURS * 60 * 60 * 1000;
  const totalWidth = GUIDE_WINDOW_HOURS * 60 * GUIDE_PX_PER_MIN;
  const ticks = Array.from({ length: GUIDE_WINDOW_HOURS * 2 }, (_, index) => windowStart + index * 30 * 60 * 1000);
  const [, forceTick] = useState(0);
  useEffect(() => {
    const timer = window.setInterval(() => forceTick((value) => value + 1), 60_000);
    return () => window.clearInterval(timer);
  }, []);
  const nowOffset = ((Date.now() - windowStart) / 60000) * GUIDE_PX_PER_MIN;

  return (
    <div className="livetv-guide" role="grid" aria-label="Programme guide">
      <div className="livetv-guide-scroll">
        <div className="livetv-guide-inner" style={{ width: `${totalWidth + 232}px` }}>
          <div className="livetv-guide-timebar">
            <span className="livetv-guide-corner" />
            <div className="livetv-guide-ticks" style={{ width: `${totalWidth}px` }}>
              {ticks.map((tick) => (
                <span key={tick} style={{ width: `${30 * GUIDE_PX_PER_MIN}px` }}>{fmtTime(tick)}</span>
              ))}
              {nowOffset >= 0 && nowOffset <= totalWidth && (
                <i className="livetv-guide-nowline" style={{ left: `${nowOffset}px` }} />
              )}
            </div>
          </div>
          {channels.map((channel) => (
            <GuideRow
              key={channel.id}
              channel={channel}
              guide={nowNext[channel.id]}
              selected={selectedId === channel.id}
              windowStart={windowStart}
              windowEnd={windowEnd}
              totalWidth={totalWidth}
              nowOffset={nowOffset}
              onFocus={() => onFocus(channel)}
              onVisible={() => onVisible(channel)}
              onPlay={() => onPlay(channel)}
            />
          ))}
        </div>
      </div>
    </div>
  );
}

function GuideRow({ channel, guide, selected, windowStart, windowEnd, totalWidth, nowOffset, onFocus, onVisible, onPlay }: {
  channel: IptvChannel;
  guide?: IptvSnapshot["nowNext"][string];
  selected: boolean;
  windowStart: number;
  windowEnd: number;
  totalWidth: number;
  nowOffset: number;
  onFocus: () => void;
  onVisible: () => void;
  onPlay: () => void;
}) {
  const rowRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = rowRef.current;
    if (!el || typeof IntersectionObserver === "undefined") return undefined;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        onVisible();
        observer.disconnect();
      }
    }, { rootMargin: "300px" });
    observer.observe(el);
    return () => observer.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [channel.id]);

  const programs = useMemo(() => {
    const all = [guide?.now, ...(guide?.upcoming ?? [])].filter((program): program is NonNullable<typeof program> => Boolean(program));
    const seen = new Set<number>();
    return all
      .filter((program) => program.endUtcMillis > windowStart && program.startUtcMillis < windowEnd)
      .filter((program) => (seen.has(program.startUtcMillis) ? false : (seen.add(program.startUtcMillis), true)));
  }, [guide, windowStart, windowEnd]);

  return (
    <div ref={rowRef} className={`livetv-guide-row ${selected ? "is-selected" : ""}`} onMouseEnter={onFocus} role="row">
      <button type="button" className="livetv-guide-channel" onClick={onPlay} title={channel.name}>
        <span className="livetv-row-logo">{channel.logo ? <img src={channel.logo} alt="" loading="lazy" /> : <Tv size={16} />}</span>
        <strong>{channel.name}</strong>
      </button>
      <div className="livetv-guide-lane" style={{ width: `${totalWidth}px` }} onClick={onPlay}>
        {programs.map((program) => {
          const left = Math.max(0, ((program.startUtcMillis - windowStart) / 60000) * GUIDE_PX_PER_MIN);
          const right = Math.min(totalWidth, ((program.endUtcMillis - windowStart) / 60000) * GUIDE_PX_PER_MIN);
          const live = Date.now() >= program.startUtcMillis && Date.now() < program.endUtcMillis;
          return (
            <span
              key={program.startUtcMillis}
              className={`livetv-guide-block ${live ? "is-live" : ""}`}
              style={{ left: `${left}px`, width: `${Math.max(14, right - left - 2)}px` }}
              title={`${program.title} · ${fmtTime(program.startUtcMillis)}–${fmtTime(program.endUtcMillis)}`}
            >
              {program.title}
            </span>
          );
        })}
        {programs.length === 0 && <span className="livetv-guide-empty">No guide data</span>}
        {nowOffset >= 0 && nowOffset <= totalWidth && <i className="livetv-guide-nowline" style={{ left: `${nowOffset}px` }} />}
      </div>
    </div>
  );
}

function ChannelRow({ channel, guide, favorite, selected, onFocus, onVisible, onPlay, onToggleFavorite }: {
  channel: IptvChannel;
  guide?: IptvSnapshot["nowNext"][string];
  favorite: boolean;
  selected: boolean;
  onFocus: () => void;
  onVisible: () => void;
  onPlay: () => void;
  onToggleFavorite: () => void;
}) {
  const rowRef = useRef<HTMLElement | null>(null);
  const now = guide?.now;
  const next = guide?.next ?? guide?.later ?? guide?.upcoming?.[0];
  const progress = now ? Math.min(100, Math.max(0, ((Date.now() - now.startUtcMillis) / (now.endUtcMillis - now.startUtcMillis)) * 100)) : 0;

  useEffect(() => {
    const el = rowRef.current;
    if (!el || typeof IntersectionObserver === "undefined") return undefined;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        onVisible();
        observer.disconnect();
      }
    }, { rootMargin: "240px" });
    observer.observe(el);
    return () => observer.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [channel.id]);

  return (
    <article ref={rowRef} className={`livetv-row ${selected ? "is-selected" : ""}`} onMouseEnter={onFocus} onFocus={onFocus}>
      <button type="button" className="livetv-row-main" onClick={onPlay}>
        <span className="livetv-row-logo">{channel.logo ? <img src={channel.logo} alt="" loading="lazy" /> : <Tv size={20} />}</span>
        <span className="livetv-row-copy">
          <span className="livetv-row-title">
            <strong>{channel.name}</strong>
            {channel.qualityLabel && <i className="livetv-quality">{channel.qualityLabel}</i>}
          </span>
          {now?.title ? (
            <span className="livetv-row-now">
              <em>{now.title}</em>
              <span className="livetv-progress"><span style={{ width: `${progress}%` }} /></span>
            </span>
          ) : (
            <span className="livetv-row-now"><em className="is-muted">{channel.group || "Live TV"}</em></span>
          )}
          {next?.title && <small>{fmtTime(next.startUtcMillis)} · {next.title}</small>}
        </span>
        <span className="livetv-row-play"><Play size={16} fill="currentColor" /></span>
      </button>
      <button type="button" className={`livetv-row-star ${favorite ? "is-active" : ""}`} onClick={onToggleFavorite} aria-label={favorite ? "Remove favorite" : "Add favorite"}>
        <Star size={17} fill={favorite ? "currentColor" : "none"} />
      </button>
    </article>
  );
}
