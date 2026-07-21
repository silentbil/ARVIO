const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

process.env.ARVIO_AUTH_SECRET = "test-only-secret-that-is-longer-than-32-bytes";
process.env.APP_ANON_KEY = "test-app-key";

const backend = require("../netlify/functions/_backend");
const security = backend._test;

test("access and refresh tokens cannot be exchanged across token types", () => {
  const account = { accountId: "account-123", email: "User@Example.org" };
  const access = security.signArvioAccessToken(account);
  const refresh = security.signArvioRefreshToken(account);

  assert.equal(security.verifyArvioAccessToken(access).supabaseUserId, "account-123");
  assert.equal(security.verifyArvioRefreshToken(refresh).email, "user@example.org");
  assert.throws(() => security.verifyArvioAccessToken(refresh), /token type/i);
  assert.throws(() => security.verifyArvioRefreshToken(access), /token type/i);
});

test("new password setup keys are account scoped and legacy keys remain readable", () => {
  const accountId = "5f2d1718-7702-4aa9-bd89-9951dd70e91a";
  const token = `p2.${Buffer.from(accountId).toString("base64url")}.random-token`;
  const prefix = security.passwordSetupPrefixForAccount(accountId);

  assert.match(security.passwordSetupKeyForToken(token), new RegExp(`^${prefix}`));
  assert.match(security.passwordSetupKeyForToken("legacy-token"), /^password-setup\/[a-f0-9]{64}\.json$/);
});

test("receipt comparison rejects altered tokens", () => {
  const receipt = "receipt-token";
  assert.equal(security.safeTokenEqual(receipt, backend.sha256(receipt)), true);
  assert.equal(security.safeTokenEqual("different", backend.sha256(receipt)), false);
});

test("deletion page uses authenticated self-service flow without storing credentials", () => {
  const html = fs.readFileSync(path.join(__dirname, "..", "delete-account.html"), "utf8");
  assert.match(html, /account-delete-start/);
  assert.match(html, /account-delete-status/);
  assert.match(html, /Type DELETE to confirm/i);
  assert.doesNotMatch(html, /localStorage|sessionStorage/);
  assert.doesNotMatch(html, /How to request deletion/);
});

test("deletion endpoints reject missing app auth, bad user auth, and missing receipts", async () => {
  const missingAppAuth = await backend.handleAccountDeleteStart({
    httpMethod: "POST",
    headers: {},
    body: JSON.stringify({ confirmation: "DELETE" })
  });
  assert.equal(missingAppAuth.statusCode, 401);

  const badUserAuth = await backend.handleAccountDeleteStart({
    httpMethod: "POST",
    headers: { apikey: "test-app-key", authorization: "Bearer invalid-token" },
    body: JSON.stringify({ confirmation: "DELETE" })
  });
  assert.equal(badUserAuth.statusCode, 401);

  const missingReceipt = await backend.handleAccountDeleteStatus({
    httpMethod: "POST",
    headers: { apikey: "test-app-key", authorization: "Bearer test-app-key" },
    body: "{}"
  });
  assert.equal(missingReceipt.statusCode, 400);
});

test("usage endpoint stores pseudonymous keys instead of raw identifiers", () => {
  const source = fs.readFileSync(
    path.join(__dirname, "..", "netlify", "functions", "app-usage-event.js"),
    "utf8"
  );
  assert.match(source, /privacyHash\("usage-account"/);
  assert.match(source, /accountKey/);
  assert.doesNotMatch(source, /\binstallId,\s*\n\s*userId:/);
  assert.doesNotMatch(source, /\bemail:\s*email/);
});
