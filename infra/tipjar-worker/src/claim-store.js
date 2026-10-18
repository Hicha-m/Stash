/**
 * The supporter claim's storage: the D1 database bound as CLAIMS_DB (schema: ../migrations/0001_claims.sql).
 *
 * Why D1 and not KV: a claim's try limit and its single use must hold when requests arrive in parallel. KV is a
 * read-then-write store, so five parallel guesses could all read "tries: 0". D1 runs one statement at a time per
 * database, so every check-and-change here is ONE statement (UPDATE / INSERT / DELETE ... RETURNING) and is
 * atomic on its own; no function below reads a row and then writes it in a second statement.
 *
 * Nothing here holds a code or an address: rows are keyed by the email hash, codes are stored as keyed hashes
 * (codeHashOf in src/claim.js).
 */

/**
 * Stores (or replaces) the pending claim for an email hash: a new code, tries back to 0, a new expiry.
 * Called only once the mailer has said the code email was sent (src/claim.js deliverClaim).
 *
 * @param {D1Database} db
 * @param {string} emailHash
 * @param {string} codeHash
 * @param {number} expiresAtS epoch seconds
 */
export async function putClaim(db, emailHash, codeHash, expiresAtS) {
    await db
        .prepare(
            `INSERT INTO claims (email_hash, code_hash, tries, expires_at) VALUES (?1, ?2, 0, ?3)
             ON CONFLICT (email_hash) DO UPDATE SET code_hash = excluded.code_hash, tries = 0, expires_at = excluded.expires_at`,
        )
        .bind(emailHash, codeHash, expiresAtS)
        .run();
}

/**
 * Takes one try on a live claim, atomically: counts it and returns what's needed to check the code, or null when
 * there's no claim, it has expired, or its tries are used up. A try is taken BEFORE the code is compared, so
 * parallel guesses can't share one: at most `maxTries` calls ever get a row for one claim.
 *
 * @param {D1Database} db
 * @param {string} emailHash
 * @param {number} maxTries
 * @param {number} nowS epoch seconds
 * @returns {Promise<{ codeHash: string, tries: number } | null>} `tries` includes this one
 */
export async function takeTry(db, emailHash, maxTries, nowS) {
    const row = await db
        .prepare(
            `UPDATE claims SET tries = tries + 1
             WHERE email_hash = ?1 AND tries < ?2 AND expires_at > ?3
             RETURNING code_hash, tries`,
        )
        .bind(emailHash, maxTries, nowS)
        .first();
    return row ? { codeHash: String(row.code_hash), tries: Number(row.tries) } : null;
}

/**
 * Consumes a claim whose code just matched, atomically: deletes it only if it is still the same claim (same code
 * hash) and still live. Two parallel right answers both get here; only one deletes a row, and only that one may
 * hand out a token.
 *
 * @returns {Promise<boolean>} true when this call consumed the claim
 */
export async function consumeClaim(db, emailHash, codeHash, nowS) {
    const row = await db
        .prepare(`DELETE FROM claims WHERE email_hash = ?1 AND code_hash = ?2 AND expires_at > ?3 RETURNING email_hash`)
        .bind(emailHash, codeHash, nowS)
        .first();
    return !!row;
}

/**
 * Deletes a claim whose tries are used up. Only that claim: a newer one (a fresh start resets tries to 0) is
 * left alone. takeTry already refuses an exhausted claim, so this is tidying, not what enforces the limit.
 */
export async function dropExhausted(db, emailHash, maxTries) {
    await db.prepare(`DELETE FROM claims WHERE email_hash = ?1 AND tries >= ?2`).bind(emailHash, maxTries).run();
}

/**
 * Counts one event against a fixed-window cap, atomically. Returns true when the event is within the cap (and
 * counted), false when the cap is already reached (nothing counted). One upsert: the row is created at 1, or
 * incremented only while under the cap; RETURNING yields no row when the cap held it back.
 *
 * @param {D1Database} db
 * @param {string} key a window-specific key, e.g. "nodonation:<hour>"
 * @param {number} cap
 * @param {number} expiresAtS when the cron may drop the row (epoch seconds)
 */
export async function countWithin(db, key, cap, expiresAtS) {
    if (cap <= 0) return false;
    const row = await db
        .prepare(
            `INSERT INTO claim_counters (key, n, expires_at) VALUES (?1, 1, ?2)
             ON CONFLICT (key) DO UPDATE SET n = n + 1 WHERE n < ?3
             RETURNING n`,
        )
        .bind(key, expiresAtS, cap)
        .first();
    return !!row;
}

/**
 * Drops expired claims and counters (the daily cron). Expired rows are already ignored by every query above;
 * this only keeps the tables small.
 *
 * @returns {Promise<number>} rows deleted
 */
export async function purgeExpired(db, nowS) {
    const [a, b] = await Promise.all([
        db.prepare(`DELETE FROM claims WHERE expires_at <= ?1`).bind(nowS).run(),
        db.prepare(`DELETE FROM claim_counters WHERE expires_at <= ?1`).bind(nowS).run(),
    ]);
    return Number(a?.meta?.changes ?? 0) + Number(b?.meta?.changes ?? 0);
}
