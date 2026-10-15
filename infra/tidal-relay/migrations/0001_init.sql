CREATE TABLE tidal_accounts (
  id                 TEXT PRIMARY KEY,
  access_token       TEXT NOT NULL,
  refresh_token      TEXT NOT NULL,
  token_expires_at   INTEGER NOT NULL DEFAULT 0,
  user_id            TEXT NOT NULL DEFAULT '',
  state              TEXT NOT NULL DEFAULT 'live',
  hour_key           TEXT NOT NULL DEFAULT '',
  hour_n             INTEGER NOT NULL DEFAULT 0,
  day_key            TEXT NOT NULL DEFAULT '',
  day_n              INTEGER NOT NULL DEFAULT 0,
  generation         INTEGER NOT NULL DEFAULT 1,
  updated_at         INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE tidal_dash_manifests (
  track_id           TEXT PRIMARY KEY,
  manifest_xml       TEXT NOT NULL,
  init_url           TEXT NOT NULL,
  bit_depth          INTEGER NOT NULL DEFAULT 16,
  sample_rate        INTEGER NOT NULL DEFAULT 44100,
  expires_at         INTEGER NOT NULL
);

CREATE INDEX tidal_dash_manifests_expiry ON tidal_dash_manifests(expires_at);
