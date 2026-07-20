const { handleRetentionCleanup } = require("./_backend");

exports.handler = handleRetentionCleanup;
exports.config = { schedule: "@daily" };
