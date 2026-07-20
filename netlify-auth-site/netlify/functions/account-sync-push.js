const {
  json,
  options,
  parseBody,
  payloadMetrics,
  isExistingSnapshotRicher,
  applyAddonWipeGuard,
  resolveIdentity,
  loadSnapshotFromBlobs,
  saveSnapshotToBlobs,
  appendSnapshotEvent
} = require("./_backend");

exports.handler = async (event) => {
  const cors = options(event);
  if (cors) return cors;
  if (event.httpMethod !== "POST") {
    return json(405, { error: "method_not_allowed" });
  }

  try {
    const identity = await resolveIdentity(event);
    const body = parseBody(event);
    const rawPayload = body.payload;
    if (!rawPayload) {
      return json(400, { accepted: false, reason: "missing_payload" });
    }

    const existing = await loadSnapshotFromBlobs(event, identity);
    // Server-side addon wipe guard: refuse pushes that catastrophically shrink
    // the addon list (recurring client bug); existing addons are merged back.
    const parsedPayload = typeof rawPayload === "string" ? JSON.parse(rawPayload) : rawPayload;
    const { payload: guardedPayload, guarded } = applyAddonWipeGuard(existing, parsedPayload);
    if (guarded) {
      console.warn("account-sync-push: addon wipe guard engaged", {
        user: identity.supabaseUserId,
        incomingRootAddons: Array.isArray(parsedPayload.addons) ? parsedPayload.addons.length : null,
        preservedRootAddons: Array.isArray(guardedPayload.addons) ? guardedPayload.addons.length : null
      });
    }
    const incoming = payloadMetrics(guardedPayload);
    if (isExistingSnapshotRicher(existing, incoming)) {
      return json(200, {
        accepted: false,
        reason: "existing_snapshot_is_richer",
        existing,
        incoming: {
          restoreRank: incoming.restoreRank,
          profileCount: incoming.profileCount,
          scopedCoverage: incoming.scopedCoverage
        }
      });
    }

    const saved = await saveSnapshotToBlobs(event, identity, {
      payload: incoming.payload,
      payloadVersion: incoming.payloadVersion,
      restoreRank: incoming.restoreRank,
      profileCount: incoming.profileCount,
      scopedCoverage: incoming.scopedCoverage,
      payloadUpdatedAt: incoming.payloadUpdatedAt,
      source: "netlify"
    });
    await appendSnapshotEvent(event, identity, saved);

    return json(200, {
      accepted: true,
      addonGuard: guarded,
      restoreRank: incoming.restoreRank,
      profileCount: incoming.profileCount,
      scopedCoverage: incoming.scopedCoverage
    });
  } catch (error) {
    console.error("account-sync-push failed", error);
    return json(error?.statusCode || 500, {
      accepted: false,
      error: "sync_push_failed",
      message: error.message
    });
  }
};
