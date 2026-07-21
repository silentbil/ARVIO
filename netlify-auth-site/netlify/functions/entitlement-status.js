// GET  /entitlement-status  → the web app reads the signed-in account's web
//                             subscription status (paid / trial / none).
// POST /entitlement-status  { action: "start-trial" } → consume the one-time
//                             24h trial for this account.
// Auth: the account's ARVIO access token (same as account-sync-*). The email is
// taken from the verified identity, so a user can only read/trial THEIR account.
const { json, options, resolveIdentity, normalizeEmail, sha256 } = require("./_backend");
const {
  TRIAL_MS,
  entitlementsStore,
  readEntitlement,
  writeEntitlement,
  evaluateEntitlement
} = require("./_entitlements");

exports.handler = async (event) => {
  const cors = options(event);
  if (cors) return cors;
  if (event.httpMethod !== "GET" && event.httpMethod !== "POST") {
    return json(405, { error: "method_not_allowed" });
  }

  let identity;
  try {
    identity = await resolveIdentity(event);
  } catch (error) {
    return json(401, { error: "unauthorized", message: error.message });
  }

  const email = normalizeEmail(identity.email);
  if (!email) return json(400, { error: "no_email_on_account" });
  const emailHash = sha256(email);

  try {
    const store = entitlementsStore(event);
    let record = await readEntitlement(store, emailHash);

    // Start-trial: grant a one-time 24h trial if never used and not already paid.
    if (event.httpMethod === "POST") {
      let body = {};
      try { body = JSON.parse(event.body || "{}"); } catch { body = {}; }
      if (body.action === "start-trial") {
        const current = evaluateEntitlement(record);
        if (current.entitled) {
          return json(200, { ...current, alreadyEntitled: true });
        }
        if (record && record.trialUsed) {
          return json(409, { error: "trial_used", ...current });
        }
        const now = new Date();
        record = {
          ...(record || {}),
          status: "active",
          source: "trial",
          tier: null,
          startedAt: (record && record.startedAt) || now.toISOString(),
          expiresAt: new Date(now.getTime() + TRIAL_MS).toISOString(),
          trialUsed: true,
          lastEvent: "trial-started",
          updatedAt: now.toISOString()
        };
        await writeEntitlement(store, emailHash, record);
        return json(200, { ...evaluateEntitlement(record), trialStarted: true });
      }
      return json(400, { error: "unknown_action" });
    }

    // Renewal sync for linked Ko-fi emails. The Ko-fi webhook renews the record
    // under the KO-FI email; an account that linked a different email holds a
    // COPY with the old expiry. When that copy lapses, chase linkedFrom — if the
    // source subscription is still active, refresh the copy in place so
    // mismatched-email subscribers never have to re-link after each renewal.
    let state = evaluateEntitlement(record);
    if (!state.entitled && record && record.linkedFrom) {
      const sourceEmail = normalizeEmail(record.linkedFrom);
      if (sourceEmail && sourceEmail !== email) {
        const source = await readEntitlement(store, sha256(sourceEmail));
        if (evaluateEntitlement(source).entitled) {
          record = {
            ...source,
            linkedFrom: sourceEmail,
            trialUsed: record.trialUsed === true || source.trialUsed === true,
            updatedAt: new Date().toISOString()
          };
          await writeEntitlement(store, emailHash, record);
          state = evaluateEntitlement(record);
        }
      }
    }

    return json(200, state);
  } catch (error) {
    console.error("entitlement-status failed", error);
    return json(500, { error: "entitlement_status_failed", message: error.message });
  }
};
