// Read-only audit of the tokens collection ahead of permission enforcement.
// Reports counts by type, tokens with missing/empty or malformed permissions,
// and expired-but-present tokens.
//
// Usage: mongosh <uri> scripts/audit-tokens.js
// Set TOKENS_COLLECTION to override the collection name (default: flow_tokens).

const collectionName = process.env.TOKENS_COLLECTION || "flow_tokens";
const tokens = db.getCollection(collectionName);
const actionPattern = /^(\*{2}|[0-9a-zA-Z\-]+)\/(\*{2}|read|write|action|delete)$/;

const total = tokens.countDocuments();
print(`Token audit — collection: ${collectionName}, total tokens: ${total}`);

print("\nTokens by type:");
tokens
  .aggregate([{ $group: { _id: "$type", count: { $sum: 1 } } }, { $sort: { count: -1 } }])
  .forEach((row) => print(`  ${row._id ?? "(absent)"}: ${row.count}`));

const emptyPermissions = tokens.countDocuments({
  $or: [{ permissions: { $exists: false } }, { permissions: null }, { permissions: { $size: 0 } }],
});
print(`\nTokens with empty/absent permissions: ${emptyPermissions}`);

const emptyActions = tokens.countDocuments({
  permissions: {
    $elemMatch: {
      $or: [{ actions: { $exists: false } }, { actions: null }, { actions: { $size: 0 } }],
    },
  },
});
print(`Tokens with a permissions entry with empty/absent actions: ${emptyActions}`);

let malformed = 0;
tokens.find({ "permissions.actions": { $exists: true } }).forEach((token) => {
  const bad = (token.permissions ?? [])
    .flatMap((permission) => permission.actions ?? [])
    .filter((action) => typeof action !== "string" || !actionPattern.test(action));
  if (bad.length > 0) {
    malformed++;
    print(`  Token ${token._id} (${token.type}) has malformed actions: ${JSON.stringify(bad)}`);
  }
});
print(`Tokens with malformed action strings: ${malformed}`);

const expired = tokens.countDocuments({ expirationDate: { $lt: new Date() } });
print(`\nExpired-but-present tokens: ${expired}`);
