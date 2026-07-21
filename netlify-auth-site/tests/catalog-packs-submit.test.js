const test = require("node:test");
const assert = require("assert");
const dns = require("dns").promises;

// Mock the backend module before requiring the handler to avoid connecting to the database
const mockQueries = [];
let mockRateLimitRows = [];
let mockDuplicateCheckRows = [];

const mockBackend = {
  json: (status, body) => ({
    statusCode: status,
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body)
  }),
  options: () => null,
  parseBody: (event) => {
    return typeof event.body === "string" ? JSON.parse(event.body) : event.body;
  },
  getPool: () => ({
    query: async (queryText, values) => {
      mockQueries.push({ queryText, values });
      if (queryText.includes("submission_rate_limits") && queryText.includes("SELECT")) {
        return { rows: mockRateLimitRows };
      }
      if (queryText.includes("catalog_packs") && queryText.includes("SELECT")) {
        return { rows: mockDuplicateCheckRows };
      }
      return { rows: [{ id: "mock-id", name: "Mock Pack" }] };
    }
  }),
  sha256: (val) => require("crypto").createHash("sha256").update(val).digest("hex")
};

require.cache[require.resolve("../netlify/functions/_backend")] = {
  exports: mockBackend
};

const { handler } = require("../netlify/functions/catalog-packs-submit");

// Helper to create events
function createEvent({ url, clientIp = "1.2.3.4", bodyExtra = {} }) {
  return {
    httpMethod: "POST",
    headers: {
      "client-ip": clientIp
    },
    body: JSON.stringify({
      url,
      ...bodyExtra
    })
  };
}

test("catalog-packs-submit unit tests", async (t) => {
  const originalFetch = globalThis.fetch;

  t.afterEach(() => {
    globalThis.fetch = originalFetch;
    mockQueries.length = 0;
    mockRateLimitRows = [];
    mockDuplicateCheckRows = [];
  });

  await t.test("Allow HTTPS manifest URLs only", async () => {
    const event = createEvent({ url: "http://example.com/manifest.json" });
    const res = await handler(event);
    assert.strictEqual(res.statusCode, 400);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.error, "bad_request");
    assert.match(body.message, /https:\/\//i);
  });

  await t.test("Block localhost, private and link-local IPs (direct IP check)", async () => {
    const localhostEvent = createEvent({ url: "https://127.0.0.1/manifest.json" });
    const res = await handler(localhostEvent);
    assert.strictEqual(res.statusCode, 400);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.error, "fetch_failed");
    assert.match(body.message, /private, loopback, or link-local IP addresses/);
  });

  await t.test("Block hostnames that resolve to private IPs (DNS resolution check)", async () => {
    // Resolve any test local hostname or stub DNS lookup.
    // For safety in this test, we can mock dns.lookup or mock a local URL
    const originalLookup = dns.lookup;
    dns.lookup = async () => ({ address: "192.168.1.50" });

    try {
      const event = createEvent({ url: "https://some-internal-domain.local/manifest.json" });
      const res = await handler(event);
      assert.strictEqual(res.statusCode, 400);
      const body = JSON.parse(res.body);
      assert.strictEqual(body.error, "fetch_failed");
      assert.match(body.message, /private, loopback, or link-local IP addresses/);
    } finally {
      dns.lookup = originalLookup;
    }
  });

  await t.test("Fail on response size exceeding maximum limit", async () => {
    globalThis.fetch = async () => ({
      ok: true,
      status: 200,
      headers: {
        get: (name) => {
          if (name.toLowerCase() === "content-type") return "application/json";
          if (name.toLowerCase() === "content-length") return String(1024 * 1024); // 1 MB
          return null;
        }
      },
      arrayBuffer: async () => new ArrayBuffer(1024 * 1024)
    });

    const event = createEvent({ url: "https://example.com/huge.json" });
    const res = await handler(event);
    assert.strictEqual(res.statusCode, 400);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.error, "fetch_failed");
    assert.match(body.message, /exceeds the maximum limit/);
  });

  await t.test("Fail on fetch timeout", async () => {
    globalThis.fetch = () => new Promise((resolve, reject) => {
      // Simulate timeout by rejecting with AbortError
      const err = new Error("The operation was aborted.");
      err.name = "AbortError";
      setTimeout(() => reject(err), 50);
    });

    const event = createEvent({ url: "https://example.com/slow.json" });
    const res = await handler(event);
    assert.strictEqual(res.statusCode, 400);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.error, "fetch_failed");
    assert.match(body.message, /timed out/);
  });

  await t.test("Fail on invalid JSON content-type", async () => {
    globalThis.fetch = async () => ({
      ok: true,
      status: 200,
      headers: {
        get: (name) => {
          if (name.toLowerCase() === "content-type") return "text/html";
          return null;
        }
      }
    });

    const event = createEvent({ url: "https://example.com/index.html" });
    const res = await handler(event);
    assert.strictEqual(res.statusCode, 400);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.error, "fetch_failed");
    assert.match(body.message, /content-type must be JSON/);
  });

  await t.test("Reject duplicate manifest URLs", async () => {
    mockDuplicateCheckRows = [{ id: "existing-id" }];

    const event = createEvent({ url: "https://example.com/duplicate.json" });
    const res = await handler(event);
    assert.strictEqual(res.statusCode, 409);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.error, "conflict");
    assert.match(body.message, /already submitted/);
  });

  await t.test("Enforce hourly rate limit", async () => {
    // Mock database returning 5 submissions for this IP in the current hour
    mockRateLimitRows = [{ count: 5, last_submission_at: new Date(Date.now() - 300000).toISOString() }];

    const event = createEvent({ url: "https://example.com/ok.json" });
    const res = await handler(event);
    assert.strictEqual(res.statusCode, 429);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.error, "rate_limited");
    assert.match(body.message, /Submission limit exceeded/);
  });

  await t.test("Enforce double-click defense (10s)", async () => {
    // Mock database returning 1 submission 5 seconds ago
    mockRateLimitRows = [{ count: 1, last_submission_at: new Date(Date.now() - 5000).toISOString() }];

    const event = createEvent({ url: "https://example.com/ok.json" });
    const res = await handler(event);
    assert.strictEqual(res.statusCode, 429);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.error, "rate_limited");
    assert.match(body.message, /wait a few seconds/);
  });
});
