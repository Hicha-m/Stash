import test from "node:test";
import assert from "node:assert/strict";
import worker, { networkOf, networkTag } from "../src/index.js";

const ACCESS_TOKEN = "test-access-token";
const MPD = '<MPD><SegmentTemplate initialization="https://sp-ad-fa.audio.tidal.com/init.mp4?token=test" media="https://audio.tidal.com/$Number$" /></MPD>';

function fixture({ tidalResponse, account = liveAccount(), accounts = [account] } = {}) {
  const manifests = new Map();
  const calls = [];
  const dbWrites = [];
  const dailyQuotas = new Map();
  const hiresCache = new Map();
  const responseCache = new Map();
  const accountCaps = [];
  let nextAccount = 0;
  const env = {
    ACCESS_TOKEN,
    ADMIN_TOKEN: "test-admin-token",
    TIDAL_CLIENT_ID: "test-client-id",
    TIDAL_COUNTRY_CODE: "US",
    DB: {
      prepare(sql) {
        return {
          args: [],
          bind(...args) { this.args = args; return this; },
          async first() {
            if (sql.includes("hour_n=CASE") && sql.includes("RETURNING *")) {
              accountCaps.push({ hourly: this.args[3], daily: this.args[4] });
              return accounts[nextAccount++] || null;
            }
            if (sql.includes("SELECT manifest_xml,init_url,expires_at")) return manifests.get(this.args[0]) || null;
            if (sql.includes("FROM tidal_response_cache")) {
              const hit = responseCache.get(this.args[0]);
              return hit && hit.expires_at > this.args[1] ? { response_json: hit.response_json } : null;
            }
            if (sql.includes("FROM tidal_hires_cache")) {
              const hit = hiresCache.get(this.args[0]);
              return hit && hit.checked_at > this.args[1] ? { hires: hit.hires } : null;
            }
            return null;
          },
          async run() {
            dbWrites.push({ sql, args: this.args });
            if (sql.includes("INSERT INTO tidal_daily_quotas")) {
              const [day, key] = this.args;
              const mapKey = `${day}:${key}`;
              dailyQuotas.set(mapKey, (dailyQuotas.get(mapKey) || 0) + 1);
            }
            if (sql.includes("INTO tidal_hires_cache")) {
              const [trackId, hires, checkedAt] = this.args;
              hiresCache.set(trackId, { hires, checked_at: checkedAt });
            }
            if (sql.includes("INTO tidal_response_cache")) {
              const [key, responseJson, expiresAt] = this.args;
              responseCache.set(key, { response_json: responseJson, expires_at: expiresAt });
            }
            if (sql.includes("INSERT OR REPLACE INTO tidal_dash_manifests")) {
              const [trackId, manifestXml, initUrl, expiresAt] = this.args;
              manifests.set(trackId, { manifest_xml: manifestXml, init_url: initUrl, expires_at: expiresAt });
            }
            return { success: true };
          },
          async all() {
            if (sql.includes("FROM tidal_daily_quotas")) {
              const [day, keysJson] = this.args;
              const keys = JSON.parse(keysJson);
              return { results: keys.flatMap((key) => {
                const n = dailyQuotas.get(`${day}:${key}`) || 0;
                return n ? [{ key, n }] : [];
              }) };
            }
            return { results: [] };
          },
        };
      },
      async batch(statements) {
        for (const statement of statements) await statement.run();
        return statements.map(() => ({ success: true }));
      },
    },
  };

  const fetchTidal = async (url, options) => {
    calls.push({ url: String(url), options });
    if (tidalResponse) return tidalResponse(String(url), options);
    return new Response(JSON.stringify({ items: [] }), { status: 200 });
  };

  return { env, calls, manifests, fetchTidal, dbWrites, dailyQuotas, accountCaps, hiresCache, responseCache };
}

function liveAccount() {
  return {
    id: "test-account",
    access_token: "test-tidal-access-token",
    refresh_token: "test-tidal-refresh-token",
    token_expires_at: Math.floor(Date.now() / 1000) + 3600,
    generation: 1,
  };
}

async function withFetch(t, fetchImpl, action) {
  const original = globalThis.fetch;
  globalThis.fetch = fetchImpl;
  try { return await action(); } finally { globalThis.fetch = original; }
}

