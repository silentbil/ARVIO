import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { getStore } from "@netlify/blobs";

const baseAddress = String(process.env.ARVIO_DELETION_TEST_EMAIL || "").trim().toLowerCase();
if (!baseAddress.includes("@")) {
  console.error("Set ARVIO_DELETION_TEST_EMAIL to a real inbox that accepts plus aliases.");
  process.exit(1);
}

const [localPart, domain] = baseAddress.split("@");
const email = `${localPart}+arvio-delete-${Math.floor(Date.now() / 1000)}@${domain}`;
const password = `DeleteTest-${crypto.randomBytes(24).toString("hex")}!`;
const siteID = process.env.NETLIFY_SITE_ID || "17ccb668-bf3c-412d-9573-a28a09e52122";
const functionBase = "https://auth.arvio.tv/.netlify/functions";
const html = fs.readFileSync(new URL("../delete-account.html", import.meta.url), "utf8");
const appKey = html.match(/const APP_ANON_KEY = "([^"]+)"/)?.[1] || "";
if (!appKey) throw new Error("APP_ANON_KEY was not found in delete-account.html");

function readNetlifyToken() {
  if (process.env.NETLIFY_AUTH_TOKEN) return process.env.NETLIFY_AUTH_TOKEN;
  const configPath = path.join(process.env.APPDATA || "", "netlify", "Config", "config.json");
  const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
  return config.users[config.userId].auth.token;
}

const netlifyToken = readNetlifyToken();
const stores = {
  auth: getStore({ name: "arvio-auth", siteID, token: netlifyToken }),
  account: getStore({ name: "account-sync", siteID, token: netlifyToken }),
  legacy: getStore({ name: "legacy-supabase-sync", siteID, token: netlifyToken }),
  events: getStore({ name: "account-sync-events", siteID, token: netlifyToken })
};

const appHeaders = {
  "content-type": "application/json",
  apikey: appKey,
  authorization: `Bearer ${appKey}`
};

function sha256(value) {
  return crypto.createHash("sha256").update(String(value)).digest("hex");
}

async function request(name, pathName, { method = "POST", headers = appHeaders, body, quiet = false } = {}) {
  const response = await fetch(`${functionBase}/${pathName}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await response.text();
  let result = {};
  try {
    result = JSON.parse(text);
  } catch {
    result = { message: text.slice(0, 160) };
  }
  if (!quiet) {
    console.log(`${name}: ${response.status}`);
    if (!response.ok) {
      const details = [result.error, result.message].filter(Boolean).join(" - ") || "unknown";
      console.log(`${name} error: ${details}`);
    }
  }
  return { response, result };
}

async function deletePrefix(store, prefix) {
  for await (const page of store.list({ prefix, paginate: true })) {
    await Promise.all((page.blobs || []).map((blob) => store.delete(blob.key).catch(() => {})));
  }
}

async function fallbackCleanup(accountId) {
  await Promise.all([
    stores.auth.delete(`accounts/email/${sha256(email)}.json`).catch(() => {}),
    stores.account.delete(`supabase/${accountId}.json`).catch(() => {}),
    stores.account.delete(`email/${sha256(email)}.json`).catch(() => {}),
    stores.legacy.delete(`supabase/${accountId}.json`).catch(() => {}),
    stores.legacy.delete(`email/${sha256(email)}.json`).catch(() => {}),
    deletePrefix(stores.events, `supabase/${accountId}/`)
  ]);
}

let accountId = "";
let deletionComplete = false;
let verificationPassed = false;

try {
  const signup = await request("signup", "cloud-auth-email", {
    body: { email, password }
  });
  if (!signup.response.ok) throw new Error("Synthetic signup failed");

  const accessToken = signup.result.access_token;
  const refreshToken = signup.result.refresh_token;
  accountId = signup.result.user?.id || "";
  if (!accessToken || !refreshToken || !accountId) throw new Error("Synthetic session was incomplete");

  const claims = JSON.parse(Buffer.from(accessToken.split(".")[1], "base64url").toString("utf8"));
  const storedAccount = await stores.auth.get(`accounts/email/${sha256(email)}.json`, {
    type: "json",
    consistency: "strong"
  });
  const identityMatches = accountId === claims.sub &&
    accountId === storedAccount?.accountId &&
    claims.email === storedAccount?.email;
  console.log(`identity match: ${identityMatches}`);
  if (!identityMatches) throw new Error("Issued token does not match the stored account");

  const userHeaders = { ...appHeaders, authorization: `Bearer ${accessToken}` };
  const initialPull = await request("initial sync pull", "account-sync-pull", {
    method: "GET",
    headers: userHeaders
  });
  if (!initialPull.response.ok) throw new Error("Initial sync pull failed");

  const profileId = crypto.randomUUID();
  const push = await request("sync push", "account-sync-push", {
    headers: userHeaders,
    body: {
      payload: {
        payloadVersion: 1,
        profiles: [{ id: profileId, name: "Deletion test" }],
        activeProfileId: profileId,
        addons: [],
        addonsByProfile: {},
        iptvByProfile: {},
        updatedAt: new Date().toISOString()
      }
    }
  });
  if (!push.response.ok || !push.result.accepted) throw new Error("Synthetic sync push failed");

  const started = await request("deletion start", "account-delete-start", {
    headers: userHeaders,
    body: { confirmation: "DELETE" }
  });
  if (started.response.status !== 202) throw new Error("Deletion did not queue");

  let finalStatus = null;
  for (let poll = 0; poll < 80; poll++) {
    await new Promise((resolve) => setTimeout(resolve, 1500));
    const status = await request("deletion status", "account-delete-status", {
      body: {
        job_id: started.result.job_id,
        receipt_token: started.result.receipt_token
      },
      quiet: true
    });
    if (["complete", "failed"].includes(status.result.status)) {
      finalStatus = status.result;
      console.log(`deletion status: ${status.result.status}`);
      break;
    }
  }
  deletionComplete = finalStatus?.status === "complete";
  if (!deletionComplete) throw new Error(finalStatus?.error || "Deletion did not complete in time");

  const loginAfter = await request("login after deletion", "auth-login", {
    body: { email, password }
  });
  const refreshAfter = await request("refresh after deletion", "auth-refresh", {
    body: { refresh_token: refreshToken }
  });
  const pullAfter = await request("sync after deletion", "account-sync-pull", {
    method: "GET",
    headers: userHeaders
  });
  const revocationPassed = loginAfter.response.status === 401 &&
    refreshAfter.response.status === 401 &&
    pullAfter.response.status === 401;
  console.log(`revocation checks: ${revocationPassed}`);
  if (!revocationPassed) throw new Error("A deleted session remained usable");
  verificationPassed = true;
} finally {
  if (!verificationPassed && accountId) {
    await fallbackCleanup(accountId);
    console.log("fallback cleanup: complete");
  }
}
