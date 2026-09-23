/**
 * Schema-Migrationen. Nur anhängen, nie bestehende ändern – jede läuft genau
 * einmal in einer Transaktion (Tabelle `schema_migrations`).
 * Zeitstempel sind Millisekunden seit 1970 (UTC).
 */
export const MIGRATIONS: { version: number, sql: string }[] = [
  {
    version: 1,
    sql: `
CREATE TABLE users (
  uuid TEXT PRIMARY KEY CHECK (length(uuid) = 32),
  name TEXT NOT NULL,
  name_lower TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  last_login_at INTEGER NOT NULL,
  show_badge INTEGER NOT NULL DEFAULT 1,
  show_cape INTEGER NOT NULL DEFAULT 1,
  presence_visibility TEXT NOT NULL DEFAULT 'friends' CHECK (presence_visibility IN ('friends', 'nobody')),
  share_server INTEGER NOT NULL DEFAULT 0,
  active_cape_id TEXT REFERENCES capes(id) ON DELETE SET NULL
);
CREATE INDEX users_name_lower ON users(name_lower);

-- Sperren überleben das Löschen des Kontos (berechtigtes Interesse: kein Umgehen per Neuanmeldung).
CREATE TABLE bans (
  uuid TEXT PRIMARY KEY CHECK (length(uuid) = 32),
  reason TEXT,
  banned_at INTEGER NOT NULL,
  banned_by TEXT NOT NULL
);

CREATE TABLE sessions (
  token_hash TEXT PRIMARY KEY,
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  last_used_at INTEGER NOT NULL
);
CREATE INDEX sessions_uuid ON sessions(uuid);
CREATE INDEX sessions_expires ON sessions(expires_at);

CREATE TABLE challenges (
  server_id TEXT PRIMARY KEY,
  expires_at INTEGER NOT NULL
);
CREATE INDEX challenges_expires ON challenges(expires_at);

CREATE TABLE capes (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL CHECK (kind IN ('builtin', 'upload')),
  name TEXT NOT NULL,
  owner_uuid TEXT REFERENCES users(uuid) ON DELETE CASCADE,
  status TEXT NOT NULL CHECK (status IN ('approved', 'pending', 'rejected')),
  unlock TEXT NOT NULL CHECK (unlock IN ('free', 'code', 'admin', 'owner')),
  sha256 TEXT NOT NULL,
  -- Maße EINES Frames; animierte Umhänge liegen als senkrechter Streifen (frames × height) vor.
  width INTEGER NOT NULL,
  height INTEGER NOT NULL,
  frames INTEGER NOT NULL DEFAULT 1 CHECK (frames >= 1 AND frames <= 64),
  frame_time_ms INTEGER CHECK (frame_time_ms IS NULL OR (frame_time_ms >= 20 AND frame_time_ms <= 10000)),
  sort INTEGER NOT NULL DEFAULT 0,
  retired INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  reviewed_at INTEGER,
  reviewed_by TEXT,
  reject_reason TEXT
);
CREATE INDEX capes_owner ON capes(owner_uuid);
CREATE INDEX capes_status ON capes(status);

CREATE TABLE user_capes (
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  cape_id TEXT NOT NULL REFERENCES capes(id) ON DELETE CASCADE,
  source TEXT NOT NULL CHECK (source IN ('code', 'admin')),
  granted_at INTEGER NOT NULL,
  PRIMARY KEY (uuid, cape_id)
);

CREATE TABLE codes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  code_hash TEXT NOT NULL UNIQUE,
  hint TEXT NOT NULL,
  cape_id TEXT NOT NULL REFERENCES capes(id) ON DELETE CASCADE,
  max_uses INTEGER NOT NULL CHECK (max_uses >= 1),
  uses INTEGER NOT NULL DEFAULT 0,
  expires_at INTEGER,
  revoked_at INTEGER,
  note TEXT,
  created_at INTEGER NOT NULL,
  created_by TEXT NOT NULL
);

CREATE TABLE code_redemptions (
  code_id INTEGER NOT NULL REFERENCES codes(id) ON DELETE CASCADE,
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  redeemed_at INTEGER NOT NULL,
  PRIMARY KEY (code_id, uuid)
);

CREATE TABLE cape_reports (
  cape_id TEXT NOT NULL REFERENCES capes(id) ON DELETE CASCADE,
  reporter_uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  reason TEXT NOT NULL,
  note TEXT,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (cape_id, reporter_uuid)
);

CREATE TABLE friendships (
  a TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  b TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (a, b),
  CHECK (a < b)
);
CREATE INDEX friendships_b ON friendships(b);

CREATE TABLE friend_requests (
  from_uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  to_uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (from_uuid, to_uuid),
  CHECK (from_uuid <> to_uuid)
);
CREATE INDEX friend_requests_to ON friend_requests(to_uuid);

CREATE TABLE blocks (
  blocker TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  blocked TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (blocker, blocked),
  CHECK (blocker <> blocked)
);
CREATE INDEX blocks_blocked ON blocks(blocked);

CREATE TABLE admin_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  at INTEGER NOT NULL,
  actor TEXT NOT NULL,
  action TEXT NOT NULL,
  target TEXT,
  detail TEXT
);
`,
  },
]