function authorizedRequest(path, method = "GET", extraHeaders = {}) {
  return new Request(`https://relay.example${path}`, {
    method,
    headers: { "X-Access-Token": ACCESS_TOKEN, ...extraHeaders },
  });
}

function losslessResponse() {
  return playbackInfo("LOSSLESS", "application/vnd.tidal.bts",
    JSON.stringify({ mimeType: "audio/flac", codecs: "flac", urls: ["https://cdn.example/track.flac"] }));
}

function playbackInfo(audioQuality, manifestMimeType, payload) {
  return new Response(JSON.stringify({
    audioQuality,
    manifestMimeType,
    manifest: Buffer.from(payload).toString("base64"),
    bitDepth: 24,
    sampleRate: 96000,
  }), { status: 200 });
}

test("health and route/auth gates", async () => {
  const f = fixture();
  await withFetch(null, f.fetchTidal, async () => {
    const health = await worker.fetch(new Request("https://relay.example/health"), f.env);
    assert.equal(health.status, 200);
    assert.deepEqual(await health.json(), { ok: true, provider: "tidal" });

    const unauthorized = await worker.fetch(new Request("https://relay.example/search?q=test"), f.env);
    assert.equal(unauthorized.status, 401);
    assert.equal(f.calls.length, 0);

    const unknown = await worker.fetch(new Request("https://relay.example/nope"), f.env);
    assert.equal(unknown.status, 404);
  });
});

test("request limiter protects provider routes while health remains available", async () => {
  const f = fixture();
  f.env.REQUEST_RL = { limit: async () => ({ success: false }) };

  await withFetch(null, f.fetchTidal, async () => {
    const limited = await worker.fetch(authorizedRequest("/search?q=test"), f.env);
    assert.equal(limited.status, 429);
    assert.equal(limited.headers.get("retry-after"), "60");

    const health = await worker.fetch(new Request("https://relay.example/health"), f.env);
    assert.equal(health.status, 200);
  });
});

test("request limiter keys IPv6 clients by their /56 network", async () => {
  const f = fixture();
  let limitKey;
  f.env.REQUEST_RL = { limit: async ({ key }) => { limitKey = key; return { success: true }; } };
  await withFetch(null, f.fetchTidal, async () => {
    await worker.fetch(authorizedRequest("/search?q=test", "GET", { "CF-Connecting-IP": "2001:db8:abcd:12:3456::1" }), f.env);
  });
  assert.equal(limitKey, "2001:db8:abcd:0/56");
});

test("per-account cap defaults use the Tidal pool limits", async () => {
  const f = fixture();
  await withFetch(null, f.fetchTidal, async () => {
    await worker.fetch(authorizedRequest("/search?q=test"), f.env);
  });
  assert.deepEqual(f.accountCaps[0], { hourly: 250, daily: 1600 });
});

test("an exhausted pool returns busy with a five-minute retry before contacting Tidal", async () => {
  const f = fixture({ accounts: [] });
  await withFetch(null, f.fetchTidal, async () => {
    for (const path of ["/search?q=example", "/stream/td:321"]) {
      const response = await worker.fetch(authorizedRequest(path), f.env);
      assert.equal(response.status, 503);
      assert.equal(response.headers.get("retry-after"), "300");
      assert.deepEqual(await response.json(), { error: "busy" });
    }
  });
  assert.equal(f.calls.length, 0);
});

test("Tidal search 429 cools that account and returns busy without caching an empty result", async () => {
  const f = fixture({ tidalResponse: async () => new Response("{}", { status: 429, headers: { "Retry-After": "60" } }) });
  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/search?q=example"), f.env);
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { error: "busy" });
    assert.equal(response.headers.get("retry-after"), "300");
  });
  assert.equal(f.calls.length, 1);
  assert.equal(f.responseCache.size, 0);
  assert.ok(f.dbWrites.some(({ sql, args }) => sql.includes("SET cooling_until") && args[0] >= Math.floor(Date.now() / 1000) + 899));
});

test("Tidal playback-info 429 cools that account and returns busy without caching a track miss", async () => {
  const f = fixture({ tidalResponse: async () => new Response("{}", { status: 429, headers: { "Retry-After": "60" } }) });
  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { error: "busy" });
    assert.equal(response.headers.get("retry-after"), "300");
  });
  assert.equal(f.calls.length, 1, "a 429 is not retried across qualities or regions");
  assert.equal(f.responseCache.size, 0);
  assert.ok(f.dbWrites.some(({ sql, args }) => sql.includes("SET cooling_until") && args[0] >= Math.floor(Date.now() / 1000) + 899));
});

