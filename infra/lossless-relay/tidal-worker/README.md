# Stash Tidal Relay Worker

Separate Cloudflare Worker implementing the Tidal subset of BitChord's relay contract. It uses D1 for OAuth refresh tokens, one-time device grants, playback request caps, and short-lived DASH state. Audio bytes remain on Tidal's CDN; only the DASH fMP4 initialization segment passes through this Worker so its zero FLAC sample-rate field can be patched for Media3.

## Contract

- `GET /health` → `{ "ok": true, "provider": "tidal" }`
- `GET /search?q=...` with `X-Access-Token` → `{ "tracks": [{ "id": "td:<id>", ... }] }`
- `GET /stream/td:<id>?client=media3` with `X-Access-Token` → progressive FLAC URL or a signed DASH MPD URL, with the BitChord response fields.
- Signed `GET /dash/td/<id>.mpd` and `GET /dash/td/<id>/init.mp4` URLs need no header; their HMAC signatures expire after one hour.
- Admin-only `POST /admin/device/start`, then `POST /admin/device/poll` with `{ "user_code": "..." }` starts and completes the Tidal OAuth device flow. These require `X-Access-Token: <ADMIN_TOKEN>`.

The Tidal account is created through the device flow; passwords are never collected. The D1 schema supports multiple account rows, but this proof of concept uses one account. `TIDAL_ACCOUNT_HOURLY_CAP` and `TIDAL_ACCOUNT_DAILY_CAP` cap account selections. The Cloudflare Rate Limit binding caps all HTTP requests. Expand to multiple OAuth-approved accounts later for rotation.

## Deployment

Use Wrangler while authenticated to the Cloudflare account that should own this Worker. Create the database, copy the returned ID to `wrangler.toml`, and apply the migration:

```powershell
npx wrangler d1 create stash-tidal-relay
npx wrangler d1 migrations apply stash-tidal-relay --remote
```

Set a long random `ACCESS_TOKEN`, an unrelated private `ADMIN_TOKEN`, and `TIDAL_CLIENT_SECRET` as Wrangler secrets. The Tidal client ID and secret come from the selected [BitChord community implementation](https://github.com/R1Blmmakan/music_addon/blob/main/.env.example), not the TIDAL Developer Portal. Keep these values out of `wrangler.toml` and source control. Deploy and note the `workers.dev` origin, or configure a custom HTTPS hostname and set `PUBLIC_HOST` to that exact origin.

After deploying, set `TIDAL_RELAY_URL` and `TIDAL_ADMIN_TOKEN` in a local shell and run `npm run tidal:login`. The script prints the verification URL and code, waits while the user approves in Tidal, then polls until authorization completes. It never reads or prints a password or OAuth token. Then verify `/health`, `/search`, and `/stream` with the app access token. Publish `tidal_relay_url` and `tidal_relay_token` using the signed-config publisher only after those checks pass.
