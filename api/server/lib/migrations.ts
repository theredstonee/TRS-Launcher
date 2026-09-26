import type { DatabaseSync } from 'node:sqlite'

/**
 * Schema-Migrationen. Nur anhängen, nie bestehende ändern – jede läuft genau
 * einmal in einer Transaktion (Tabelle `schema_migrations`).
 * Zeitstempel sind Millisekunden seit 1970 (UTC).
 * `sql` = festes Skript; `run` = Code für Schritte, die vom Bestand abhängen (idempotent schreiben).
 */
export interface Migration {
  version: number
  sql?: string
  run?: (db: DatabaseSync) => void
}

export const MIGRATIONS: Migration[] = [
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
  {
    // Kosmetik (Hüte, Flügel, Rücken, Aura) + Emotes; Codes können jetzt auch Kosmetik freischalten.
    version: 2,
    sql: `
ALTER TABLE users ADD COLUMN show_cosmetics INTEGER NOT NULL DEFAULT 1;

CREATE TABLE cosmetics (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL CHECK (kind IN ('builtin', 'upload')),
  slot TEXT NOT NULL CHECK (slot IN ('hat', 'wings', 'back', 'aura', 'emote')),
  -- Vorlage aus assets/cosmetics/templates.json; Emotes haben weder Vorlage noch Textur.
  template TEXT,
  name TEXT NOT NULL,
  owner_uuid TEXT REFERENCES users(uuid) ON DELETE CASCADE,
  status TEXT NOT NULL CHECK (status IN ('approved', 'pending', 'rejected')),
  unlock TEXT NOT NULL CHECK (unlock IN ('free', 'code', 'admin', 'owner')),
  sha256 TEXT,
  -- Maße EINES Frames in Pixeln (Vorlagen-Texturgröße × scale); Frames liegen senkrecht übereinander.
  width INTEGER,
  height INTEGER,
  scale INTEGER CHECK (scale IS NULL OR (scale >= 1 AND scale <= 8)),
  frames INTEGER NOT NULL DEFAULT 1 CHECK (frames >= 1 AND frames <= 64),
  frame_time_ms INTEGER CHECK (frame_time_ms IS NULL OR (frame_time_ms >= 20 AND frame_time_ms <= 10000)),
  emissive INTEGER NOT NULL DEFAULT 0,
  sort INTEGER NOT NULL DEFAULT 0,
  retired INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  reviewed_at INTEGER,
  reviewed_by TEXT,
  reject_reason TEXT,
  CHECK ((slot = 'emote') = (template IS NULL)),
  CHECK ((template IS NULL) = (sha256 IS NULL)),
  CHECK (slot <> 'emote' OR kind = 'builtin')
);
CREATE INDEX cosmetics_owner ON cosmetics(owner_uuid);
CREATE INDEX cosmetics_status ON cosmetics(status);

CREATE TABLE user_cosmetics (
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  cosmetic_id TEXT NOT NULL REFERENCES cosmetics(id) ON DELETE CASCADE,
  source TEXT NOT NULL CHECK (source IN ('code', 'admin')),
  granted_at INTEGER NOT NULL,
  PRIMARY KEY (uuid, cosmetic_id)
);

CREATE TABLE equipped_cosmetics (
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  slot TEXT NOT NULL CHECK (slot IN ('hat', 'wings', 'back', 'aura')),
  cosmetic_id TEXT NOT NULL REFERENCES cosmetics(id) ON DELETE CASCADE,
  PRIMARY KEY (uuid, slot)
);
CREATE INDEX equipped_cosmetics_item ON equipped_cosmetics(cosmetic_id);

CREATE TABLE cosmetic_reports (
  cosmetic_id TEXT NOT NULL REFERENCES cosmetics(id) ON DELETE CASCADE,
  reporter_uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  reason TEXT NOT NULL,
  note TEXT,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (cosmetic_id, reporter_uuid)
);

-- codes neu aufbauen: cape_id wird optional, dazu cosmetic_id (genau eins von beiden).
CREATE TABLE codes_v2 (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  code_hash TEXT NOT NULL UNIQUE,
  hint TEXT NOT NULL,
  cape_id TEXT REFERENCES capes(id) ON DELETE CASCADE,
  cosmetic_id TEXT REFERENCES cosmetics(id) ON DELETE CASCADE,
  max_uses INTEGER NOT NULL CHECK (max_uses >= 1),
  uses INTEGER NOT NULL DEFAULT 0,
  expires_at INTEGER,
  revoked_at INTEGER,
  note TEXT,
  created_at INTEGER NOT NULL,
  created_by TEXT NOT NULL,
  CHECK ((cape_id IS NULL) <> (cosmetic_id IS NULL))
);
INSERT INTO codes_v2 (id, code_hash, hint, cape_id, cosmetic_id, max_uses, uses, expires_at, revoked_at, note, created_at, created_by)
  SELECT id, code_hash, hint, cape_id, NULL, max_uses, uses, expires_at, revoked_at, note, created_at, created_by FROM codes;
CREATE TABLE code_redemptions_v2 (
  code_id INTEGER NOT NULL REFERENCES codes_v2(id) ON DELETE CASCADE,
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  redeemed_at INTEGER NOT NULL,
  PRIMARY KEY (code_id, uuid)
);
INSERT INTO code_redemptions_v2 (code_id, uuid, redeemed_at) SELECT code_id, uuid, redeemed_at FROM code_redemptions;
DROP TABLE code_redemptions;
DROP TABLE codes;
ALTER TABLE codes_v2 RENAME TO codes;
ALTER TABLE code_redemptions_v2 RENAME TO code_redemptions;
`,
  },
  {
    // Admin-Login der Website: Code auf der Website, Bestätigung im TRS Launcher, dann Cookie-Sitzung.
    version: 3,
    sql: `
CREATE TABLE web_logins (
  code_hash TEXT PRIMARY KEY,
  poll_hash TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  approved_uuid TEXT REFERENCES users(uuid) ON DELETE CASCADE
);
CREATE INDEX web_logins_expires ON web_logins(expires_at);

CREATE TABLE web_sessions (
  token_hash TEXT PRIMARY KEY,
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  csrf TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX web_sessions_uuid ON web_sessions(uuid);
`,
  },
  {
    // TRS-Sync: eigene Skins (PNG als BLOB), Grabsteine gelöschter Skins, Presets + Launcher-Einstellungen.
    version: 4,
    sql: `
CREATE TABLE sync_skins (
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  id TEXT NOT NULL CHECK (length(id) = 12),
  name TEXT NOT NULL,
  variant TEXT NOT NULL CHECK (variant IN ('classic', 'slim')),
  png BLOB NOT NULL,
  sha256 TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (uuid, id)
);

CREATE TABLE sync_skin_tombstones (
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  id TEXT NOT NULL CHECK (length(id) = 12),
  deleted_at INTEGER NOT NULL,
  PRIMARY KEY (uuid, id)
);
CREATE INDEX sync_skin_tombstones_deleted ON sync_skin_tombstones(deleted_at);

-- Ein JSON-Dokument je Konto und Art; updated_at = Änderungszeit des Clients (letzter Schreiber gewinnt).
CREATE TABLE sync_docs (
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('presets', 'settings')),
  data TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (uuid, kind)
);
`,
  },
  {
    // TRS-Client-Sync: eigene Dokumente für die Client-Einstellungen (Module, HUD, Tasten, Einführung)
    // und die Garderobe (Outfits, Favoriten). SQLite kann den CHECK nicht ändern → Tabelle neu anlegen.
    version: 5,
    sql: `
CREATE TABLE sync_docs_v5 (
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('presets', 'settings', 'client', 'wardrobe')),
  data TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (uuid, kind)
);
INSERT INTO sync_docs_v5 (uuid, kind, data, updated_at) SELECT uuid, kind, data, updated_at FROM sync_docs;
DROP TABLE sync_docs;
ALTER TABLE sync_docs_v5 RENAME TO sync_docs;
`,
  },
  {
    // Eigene (freigegebene) Umhänge mit Freunden teilen: Angebot → annehmen → Umhang in der Sammlung des Freundes.
    // granted_by = wer angeboten hat (Ersteller oder ein Freund, der weiterteilt) → Baum je Umhang, Entzug
    // nimmt den ganzen Ast mit (im Code, rekursiv). Umhang weg / Konto weg → Zeilen per FK weg.
    version: 6,
    sql: `
CREATE TABLE cape_shares (
  cape_id TEXT NOT NULL REFERENCES capes(id) ON DELETE CASCADE,
  holder_uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  granted_by TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  status TEXT NOT NULL CHECK (status IN ('offered', 'accepted')),
  created_at INTEGER NOT NULL,
  accepted_at INTEGER,
  PRIMARY KEY (cape_id, holder_uuid),
  CHECK (holder_uuid <> granted_by),
  CHECK ((status = 'accepted') = (accepted_at IS NOT NULL))
);
CREATE INDEX cape_shares_holder ON cape_shares(holder_uuid, status);
CREATE INDEX cape_shares_granted_by ON cape_shares(granted_by, cape_id);
`,
  },
  {
    // Sozial/Chat: Unterhaltungen (DM + Gruppen), Nachrichten (Inhalte AES-GCM-verschlüsselt, s. crypto.ts),
    // Bilder (Dateien in <DATA_DIR>/chat, ebenfalls verschlüsselt), Reaktionen, Lese-/Tipp-Einstellungen,
    // Meldungen mit Beweis-Schnappschuss, Sanktionen (Verwarnung/Stumm), Wortfilter, Audit-Bezug.
    version: 7,
    sql: `
ALTER TABLE users ADD COLUMN chat_read_receipts INTEGER NOT NULL DEFAULT 1;
ALTER TABLE users ADD COLUMN chat_typing INTEGER NOT NULL DEFAULT 1;
ALTER TABLE admin_log ADD COLUMN ref TEXT;
CREATE INDEX admin_log_ref ON admin_log(ref);
CREATE INDEX admin_log_at ON admin_log(at);

CREATE TABLE chat_conversations (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL CHECK (kind IN ('dm', 'group')),
  -- DM: "<uuid-a>:<uuid-b>" (sortiert) – höchstens eine DM je Paar.
  dm_key TEXT UNIQUE,
  -- Gruppenname, verschlüsselt (AAD "grp:<id>").
  name BLOB,
  owner_uuid TEXT REFERENCES users(uuid) ON DELETE SET NULL,
  created_at INTEGER NOT NULL,
  -- Letzte Aktivität (Nachricht oder Änderung) – Sortierung der Liste.
  updated_at INTEGER NOT NULL,
  last_seq INTEGER NOT NULL DEFAULT 0,
  CHECK ((kind = 'dm') = (dm_key IS NOT NULL)),
  CHECK ((kind = 'group') = (name IS NOT NULL))
);

CREATE TABLE chat_members (
  conversation_id TEXT NOT NULL REFERENCES chat_conversations(id) ON DELETE CASCADE,
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  role TEXT NOT NULL CHECK (role IN ('owner', 'member')),
  joined_at INTEGER NOT NULL,
  -- Neue Gruppenmitglieder sehen erst Nachrichten ab ihrem Beitritt.
  visible_from_seq INTEGER NOT NULL DEFAULT 1,
  -- Eigener Lesestand (darf beim „ungelesen markieren“ zurückgehen) …
  read_seq INTEGER NOT NULL DEFAULT 0,
  -- … und die Lesebestätigung für andere (steigt nur).
  receipt_seq INTEGER NOT NULL DEFAULT 0,
  receipt_at INTEGER,
  marked_unread INTEGER NOT NULL DEFAULT 0,
  -- NULL = nicht stummgeschaltet; 253402300799000 = unbefristet.
  muted_until INTEGER,
  PRIMARY KEY (conversation_id, uuid)
);
CREATE INDEX chat_members_uuid ON chat_members(uuid);

CREATE TABLE chat_messages (
  id TEXT PRIMARY KEY,
  conversation_id TEXT NOT NULL REFERENCES chat_conversations(id) ON DELETE CASCADE,
  seq INTEGER NOT NULL,
  sender_uuid TEXT REFERENCES users(uuid) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('text', 'system')),
  -- JSON {text?, invite?, system?}, verschlüsselt (AAD "msg:<id>"); NULL nach dem Löschen (Grabstein).
  body BLOB,
  reply_to TEXT,
  created_at INTEGER NOT NULL,
  edited_at INTEGER,
  deleted_at INTEGER,
  deleted_by TEXT CHECK (deleted_by IS NULL OR deleted_by IN ('sender', 'owner', 'admin')),
  -- Idempotenz: gleiche nonce vom gleichen Absender = dieselbe Nachricht.
  nonce TEXT,
  UNIQUE (conversation_id, seq)
);
CREATE INDEX chat_messages_sender ON chat_messages(sender_uuid, created_at);
CREATE UNIQUE INDEX chat_messages_nonce ON chat_messages(sender_uuid, nonce) WHERE nonce IS NOT NULL;

CREATE TABLE chat_attachments (
  id TEXT PRIMARY KEY,
  uploader_uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  -- NULL = hochgeladen, aber noch keiner Nachricht zugeordnet (verfällt nach 1 h).
  message_id TEXT REFERENCES chat_messages(id) ON DELETE CASCADE,
  position INTEGER,
  mime TEXT NOT NULL CHECK (mime IN ('image/png', 'image/jpeg')),
  width INTEGER NOT NULL,
  height INTEGER NOT NULL,
  bytes INTEGER NOT NULL,
  thumb_mime TEXT NOT NULL CHECK (thumb_mime IN ('image/png', 'image/jpeg')),
  thumb_width INTEGER NOT NULL,
  thumb_height INTEGER NOT NULL,
  thumb_bytes INTEGER NOT NULL,
  sha256 TEXT NOT NULL,
  key_id TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX chat_attachments_message ON chat_attachments(message_id);
CREATE INDEX chat_attachments_uploader ON chat_attachments(uploader_uuid, created_at);

CREATE TABLE chat_reactions (
  message_id TEXT NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  emoji TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (message_id, uuid, emoji)
);

CREATE TABLE chat_reports (
  id TEXT PRIMARY KEY,
  reporter_uuid TEXT REFERENCES users(uuid) ON DELETE SET NULL,
  -- Gemeldeter Spieler (bei Gruppen: Besitzer zur Meldezeit). Ohne FK: überlebt die Kontolöschung.
  target_uuid TEXT CHECK (target_uuid IS NULL OR length(target_uuid) = 32),
  kind TEXT NOT NULL CHECK (kind IN ('message', 'image', 'player', 'group')),
  conversation_id TEXT,
  message_id TEXT,
  attachment_id TEXT,
  reason TEXT NOT NULL CHECK (reason IN ('insult_hate', 'spam', 'inappropriate', 'scam_phishing', 'harassment', 'other')),
  note BLOB,
  -- Schnappschuss zur Meldezeit (JSON, verschlüsselt, AAD "rep:<id>"); NULL nach Ablauf der Aufbewahrung.
  evidence BLOB,
  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'in_review', 'resolved')),
  outcome TEXT CHECK (outcome IS NULL OR outcome IN ('actioned', 'dismissed')),
  -- Melder mit vielen abgewiesenen Meldungen: zählt nicht für die Auto-Stummschaltung.
  low_trust INTEGER NOT NULL DEFAULT 0,
  assigned_to TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  resolved_at INTEGER,
  resolved_by TEXT,
  evidence_purged_at INTEGER,
  CHECK ((status = 'resolved') = (outcome IS NOT NULL))
);
CREATE INDEX chat_reports_status ON chat_reports(status, created_at);
CREATE INDEX chat_reports_target ON chat_reports(target_uuid, created_at);
CREATE INDEX chat_reports_reporter ON chat_reports(reporter_uuid, status);

CREATE TABLE chat_report_notes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  report_id TEXT NOT NULL REFERENCES chat_reports(id) ON DELETE CASCADE,
  at INTEGER NOT NULL,
  actor TEXT NOT NULL,
  text BLOB NOT NULL
);
CREATE INDEX chat_report_notes_report ON chat_report_notes(report_id);

-- Kopien der gemeldeten Bilder (Dateien in <DATA_DIR>/chat/evidence), unabhängig vom Löschen der Nachricht.
CREATE TABLE chat_evidence_files (
  report_id TEXT NOT NULL REFERENCES chat_reports(id) ON DELETE CASCADE,
  attachment_id TEXT NOT NULL,
  mime TEXT NOT NULL,
  width INTEGER NOT NULL,
  height INTEGER NOT NULL,
  bytes INTEGER NOT NULL,
  key_id TEXT NOT NULL,
  PRIMARY KEY (report_id, attachment_id)
);

-- Verwarnungen und Stummschaltungen. Ohne FK: aktive Stummschaltungen überleben die Kontolöschung (wie Sperren).
CREATE TABLE chat_sanctions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  uuid TEXT NOT NULL CHECK (length(uuid) = 32),
  kind TEXT NOT NULL CHECK (kind IN ('warn', 'mute')),
  reason TEXT,
  report_id TEXT,
  auto TEXT CHECK (auto IS NULL OR auto IN ('reports', 'spam')),
  created_at INTEGER NOT NULL,
  created_by TEXT NOT NULL,
  -- NULL bei 'mute' = bis zur Prüfung / unbefristet.
  expires_at INTEGER,
  lifted_at INTEGER,
  lifted_by TEXT
);
CREATE INDEX chat_sanctions_uuid ON chat_sanctions(uuid, kind);

CREATE TABLE chat_reporter_stats (
  uuid TEXT PRIMARY KEY REFERENCES users(uuid) ON DELETE CASCADE,
  actioned INTEGER NOT NULL DEFAULT 0,
  dismissed INTEGER NOT NULL DEFAULT 0
);

-- Optionaler Wortfilter (Admin): normalisierte Wörter, keine Regex (kein ReDoS).
CREATE TABLE chat_word_filter (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  word TEXT NOT NULL UNIQUE,
  mode TEXT NOT NULL CHECK (mode IN ('word', 'contains')),
  action TEXT NOT NULL CHECK (action IN ('block', 'mask')),
  created_at INTEGER NOT NULL,
  created_by TEXT NOT NULL
);
`,
  },
  {
    // Welt-Hosting: Räume (Host-Welt für Freunde), Mitglieder je Raum (eingeladen/angefragt/angenommen/
    // gesperrt) und dauerhafte Sperren je Host. Räume leben nur, solange der Host Herzschläge schickt.
    version: 8,
    sql: `
CREATE TABLE hosting_rooms (
  id TEXT PRIMARY KEY,
  host_uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  code TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  mc_version TEXT NOT NULL,
  loader TEXT NOT NULL,
  max_players INTEGER NOT NULL CHECK (max_players BETWEEN 2 AND 10),
  game_mode TEXT NOT NULL CHECK (game_mode IN ('survival', 'creative', 'adventure', 'spectator')),
  pvp INTEGER NOT NULL,
  cheats INTEGER NOT NULL,
  open INTEGER NOT NULL DEFAULT 1,
  visibility TEXT NOT NULL DEFAULT 'friends' CHECK (visibility IN ('friends', 'invited')),
  players INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  heartbeat_at INTEGER NOT NULL
);
CREATE INDEX hosting_rooms_host ON hosting_rooms(host_uuid);
CREATE INDEX hosting_rooms_heartbeat ON hosting_rooms(heartbeat_at);

CREATE TABLE hosting_members (
  room_id TEXT NOT NULL REFERENCES hosting_rooms(id) ON DELETE CASCADE,
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  state TEXT NOT NULL CHECK (state IN ('invited', 'requested', 'accepted', 'banned')),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (room_id, uuid)
);
CREATE INDEX hosting_members_uuid ON hosting_members(uuid, state);

CREATE TABLE hosting_bans (
  host_uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (host_uuid, uuid)
);
CREATE INDEX hosting_bans_uuid ON hosting_bans(uuid);
`,
  },
  {
    // Moderation v2: Rollen (Admin/Moderator), EINE Tabelle für alle Strafen (Verwarnung, Chat-Stumm,
    // Sozial-, Upload-, Hosting-Sperre, Konto-Bann) mit Änderungsverlauf und Einspruch, Notizen zu Spielern,
    // Namen-Verlauf, eingeschränkte Sitzungen (nur Einspruch) für gesperrte Konten.
    // Übernimmt chat_sanctions und bans verlustfrei (legacy_source/legacy_id) und entfernt die alten Tabellen.
    // Idempotent: jeder Schritt prüft den Bestand, ein zweiter Lauf ändert nichts.
    version: 9,
    run: migrateModerationV2,
  },
  {
    // Versteckte Kosmetik (z. B. die Quietscheente): taucht in keinem Katalog auf, bis sie jemand per Code
    // freigeschaltet hat. Wert kommt beim Start aus assets/cosmetics/catalog.json.
    version: 10,
    sql: `ALTER TABLE cosmetics ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0;`,
  },
]