test("global daily stream cap allows requests to the cap and blocks the next one", async () => {
  const accounts = Array.from({ length: 4 }, (_, i) => ({ ...liveAccount(), id: `account-${i}` }));
  const f = fixture({ accounts, tidalResponse: async () => losslessResponse() });
  f.env.GLOBAL_DAILY_CAP = "2";
  f.env.NETWORK_DAILY_CAP = "10";
  f.env.IP_SALT = "test-salt";
  const headers = { "CF-Connecting-IP": "192.0.2.15" };

  await withFetch(null, f.fetchTidal, async () => {
    assert.equal((await worker.fetch(authorizedRequest("/stream/td:321", "GET", headers), f.env)).status, 200);
    assert.equal((await worker.fetch(authorizedRequest("/stream/td:322", "GET", headers), f.env)).status, 200);
    const limited = await worker.fetch(authorizedRequest("/stream/td:323", "GET", headers), f.env);
    assert.equal(limited.status, 503);
    assert.deepEqual(await limited.json(), { error: "global_daily_cap" });
  });

  const day = new Date().toISOString().slice(0, 10);
  const networkKey = `n:${networkTag(f.env, networkOf(headers["CF-Connecting-IP"]))}`;
  assert.equal(f.dailyQuotas.get(`${day}:global`), 2);
  assert.equal(f.dailyQuotas.get(`${day}:${networkKey}`), 2);
});

test("per-network daily cap is salted, shared across installs, and isolates other networks", async () => {
  const accounts = Array.from({ length: 4 }, (_, i) => ({ ...liveAccount(), id: `account-${i}` }));
  const f = fixture({ accounts, tidalResponse: async () => losslessResponse() });
  f.env.GLOBAL_DAILY_CAP = "10";
  f.env.NETWORK_DAILY_CAP = "1";
  f.env.IP_SALT = "test-salt";
  await withFetch(null, f.fetchTidal, async () => {
    assert.equal((await worker.fetch(authorizedRequest("/stream/td:321", "GET", { "CF-Connecting-IP": "192.0.2.15" }), f.env)).status, 200);
    const sameNetwork = await worker.fetch(authorizedRequest("/stream/td:322", "GET", { "CF-Connecting-IP": "192.0.2.15" }), f.env);
    assert.equal(sameNetwork.status, 429);
    assert.deepEqual(await sameNetwork.json(), { error: "network_daily_cap" });
    assert.equal((await worker.fetch(authorizedRequest("/stream/td:323", "GET", { "CF-Connecting-IP": "192.0.2.16" }), f.env)).status, 200);
  });
  assert.equal([...f.dailyQuotas.keys()].filter((key) => key.endsWith(":global")).length, 1);
  assert.equal([...f.dailyQuotas.values()].reduce((sum, n) => sum + n, 0), 4);
});

test("search spends an account slot but not global or network daily stream quota", async () => {
  const f = fixture();
  f.env.IP_SALT = "test-salt";
  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/search?q=example", "GET", { "CF-Connecting-IP": "192.0.2.15" }), f.env);
    assert.equal(response.status, 200);
  });
  assert.equal(f.accountCaps.length, 1);
  assert.equal(f.dailyQuotas.size, 0);
});

test("search maps Tidal catalog entries to the relay contract", async () => {
  const f = fixture({ tidalResponse: async () => new Response(JSON.stringify({ items: [{
    id: 321,
    title: "Example",
    artist: { name: "Artist" },
    album: { title: "Album", cover: "abc-def" },
    duration: 180,
    audioQuality: "LOSSLESS",
    isrc: "USABC1234567",
  }] }), { status: 200 }) });

  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/search?q=example"), f.env);
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.tracks[0].id, "td:321");
    assert.equal(body.tracks[0].audioQuality, "LOSSLESS");
    assert.equal(body.tracks[0].format, "flac");
    assert.equal(f.calls.length, 1);
  });
});

