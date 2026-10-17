/**
 * The daily lossless counter (spec docs/superpowers/specs/2026-10-17-lossless-daily-counter-design.md):
 * FREE_LOSSLESS_DAILY lossless answers per subject per UTC day, counted in ONE D1 database both relays share
 * (`stash-quota`, binding QUOTA_DB, schema in infra/lossless-quota/migrations), so a user's 500 covers Qobuz
 * and Tidal together. Subject = "i:<verified install id>".
 *
 * KEEP IN SYNC: infra/lossless-relay/src/quota.js and infra/lossless-relay/tidal-worker/src/quota.js are
 * identical copies (each Worker deploys on its own). Pure apart from the D1 calls; never throws.
 */

/** Usage rows kept (the cap only reads today's; the rest is for looking back). */
export const USAGE_KEEP_DAYS = 7;
const DEFAULT_FREE_DAILY = 500;

export const quotaDay = (nowSec) => new Date(nowSec * 1000).toISOString().slice(0, 10); // "2026-10-17", UTC
/** The next UTC midnight, epoch seconds: one reset moment for everyone. */
export const nextResetSec = (nowSec) => (Math.floor(nowSec / 86400) + 1) * 86400;

export function freeDailyLimit(env) {
    const n = Number.parseInt(env.FREE_LOSSLESS_DAILY, 10);
    return n > 0 ? n : DEFAULT_FREE_DAILY;
}

/**
 * Supporter hook (step 3 of the spec): will verify an `X-Stash-Entitlement` Ed25519 token and return
 * `{ tier: "supporter", tid }`. Until then nobody is a supporter. A non-null entitlement skips the cap
 * (the answer is still counted, and the header reads `<used>/unlimited`).
 */
export async function entitlementOf(_request, _env) {
    return null;
}

/** Indirection so tests (and step 3) can swap the entitlement check without touching the relays. */
export const hooks = { entitlementOf };

let warnedNoDb = false;
function quotaDb(env) {
    if (env.QUOTA_DB) return env.QUOTA_DB;
    // Tests and local dev run without it; in production it silently drops the counter, so say so once per isolate.
    if (!warnedNoDb) { warnedNoDb = true; console.warn("QUOTA_DB not bound: the daily lossless counter is OFF"); }
    return null;
}

/**
 * Where `subject` stands today, read before serving:
 *   { db, subject, day, used, limit (null = unlimited), resetsAt, over, supporter }
 * or null when the counter is off for this request: no QUOTA_DB, no subject, or D1 failed. A counter
 * failure must never stop the music; the relays' abuse caps still apply.
 */
export async function readStanding(request, env, subject, nowSec) {
    const db = quotaDb(env);
    if (!db || !subject) return null;
    try {
        const entitlement = await hooks.entitlementOf(request, env);
        const day = quotaDay(nowSec);
        const row = await db.prepare("SELECT n FROM usage WHERE day = ?1 AND subject = ?2").bind(day, subject).first();
        const used = row ? Number(row.n) : 0;
        const limit = entitlement ? null : freeDailyLimit(env);
        return { db, subject, day, used, limit, resetsAt: nextResetSec(nowSec), over: limit !== null && used >= limit, supporter: Boolean(entitlement) };
    } catch (e) {
        console.error(`quota read failed: ${e?.message || e}`);
        return null;
    }
}

/**
 * One unit for a lossless answer just served: one atomic upsert, and `standing.used` becomes the count
 * after it. Read-then-bump can serve one or two past the cap under concurrency (accepted in the spec).
 * Returns the standing (null stays null).
 */
export async function spendLossless(standing) {
    if (!standing) return standing;
    try {
        const row = await standing.db.prepare(
            "INSERT INTO usage (day, subject, n) VALUES (?1, ?2, 1) ON CONFLICT(day, subject) DO UPDATE SET n = n + 1 RETURNING n",
        ).bind(standing.day, standing.subject).first();
        standing.used = row ? Number(row.n) : standing.used + 1;
    } catch (e) {
        console.error(`quota bump failed: ${e?.message || e}`);
        standing.used += 1; // the answer was served: show it, even if the write was lost
    }
    return standing;
}

/** The counter headers on every counted answer, the 429 and /quota. {} with the counter off. */
export function losslessHeaders(standing) {
    if (!standing) return {};
    return { "X-Stash-Lossless": `${standing.used}/${standing.limit ?? "unlimited"}`, "X-Stash-Lossless-Reset": String(standing.resetsAt) };
}

/** Header names for Access-Control-Expose-Headers, should a relay ever answer browsers directly. */
export const LOSSLESS_HEADER_NAMES = "X-Stash-Lossless, X-Stash-Lossless-Reset";

/** The answer at the cap: body, status and headers (Retry-After = seconds to the reset, at least 1). */
export function capAnswer(standing, nowSec) {
    return {
        body: { error: "daily_lossless_cap", used: standing.used, limit: standing.limit, resetsAt: standing.resetsAt },
        status: 429,
        headers: { ...losslessHeaders(standing), "Retry-After": String(Math.max(1, standing.resetsAt - nowSec)) },
    };
}

/** The GET /quota body. `limit` is null for a supporter (unlimited), matching the header's "unlimited". */
export function quotaBody(standing) {
    return { used: standing.used, limit: standing.limit, resetsAt: standing.resetsAt, supporter: standing.supporter };
}

/** Hourly cron: drop usage rows older than USAGE_KEEP_DAYS. Both relays run it; it is idempotent. */
export async function pruneUsage(env, nowSec) {
    if (!env.QUOTA_DB) return;
    try {
        await env.QUOTA_DB.prepare("DELETE FROM usage WHERE day < ?1").bind(quotaDay(nowSec - USAGE_KEEP_DAYS * 86400)).run();
    } catch (e) {
        console.error(`quota prune failed: ${e?.message || e}`);
    }
}