function hasTable(db: DatabaseSync, name: string): boolean {
  return db.prepare("SELECT 1 AS x FROM sqlite_master WHERE type = 'table' AND name = ?").get(name) !== undefined
}

function hasColumn(db: DatabaseSync, table: string, column: string): boolean {
  // Tabellenname stammt nur aus dem Code unten, nie aus Eingaben.
  return (db.prepare(`PRAGMA table_info(${table})`).all() as { name: string }[]).some((c) => c.name === column)
}

/** Migration 9 (siehe oben). Exportiert für den Idempotenz-Test. */
export function migrateModerationV2(db: DatabaseSync): void {
  db.exec(`
CREATE TABLE IF NOT EXISTS staff_roles (
  uuid TEXT PRIMARY KEY REFERENCES users(uuid) ON DELETE CASCADE,
  role TEXT NOT NULL CHECK (role IN ('admin', 'moderator')),
  granted_at INTEGER NOT NULL,
  granted_by TEXT NOT NULL,
  note TEXT
);

-- Alle Strafen. Ohne FK auf users: aktive Strafen überleben die Kontolöschung (kein Umgehen per Neuanmeldung).
CREATE TABLE IF NOT EXISTS sanctions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  uuid TEXT NOT NULL CHECK (length(uuid) = 32),
  kind TEXT NOT NULL CHECK (kind IN ('warn', 'chat_mute', 'social_ban', 'upload_ban', 'hosting_ban', 'account_ban')),
  -- Grund-Vorlage (fester Code, s. sanctions.ts) – Spieler sehen ihn übersetzt.
  reason_code TEXT NOT NULL,
  -- Öffentlicher Freitext (sieht der Spieler), optional.
  reason TEXT,
  -- Interne Notiz (nie an Spieler).
  note TEXT,
  report_id TEXT,
  auto TEXT CHECK (auto IS NULL OR auto IN ('reports', 'spam')),
  created_at INTEGER NOT NULL,
  created_by TEXT NOT NULL,
  -- Rolle des Erstellers zum Zeitpunkt der Strafe (Rechte: Moderatoren ändern keine Admin-Strafen).
  created_role TEXT NOT NULL CHECK (created_role IN ('admin', 'moderator', 'system')),
  -- NULL = dauerhaft (bzw. bei automatischer Stummschaltung: bis zur Prüfung).
  expires_at INTEGER,
  lifted_at INTEGER,
  lifted_by TEXT,
  lift_reason TEXT,
  updated_at INTEGER NOT NULL,
  legacy_source TEXT CHECK (legacy_source IS NULL OR legacy_source IN ('chat_sanctions', 'bans')),
  legacy_id INTEGER,
  UNIQUE (legacy_source, legacy_id)
);
CREATE INDEX IF NOT EXISTS sanctions_uuid ON sanctions(uuid, kind);
CREATE INDEX IF NOT EXISTS sanctions_created ON sanctions(created_at);
CREATE INDEX IF NOT EXISTS sanctions_active ON sanctions(kind, lifted_at, expires_at);

-- Verlauf je Strafe: Verkürzen, Verlängern, Aufheben (wer, wann, warum).
CREATE TABLE IF NOT EXISTS sanction_changes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  sanction_id INTEGER NOT NULL REFERENCES sanctions(id) ON DELETE CASCADE,
  at INTEGER NOT NULL,
  actor TEXT NOT NULL,
  action TEXT NOT NULL CHECK (action IN ('shorten', 'extend', 'lift')),
  old_expires_at INTEGER,
  new_expires_at INTEGER,
  reason TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS sanction_changes_sanction ON sanction_changes(sanction_id);

-- Einspruch: genau einer je Strafe.
CREATE TABLE IF NOT EXISTS sanction_appeals (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  sanction_id INTEGER NOT NULL UNIQUE REFERENCES sanctions(id) ON DELETE CASCADE,
  uuid TEXT NOT NULL CHECK (length(uuid) = 32),
  text TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'lifted', 'shortened', 'upheld')),
  created_at INTEGER NOT NULL,
  decided_at INTEGER,
  decided_by TEXT,
  response TEXT,
  CHECK ((status = 'open') = (decided_at IS NULL))
);
CREATE INDEX IF NOT EXISTS sanction_appeals_status ON sanction_appeals(status, created_at);

-- Interne Moderations-Notizen zu einem Spieler (ohne FK: bleiben bei aktiver Strafe über die Löschung hinaus).
CREATE TABLE IF NOT EXISTS player_notes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  uuid TEXT NOT NULL CHECK (length(uuid) = 32),
  at INTEGER NOT NULL,
  actor TEXT NOT NULL,
  text TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS player_notes_uuid ON player_notes(uuid, at);

-- Namen, unter denen sich ein Konto bei TRS angemeldet hat.
CREATE TABLE IF NOT EXISTS name_history (
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  name TEXT NOT NULL,
  first_seen INTEGER NOT NULL,
  last_seen INTEGER NOT NULL,
  PRIMARY KEY (uuid, name)
);
CREATE INDEX IF NOT EXISTS name_history_name ON name_history(name COLLATE NOCASE);
`)

  // Eingeschränkte Sitzungen: `appeal` = gesperrtes Konto, darf nur die eigenen Strafen sehen und Einspruch einlegen.
  if (!hasColumn(db, 'sessions', 'scope')) {
    db.exec("ALTER TABLE sessions ADD COLUMN scope TEXT NOT NULL DEFAULT 'full' CHECK (scope IN ('full', 'appeal'))")
  }

  if (hasTable(db, 'chat_sanctions')) {
    // Verwarnungen galten 90 Tage (GET /v1/me/moderation) → Ende = Zeitpunkt + 90 Tage.
    db.exec(`
INSERT OR IGNORE INTO sanctions (uuid, kind, reason_code, reason, note, report_id, auto, created_at, created_by, created_role,
  expires_at, lifted_at, lifted_by, lift_reason, updated_at, legacy_source, legacy_id)
SELECT uuid,
  CASE kind WHEN 'warn' THEN 'warn' ELSE 'chat_mute' END,
  CASE auto WHEN 'spam' THEN 'auto_spam' WHEN 'reports' THEN 'auto_reports' ELSE 'legacy' END,
  reason, NULL, report_id, auto, created_at, created_by, CASE created_by WHEN 'system' THEN 'system' ELSE 'admin' END,
  CASE kind WHEN 'warn' THEN created_at + 7776000000 ELSE expires_at END,
  lifted_at, lifted_by, NULL, COALESCE(lifted_at, created_at), 'chat_sanctions', id
FROM chat_sanctions;
DROP TABLE chat_sanctions;
`)
  }
  if (hasTable(db, 'bans')) {
    db.exec(`
INSERT OR IGNORE INTO sanctions (uuid, kind, reason_code, reason, note, report_id, auto, created_at, created_by, created_role,
  expires_at, lifted_at, lifted_by, lift_reason, updated_at, legacy_source, legacy_id)
SELECT uuid, 'account_ban', 'legacy', reason, NULL, NULL, NULL, banned_at, banned_by, 'admin', NULL, NULL, NULL, NULL, banned_at, 'bans', rowid
FROM bans;
DROP TABLE bans;
`)
  }
  db.exec('INSERT OR IGNORE INTO name_history (uuid, name, first_seen, last_seen) SELECT uuid, name, created_at, last_login_at FROM users')
}