test("search cache is shared across normalized query variants for five days", async () => {
  const f = fixture({ tidalResponse: async () => new Response(JSON.stringify({ items: [{ id: 88, title: "Birds" }] }), { status: 200 }) });
  await withFetch(null, f.fetchTidal, async () => {
    const first = await worker.fetch(authorizedRequest("/search?q=%20%20Birds%20%20of%20%20Feather%20"), f.env);
    const second = await worker.fetch(authorizedRequest("/search?q=birds%20of%20feather"), f.env);
    assert.deepEqual(await first.json(), await second.json());
  });
  assert.equal(f.calls.length, 1);
  assert.equal(f.accountCaps.length, 1);
  const [key, entry] = [...f.responseCache.entries()][0];
  assert.match(key, /^search:US:[a-f0-9]{64}$/);
  assert.equal(key.includes("birds"), false, "the normalized query is not stored as a cache key");
  assert.ok(entry.expires_at >= Math.floor(Date.now() / 1000) + 5 * 86400 - 2);
});

test("stream continues from a downgraded Hi Res response to a Lossless request", async () => {
  const direct = JSON.stringify({ mimeType: "audio/flac", codecs: "flac", urls: ["https://cdn.example/track.flac"] });
  const f = fixture({ tidalResponse: async (url) => {
    const quality = new URL(url).searchParams.get("audioquality");
    return playbackInfo(quality === "HI_RES_LOSSLESS" ? "HIGH" : "LOSSLESS", "application/vnd.tidal.bts", direct);
  } });

  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:321?client=media3"), f.env);
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.codec, "flac");
    assert.equal(body.url, "https://cdn.example/track.flac");
    assert.ok(f.calls.some(({ url }) => new URL(url).searchParams.get("audioquality") === "LOSSLESS"));
  });
});

test("successful stream answers are shared only until shortly before the Tidal URL expires", async () => {
  const expiry = Math.floor(Date.now() / 1000) + 1800;
  const direct = JSON.stringify({ mimeType: "audio/flac", codecs: "flac", urls: [`https://cdn.example/track.flac?token=${expiry}~sig`] });
  const f = fixture({ tidalResponse: async (url) => {
    const quality = new URL(url).searchParams.get("audioquality");
    return playbackInfo(quality === "HI_RES_LOSSLESS" ? "HI_RES_LOSSLESS" : "LOSSLESS", "application/vnd.tidal.bts", direct);
  } });
  await withFetch(null, f.fetchTidal, async () => {
    const first = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    const firstBody = await first.json();
    const firstCallCount = f.calls.length;
    const second = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.deepEqual(await second.json(), firstBody);
    assert.equal(f.calls.length, firstCallCount);
  });
  assert.equal(f.accountCaps.length, 1);
  const entry = [...f.responseCache.values()][0];
  assert.ok(entry.expires_at <= expiry - 60);
  assert.ok(entry.expires_at > Math.floor(Date.now() / 1000));
});

test("track-level lossless refusals are cached across app requests", async () => {
  const accounts = [liveAccount(), { ...liveAccount(), id: "second-account" }];
  const f = fixture({ accounts, tidalResponse: async () => playbackInfo("HIGH", "application/vnd.tidal.bts", "{}") });
  await withFetch(null, f.fetchTidal, async () => {
    const first = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.deepEqual(await first.json(), { error: "lossless_unavailable" });
    const callsAfterMiss = f.calls.length;
    const second = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.deepEqual(await second.json(), { error: "lossless_unavailable" });
    assert.equal(f.calls.length, callsAfterMiss);
  });
  assert.equal(f.responseCache.size, 1);
  assert.equal(f.accountCaps.length, 2, "the second request spends a relay request quota but takes no Tidal account");
});

test("stream rejects a Tidal response below lossless quality", async () => {
  const direct = JSON.stringify({ mimeType: "audio/flac", codecs: "flac", urls: ["https://cdn.example/track.flac"] });
  const accounts = [liveAccount(), { ...liveAccount(), id: "second-account" }];
  const f = fixture({ accounts, tidalResponse: async () => playbackInfo("HIGH", "application/vnd.tidal.bts", direct) });

  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.equal(response.status, 404);
    assert.deepEqual(await response.json(), { error: "lossless_unavailable" });
    assert.ok(f.calls.length > 1); // retries other quality/region combinations
  });
});

