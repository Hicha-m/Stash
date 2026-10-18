import { DatabaseSync } from "node:sqlite";
import { readdirSync, readFileSync } from "node:fs";

/**
 * Just enough of D1 (prepare / bind / first / all / run) over node:sqlite for src/claim-store.js, with the real
 * schema: every ../migrations/*.sql file is applied in order, so a broken migration fails the tests.
 *
 * Concurrency, like D1: each statement runs whole (node:sqlite is synchronous, so nothing can interleave inside
 * one), but every call first yields to the event loop. Parallel requests therefore interleave BETWEEN statements,
 * which is exactly where a read-then-write race would show; atomicity has to come from single statements.
 */
export function fakeD1() {
    const raw = new DatabaseSync(":memory:");
    const dir = new URL("../migrations/", import.meta.url);
    for (const file of readdirSync(dir).filter((f) => f.endsWith(".sql")).sort()) raw.exec(readFileSync(new URL(file, dir), "utf8"));
    const tick = () => new Promise((resolve) => setImmediate(resolve));
    const statement = (sql, args = []) => ({
        bind: (...a) => statement(sql, a),
        async first() {
            await tick();
            const row = raw.prepare(sql).get(...args);
            // node:sqlite rows are null-prototype objects; D1 hands back plain ones.
            return row ? { ...row } : null;
        },
        async all() {
            await tick();
            return { results: raw.prepare(sql).all(...args).map((r) => ({ ...r })), success: true, meta: {} };
        },
        async run() {
            await tick();
            const info = raw.prepare(sql).run(...args);
            return { success: true, meta: { changes: Number(info.changes) } };
        },
    });
    return {
        raw,
        prepare: (sql) => statement(sql),
        /** The claim row for an email hash, or null (a test helper, not D1 API). */
        claim(emailHash) {
            const row = raw.prepare("SELECT * FROM claims WHERE email_hash = ?").get(emailHash);
            return row ? { ...row } : null;
        },
        /** Sets columns on a claim row directly (a test helper). */
        setClaim(emailHash, fields) {
            for (const [col, value] of Object.entries(fields)) {
                if (!["code_hash", "tries", "expires_at"].includes(col)) throw new Error(`no column ${col}`);
                raw.prepare(`UPDATE claims SET ${col} = ? WHERE email_hash = ?`).run(value, emailHash);
            }
        },
        count(table) {
            return Number(raw.prepare(`SELECT COUNT(*) AS n FROM ${table}`).get().n);
        },
    };
}
