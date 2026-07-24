import { mdblistClient } from "./mdblist";
import { traktClient } from "./store";

export type SyncProvider = "trakt" | "mdblist" | "none";

export interface SyncMediaRef {
  mediaType: "movie" | "tv";
  tmdbId: number;
  season?: number | null;
  episode?: number | null;
}

/**
 * The read/write surface shared by Trakt and MDBList. Both clients return reads
 * in Trakt-compatible shapes, so the store's mappers work with either provider.
 */
export interface SyncClient {
  readonly isConnected: boolean;
  watchlist(): Promise<unknown[]>;
  playback(): Promise<unknown[]>;
  watched(type: "movies" | "shows"): Promise<unknown[]>;
  addToWatchlist(item: SyncMediaRef): Promise<void>;
  removeFromWatchlist(item: SyncMediaRef): Promise<void>;
  addToHistory(item: SyncMediaRef): Promise<void>;
  removeFromHistory(item: SyncMediaRef): Promise<void>;
  scrobble(action: "start" | "pause" | "stop", item: SyncMediaRef & { progress: number }): Promise<void>;
}

/** Which remote a profile is actively connected to (MDBList takes precedence). */
export function activeSyncProvider(): SyncProvider {
  if (mdblistClient.isConnected) return "mdblist";
  if (traktClient.isConnected) return "trakt";
  return "none";
}

/** The active provider client, or the Trakt client when neither is connected. */
export function syncClient(): SyncClient {
  return (mdblistClient.isConnected ? mdblistClient : traktClient) as unknown as SyncClient;
}