test("two accounts declining the same track are not cooled", async () => {
  const direct = JSON.stringify({ mimeType: "audio/flac", codecs: "flac", urls: ["https://cdn.example/track.flac"] });
  const accounts = [liveAccount(), { ...liveAccount(), id: "second-account", access_token: "second-token" }];
  const f = fixture({ accounts, tidalResponse: async () => playbackInfo("HIGH", "application/vnd.tidal.bts", direct) });

  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.equal(response.status, 404);
    assert.deepEqual(await response.json(), { error: "lossless_unavailable" });
    assert.equal(f.dbWrites.some(({ sql }) => sql.includes("SET cooling_until")), false);
  });
});

test("a single-account track-level quality refusal returns busy without cooling the account", async () => {
  const direct = JSON.stringify({ mimeType: "audio/flac", codecs: "flac", urls: ["https://cdn.example/track.flac"] });
  const f = fixture({ tidalResponse: async () => playbackInfo("HIGH", "application/vnd.tidal.bts", direct) });

  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { error: "busy" });
    assert.equal(response.headers.get("retry-after"), "300");
    assert.equal(f.dbWrites.some(({ sql }) => sql.includes("SET cooling_until")), false);
  });
});

test("a refusal without a second account to cross-check returns busy instead of caching a false miss", async () => {
  const f = fixture({ tidalResponse: async () => playbackInfo("HIGH", "application/vnd.tidal.bts", "{}") });
  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { error: "busy" });
  });
  assert.equal(f.responseCache.size, 0);
});

test("stream cross-checks a downgraded account against the next account, then cools the refuser", async () => {
  const direct = JSON.stringify({ mimeType: "audio/flac", codecs: "flac", urls: ["https://cdn.example/track.flac"] });
  const accounts = [liveAccount(), { ...liveAccount(), id: "reserve-account", access_token: "reserve-token" }];
  const f = fixture({ accounts, tidalResponse: async (_url, options) => {
    const isReserve = options.headers.authorization === "Bearer reserve-token";
    return playbackInfo(isReserve ? "LOSSLESS" : "HIGH", "application/vnd.tidal.bts", direct);
  } });

  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.equal(response.status, 200);
    assert.ok(f.dbWrites.some(({ sql, args }) => sql.includes("cooling_until") && args[1] === "test-account" && args[0] >= Math.floor(Date.now() / 1000) + 899));
  });
});

test("an invalid-session account is quarantined and the next account serves", async () => {
  const direct = JSON.stringify({ mimeType: "audio/flac", codecs: "flac", urls: ["https://cdn.example/track.flac"] });
  const accounts = [liveAccount(), { ...liveAccount(), id: "reserve-account", access_token: "reserve-token" }];
  const f = fixture({ accounts, tidalResponse: async (_url, options) => {
    if (options.headers.authorization === "Bearer test-tidal-access-token") return new Response("{}", { status: 401 });
    return playbackInfo("LOSSLESS", "application/vnd.tidal.bts", direct);
  } });

  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.equal(response.status, 200);
    assert.ok(f.dbWrites.some(({ sql, args }) => sql.includes("state='dead'") && args[1] === "test-account"));
  });
});

test("Tidal subStatus 4005 without another account returns busy and never retires the account", async () => {
  const f = fixture({ tidalResponse: async () => new Response(JSON.stringify({ subStatus: 4005 }), { status: 401 }) });

  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { error: "busy" });
    assert.equal(f.dbWrites.some(({ sql }) => sql.includes("state='dead'")), false);
  });
});

test("?aac=only retries a 4005 refusal on another account for a Hi-Res track", async () => {
  const accounts = [liveAccount(), { ...liveAccount(), id: "second-account", access_token: "second-token" }];
  const aac = JSON.stringify({ mimeType: "audio/mp4", codecs: "mp4a.40.2", urls: ["https://cdn.example/track.m4a"] });
  const f = fixture({ accounts, tidalResponse: async (url, options) => {
    if (options.headers.authorization === "Bearer test-tidal-access-token") return new Response(JSON.stringify({ subStatus: 4005 }), { status: 401 });
    if (url.includes("playbackinfopostpaywall")) return playbackInfo("HIGH", "application/vnd.tidal.bts", aac);
    return new Response(JSON.stringify({ mediaMetadata: { tags: ["HIRES_LOSSLESS"] } }), { status: 200 });
  } });

  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:363236466?aac=only"), f.env);
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.codec, "aac");
    assert.equal(body.hires, true);
    assert.ok(f.calls.some(({ options }) => options.headers.authorization === "Bearer second-token"));
    assert.equal(f.dbWrites.some(({ sql }) => sql.includes("SET cooling_until") || sql.includes("state='dead'")), false);
  });
});

