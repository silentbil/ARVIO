const dns = require("dns").promises;
const https = require("https");
const ipaddr = require("ipaddr.js");
const {
  json,
  options,
  parseBody,
  getPool,
  sha256
} = require("./_backend");

const MAX_MANIFEST_BYTES = 512 * 1024;
const MANIFEST_TIMEOUT_MS = 4_000;
const MAX_REDIRECTS = 3;

function isPrivateIp(ip) {
  if (!ip) return true;
  const cleaned = String(ip).trim().replace(/^\[|\]$/g, "");
  try {
    // process() converts IPv4-mapped IPv6 addresses before classification.
    return ipaddr.process(cleaned).range() !== "unicast";
  } catch {
    return true;
  }
}

async function resolvePublicAddresses(hostname) {
  const cleaned = hostname.trim().replace(/^\[|\]$/g, "");
  let addresses;

  if (ipaddr.isValid(cleaned)) {
    const parsed = ipaddr.parse(cleaned);
    addresses = [{ address: cleaned, family: parsed.kind() === "ipv6" ? 6 : 4 }];
  } else {
    try {
      addresses = await dns.lookup(hostname, { all: true, verbatim: true });
    } catch {
      throw new Error(`DNS lookup failed for hostname: ${hostname}`);
    }
  }

  if (!Array.isArray(addresses) || addresses.length === 0) {
    throw new Error(`DNS lookup returned no addresses for hostname: ${hostname}`);
  }
  if (addresses.some(({ address }) => isPrivateIp(address))) {
    throw new Error("Access to private, loopback, or link-local IP addresses is blocked");
  }
  return addresses;
}

function requestPinnedHttps(url, resolvedAddress) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const fail = (error) => {
      if (settled) return;
      settled = true;
      reject(error);
    };

    const request = https.request(url, {
      method: "GET",
      agent: false,
      headers: { "User-Agent": "ARVIO-Pack-Validator/1.0" },
      // Pin the connection to the address that passed validation. TLS still
      // verifies the certificate against the original URL hostname.
      lookup: (_hostname, options, callback) => {
        if (options && options.all) {
          callback(null, [resolvedAddress]);
        } else {
          callback(null, resolvedAddress.address, resolvedAddress.family);
        }
      }
    }, (response) => {
      const contentLength = Number(response.headers["content-length"] || 0);
      if (contentLength > MAX_MANIFEST_BYTES) {
        fail(new Error("Response size exceeds the maximum limit of 512 KB"));
        response.destroy();
        return;
      }

      const chunks = [];
      let totalBytes = 0;
      response.on("data", (chunk) => {
        if (settled) return;
        const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
        totalBytes += buffer.length;
        if (totalBytes > MAX_MANIFEST_BYTES) {
          fail(new Error("Response size exceeds the maximum limit of 512 KB"));
          response.destroy();
          request.destroy();
          return;
        }
        chunks.push(buffer);
      });
      response.on("error", fail);
      response.on("end", () => {
        if (settled) return;
        settled = true;
        resolve({
          statusCode: response.statusCode || 0,
          headers: response.headers,
          body: Buffer.concat(chunks)
        });
      });
    });

    request.setTimeout(MANIFEST_TIMEOUT_MS, () => {
      const error = new Error("Request timed out");
      error.code = "ETIMEDOUT";
      fail(error);
      request.destroy();
    });
    request.on("error", fail);
    request.end();
  });
}

let secureRequest = requestPinnedHttps;

// Normalize URL helper
function normalizeManifestUrl(urlStr) {
  let trimmed = urlStr.trim();
  if (trimmed.endsWith("/")) {
    trimmed = trimmed.slice(0, -1);
  }
  return trimmed.toLowerCase();
}

