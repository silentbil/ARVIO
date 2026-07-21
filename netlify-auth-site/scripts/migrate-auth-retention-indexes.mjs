import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { getStore } from "@netlify/blobs";

const rawArgs = process.argv.slice(2);
const args = new Set(rawArgs);
const apply = args.has("--apply");
const usageOnly = args.has("--usage-only");
const concurrencyIndex = rawArgs.indexOf("--concurrency");
const concurrency = Math.max(1, Math.min(120, Number(rawArgs[concurrencyIndex + 1] || 20)));
const siteID = process.env.NETLIFY_SITE_ID || "17ccb668-bf3c-412d-9573-a28a09e52122";

function readNetlifyToken() {
  if (process.env.NETLIFY_AUTH_TOKEN) return process.env.NETLIFY_AUTH_TOKEN;
  const configPath = path.join(process.env.APPDATA || "", "netlify", "Config", "config.json");
  const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
  return config.users[config.userId].auth.token;
}

const token = readNetlifyToken();
const auth = getStore({ name: "arvio-auth", siteID, token });
const tv = getStore({ name: "tv-auth-sessions", siteID, token });
const usage = getStore({ name: "app-usage", siteID, token });

function sha256(value) {
  return crypto.createHash("sha256").update(String(value || "")).digest("hex");
}

function privacyHash(namespace, value) {
  const secret = process.env.ARVIO_AUTH_SECRET || "";
  if (!secret) throw new Error("ARVIO_AUTH_SECRET is required to migrate usage records");
  return crypto.createHmac("sha256", secret).update(`${namespace}:${String(value || "")}`).digest("hex");
}

async function getJSON(store, key) {
  try {
    return await store.get(key, { type: "json", consistency: "strong" });
  } catch (error) {
    if (String(error?.message || "").includes("uncachedEdgeURL")) {
      return store.get(key, { type: "json" });
    }
    if (error?.status === 404 || error?.name === "BlobNotFoundError") return null;
    throw error;
  }
}

async function listKeys(store, prefix) {
  const keys = [];
  for await (const page of store.list({ prefix, paginate: true })) {
    for (const blob of page.blobs || []) keys.push(blob.key);
  }
  return keys;
}

async function mapConcurrent(items, task) {
  let cursor = 0;
  const workers = Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (cursor < items.length) {
      const index = cursor++;
      await task(items[index]);
    }
  });
  await Promise.all(workers);
}

const stats = {
  refreshActive: 0,
  refreshExpired: 0,
  passwordActive: 0,
  passwordExpired: 0,
  tvActiveApproved: 0,
  tvExpired: 0,
  usageMigrated: 0,
  usageExpired: 0,
  errors: 0
};

const now = Date.now();
const usageCutoff = new Date(now - 30 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);

async function migrateRefreshTokens() {
  const keys = (await listKeys(auth, "refresh/"))
    .filter((key) => /^refresh\/[a-f0-9]{64}\.json$/.test(key));
  let processed = 0;
  await mapConcurrent(keys, async (key) => {
    try {
      const record = await getJSON(auth, key);
      if (!record?.accountId || !record?.expiresAt || Date.parse(record.expiresAt) <= now) {
        stats.refreshExpired++;
        if (apply) await auth.delete(key);
        return;
      }
      stats.refreshActive++;
      if (apply) {
        await auth.setJSON(
          `refresh-account/${sha256(record.accountId)}/${key.slice("refresh/".length)}`,
          { targetKey: key, expiresAt: record.expiresAt },
          { metadata: { expiresAt: record.expiresAt } }
        );
      }
    } catch (error) {
      stats.errors++;
      console.error(`refresh migration failed for ${key}: ${error.message}`);
    } finally {
      processed++;
      if (processed % 5000 === 0) console.log(`refresh: ${processed}/${keys.length}`);
    }
  });
  console.log(`refresh: ${processed}/${keys.length}`);
}