test("?web=1 retries a 4005 refusal on another account and returns signed Hi-Res links", async () => {
  const accounts = [liveAccount(), { ...liveAccount(), id: "second-account", access_token: "second-token" }];
  const f = fixture({ accounts, tidalResponse: async (_url, options) => {
    if (options.headers.authorization === "Bearer test-tidal-access-token") return new Response(JSON.stringify({ subStatus: 4005 }), { status: 401 });
    return playbackInfo("HI_RES_LOSSLESS", "application/dash+xml", MPD);
  } });
  Object.assign(f.env, { EDGE_SECRET: "e".repeat(64), EDGE_BASE: "https://tidal-edge.example.deno.net" });

  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:363236466?web=1"), f.env);
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.edge, true);
    assert.equal(body.codec, "flac");
    assert.match(body.url, /^https:\/\/tidal-edge\.example\.deno\.net\/t\//);
    assert.ok(f.calls.some(({ options }) => options.headers.authorization === "Bearer second-token"));
    assert.equal(f.dbWrites.some(({ sql }) => sql.includes("SET cooling_until") || sql.includes("state='dead'")), false);
  });
});

test("?aac=1 retries a 4005 refusal on another account and preserves the app AAC fallback", async () => {
  const accounts = [liveAccount(), { ...liveAccount(), id: "second-account", access_token: "second-token" }];
  const aac = JSON.stringify({ mimeType: "audio/mp4", codecs: "mp4a.40.2", urls: ["https://cdn.example/track.m4a"] });
  const f = fixture({ accounts, tidalResponse: async (_url, options) => {
    if (options.headers.authorization === "Bearer test-tidal-access-token") return new Response(JSON.stringify({ subStatus: 4005 }), { status: 401 });
    return playbackInfo("HIGH", "application/vnd.tidal.bts", aac);
  } });

  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:1550546?aac=1"), f.env);
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.codec, "aac");
    assert.equal(body.bitrate, 320);
    assert.ok(f.calls.some(({ options }) => options.headers.authorization === "Bearer second-token"));
    assert.equal(f.dbWrites.some(({ sql }) => sql.includes("SET cooling_until") || sql.includes("state='dead'")), false);
  });
});

test("DASH manifest route passes Tidal's init URL through unchanged", async () => {
  const f = fixture({ tidalResponse: async () => playbackInfo("HI_RES_LOSSLESS", "application/dash+xml", MPD) });

  await withFetch(null, f.fetchTidal, async () => {
    const stream = await worker.fetch(authorizedRequest("/stream/td:654?client=media3"), f.env);
    assert.equal(stream.status, 200);
    const streamBody = await stream.json();
    assert.equal(streamBody.manifest, "dash");

    const manifest = await worker.fetch(new Request(streamBody.url), f.env);
    assert.equal(manifest.status, 200);
    assert.equal(manifest.headers.get("content-type"), "application/dash+xml");
    assert.equal(await manifest.text(), MPD);
    assert.equal(f.calls.length, 1); // serving the MPD does not proxy the CDN init segment
  });
});

test("DASH stream cache expires before the signed Tidal segment URLs", async () => {
  const tidalExpiry = Math.floor(Date.now() / 1000) + 1200;
  const mpd = `<MPD><SegmentTemplate initialization="https://audio.tidal.com/init.mp4?token=${tidalExpiry}~sig&amp;x=1" media="https://audio.tidal.com/$Number$?token=${tidalExpiry}~sig" /></MPD>`;
  const f = fixture({ tidalResponse: async () => playbackInfo("HI_RES_LOSSLESS", "application/dash+xml", mpd) });
  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:655"), f.env);
    const body = await response.json();
    assert.equal(response.status, 200);
    assert.equal(Number(new URL(body.url).searchParams.get("exp")), tidalExpiry - 30);
  });
  const entry = [...f.responseCache.values()][0];
  assert.ok(entry.expires_at <= tidalExpiry - 90);
  assert.ok(entry.expires_at > Math.floor(Date.now() / 1000));
});

const EDGE = { EDGE_SECRET: "e".repeat(64), EDGE_BASE: "https://tidal-edge.example.deno.net" };
const decodeToken = (url) => JSON.parse(Buffer.from(new URL(url).pathname.split("/")[2].split(".")[0], "base64url").toString());

