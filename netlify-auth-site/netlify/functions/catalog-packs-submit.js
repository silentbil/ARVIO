const {
  json,
  options,
  parseBody,
  getPool
} = require("./_backend");

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

    if (!manifestUrl.startsWith("http://") && !manifestUrl.startsWith("https://")) {
      return json(400, { error: "bad_request", message: "Manifest URL must start with http:// or https://" });
    }

    // Fetch the manifest JSON
    let response;
    try {
      response = await fetch(manifestUrl);
    } catch (fetchErr) {
      return json(400, {
        error: "fetch_failed",
        message: `Unable to fetch manifest JSON from "${manifestUrl}". Please verify the URL is correct and public.`
      });
    }

    if (!response.ok) {
      return json(400, {
        error: "fetch_failed",
        message: `Unable to fetch manifest JSON from "${manifestUrl}". Server returned status ${response.status}.`
      });
    }

    let manifest;
    try {
      manifest = await response.json();
    } catch (parseErr) {
      return json(400, {
        error: "invalid_json",
        message: "Failed to parse manifest as JSON. Please ensure it is valid JSON."
      });
    }

    // Validate manifest fields
    if (!manifest.id || typeof manifest.id !== "string" || !manifest.id.trim()) {
      return json(400, { error: "invalid_manifest", message: "Manifest is missing a valid 'id'." });
    }
    if (!manifest.name || typeof manifest.name !== "string" || !manifest.name.trim()) {
      return json(400, { error: "invalid_manifest", message: "Manifest is missing a valid 'name'." });
    }
    if (!Array.isArray(manifest.catalogs) || manifest.catalogs.length === 0) {
      return json(400, { error: "invalid_manifest", message: "Manifest must contain a non-empty 'catalogs' list." });
    }

    // Validate catalogs structure and check for duplicate URLs
    const normalizedUrls = new Set();
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

      // Check for duplicate URLs (normalized)
      // Mirroring CatalogUrlParser.normalize: trimmed, start with https if missing, remove trailing slash, lowercase
      let trimmed = item.url.trim();
      let withScheme = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        ? trimmed
        : `https://${trimmed}`;
      if (withScheme.endsWith("/")) {
        withScheme = withScheme.slice(0, -1);
      }
      const normalizedUrl = withScheme.toLowerCase();

      if (normalizedUrls.has(normalizedUrl)) {
        return json(400, {
          error: "invalid_manifest",
          message: `Duplicate catalog URL found: '${item.url}'.`
        });
      }
      normalizedUrls.add(normalizedUrl);
      catalogsList.push(item.name.trim());
    }

    // Insert into public.catalog_packs database table
    const name = (manifest.name || "").trim();
    const author = (body.author || manifest.author || "Anonymous").trim();
    const version = (manifest.version || "1.0.0").trim();
    const description = (body.description || manifest.description || "").trim();

    const insertResult = await getPool().query(
      `INSERT INTO public.catalog_packs (name, url, author, version, description, catalogs, status)
       VALUES ($1, $2, $3, $4, $5, $6, $7)
       RETURNING id, name, url, author, version, description, catalogs, status`,
      [name, manifestUrl, author, version, description, JSON.stringify(catalogsList), "pending"]
    );

    return json(201, insertResult.rows[0]);
  } catch (error) {
    console.error("catalog-packs-submit failed", error);
    return json(500, { error: "internal_error", message: error.message });
  }
};
