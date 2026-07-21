// Web subscription entitlements. Stored in a dedicated Netlify Blobs store,
// keyed by sha256(email) so the Ko-fi webhook (which only knows the buyer's
// email, not an ARVIO access token) and the app (which has a JWT identity with
// an email) resolve the SAME record. The APK never reads this — web only.
//
// An entitlement record:
//   {
//     status: "active" | "expired" | "cancelled",
//     source: "kofi" | "manual" | "trial",
//     tier: string | null,
//     startedAt: ISO string,
//     expiresAt: ISO string | null,   // null = lifetime
//     trialUsed: boolean,             // a one-time 24h trial was consumed
//     lastEvent: string | null,
//     updatedAt: ISO string
//   }
const { connectLambda, getStore } = require("@netlify/blobs");

const TRIAL_MS = 24 * 60 * 60 * 1000;

function entitlementsStore(event) {
  connectLambda(event);
  return getStore("entitlements");
}

function entitlementKey(emailHash) {
  return `email/${emailHash}.json`;
}

async function readEntitlement(store, emailHash) {
  try {
    return await store.get(entitlementKey(emailHash), { type: "json", consistency: "strong" });
  } catch (error) {
    if (String(error?.message || "").includes("uncachedEdgeURL")) {
      return store.get(entitlementKey(emailHash), { type: "json" });
    }
    if (error?.status === 404 || error?.name === "BlobNotFoundError") return null;
    throw error;
  }
}

async function writeEntitlement(store, emailHash, record) {
  await store.setJSON(entitlementKey(emailHash), record);
  return record;
}

// The single source of truth the app reads: is this account entitled to web
// access right now, and why. Never throws — a storage hiccup returns "unknown"
// so the caller can decide (we fail-closed on the paywall, open on errors only
// with an explicit grace, see the check function in the web app).
function evaluateEntitlement(record) {
  const now = Date.now();
  if (!record) {
    return { entitled: false, reason: "none", status: "none", trialAvailable: true, expiresAt: null, source: null };
  }
  const expiresAt = record.expiresAt ? Date.parse(record.expiresAt) : null;
  const active =
    record.status === "active" && (expiresAt === null || expiresAt > now);
  return {
    entitled: active,
    reason: active ? (record.source === "trial" ? "trial" : "subscription") : "expired",
    status: record.status || "none",
    source: record.source || null,
    tier: record.tier || null,
    expiresAt: record.expiresAt || null,
    trialAvailable: !record.trialUsed && !active,
    updatedAt: record.updatedAt || null
  };
}

// Grant/renew a paid subscription for `days` from now (or lifetime when days is
// null). Merges onto any existing record so a trial->paid upgrade keeps history.
function buildPaidRecord(existing, { source, tier, days, event }) {
  const now = new Date();
  const expiresAt = days == null ? null : new Date(now.getTime() + days * 24 * 60 * 60 * 1000).toISOString();
  return {
    ...(existing || {}),
    status: "active",
    source: source || "kofi",
    tier: tier || (existing && existing.tier) || null,
    startedAt: (existing && existing.startedAt) || now.toISOString(),
    expiresAt,
    trialUsed: existing ? existing.trialUsed === true : false,
    lastEvent: event || null,
    updatedAt: now.toISOString()
  };
}

module.exports = {
  TRIAL_MS,
  entitlementsStore,
  readEntitlement,
  writeEntitlement,
  evaluateEntitlement,
  buildPaidRecord
};
