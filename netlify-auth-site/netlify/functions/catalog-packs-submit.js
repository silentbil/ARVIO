const dns = require("dns").promises;
const {
  json,
  options,
  parseBody,
  getPool,
  sha256
} = require("./_backend");

// Self-contained helper to check if an IP is private/loopback/link-local/multicast/cloud metadata
function isPrivateIp(ip) {
  if (!ip) return true;

  let cleaned = ip.trim().replace(/^\[|\]$/g, "");

  // Convert IPv4-mapped IPv6 address to IPv4
  if (cleaned.toLowerCase().startsWith("::ffff:")) {
    cleaned = cleaned.substring(7);
  }

  // IPv4 format check
  if (/^(?:[0-9]{1,3}\.){3}[0-9]{1,3}$/.test(cleaned)) {
    const parts = cleaned.split(".").map(Number);
    if (parts.some(isNaN) || parts.some(p => p < 0 || p > 255)) return true;

    const first = parts[0];
    const second = parts[1];

    // Loopback: 127.0.0.0/8
    if (first === 127) return true;
    // Broadcast/any: 0.0.0.0/8
    if (first === 0) return true;
    // Private RFC 1918: 10.0.0.0/8
    if (first === 10) return true;
    // Link-local/Cloud metadata: 169.254.0.0/16
    if (first === 169 && second === 254) return true;
    // Private RFC 1918: 172.16.0.0/12
    if (first === 172 && (second >= 16 && second <= 31)) return true;
    // Private RFC 1918: 192.168.0.0/16
    if (first === 192 && second === 168) return true;
    // Carrier-grade NAT: 100.64.0.0/10
    if (first === 100 && (second >= 64 && second <= 127)) return true;
    // Benchmark testing: 198.18.0.0/15
    if (first === 198 && (second === 18 || second === 19)) return true;
    // Multicast/Reserved: >= 224
    if (first >= 224) return true;

    return false;
  }

  // IPv6 format check
  const ipv6Lower = cleaned.toLowerCase();
  if (ipv6Lower === "::1" || ipv6Lower === "0:0:0:0:0:0:0:1") return true;
  if (ipv6Lower === "::" || ipv6Lower === "0:0:0:0:0:0:0:0") return true;

  // Link-local: fe80::/10
  if (ipv6Lower.startsWith("fe8") || ipv6Lower.startsWith("fe9") || ipv6Lower.startsWith("fea") || ipv6Lower.startsWith("feb")) return true;
  // ULA (Unique Local Address): fc00::/7
  if (ipv6Lower.startsWith("fc") || ipv6Lower.startsWith("fd")) return true;
  // Multicast: ff00::/8
  if (ipv6Lower.startsWith("ff")) return true;

  return false;
}

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
  const maxRedirects = 3;

  while (true) {
    if (!currentUrl.startsWith("https://")) {
      throw new Error("Only HTTPS manifest URLs are allowed");
    }

    let parsedUrl;
    try {
      parsedUrl = new URL(currentUrl);
    } catch (err) {
      throw new Error("Invalid URL format");
    }

    const hostname = parsedUrl.hostname;
    if (!hostname) {
      throw new Error("Invalid hostname");
    }

    // DNS Resolution check
    let ip;
    if (/^(?:[0-9]{1,3}\.){3}[0-9]{1,3}$/.test(hostname) || hostname.includes(":")) {
      ip = hostname;
    } else {
      try {
        const lookup = await dns.lookup(hostname);
        ip = lookup.address;
      } catch (dnsErr) {
        throw new Error(`DNS lookup failed for hostname: ${hostname}`);
      }
    }

    if (isPrivateIp(ip)) {
      throw new Error("Access to private, loopback, or link-local IP addresses is blocked");
    }

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 4000); // 4 seconds timeout

    let response;
    try {
      response = await fetch(currentUrl, {
        signal: controller.signal,
        redirect: "manual",
        headers: {
          "User-Agent": "ARVIO-Pack-Validator/1.0"
        }
      });
    } catch (err) {
      if (err.name === "AbortError") {
        throw new Error("Request timed out");
      }
      throw new Error(`Failed to fetch manifest: ${err.message}`);
    } finally {
      clearTimeout(timeoutId);
    }

    // Handle redirects manually to secure them
    if ([301, 302, 303, 307, 308].includes(response.status)) {
      if (redirects >= maxRedirects) {
        throw new Error("Too many redirects");
      }
      const location = response.headers.get("location");
      if (!location) {
        throw new Error("Redirect response missing location header");
      }
      currentUrl = new URL(location, currentUrl).toString();
      redirects++;
      continue;
    }

    if (!response.ok) {
      throw new Error(`Server returned status ${response.status}`);
    }

    // Content-Type validation
    const contentType = response.headers.get("content-type") || "";
    if (!contentType.includes("application/json") && !contentType.includes("text/plain")) {
      throw new Error("Response content-type must be JSON");
    }

    // Content-Length check
    const contentLength = response.headers.get("content-length");
    if (contentLength && parseInt(contentLength, 10) > 512 * 1024) {
      throw new Error("Response size exceeds the maximum limit of 512 KB");
    }

    // Download body buffer and enforce size limit
    const buffer = await response.arrayBuffer();
    if (buffer.byteLength > 512 * 1024) {
      throw new Error("Response size exceeds the maximum limit of 512 KB");
    }

    const text = new TextDecoder().decode(buffer);
    try {
      return JSON.parse(text);
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
