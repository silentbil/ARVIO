"use client";

import { BadgeCheck, ExternalLink, Loader2, LogOut, Sparkles } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { config } from "@/lib/config";
import {
  cachedEntitlement,
  fetchEntitlement,
  kofiSubscribeUrl,
  linkKofiEmail,
  startTrial,
  type EntitlementState
} from "@/lib/entitlement";
import { authClient, useApp } from "@/lib/store";

// 24-hour free trial: hidden for now (subscribe-only paywall). The trial
// backend + start flow stay intact — flip this to true to re-enable the button.
const SHOW_TRIAL = false;

// Gate that stands between profile selection and the app when the paywall is
// enabled. Fails OPEN on backend errors (a paying user is never locked out by a
// hiccup) and CLOSED on a confirmed non-entitled state.
export function EntitlementGate({ children }: { children: React.ReactNode }) {
  const { signOut } = useApp();
  const [state, setState] = useState<EntitlementState | null>(() => cachedEntitlement());
  const [status, setStatus] = useState<"loading" | "ready" | "error">(state ? "ready" : "loading");
  const checked = useRef(false);

  useEffect(() => {
    if (!config.paywallEnabled || checked.current) return;
    checked.current = true;
    let active = true;
    void fetchEntitlement(authClient)
      .then((next) => { if (active) { setState(next); setStatus("ready"); } })
      .catch(() => { if (active) setStatus("error"); });
    return () => { active = false; };
  }, []);

  // Paywall off, or entitled → app. On a backend error with no cached "not
  // entitled", fail open so we never lock out a paying user over a hiccup.
  if (!config.paywallEnabled) return <>{children}</>;
  if (state?.entitled) return <>{children}</>;
  if (status === "error" && !state) return <>{children}</>;
  if (status === "loading") {
    return (
      <main className="paywall-boot">
        <Loader2 className="paywall-spinner" size={40} />
      </main>
    );
  }

  return (
    <PaywallScreen
      state={state}
      onEntitled={(next) => setState(next)}
      onSignOut={signOut}
    />
  );
}

function PaywallScreen({
  state,
  onEntitled,
  onSignOut
}: {
  state: EntitlementState | null;
  onEntitled: (next: EntitlementState) => void;
  onSignOut: () => void;
}) {
  const [busy, setBusy] = useState<"trial" | "link" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [linkOpen, setLinkOpen] = useState(false);
  const [kofiEmail, setKofiEmail] = useState("");
  const trialAvailable = state?.trialAvailable ?? true;

  const beginTrial = useCallback(async () => {
    setBusy("trial"); setError(null);
    try {
      const next = await startTrial(authClient);
      if (next.entitled) onEntitled(next);
      else setError("Your free trial has already been used.");
    } catch {
      setError("Could not start the trial. Please try again.");
    } finally {
      setBusy(null);
    }
  }, [onEntitled]);

  const link = useCallback(async () => {
    if (!kofiEmail.trim()) return;
    setBusy("link"); setError(null);
    try {
      const next = await linkKofiEmail(authClient, kofiEmail.trim());
      if (next.entitled) onEntitled(next);
      else setError("No active membership was found for that email.");
    } catch {
      setError("No active membership was found for that email.");
    } finally {
      setBusy(null);
    }
  }, [kofiEmail, onEntitled]);

  const expired = state?.reason === "expired" || state?.status === "cancelled";

  return (
    <main className="paywall">
      <div className="paywall-card">
        <div className="paywall-brand">
          <img src="/arvio-logo.svg" alt="" className="paywall-logo" />
          <img src="/arvio-wordmark.svg" alt="ARVIO" className="paywall-wordmark" />
        </div>

        <h1>{expired ? "Your ARVIO Web membership has ended" : "ARVIO Web is a members feature"}</h1>
        <p className="paywall-sub">
          Streaming, live TV and downloads in the browser are part of ARVIO Web membership.
          The Android app stays completely free — this unlocks ARVIO on iPhone, iPad, smart-TV
          browsers and any desktop.
        </p>

        <div className="paywall-price">
          <span className="paywall-amount">$2.99</span>
          <span className="paywall-period">/ month</span>
        </div>

        <a
          className="paywall-primary"
          href={kofiSubscribeUrl()}
          target="_blank"
          rel="noopener noreferrer"
        >
          <BadgeCheck size={18} /> Subscribe on Ko-fi <ExternalLink size={15} />
        </a>

        {SHOW_TRIAL && trialAvailable && !expired && (
          <button type="button" className="paywall-trial" onClick={() => void beginTrial()} disabled={busy !== null}>
            {busy === "trial" ? <Loader2 className="paywall-spinner" size={16} /> : <Sparkles size={16} />}
            Start 24-hour free trial
          </button>
        )}

        <button type="button" className="paywall-link-toggle" onClick={() => setLinkOpen((v) => !v)}>
          Already subscribed? Link your Ko-fi email
        </button>

        {linkOpen && (
          <div className="paywall-link-row">
            <input
              type="email"
              placeholder="Your Ko-fi / PayPal email"
              value={kofiEmail}
              onChange={(e) => setKofiEmail(e.target.value)}
            />
            <button type="button" onClick={() => void link()} disabled={busy !== null || !kofiEmail.trim()}>
              {busy === "link" ? <Loader2 className="paywall-spinner" size={16} /> : "Link"}
            </button>
          </div>
        )}

        {error && <p className="paywall-error">{error}</p>}

        <button type="button" className="paywall-signout" onClick={onSignOut}>
          <LogOut size={15} /> Sign out
        </button>
      </div>
    </main>
  );
}
