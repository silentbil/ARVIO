const {
  json,
  options,
  parseBody,
  runAccountDeletionJob
} = require("./_backend");

exports.handler = async (event) => {
  const preflight = options(event);
  if (preflight) return preflight;
  if (event.httpMethod !== "POST") return json(405, { error: "method_not_allowed" });

  try {
    const body = parseBody(event);
    const jobId = String(body.job_id || "").trim();
    const workerToken = String(body.worker_token || "").trim();
    if (!jobId || !workerToken) return json(400, { error: "missing_worker_credentials" });
    await runAccountDeletionJob(event, jobId, workerToken);
    return json(200, { ok: true });
  } catch (error) {
    console.error("account-delete-worker failed", error);
    return json(error?.statusCode || 500, {
      error: "account_delete_worker_failed",
      message: error.message
    });
  }
};
