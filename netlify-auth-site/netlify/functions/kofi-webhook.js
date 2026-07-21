// POST /kofi-webhook  ← Ko-fi calls this on every payment.
// Ko-fi sends application/x-www-form-urlencoded with a single `data` field
// containing a JSON string. We verify Ko-fi's verification_token against our
// secret, then grant/renew web access for the buyer's email.
//
// Env required: KOFI_VERIFICATION_TOKEN (from Ko-fi → Settings → Webhooks/API).
// Optional:     KOFI_MEMBERSHIP_DAYS (default 31, the paid window per payment).
const { json, options, normalizeEmail, sha256 } = require("./_backend");
const {
  entitlementsStore,
  readEntitlement,
  writeEntitlement,
  buildPaidRecord
} = require("./_entitlements");

function parseKofiData(event) {
  // Ko-fi posts form-urlencoded: data=<url-encoded JSON string>.
  const raw = event.isBase64Encoded ? Buffer.from(event.body || "", "base64").toString("utf8") : (event.body || "");
  const params = new URLSearchParams(raw);
  const dataField = params.get("data");
  if (!dataField) {
    // Some setups post raw JSON; tolerate that too.
    try { return JSON.parse(raw); } catch { return null; }
  }
  try { return JSON.parse(dataField); } catch { return null; }
}

exports.handler = async (event) => {
  const cors = options(event);
  if (cors) return cors;
  if (event.httpMethod !== "POST") return json(405, { error: "method_not_allowed" });

  const expectedToken = process.env.KOFI_VERIFICATION_TOKEN || "";
  if (!expectedToken) {
    console.error("kofi-webhook: KOFI_VERIFICATION_TOKEN not configured");
    return json(500, { error: "not_configured" });
  }

  const data = parseKofiData(event);
  if (!data || typeof data !== "object") {
    return json(400, { error: "bad_payload" });
  }

  // Verify this really came from OUR Ko-fi account.
  if (String(data.verification_token || "") !== expectedToken) {
    return json(401, { error: "invalid_verification_token" });
  }

  const email = normalizeEmail(data.email);
  if (!email) {
    // No email to attach the entitlement to — accept (200) so Ko-fi doesn't
    // retry forever, but record nothing.
    return json(200, { ok: true, ignored: "no_email" });
  }

  // Only grant on subscription/membership payments. A one-off tip should not
  // unlock a subscription. Ko-fi marks recurring payments with
  // is_subscription_payment=true and type "Subscription".
  const type = String(data.type || "").toLowerCase();
  const isSubscription = data.is_subscription_payment === true || type === "subscription";
  const isFirstSub = data.is_first_subscription_payment === true;

  try {
    const store = entitlementsStore(event);
    const emailHash = sha256(email);
    const existing = await readEntitlement(store, emailHash);

    if (!isSubscription) {
      // Not a membership payment — leave any existing entitlement untouched.
      return json(200, { ok: true, ignored: "not_subscription", type });
    }

    const days = Number(process.env.KOFI_MEMBERSHIP_DAYS || 31);
    const record = buildPaidRecord(existing, {
      source: "kofi",
      tier: data.tier_name || (existing && existing.tier) || null,
      days,
      event: isFirstSub ? "kofi-subscription-started" : "kofi-subscription-renewed"
    });
    await writeEntitlement(store, emailHash, record);

    return json(200, { ok: true, entitled: true, expiresAt: record.expiresAt });
  } catch (error) {
    console.error("kofi-webhook failed", error);
    // 500 → Ko-fi will retry, which is what we want on a transient storage error.
    return json(500, { error: "kofi_webhook_failed", message: error.message });
  }
};
