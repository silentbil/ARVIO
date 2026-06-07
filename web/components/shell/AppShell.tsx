"use client";

import { useApp } from "@/lib/store";
import { AddonsScreen } from "@/components/addons/AddonsScreen";
import { DetailsDrawer } from "@/components/details/DetailsDrawer";
import { HomeScreen } from "@/components/home/HomeScreen";
import { LiveTvScreen } from "@/components/livetv/LiveTvScreen";
import { LoginScreen } from "@/components/login/LoginScreen";
import { PlayerOverlay } from "@/components/player/PlayerOverlay";
import { ProfileSelectionScreen } from "@/components/profile/ProfileSelectionScreen";
import { SearchScreen } from "@/components/search/SearchScreen";
import { SettingsScreen } from "@/components/settings/SettingsScreen";
import { WatchlistScreen } from "@/components/watchlist/WatchlistScreen";
import { SyncStrip } from "./SyncStrip";
import { Toast } from "./Toast";
import { TopNav } from "./TopNav";

export function AppShell() {
  const { view, section } = useApp();

  if (view === "login") return <LoginScreen />;
  if (view === "profiles") return <ProfileSelectionScreen />;

  return (
    <main className="app-shell">
      <TopNav />

      <section className="content">
        <SyncStrip />
        {section === "home" && <HomeScreen />}
        {section === "search" && <SearchScreen />}
        {section === "watchlist" && <WatchlistScreen />}
        {section === "tv" && <LiveTvScreen />}
        {section === "addons" && <AddonsScreen />}
        {section === "settings" && <SettingsScreen />}
      </section>

      <DetailsDrawer />
      <PlayerOverlay />
      <Toast />
    </main>
  );
}
