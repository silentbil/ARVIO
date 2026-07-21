// POST /entitlement-link  { kofiEmail: "buyer@example.com" }
// For users whose Ko-fi/PayPal email differs from their ARVIO account email:
// copy an ACTIVE entitlement found under the Ko-fi email onto the signed-in
// account's email. Auth: the account's ARVIO access token (so a user can only
// attach an entitlement TO their own account). We only copy if the Ko-fi email
// actually has a live paid entitlement — no way to fabricate access.
const { json, options, resolveIdentity, normalizeEmail, sha256 } = require("./_backend");
const {
  entitlementsStore,
  readEntitlement,
  writeEntitlement,
  evaluateEntitlement
} = require("./_entitlements");

exports.handler = async (event) => {
  const cors = options(event);
  if (cors) return cors;
  if (event.httpMethod !== "POST") return json(405, { error: "method_not_allowed" });

  let identity;
  try {
    identity = await resolveIdentity(event);
  } catch (error) {
    return json(401, { error: "unauthorized", message: error.message });
  }

  const accountEmail = normalizeEmail(identity.email);
  if (!accountEmail) return json(400, { error: "no_email_on_account" });

  let body = {};
  try { body = JSON.parse(event.body || "{}"); } catch { body = {}; }
  const kofiEmail = normalizeEmail(body.kofiEmail);
  if (!kofiEmail) return json(400, { error: "missing_kofi_email" });

  try {
    const store = entitlementsStore(event);
    const accountHash = sha256(accountEmail);

    // Same email → nothing to link; the webhook already targets this account.
    if (kofiEmail === accountEmail) {
      const current = evaluateEntitlement(await readEntitlement(store, accountHash));
      return json(200, { ...current, linked: false, note: "emails_match" });
    }

    const kofiHash = sha256(kofiEmail);
    const kofiRecord = await readEntitlement(store, kofiHash);
    const kofiState = evaluateEntitlement(kofiRecord);
    if (!kofiState.entitled) {
      return json(404, { error: "no_active_entitlement_for_kofi_email" });
    }

    // Merge the paid entitlement onto the account email. Keep whichever record
    // is already richer (e.g. don't downgrade a lifetime).
    const existing = await readEntitlement(store, accountHash);
    const merged = {
      ...kofiRecord,
      linkedFrom: kofiEmail,
      updatedAt: new Date().toISOString()
    };
    // Preserve an existing later expiry / lifetime on the account.
    if (existing) {
      const existingExp = existing.expiresAt ? Date.parse(existing.expiresAt) : Infinity;
      const kofiExp = kofiRecord.expiresAt ? Date.parse(kofiRecord.expiresAt) : Infinity;
      if (existingExp >= kofiExp) merged.expiresAt = existing.expiresAt;
      merged.trialUsed = existing.trialUsed === true || kofiRecord.trialUsed === true;
    }
    await writeEntitlement(store, accountHash, merged);
    return json(200, { ...evaluateEntitlement(merged), linked: true });
  } catch (error) {
    console.error("entitlement-link failed", error);
    return json(500, { error: "entitlement_link_failed", message: error.message });
  }
};