test("?web=1 answers a DASH track with signed edge URLs and writes nothing to D1", async () => {
  const f = fixture({ tidalResponse: async () => playbackInfo("HI_RES_LOSSLESS", "application/dash+xml", MPD) });
  Object.assign(f.env, EDGE);
  await withFetch(null, f.fetchTidal, async () => {
    const r = await worker.fetch(authorizedRequest("/stream/td:654?web=1"), f.env);
    assert.equal(r.status, 200);
    const b = await r.json();
    assert.equal(b.edge, true);
    assert.equal(b.manifest, "dash");
    assert.match(b.url, /^https:\/\/tidal-edge\.example\.deno\.net\/t\/[\w-]+\.[\w-]+\/manifest\.mpd$/);
    assert.match(b.download, /\/file\.flac$/);
    assert.equal(b.sampleRate, 96000);
    const p = decodeToken(b.download);
    assert.equal(p.k, "d");
    assert.equal(p.x, MPD);
    assert.ok(p.exp > Date.now() / 1000 && p.exp <= Date.now() / 1000 + 3500);
    assert.equal(b.expiresAt, p.exp);
    assert.equal(f.dbWrites.filter(({ sql }) => sql.includes("tidal_dash_manifests")).length, 0);
  });
});

test("?web=1 token signature is what the edge verifies (HMAC-SHA256 over the base64url body)", async () => {
  const { edgeToken } = await import("../src/index.js");
  const t = await edgeToken(EDGE.EDGE_SECRET, { v: 1, k: "p", exp: 1, u: "https://x.audio.tidal.com/a.flac" });
  const [body, sig] = t.split(".");
  const { createHmac } = await import("node:crypto");
  assert.equal(sig, createHmac("sha256", EDGE.EDGE_SECRET).update(body).digest("base64url"));
});

test("?web=1 progressive FLAC uses stream.flac; a progressive .mp4 FLAC is refused", async () => {
  const flac = JSON.stringify({ mimeType: "audio/flac", codecs: "flac", urls: ["https://sp-pr-cf.audio.tidal.com/a.flac?token=1~x"] });
  const f = fixture({ tidalResponse: async () => playbackInfo("LOSSLESS", "application/vnd.tidal.bts", flac) });
  Object.assign(f.env, EDGE);
  await withFetch(null, f.fetchTidal, async () => {
    const b = await (await worker.fetch(authorizedRequest("/stream/td:7?web=1"), f.env)).json();
    assert.match(b.url, /\/stream\.flac$/);
    assert.equal(decodeToken(b.url).u, "https://sp-pr-cf.audio.tidal.com/a.flac?token=1~x");
  });
  const mp4 = JSON.stringify({ mimeType: "audio/mp4", codecs: "flac", urls: ["https://sp-pr-cf.audio.tidal.com/a.mp4"] });
  const g = fixture({ tidalResponse: async () => playbackInfo("LOSSLESS", "application/vnd.tidal.bts", mp4) });
  Object.assign(g.env, EDGE);
  await withFetch(null, g.fetchTidal, async () => {
    assert.equal((await worker.fetch(authorizedRequest("/stream/td:7?web=1"), g.env)).status, 404);
  });
});

test("?web=1 without an edge configured keeps the normal answer; no ?web keeps it too", async () => {
  const cases = [[{}, "/stream/td:654?web=1"], [EDGE, "/stream/td:654"], [{ EDGE_SECRET: "short", EDGE_BASE: EDGE.EDGE_BASE }, "/stream/td:654?web=1"], [{ ...EDGE, EDGE_BASE: "http://evil.example" }, "/stream/td:654?web=1"]];
  for (const [env, path] of cases) {
    const f = fixture({ tidalResponse: async () => playbackInfo("HI_RES_LOSSLESS", "application/dash+xml", MPD) });
    Object.assign(f.env, env);
    await withFetch(null, f.fetchTidal, async () => {
      const b = await (await worker.fetch(authorizedRequest(path), f.env)).json();
      assert.equal(b.edge, undefined);
      assert.match(b.url, /^https:\/\/relay\.example\/dash\/td\/654\.mpd/);
    });
  }
});

