import { test } from "node:test";
import assert from "node:assert/strict";
import { fakeD1, QUOTA_MIGRATIONS } from "./fake-d1.js";
import {
    quotaDay, nextResetSec, freeDailyLimit, readStanding, spendLossless, losslessHeaders, capAnswer, quotaBody,
    pruneUsage, hooks, entitlementOf,
} from "../src/quota.js";

// Real SQLite with the shared schema from infra/lossless-quota/migrations.
const NOW = 1792195200 + 3600; // 2026-10-17T01:00:00Z
const REQ = new Request("https://relay.test/");

test("day is the UTC date; the reset is the next UTC midnight", () => {
    assert.equal(quotaDay(NOW), "2026-10-17");
    assert.equal(nextResetSec(NOW), 1792195200 + 86400);
    assert.equal(nextResetSec(1792195200), 1792195200 + 86400, "exactly midnight resets at the next one");
    assert.equal(quotaDay(1792195200 + 86399), "2026-10-17");
});

test("FREE_LOSSLESS_DAILY defaults to 500; junk or non-positive values fall back", () => {
    assert.equal(freeDailyLimit({}), 500);
    assert.equal(freeDailyLimit({ FREE_LOSSLESS_DAILY: "20" }), 20);
    for (const v of ["0", "-3", "x"]) assert.equal(freeDailyLimit({ FREE_LOSSLESS_DAILY: v }), 500, v);
});

test("no QUOTA_DB or no subject: the counter is off (null) and every helper accepts that", async () => {
    assert.equal(await readStanding(REQ, {}, "i:abc", NOW), null);
    assert.equal(await readStanding(REQ, { QUOTA_DB: fakeD1(QUOTA_MIGRATIONS) }, "", NOW), null);
    assert.equal(await spendLossless(null), null);
    assert.deepEqual(losslessHeaders(null), {});
    await pruneUsage({}, NOW); // no throw
});

test("read, then one atomic upsert per answer: used counts per day and subject", async () => {
    const env = { QUOTA_DB: fakeD1(QUOTA_MIGRATIONS), FREE_LOSSLESS_DAILY: "2" };
    let s = await readStanding(REQ, env, "i:a", NOW);
    assert.equal(s.used, 0);
    assert.equal(s.limit, 2);
    assert.equal(s.over, false);
    assert.equal((await spendLossless(s)).used, 1);
    assert.deepEqual(losslessHeaders(s), { "X-Stash-Lossless": "1/2", "X-Stash-Lossless-Reset": String(nextResetSec(NOW)) });
    await spendLossless(await readStanding(REQ, env, "i:a", NOW));
    s = await readStanding(REQ, env, "i:a", NOW);
    assert.equal(s.used, 2);
    assert.equal(s.over, true);
    assert.equal((await readStanding(REQ, env, "i:b", NOW)).used, 0, "other subjects have their own count");
    assert.equal((await readStanding(REQ, env, "i:a", NOW + 86400)).used, 0, "a new UTC day starts at 0");
    assert.deepEqual({ ...env.QUOTA_DB.raw.prepare("SELECT day, subject, n FROM usage").get() }, { day: "2026-10-17", subject: "i:a", n: 2 });
});

test("the cap answer: 429 daily_lossless_cap with used/limit/resetsAt and Retry-After to the reset", async () => {
    const env = { QUOTA_DB: fakeD1(QUOTA_MIGRATIONS), FREE_LOSSLESS_DAILY: "1" };
    await spendLossless(await readStanding(REQ, env, "i:a", NOW));
    const a = capAnswer(await readStanding(REQ, env, "i:a", NOW), NOW);
    assert.equal(a.status, 429);
    assert.deepEqual(a.body, { error: "daily_lossless_cap", used: 1, limit: 1, resetsAt: nextResetSec(NOW) });
    assert.equal(a.headers["Retry-After"], String(nextResetSec(NOW) - NOW));
    assert.equal(a.headers["X-Stash-Lossless"], "1/1");
    assert.equal(capAnswer({ used: 1, limit: 1, resetsAt: NOW }, NOW).headers["Retry-After"], "1", "never 0");
});

test("a supporter entitlement skips the cap: limit null, header 'unlimited', still counted", async () => {
    assert.equal(await entitlementOf(REQ, {}), null, "nobody is a supporter until step 3");
    const env = { QUOTA_DB: fakeD1(QUOTA_MIGRATIONS), FREE_LOSSLESS_DAILY: "1" };
    await spendLossless(await readStanding(REQ, env, "i:a", NOW));
    const original = hooks.entitlementOf;
    hooks.entitlementOf = async () => ({ tier: "supporter", tid: "t1" });
    try {
        const s = await readStanding(REQ, env, "i:a", NOW);
        assert.equal(s.over, false);
        assert.equal(s.limit, null);
        await spendLossless(s);
        assert.equal(losslessHeaders(s)["X-Stash-Lossless"], "2/unlimited");
        assert.deepEqual(quotaBody(s), { used: 2, limit: null, resetsAt: nextResetSec(NOW), supporter: true });
    } finally { hooks.entitlementOf = original; }
});

test("D1 failures never throw: a failed read turns the counter off, a failed bump still shows the answer", async () => {
    const broken = { prepare: () => { throw new Error("D1 down"); } };
    assert.equal(await readStanding(REQ, { QUOTA_DB: broken }, "i:a", NOW), null);
    const s = { db: broken, day: "2026-10-17", subject: "i:a", used: 4, limit: 500, resetsAt: 1 };
    assert.equal((await spendLossless(s)).used, 5);
    await pruneUsage({ QUOTA_DB: broken }, NOW);
});

test("pruneUsage keeps a week plus today and drops older days", async () => {
    const env = { QUOTA_DB: fakeD1(QUOTA_MIGRATIONS) };
    const put = env.QUOTA_DB.raw.prepare("INSERT INTO usage (day, subject, n) VALUES (?, 'i:a', 1)");
    for (const d of ["2026-10-09", "2026-10-10", "2026-10-17"]) put.run(d);
    await pruneUsage(env, NOW);
    assert.deepEqual(env.QUOTA_DB.raw.prepare("SELECT day FROM usage ORDER BY day").all().map((r) => r.day), ["2026-10-10", "2026-10-17"]);
});
