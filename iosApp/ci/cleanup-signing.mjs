import crypto from "node:crypto";
import fs from "node:fs";
import https from "node:https";

const keyId = process.env.APP_STORE_CONNECT_KEY_ID;
const issuerId = process.env.APP_STORE_CONNECT_ISSUER_ID;
const keyPath = process.env.ASC_KEY_PATH;
const certificateId = process.env.IOS_CREATED_CERTIFICATE_ID;
const profileId = process.env.IOS_CREATED_PROFILE_ID;

if (!keyId || !issuerId || !keyPath) {
  console.log("App Store Connect credentials unavailable; skipping signing cleanup.");
  process.exit(0);
}

function base64Url(input) {
  return Buffer.from(input)
    .toString("base64")
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
}

function createJwt() {
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: "ES256", kid: keyId, typ: "JWT" }));
  const payload = base64Url(JSON.stringify({
    iss: issuerId,
    iat: now,
    exp: now + 1200,
    aud: "appstoreconnect-v1",
  }));
  const unsignedToken = `${header}.${payload}`;
  const signature = crypto.sign("sha256", Buffer.from(unsignedToken), {
    key: fs.readFileSync(keyPath, "utf8"),
    dsaEncoding: "ieee-p1363",
  });
  return `${unsignedToken}.${base64Url(signature)}`;
}

function deleteResource(path) {
  return new Promise((resolve, reject) => {
    const request = https.request(new URL(`https://api.appstoreconnect.apple.com/v1${path}`), {
      method: "DELETE",
      headers: {
        Authorization: `Bearer ${createJwt()}`,
        Accept: "application/json",
      },
    }, (response) => {
      let body = "";
      response.setEncoding("utf8");
      response.on("data", (chunk) => { body += chunk; });
      response.on("end", () => {
        if ((response.statusCode >= 200 && response.statusCode < 300) || response.statusCode === 404) {
          resolve(response.statusCode);
          return;
        }
        reject(new Error(`DELETE ${path} failed with ${response.statusCode}: ${body}`));
      });
    });
    request.on("error", reject);
    request.end();
  });
}

async function main() {
  if (profileId) {
    console.log(`Deleted profile ${profileId}: ${await deleteResource(`/profiles/${profileId}`)}`);
  }
  if (certificateId) {
    console.log(`Deleted certificate ${certificateId}: ${await deleteResource(`/certificates/${certificateId}`)}`);
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
