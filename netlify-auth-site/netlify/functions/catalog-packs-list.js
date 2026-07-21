const {
  json,
  options,
  getPool
} = require("./_backend");

exports.handler = async (event) => {
  const cors = options(event);
  if (cors) return cors;

  try {
    const result = await getPool().query(
      "SELECT id, name, author, version, description, url, catalogs, status FROM public.catalog_packs WHERE status = $1 ORDER BY created_at DESC",
      ["approved"]
    );
    return json(200, result.rows);
  } catch (error) {
    console.error("catalog-packs-list failed", error);
    return json(500, { error: "internal_error", message: error.message });
  }
};
