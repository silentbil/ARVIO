"use client";

import { useEffect, useState } from "react";
import { ArrowLeft } from "lucide-react";
import { hasNetlifyBackendConfig, hasSupabaseConfig, getAuthPortalUrl } from "@/lib/config";
import { useApp } from "@/lib/store";

export function LoginScreen() {
  const { backToProfiles, cloudLoginRequired } = useApp();
  const cloudConfigured = hasNetlifyBackendConfig() || hasSupabaseConfig();
  const [mounted, setMounted] = useState(false);

  const redirectToAuthPortal = () => {
    if (typeof window === "undefined") return;
    const redirectUri = window.location.origin + "/login";
    const portalUrl = getAuthPortalUrl();
    window.location.href = `${portalUrl}?redirect_uri=${encodeURIComponent(redirectUri)}`;
  };

  useEffect(() => {
    setMounted(true);
  }, []);

  useEffect(() => {
    if (mounted && cloudConfigured) {
      const hash = window.location.hash || "";
      if (!hash.includes("access_token=")) {
        redirectToAuthPortal();
      }
    }
  }, [mounted, cloudConfigured]);

  return (
    <main className="login-shell">
      {!cloudLoginRequired && (
        <button type="button" className="login-back" onClick={backToProfiles} aria-label="Back"><ArrowLeft size={20} /> Back</button>
      )}
      <div className="login-hero">
        <div className="login-copy">
          <div className="login-brand-lockup">
            <img src="/arvio-logo.svg" alt="" className="login-brand-logo" />
            <img src="/arvio-wordmark.svg" alt="ARVIO" className="login-wordmark" />
          </div>
          <p className="login-tag">Cloud sign-in required</p>
          <p className="login-sub">Use your ARVIO Cloud account to sync profiles, continue watching, Trakt activity, addons, catalogs, and playback settings across devices.</p>
          <div className="login-proof">
            <span>Profiles</span>
            <span>Watch history</span>
            <span>Addons</span>
            <span>Trakt sync</span>
          </div>
        </div>

        <div className="login-card">
          <p className="login-card-title">Sign in to continue</p>
          {!cloudConfigured && <p className="login-error">ARVIO Cloud backend env is missing. Add values in web/.env.local.</p>}
          <button type="button" className="primary login-submit" onClick={redirectToAuthPortal} disabled={!cloudConfigured}>
            Sign In with ARVIO Cloud
          </button>
        </div>
      </div>
    </main>
  );
}