async function migratePasswordSetupTokens() {
  const keys = (await listKeys(auth, "password-setup/"))
    .filter((key) => /^password-setup\/[a-f0-9]{64}\.json$/.test(key));
  let processed = 0;
  await mapConcurrent(keys, async (key) => {
    try {
      const record = await getJSON(auth, key);
      if (!record?.accountId || !record?.expiresAt || Date.parse(record.expiresAt) <= now) {
        stats.passwordExpired++;
        if (apply) await auth.delete(key);
        return;
      }
      stats.passwordActive++;
      if (apply) {
        await auth.setJSON(
          `password-setup-account/${sha256(record.accountId)}/${key.slice("password-setup/".length)}`,
          { targetKey: key, expiresAt: record.expiresAt },
          { metadata: { expiresAt: record.expiresAt } }
        );
      }
    } catch (error) {
      stats.errors++;
      console.error(`password migration failed for ${key}: ${error.message}`);
    } finally {
      processed++;
      if (processed % 5000 === 0) console.log(`password: ${processed}/${keys.length}`);
    }
  });
  console.log(`password: ${processed}/${keys.length}`);
}

async function migrateTvSessions() {
  const keys = await listKeys(tv, "device/");
  let processed = 0;
  await mapConcurrent(keys, async (key) => {
    try {
      const session = await getJSON(tv, key);
      if (!session?.deviceCode || !session?.userCode || !session?.expiresAt || Date.parse(session.expiresAt) <= now || ["expired", "consumed"].includes(session.status)) {
        stats.tvExpired++;
        if (apply) {
          await Promise.all([
            tv.delete(key).catch(() => {}),
            session?.userCode ? tv.delete(`code/${String(session.userCode).toUpperCase()}.json`).catch(() => {}) : Promise.resolve()
          ]);
        }
        return;
      }
      if (session.status === "approved" && session.userId) {
        stats.tvActiveApproved++;
        if (apply) {
          await tv.setJSON(`account/${sha256(session.userId)}/${session.deviceCode}.json`, {
            deviceCode: session.deviceCode,
            userCode: session.userCode,
            expiresAt: session.expiresAt
          }, { metadata: { expiresAt: session.expiresAt } });
        }
      }
    } catch (error) {
      stats.errors++;
      console.error(`TV session migration failed for ${key}: ${error.message}`);
    } finally {
      processed++;
      if (processed % 5000 === 0) console.log(`tv: ${processed}/${keys.length}`);
    }
  });
  console.log(`tv: ${processed}/${keys.length}`);
}

async function migrateUsageRecords() {
  const keys = (await listKeys(usage, ""))
    .filter((key) => /^\d{4}-\d{2}-\d{2}\//.test(key));
  let processed = 0;
  await mapConcurrent(keys, async (key) => {
    try {
      const date = key.slice(0, 10);
      if (date < usageCutoff) {
        stats.usageExpired++;
        if (apply) await usage.delete(key);
        return;
      }
      const record = await getJSON(usage, key);
      if (!record) return;
      const accountIdentity = String(record.userId || record.email || "");
      const accountKey = accountIdentity ? privacyHash("usage-account", accountIdentity) : "";
      const installKey = privacyHash("usage-install", record.installId || key);
      const profileKey = record.profileId ? privacyHash("usage-profile", record.profileId) : null;
      const ownerPath = accountKey ? `account/${accountKey}` : "anonymous";
      const targetKey = `date/${date}/${ownerPath}/${sha256(`${installKey}:${record.eventName || "app_open"}`)}.json`;
      stats.usageMigrated++;
      if (apply) {
        const existing = await getJSON(usage, targetKey);
        await usage.setJSON(targetKey, {
          date,
          eventName: record.eventName || "app_open",
          installKey,
          accountKey: accountKey || null,
          profileKey,
          platform: record.platform || null,
          deviceType: record.deviceType || null,
          appVersion: record.appVersion || null,
          appVersionCode: record.appVersionCode || null,
          distribution: record.distribution || null,
          metadata: record.metadata || {},
          count: Math.max(Number(existing?.count || 0), Number(record.count || 1)),
          updatedAt: new Date().toISOString()
        });
        await usage.delete(key);
      }
    } catch (error) {
      stats.errors++;
      console.error(`usage migration failed for ${key}: ${error.message}`);
    } finally {
      processed++;
      if (processed % 5000 === 0) console.log(`usage: ${processed}/${keys.length}`);
    }
  });
  console.log(`usage: ${processed}/${keys.length}`);
}

console.log(`${apply ? "Applying" : "Dry-running"} legacy auth retention migration...`);
if (!usageOnly) {
  await migrateRefreshTokens();
  await migratePasswordSetupTokens();
  await migrateTvSessions();
}
await migrateUsageRecords();
console.log(JSON.stringify(stats, null, 2));
if (!apply) console.log("No records changed. Re-run with --apply after reviewing the counts.");
if (stats.errors > 0) process.exitCode = 1;