async function fetchSecure(urlStr) {
  let currentUrl = urlStr;
  let redirects = 0;

  while (true) {
    let parsedUrl;
    try {
      parsedUrl = new URL(currentUrl);
    } catch {
      throw new Error("Invalid URL format");
    }
    if (parsedUrl.protocol !== "https:") {
      throw new Error("Only HTTPS manifest URLs are allowed");
    }

    const hostname = parsedUrl.hostname;
    if (!hostname) {
      throw new Error("Invalid hostname");
    }

    const addresses = await resolvePublicAddresses(hostname);
    let response;
    try {
      response = await secureRequest(parsedUrl, addresses[0]);
    } catch (err) {
      if (err.code === "ETIMEDOUT" || /timed out/i.test(err.message || "")) {
        throw new Error("Request timed out");
      }
      throw new Error(`Failed to fetch manifest: ${err.message}`);
    }

    if ([301, 302, 303, 307, 308].includes(response.statusCode)) {
      if (redirects >= MAX_REDIRECTS) {
        throw new Error("Too many redirects");
      }
      const location = response.headers.location;
      if (!location) {
        throw new Error("Redirect response missing location header");
      }
      currentUrl = new URL(location, currentUrl).toString();
      redirects++;
      continue;
    }

    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw new Error(`Server returned status ${response.statusCode}`);
    }

    const contentType = String(response.headers["content-type"] || "");
    if (!contentType.includes("application/json") && !contentType.includes("text/plain")) {
      throw new Error("Response content-type must be JSON");
    }

    const contentLength = Number(response.headers["content-length"] || 0);
    if (contentLength > MAX_MANIFEST_BYTES || response.body.length > MAX_MANIFEST_BYTES) {
      throw new Error("Response size exceeds the maximum limit of 512 KB");
    }

    try {
      return JSON.parse(response.body.toString("utf8"));
    } catch (e) {
      throw new Error("Failed to parse manifest as JSON. Please ensure it is valid JSON.");
    }
  }
}

