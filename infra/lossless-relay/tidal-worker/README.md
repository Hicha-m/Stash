# Stash Tidal Relay Worker

Separate Cloudflare Worker implementing the Tidal subset of BitChord's relay contract. It uses D1 for OAuth refresh tokens, one-time device grants, playback request caps, and short-lived DASH state. Audio bytes remain on Tidal's CDN; only the DASH fMP4 initialization segment passes through this Worker so its zero FLAC sample-rate field can be patched for Media3.

## Contract

- `GET /health` → `{ "ok": true, "provider": "tidal" }`
- `GET /search?q=...` with `X-Access-Token` → `{ "tracks": [{ "id": "td:<id>", ... }] }`
- `GET /stream/td:<id>?client=media3` with `X-Access-Token` → progressive FLAC URL or a signed DASH MPD URL, with the BitChord response fields.
- Signed `GET /dash/td/<id>.mpd` and `GET /dash/td/<id>/init.mp4` URLs need no header; their HMAC signatures expire after one hour.
- Admin-only `POST /admin/device/start`, then `POST /admin/device/poll` with `{ "user_code": "..." }` starts and completes the Tidal OAuth device flow. These require `X-Access-Token: <ADMIN_TOKEN>`.

Tidal accounts are linked through the device flow; passwords and OAuth tokens are never printed. The pool rotates least-recently-used live accounts, with a 100 requests/hour cap during ramp, a later target of 250/hour, and 1,600/day per account. It applies cooldowns for transient failures and quarantines dead accounts after an invalid-session 401. A lower-than-lossless answer is final (no cross-check on another account), and Tidal's `subStatus=4005` is a track/rights refusal (`not_available`) that never kills an account. When no account is available, search and stream return `503 busy`: `Retry-After: 300` when seats are capped or cooling, `Retry-After: 15` when seats are only waiting out playback pacing.

Protective limits mirror the Qobuz relay: 34,667 answered `/stream` requests/day shared across the pool, 2,000/day per network, and 40 requests/60 seconds per network for provider/admin routes. The network key is the IPv4 address or IPv6 `/56`, hashed with the private `IP_SALT`; raw IP addresses are not written to D1. `IP_SALT` is required for the daily network cap; without it only the shared global cap and request limiter apply. `/health` is exempt from the request limiter. There is no per-install daily cap: the Tidal app client sends only `X-Access-Token`, not an install identifier. `/search` spends a per-account hourly/daily slot through account selection and is subject to the request-rate limiter, but does not spend global or network daily stream quota. Each `/stream` spends at most one global/network quota unit for a successful response or definitive track-level 404, even if account rotation queried two accounts; upstream failures and `no_account` do not spend daily quota. These daily counters reset at midnight UTC and are pruned hourly.

Run `npm run tidal:login` to add a live account or `npm run tidal:login -- --reserve` to add an account held out of rotation. Promote a reserve in D1 with `UPDATE tidal_accounts SET state='live' WHERE id='<account_id>';`; move a live account back with `state='reserve'`. Reauthorization restores that account to the selected state. An operator can inspect pool health without exposing tokens using `SELECT id,state,hour_n,day_n,last_used_at,cooling_until,dead_reason FROM tidal_accounts;`.

## Upstream budget

One `/stream` cache miss makes **one** upstream `playbackinfopostpaywall` call: `countryCode=TIDAL_COUNTRY_CODE` (default `US`) and `audioquality=HI_RES_LOSSLESS` (`HIGH` for `?aac=only`). Tidal answers with the best quality available up to that level; with this client ID CD-only tracks come back `HIGH` (AAC), which is final (`lossless_unavailable`, or AAC under `?aac=1`). There is no quality/country walk and no second-account retry for a track-level answer; a second seat is tried only after a seat-level failure (invalid session, failed refresh, Tidal 5xx). `?aac=only` adds one `/tracks/<id>` metadata call on a hires-cache miss.

Every upstream call counts in the seat's `hour_n`/`day_n`: `takeAccount` counts the first, `bumpSeat` counts the metadata call and a post-refresh retry, so the caps reflect real Tidal load.

Playback pacing: a seat serves at most one playback lookup per `TIDAL_PLAYBACK_MIN_INTERVAL_SECONDS` (default 12, column `last_playback_at`, migration 0007). Searches and metadata lookups are never paced and never stamp it.

