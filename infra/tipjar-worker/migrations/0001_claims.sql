-- stash-claims (binding CLAIMS_DB): the tip jar's supporter claim (src/claim.js, src/claim-store.js).
-- Apply with: npx wrangler d1 migrations apply stash-claims --remote   (see ../README.md, "Supporter claim").
--
-- D1 runs one statement at a time per database, so each check-and-change below is a single statement
-- (UPDATE/INSERT/DELETE ... RETURNING) and holds under parallel requests, which KV could not promise.

-- One pending claim per email hash (HMAC-SHA256 of the address with EMAIL_PEPPER, as in ACCESS_KV access:<hash>).
-- code_hash is HMAC-SHA256(EMAIL_PEPPER, "claim-code:v1:<email_hash>:<code>"): never the code, and useless
-- without the pepper. tries counts verify attempts (MAX_TRIES in src/claim.js); expires_at is epoch seconds.
CREATE TABLE IF NOT EXISTS claims (
    email_hash TEXT PRIMARY KEY,
    code_hash  TEXT NOT NULL,
    tries      INTEGER NOT NULL DEFAULT 0,
    expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS claims_expires_at ON claims (expires_at);

-- Fixed-window counters, one row per key and window (e.g. "nodonation:<hour>"): the global cap on
-- "no donation found" emails. expires_at (epoch seconds) is when the daily cron may drop the row.
CREATE TABLE IF NOT EXISTS claim_counters (
    key        TEXT PRIMARY KEY,
    n          INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS claim_counters_expires_at ON claim_counters (expires_at);