function aacResponse() {
  return playbackInfo("HIGH", "application/vnd.tidal.bts",
    JSON.stringify({ mimeType: "audio/mp4", codecs: "mp4a.40.2", urls: ["https://cdn.example/track.m4a"] }));
}

/** Tidal stub: AAC playbackinfo plus a /tracks/<id> metadata answer from `meta(url)`. */
function aacWithMeta(meta) {
  return (url) => (url.includes("/playbackinfopostpaywall") ? aacResponse() : meta(url));
}
const metaCalls = (f) => f.calls.filter((c) => /\/tracks\/\d+\?/.test(c.url));

test("?aac=only reports hires=true from mediaMetadata HIRES_LOSSLESS, then serves it from the cache", async () => {
  const f = fixture({ accounts: [liveAccount(), liveAccount()], tidalResponse: aacWithMeta(() => new Response(JSON.stringify({ audioQuality: "LOSSLESS", mediaMetadata: { tags: ["LOSSLESS", "HIRES_LOSSLESS"] } }), { status: 200 })) });
  await withFetch(null, f.fetchTidal, async () => {
    const first = await (await worker.fetch(authorizedRequest("/stream/42?aac=only"), f.env)).json();
    assert.equal(first.codec, "aac");
    assert.equal(first.hires, true);
    assert.equal(metaCalls(f).length, 1);
    assert.match(metaCalls(f)[0].url, /\/tracks\/42\?countryCode=US$/);

    const second = await (await worker.fetch(authorizedRequest("/stream/42?aac=only"), f.env)).json();
    assert.equal(second.hires, true);
    assert.equal(metaCalls(f).length, 1, "cache hit makes no metadata call");
  });
  // One daily stream-quota unit per stream; the metadata lookup adds none.
  assert.equal(f.dailyQuotas.get(`${new Date().toISOString().slice(0, 10)}:global`), 2);
});

test("?aac=only accepts audioQuality HI_RES_LOSSLESS as hires", async () => {
  const f = fixture({ tidalResponse: aacWithMeta(() => new Response(JSON.stringify({ audioQuality: "HI_RES_LOSSLESS" }), { status: 200 })) });
  await withFetch(null, f.fetchTidal, async () => {
    const body = await (await worker.fetch(authorizedRequest("/stream/7?aac=only"), f.env)).json();
    assert.equal(body.hires, true);
  });
});

test("?aac=only reports hires=false for a CD-quality track and caches the false", async () => {
  const f = fixture({ accounts: [liveAccount(), liveAccount()], tidalResponse: aacWithMeta(() => new Response(JSON.stringify({ audioQuality: "LOSSLESS", mediaMetadata: { tags: ["LOSSLESS"] } }), { status: 200 })) });
  await withFetch(null, f.fetchTidal, async () => {
    assert.equal((await (await worker.fetch(authorizedRequest("/stream/8?aac=only"), f.env)).json()).hires, false);
    assert.equal((await (await worker.fetch(authorizedRequest("/stream/8?aac=only"), f.env)).json()).hires, false);
    assert.equal(metaCalls(f).length, 1);
  });
  assert.deepEqual(f.hiresCache.get("8").hires, 0);
});

test("?aac=only omits hires when metadata fails, is unparseable, or throws; playback still answers", async () => {
  for (const meta of [
    () => new Response("", { status: 500 }),
    () => new Response("not json", { status: 200 }),
    () => new Response(JSON.stringify({ title: "no quality fields" }), { status: 200 }),
    () => { throw new TypeError("network down"); },
  ]) {
    const f = fixture({ tidalResponse: aacWithMeta(meta) });
    await withFetch(null, f.fetchTidal, async () => {
      const res = await worker.fetch(authorizedRequest("/stream/9?aac=only"), f.env);
      assert.equal(res.status, 200);
      const body = await res.json();
      assert.equal(body.codec, "aac");
      assert.equal("hires" in body, false);
    });
    assert.equal(f.hiresCache.size, 0, "unknown is never cached");
  }
});

test("only ?aac=only looks up hires; lossless streams make no metadata call", async () => {
  const f = fixture({ tidalResponse: () => losslessResponse() });
  await withFetch(null, f.fetchTidal, async () => {
    const body = await (await worker.fetch(authorizedRequest("/stream/10"), f.env)).json();
    assert.equal("hires" in body, false);
    assert.equal(metaCalls(f).length, 0);
  });
});