## Deployment

Use Wrangler while authenticated to the Cloudflare account that should own this Worker. Create the database, copy the returned ID to `wrangler.toml`, and apply the migration:

```powershell
npx wrangler d1 create stash-tidal-relay
npx wrangler d1 migrations apply stash-tidal-relay --remote
```

Set a long random `ACCESS_TOKEN`, an unrelated private `ADMIN_TOKEN`, `TIDAL_CLIENT_SECRET`, and random private `IP_SALT` as Wrangler secrets. The Tidal client ID and secret come from the selected [BitChord community implementation](https://github.com/R1Blmmakan/music_addon/blob/main/.env.example), not the TIDAL Developer Portal. Keep these values out of `wrangler.toml` and source control. Apply D1 migrations with `npm run db:migrate`, then deploy and note the `workers.dev` origin, or configure a custom HTTPS hostname and set `PUBLIC_HOST` to that exact origin.

After deploying, set `TIDAL_RELAY_URL` and `TIDAL_ADMIN_TOKEN` in a local shell and run `npm run tidal:login` (append `-- --reserve` to hold an account in reserve). The script prints the verification URL and code, waits while the user approves in Tidal, then polls until authorization completes. It never reads or prints a password or OAuth token. Then verify `/health`, `/search`, and `/stream` with the app access token. Publish `tidal_relay_url` and `tidal_relay_token` using the signed-config publisher only after those checks pass.

## Web FLAC lane (`?web=1`)

`GET /stream/td:<id>?web=1` answers lossless tracks with URLs on the **tidal-edge** service (Deno Deploy; Tidal's CDN
refuses Cloudflare egress and browser Origins): `{ url: <edge>/t/<token>/manifest.mpd | stream.flac, download:
<edge>/t/<token>/file.flac, codec: "flac", manifest: "dash" | "none", edge: true, expiresAt, bitDepth, sampleRate }`.
The token is base64url(JSON) "." base64url(HMAC-SHA256(EDGE_SECRET, body)) and carries the Tidal URLs / MPD itself
(no D1 write). Needs `wrangler secret put EDGE_SECRET` (same value as the edge) and the var `EDGE_BASE`
(e.g. `https://tidal-edge.<org>.deno.net`). Without both, `?web=1` is ignored and the normal answer is returned.

## `?aac=only` and `hires`

`/stream/<id>?aac=only` (the web player) answers Tidal's 320 kbps AAC and adds
`hires: true|false` = whether the track has a Hi-Res lossless master (the only kind
this client can fetch as FLAC). It is read from `GET /tracks/<id>` with the account
already taken for the stream (no extra account slot, no extra daily stream quota) and
cached in D1 table `tidal_hires_cache` (migration 0004) for 30 days. D1, not the Cache
API: `cache.put` is a no-op on `*.workers.dev`. If the lookup fails, `hires` is omitted.

## Shared response cache

Search results are shared in D1 for five days by country and a SHA-256 digest of the
trimmed, case-folded, whitespace-collapsed query. Definitive per-track
`lossless_unavailable` and `not_available` answers are cached for five days per stream
mode. Successful stream answers are cached only until 60 seconds before the earliest
expiry exposed by the signed playback URL. Requests still pass the relay's caller and
daily quota checks; a D1 cache hit does not consume a Tidal account slot.
Apply with `wrangler d1 migrations apply` before deploying.

## Cautious pool ramp

The restart cap is configured to 100 requests/hour per account with a 1,600/day ceiling.
Only raise the hourly cap to 250 after 3–4 hours without 429s. With the relay unpaused,
the first cache miss starts a shared 10-minute soft-open window; 50% of uncached search
and stream requests get `503 busy` with a five-minute retry. Cache hits continue to be
served. After a later paused interval, reset the one-shot window with
`DELETE FROM tidal_soft_open_state WHERE id=1` before reopening.

Every upstream 429 is logged with the account suffix and its hour/day counters, and is
stored in D1 for 30 days in `tidal_upstream_429_events`. Inspect recent events with:

```sql
SELECT substr(account_id, -4) AS account, endpoint, hour_n, day_n,
       datetime(occurred_at, 'unixepoch') AS occurred_at
FROM tidal_upstream_429_events ORDER BY occurred_at DESC;
```