exports.handler = async (event) => {
  const cors = options(event);
  if (cors) return cors;

  if (event.httpMethod !== "POST") {
    return json(405, { error: "method_not_allowed", message: "Method not allowed" });
  }

  try {
    const body = parseBody(event);
    const manifestUrl = (body.url || "").trim();

    if (!manifestUrl) {
      return json(400, { error: "bad_request", message: "Manifest URL is required" });
    }

    if (!manifestUrl.startsWith("https://")) {
      return json(400, { error: "bad_request", message: "Manifest URL must start with https://" });
    }

    // Rate Limiting Check
    const clientIp = event.headers["x-nf-client-connection-ip"] ||
                     event.headers["client-ip"] ||
                     event.headers["x-forwarded-for"] ||
                     "unknown-ip";

    const ipHash = sha256(clientIp);

    // Create rate limit table if not exists (safety check)
    await getPool().query(`
      CREATE TABLE IF NOT EXISTS public.submission_rate_limits (
        ip_hash text PRIMARY KEY,
        count integer NOT NULL DEFAULT 1,
        last_submission_at timestamptz NOT NULL DEFAULT now()
      );
    `).catch(err => console.warn("Failed to create submission_rate_limits table", err));

    // Prune entries older than 1 hour
    await getPool().query(
      "DELETE FROM public.submission_rate_limits WHERE last_submission_at < now() - interval '1 hour'"
    ).catch(err => console.warn("Failed to prune rate limits", err));

    const rateLimitRes = await getPool().query(
      "SELECT count, last_submission_at FROM public.submission_rate_limits WHERE ip_hash = $1",
      [ipHash]
    );

    if (rateLimitRes.rows.length > 0) {
      const { count, last_submission_at } = rateLimitRes.rows[0];
      const lastTime = new Date(last_submission_at).getTime();
      const now = Date.now();

      // Double-click defense (10s)
      if (now - lastTime < 10000) {
        return json(429, {
          error: "rate_limited",
          message: "Too many requests. Please wait a few seconds before trying again."
        });
      }

      // Hourly limit check (5 submissions max per hour)
      if (count >= 5) {
        return json(429, {
          error: "rate_limited",
          message: "Submission limit exceeded. Please try again in an hour."
        });
      }

      await getPool().query(
        "UPDATE public.submission_rate_limits SET count = count + 1, last_submission_at = now() WHERE ip_hash = $1",
        [ipHash]
      );
    } else {
      await getPool().query(
        "INSERT INTO public.submission_rate_limits (ip_hash, count, last_submission_at) VALUES ($1, 1, now())",
        [ipHash]
      );
    }

    // Check for duplicate manifest URL in DB
    const normalizedUrl = normalizeManifestUrl(manifestUrl);
    const duplicateCheck = await getPool().query(
      "SELECT id FROM public.catalog_packs WHERE normalized_url = $1",
      [normalizedUrl]
    );

    if (duplicateCheck.rows.length > 0) {
      return json(409, {
        error: "conflict",
        message: "This catalog pack manifest URL is already submitted."
      });
    }

    // Securely fetch and validate manifest content
    let manifest;
    try {
      manifest = await fetchSecure(manifestUrl);
    } catch (fetchErr) {
      return json(400, {
        error: "fetch_failed",
        message: fetchErr.message
      });
    }

    // Validate manifest structure
    if (!manifest.id || typeof manifest.id !== "string" || !manifest.id.trim()) {
      return json(400, { error: "invalid_manifest", message: "Manifest is missing a valid 'id'." });
    }
    if (!manifest.name || typeof manifest.name !== "string" || !manifest.name.trim()) {
      return json(400, { error: "invalid_manifest", message: "Manifest is missing a valid 'name'." });
    }
    if (!Array.isArray(manifest.catalogs) || manifest.catalogs.length === 0) {
      return json(400, { error: "invalid_manifest", message: "Manifest must contain a non-empty 'catalogs' list." });
    }

    // Validate catalog list and check for duplicate URLs within manifest
    const innerUrls = new Set();
    const catalogsList = [];

    for (const item of manifest.catalogs) {
      if (!item.name || typeof item.name !== "string" || !item.name.trim()) {
        return json(400, {
          error: "invalid_manifest",
          message: "All catalog items in the manifest must have a valid 'name'."
        });
      }
      if (!item.url || typeof item.url !== "string" || !item.url.trim()) {
        return json(400, {
          error: "invalid_manifest",
          message: "All catalog items in the manifest must have a valid 'url'."
        });
      }

      let trimmed = item.url.trim();
      let withScheme = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        ? trimmed
        : `https://${trimmed}`;
      if (withScheme.endsWith("/")) {
        withScheme = withScheme.slice(0, -1);
      }
      const innerNorm = withScheme.toLowerCase();

      if (innerUrls.has(innerNorm)) {
        return json(400, {
          error: "invalid_manifest",
          message: `Duplicate catalog URL found inside manifest: '${item.url}'.`
        });
      }
      innerUrls.add(innerNorm);
      catalogsList.push(item.name.trim());
    }

    // Insert into database
    const name = (manifest.name || "").trim();
    const author = (body.author || manifest.author || "Anonymous").trim();
    const version = (manifest.version || "1.0.0").trim();
    const description = (body.description || manifest.description || "").trim();

    const insertResult = await getPool().query(
      `INSERT INTO public.catalog_packs (name, url, normalized_url, author, version, description, catalogs, status)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
       RETURNING id, name, url, normalized_url, author, version, description, catalogs, status`,
      [name, manifestUrl, normalizedUrl, author, version, description, JSON.stringify(catalogsList), "pending"]
    );

    return json(201, insertResult.rows[0]);
  } catch (error) {
    console.error("catalog-packs-submit failed", error);
    return json(500, { error: "internal_error", message: error.message });
  }
};

exports._test = {
  fetchSecure,
  isPrivateIp,
  setSecureRequest(requester) {
    secureRequest = requester;
  },
  resetSecureRequest() {
    secureRequest = requestPinnedHttps;
  }
};
