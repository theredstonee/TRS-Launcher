import type { DatabaseSync } from 'node:sqlite'
import { BUILTIN_ROLES } from '../../shared/team'

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
  {
    // Welt-Hosting mit Mods + Resource Pack: Liste der geteilten Mods (Name, Version, Quelle, IDs, Hashes,
    // Größe, Pflicht/optional) und Pack-Info als JSON. Nur Metadaten – Dateien gehen direkt vom Host an die Gäste.
    version: 11,
    sql: `ALTER TABLE hosting_rooms ADD COLUMN content TEXT;`,
  },
  {
    // Geteilte Screenshots (§23): öffentlich per Link, 30 Tage gültig. Dazu das Upload-Protokoll für die
    // Tagesgrenze (bleibt beim Löschen eines Links, sonst ließe sich die Grenze umgehen; nach 24 h weg).
    // Meldungen bekommen die Art `share` (+ Spalte share_id). SQLite kann den CHECK nicht ändern → die drei
    // Meldungs-Tabellen neu anlegen. Erst die Kinder kopieren und löschen, dann die Eltern: ein DROP der
    // Eltern-Tabelle würde sonst per ON DELETE CASCADE die Notizen und Beweis-Dateien mitnehmen.
    version: 12,
    sql: `
CREATE TABLE shared_images (
  id TEXT PRIMARY KEY CHECK (length(id) = 22),
  owner_uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  mime TEXT NOT NULL CHECK (mime IN ('image/png', 'image/jpeg')),
  width INTEGER NOT NULL,
  height INTEGER NOT NULL,
  bytes INTEGER NOT NULL,
  thumb_mime TEXT NOT NULL CHECK (thumb_mime IN ('image/png', 'image/jpeg')),
  thumb_width INTEGER NOT NULL,
  thumb_height INTEGER NOT NULL,
  thumb_bytes INTEGER NOT NULL,
  sha256 TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX shared_images_owner ON shared_images(owner_uuid, created_at);
CREATE INDEX shared_images_expires ON shared_images(expires_at);

CREATE TABLE shared_image_uploads (
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  at INTEGER NOT NULL
);
CREATE INDEX shared_image_uploads_uuid ON shared_image_uploads(uuid, at);

CREATE TABLE chat_reports_v12 (
  id TEXT PRIMARY KEY,
  reporter_uuid TEXT REFERENCES users(uuid) ON DELETE SET NULL,
  target_uuid TEXT CHECK (target_uuid IS NULL OR length(target_uuid) = 32),
  kind TEXT NOT NULL CHECK (kind IN ('message', 'image', 'player', 'group', 'share')),
  conversation_id TEXT,
  message_id TEXT,
  attachment_id TEXT,
  share_id TEXT,
  reason TEXT NOT NULL CHECK (reason IN ('insult_hate', 'spam', 'inappropriate', 'scam_phishing', 'harassment', 'other')),
  note BLOB,
  evidence BLOB,
  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'in_review', 'resolved')),
  outcome TEXT CHECK (outcome IS NULL OR outcome IN ('actioned', 'dismissed')),
  low_trust INTEGER NOT NULL DEFAULT 0,
  assigned_to TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  resolved_at INTEGER,
  resolved_by TEXT,
  evidence_purged_at INTEGER,
  CHECK ((status = 'resolved') = (outcome IS NOT NULL))
);
INSERT INTO chat_reports_v12 (id, reporter_uuid, target_uuid, kind, conversation_id, message_id, attachment_id, share_id, reason, note,
  evidence, status, outcome, low_trust, assigned_to, created_at, updated_at, resolved_at, resolved_by, evidence_purged_at)
SELECT id, reporter_uuid, target_uuid, kind, conversation_id, message_id, attachment_id, NULL, reason, note,
  evidence, status, outcome, low_trust, assigned_to, created_at, updated_at, resolved_at, resolved_by, evidence_purged_at
FROM chat_reports;

CREATE TABLE chat_report_notes_v12 (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  report_id TEXT NOT NULL REFERENCES chat_reports_v12(id) ON DELETE CASCADE,
  at INTEGER NOT NULL,
  actor TEXT NOT NULL,
  text BLOB NOT NULL
);
INSERT INTO chat_report_notes_v12 (id, report_id, at, actor, text) SELECT id, report_id, at, actor, text FROM chat_report_notes;

CREATE TABLE chat_evidence_files_v12 (
  report_id TEXT NOT NULL REFERENCES chat_reports_v12(id) ON DELETE CASCADE,
  attachment_id TEXT NOT NULL,
  mime TEXT NOT NULL,
  width INTEGER NOT NULL,
  height INTEGER NOT NULL,
  bytes INTEGER NOT NULL,
  key_id TEXT NOT NULL,
  PRIMARY KEY (report_id, attachment_id)
);
INSERT INTO chat_evidence_files_v12 (report_id, attachment_id, mime, width, height, bytes, key_id)
SELECT report_id, attachment_id, mime, width, height, bytes, key_id FROM chat_evidence_files;

DROP TABLE chat_evidence_files;
DROP TABLE chat_report_notes;
DROP TABLE chat_reports;
ALTER TABLE chat_reports_v12 RENAME TO chat_reports;
ALTER TABLE chat_report_notes_v12 RENAME TO chat_report_notes;
ALTER TABLE chat_evidence_files_v12 RENAME TO chat_evidence_files;
CREATE INDEX chat_reports_status ON chat_reports(status, created_at);
CREATE INDEX chat_reports_target ON chat_reports(target_uuid, created_at);
CREATE INDEX chat_reports_reporter ON chat_reports(reporter_uuid, status);
CREATE INDEX chat_reports_share ON chat_reports(share_id);
CREATE INDEX chat_report_notes_report ON chat_report_notes(report_id);
`,
  },
  {
    // Team v3 (§24): feste + eigene Rollen mit feingranularen Rechten (statt staff_roles admin/moderator),
    // Rang der Strafen-Ersteller, Stellenausschreibungen + Bewerbungen, Website-Login nur noch über Microsoft
    // (web_logins entfällt, alte Code-Sitzungen enden). Idempotent wie Migration 9.
    // HINWEIS beim Mergen: Nummer ggf. an parallele Branches anpassen (nur anhängen).
    version: 13,
    run: migrateTeamV3,
  },
  {
    // Schaltungs-Bibliothek (§25): Schaltungen (rev je Änderung, Status, Texte im JSON), Grabsteine gelöschter IDs,
    // Einreichungen von Spielern. Neues Recht `circuits.manage` für die Standardrollen Owner, Admin, Senior-Moderator
    // und Content (angepasste Rechte bleiben, es wird nur ergänzt). Meldungen bekommen die Art `circuit`
    // (+ Spalte circuit_id) – wie in Migration 12 die drei Meldungs-Tabellen neu anlegen (Kinder zuerst).
    // Idempotent. HINWEIS beim Mergen: Nummer ggf. an parallele Branches anpassen (nur anhängen).
    version: 14,
    run: migrateCircuits,
  },
  {
    // Team-Seite (§26): von Hand gepflegte Mitglieder (Gruppe = Rolle, Reihenfolge, Positions-Titel EN/DE/ES,
    // Discord, Links) + neues Recht `team.page` für Owner/Admin. Dazu die zuletzt gesehene Skin-Adresse je Konto,
    // damit Admin-Listen und die Team-Seite Köpfe/Skins ohne Mojang-Abfrage je Aufruf zeigen. Idempotent.
    version: 15,
    run: migrateTeamPage,
  },
  {
    // Geteilte Modpacks (§27): Packs mit Code (Laufzeit je Pack, `expires_at` NULL = unbegrenzt), an Freunde geschickte
    // Packs, Upload-Protokoll für die Tagesgrenze. Meldungen bekommen die Art `pack` (+ Spalte pack_id) – wie in
    // Migration 14 die drei Meldungs-Tabellen neu anlegen (Kinder zuerst). Idempotent.
    // HINWEIS beim Mergen: Nummer ggf. an parallele Branches anpassen (nur anhängen).
    version: 16,
    run: migrateSharedPacks,
  },
  {
    // Issues & Roadmap (§28): öffentlicher Issue-Tracker (Nummer = id), Stimmen hoch/runter, Folgen, Kommentare,
    // Bild-Uploads, Tags, interne Notizen, öffentlicher Verlauf, Tagesgrenzen. Neue Rechte `issues.manage` und
    // `issues.moderate` für die Standardrollen (angepasste Rechte bleiben, es wird nur ergänzt). Meldungen bekommen
    // die Arten `issue` und `issue_comment` (+ Spalten issue_id, issue_comment_id) – wie in Migration 16 die drei
    // Meldungs-Tabellen neu anlegen (Kinder zuerst). Idempotent.
    // HINWEIS beim Mergen: Nummer ggf. an parallele Branches anpassen (nur anhängen; Login/Blog = 18).
    version: 17,
    run: migrateIssues,
  },
  {
    // Anmeldung auf der Website per TRS Launcher (§29): kurzlebige Anfragen (Link-Token + Bestätigungscode, an den
    // Browser gebunden). Blog (§30): eigene News-Beiträge mit Texten je Sprache, Titelbild und hochgeladenen Bildern;
    // neue Rechte `blog.write` (Owner, Admin, Content) und `blog.publish` (Owner, Admin) – angepasste Rechte bleiben,
    // es wird nur ergänzt. Idempotent.
    // HINWEIS beim Mergen: Nummer ggf. an parallele Branches anpassen (nur anhängen; der Issue-Tracker nimmt 17).
    version: 18,
    run: migrateLauncherLoginBlog,
  },
  {
    // Erfolge (§31): Freischaltungen (mit Stand der Belohnung), Zähler/Flags je Konto (Launcher-Meldungen und
    // Server-Zähler für Dinge, die später verschwinden – geteilte Packs, Welten, Screenshots), Spielzeit-Summe + Serie,
    // Spalte users.achievements_visible (für Freunde sichtbar). Dazu Notizen je Welt/Server (§17.5): sync_notes +
    // Änderungszähler je Konto. Alles per ON DELETE CASCADE am Konto. Idempotent.
    // HINWEIS beim Mergen: Nummer ggf. an parallele Branches anpassen (nur anhängen).
    version: 19,
    run: (db) => {
      migrateAchievements(db)
      migrateSyncNotes(db)
    },
  },
  {
    // Kosmetik-Format v2 (§11.9): echte 3D-Modelle (model.json + Textur + Leucht-Streifen) statt Vorlage + Textur.
    // `format` = 1 (Vorlage) oder 2 (Modell). v2-Zeilen tragen in `template` den Platzhalter '@v2', weil der alte
    // CHECK (Emote ⇔ keine Vorlage ⇔ keine Textur) sich ohne Neuanlage der Tabelle nicht ändern lässt; Modell,
    // Leucht-Streifen und Karten kommen beim Start aus assets/cosmetics/v2/ (nicht aus der DB). Idempotent.
    // HINWEIS beim Mergen: Nummer ggf. an parallele Branches anpassen (nur anhängen).
    version: 20,
    run: (db) => {
      if (!hasColumn(db, 'cosmetics', 'format')) {
        db.exec('ALTER TABLE cosmetics ADD COLUMN format INTEGER NOT NULL DEFAULT 1 CHECK (format IN (1, 2))')
      }
    },
  },
  {
    // Events (§32, erstes: Halloween 2026): `events` (global an/aus) + `event_players` (Freigabe für einzelne Spieler,
    // auch bei globalem „aus“); Recht `events.manage` (Owner, Admin); Spalte `event` an cosmetics/capes (Event-Teile:
    // gratis abholbar, solange das Event für den Spieler aktiv ist, danach behalten). Die Blätter-Tabellen
    // user_cosmetics/user_capes/equipped_cosmetics werden neu angelegt, damit `source = 'event'` bzw. der Platz
    // `companion` erlaubt sind (SQLite kann CHECKs nicht ändern; sonst verweist niemand auf diese Tabellen). Idempotent.
    // HINWEIS beim Mergen: Nummer ggf. an parallele Branches anpassen (nur anhängen).
    version: 21,
    run: migrateEvents,
  },
  {
    // Modpack-Upload in Stücken (§27.7): Sitzung (24 h) + empfangene Stücke. Die Bytes liegen unter
    // `<DATA_DIR>/packs/tmp/<id>/`, nicht in der Datenbank. Idempotent.
    // HINWEIS beim Mergen: Nummer ggf. an parallele Branches anpassen (nur anhängen).
    version: 22,
    run: migratePackUploads,
  },
  {
    // Push für die Apps (§33): Geräte (UnifiedPush mit Endpunkt + Web-Push-Schlüsseln oder „poll“ für iOS), an die
    // Sitzung gebunden (Abmelden/Ablauf/Sperre → Gerät weg), Kategorien als JSON. Abruf-Liste `push_pending` für
    // poll-Geräte (Inhalt verschlüsselt wie Chat, AAD `push:<device>`). Idempotent.
    // HINWEIS beim Mergen: Nummer ggf. an parallele Branches anpassen (nur anhängen).
    version: 23,
    run: migratePush,
  },
]

/** Migration 23 (siehe oben). Exportiert für den Idempotenz-Test. */
export function migratePush(db: DatabaseSync): void {
  db.exec(`
CREATE TABLE IF NOT EXISTS push_devices (
  id TEXT PRIMARY KEY CHECK (length(id) = 21),
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  session_hash TEXT NOT NULL REFERENCES sessions(token_hash) ON DELETE CASCADE,
  platform TEXT NOT NULL CHECK (platform IN ('android', 'ios')),
  kind TEXT NOT NULL CHECK (kind IN ('unifiedpush', 'poll')),
  endpoint TEXT UNIQUE,
  p256dh TEXT,
  auth TEXT,
  device_name TEXT NOT NULL,
  app_version TEXT NOT NULL,
  locale TEXT NOT NULL,
  categories TEXT NOT NULL,
  preview INTEGER NOT NULL DEFAULT 0,
  push_while_playing INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  last_success_at INTEGER,
  last_failure_at INTEGER,
  failures INTEGER NOT NULL DEFAULT 0,
  CHECK ((kind = 'unifiedpush') = (endpoint IS NOT NULL)),
  CHECK ((endpoint IS NULL) = (p256dh IS NULL) AND (endpoint IS NULL) = (auth IS NULL))
);
CREATE INDEX IF NOT EXISTS push_devices_uuid ON push_devices(uuid);
CREATE INDEX IF NOT EXISTS push_devices_session ON push_devices(session_hash);

CREATE TABLE IF NOT EXISTS push_pending (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT NOT NULL REFERENCES push_devices(id) ON DELETE CASCADE,
  payload BLOB NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS push_pending_device ON push_pending(device_id, id);
CREATE INDEX IF NOT EXISTS push_pending_expires ON push_pending(expires_at);
`)
}

/** Migration 22 (siehe oben). Exportiert für den Idempotenz-Test. */
export function migratePackUploads(db: DatabaseSync): void {
  db.exec(`
CREATE TABLE IF NOT EXISTS pack_upload_sessions (
  id TEXT PRIMARY KEY CHECK (length(id) = 22),
  owner_uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  size INTEGER NOT NULL CHECK (size >= 1),
  sha256 TEXT NOT NULL CHECK (length(sha256) = 64),
  name TEXT,
  chunk_size INTEGER NOT NULL CHECK (chunk_size >= 1),
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  completed_at INTEGER,
  token_hash TEXT UNIQUE,
  consumed_at INTEGER
);
CREATE INDEX IF NOT EXISTS pack_upload_sessions_owner ON pack_upload_sessions(owner_uuid, expires_at);
CREATE INDEX IF NOT EXISTS pack_upload_sessions_expires ON pack_upload_sessions(expires_at);

CREATE TABLE IF NOT EXISTS pack_upload_chunks (
  upload_id TEXT NOT NULL REFERENCES pack_upload_sessions(id) ON DELETE CASCADE,
  idx INTEGER NOT NULL CHECK (idx >= 0),
  sha256 TEXT NOT NULL CHECK (length(sha256) = 64),
  bytes INTEGER NOT NULL CHECK (bytes >= 1),
  PRIMARY KEY (upload_id, idx)
);
`)
}

/** Migration 21 (siehe oben). Exportiert für den Idempotenz-Test. */
export function migrateEvents(db: DatabaseSync): void {
  db.exec(`
CREATE TABLE IF NOT EXISTS events (
  id TEXT PRIMARY KEY CHECK (length(id) BETWEEN 1 AND 32),
  enabled INTEGER NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)),
  updated_at INTEGER,
  updated_by TEXT
);
CREATE TABLE IF NOT EXISTS event_players (
  event_id TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  player_uuid TEXT NOT NULL CHECK (length(player_uuid) = 32),
  player_name TEXT,
  added_at INTEGER NOT NULL,
  added_by TEXT,
  PRIMARY KEY (event_id, player_uuid)
);
CREATE INDEX IF NOT EXISTS event_players_player ON event_players(player_uuid);
INSERT OR IGNORE INTO events (id, enabled) VALUES ('halloween', 0);
`)
  grantBuiltin(db, { owner: ['events.manage'], admin: ['events.manage'] })

  if (!hasColumn(db, 'cosmetics', 'event')) db.exec('ALTER TABLE cosmetics ADD COLUMN event TEXT')
  if (!hasColumn(db, 'capes', 'event')) db.exec('ALTER TABLE capes ADD COLUMN event TEXT')

  // Blätter-Tabellen neu anlegen (nur wenn der alte CHECK noch gilt).
  const sqlOf = (name: string) => (db.prepare("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?").get(name) as { sql: string } | undefined)?.sql ?? ''
  if (!sqlOf('user_cosmetics').includes("'event'")) {
    db.exec(`
CREATE TABLE user_cosmetics_v21 (
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  cosmetic_id TEXT NOT NULL REFERENCES cosmetics(id) ON DELETE CASCADE,
  source TEXT NOT NULL CHECK (source IN ('code', 'admin', 'event')),
  granted_at INTEGER NOT NULL,
  PRIMARY KEY (uuid, cosmetic_id)
);
INSERT INTO user_cosmetics_v21 (uuid, cosmetic_id, source, granted_at) SELECT uuid, cosmetic_id, source, granted_at FROM user_cosmetics;
DROP TABLE user_cosmetics;
ALTER TABLE user_cosmetics_v21 RENAME TO user_cosmetics;
`)
  }
  if (!sqlOf('user_capes').includes("'event'")) {
    db.exec(`
CREATE TABLE user_capes_v21 (
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  cape_id TEXT NOT NULL REFERENCES capes(id) ON DELETE CASCADE,
  source TEXT NOT NULL CHECK (source IN ('code', 'admin', 'event')),
  granted_at INTEGER NOT NULL,
  PRIMARY KEY (uuid, cape_id)
);
INSERT INTO user_capes_v21 (uuid, cape_id, source, granted_at) SELECT uuid, cape_id, source, granted_at FROM user_capes;
DROP TABLE user_capes;
ALTER TABLE user_capes_v21 RENAME TO user_capes;
`)
  }
  if (!sqlOf('equipped_cosmetics').includes("'companion'")) {
    db.exec(`
CREATE TABLE equipped_cosmetics_v21 (
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  slot TEXT NOT NULL CHECK (slot IN ('hat', 'wings', 'back', 'aura', 'companion')),
  cosmetic_id TEXT NOT NULL REFERENCES cosmetics(id) ON DELETE CASCADE,
  PRIMARY KEY (uuid, slot)
);
INSERT INTO equipped_cosmetics_v21 (uuid, slot, cosmetic_id) SELECT uuid, slot, cosmetic_id FROM equipped_cosmetics;
DROP TABLE equipped_cosmetics;
ALTER TABLE equipped_cosmetics_v21 RENAME TO equipped_cosmetics;
CREATE INDEX IF NOT EXISTS equipped_cosmetics_item ON equipped_cosmetics(cosmetic_id);
`)
  }
}

/** Notizen-Sync (§17.5), Teil von Migration 19. Exportiert für den Idempotenz-Test. */
export function migrateSyncNotes(db: DatabaseSync): void {
  db.exec(`
-- Notizen des TRS Clients je Server/Welt. deleted = 1 → Grabstein (title/text leer), deleted_at = Serverzeit des Löschens.
CREATE TABLE IF NOT EXISTS sync_notes (
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  id TEXT NOT NULL CHECK (length(id) = 16),
  world_type TEXT NOT NULL CHECK (world_type IN ('server', 'world')),
  -- Server-Adresse bzw. Welt-Kennung (16 hex)
  world_ref TEXT NOT NULL,
  world_name TEXT,
  title TEXT NOT NULL DEFAULT '',
  text TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  deleted_at INTEGER,
  -- Stand des Änderungszählers des Kontos bei der letzten Änderung (Cursor).
  seq INTEGER NOT NULL,
  PRIMARY KEY (uuid, id)
);
CREATE INDEX IF NOT EXISTS sync_notes_seq ON sync_notes(uuid, seq);
CREATE INDEX IF NOT EXISTS sync_notes_world ON sync_notes(uuid, world_type, world_ref, deleted);
CREATE INDEX IF NOT EXISTS sync_notes_tombstones ON sync_notes(deleted, deleted_at);

-- Änderungszähler je Konto; horizon = höchster seq entfernter Grabsteine (ältere Cursor → komplette Liste).
CREATE TABLE IF NOT EXISTS sync_note_state (
  uuid TEXT PRIMARY KEY REFERENCES users(uuid) ON DELETE CASCADE,
  seq INTEGER NOT NULL DEFAULT 0,
  horizon INTEGER NOT NULL DEFAULT 0
);
`)
}

/** Migration 19 (siehe oben). Exportiert für den Idempotenz-Test. */
export function migrateAchievements(db: DatabaseSync): void {
  db.exec(`
CREATE TABLE IF NOT EXISTS achievement_unlocks (
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  achievement_id TEXT NOT NULL CHECK (length(achievement_id) BETWEEN 1 AND 40),
  unlocked_at INTEGER NOT NULL,
  -- Belohnung vergeben (NULL = keine oder noch nicht – das Teil gibt es noch nicht).
  reward_granted_at INTEGER,
  PRIMARY KEY (uuid, achievement_id)
);
CREATE INDEX IF NOT EXISTS achievement_unlocks_pending ON achievement_unlocks(achievement_id) WHERE reward_granted_at IS NULL;

-- Zähler und Flags je Konto (key aus der festen Liste in achievement-catalog.ts).
CREATE TABLE IF NOT EXISTS achievement_stats (
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  key TEXT NOT NULL CHECK (length(key) BETWEEN 1 AND 40),
  value INTEGER NOT NULL CHECK (value >= 0),
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (uuid, key)
);

-- Spielzeit aus den In-Game-Herzschlägen (§31.7): nur Summen, kein Verlauf. Tage = UTC-Tage seit 1970.
CREATE TABLE IF NOT EXISTS achievement_playtime (
  uuid TEXT PRIMARY KEY REFERENCES users(uuid) ON DELETE CASCADE,
  played_ms INTEGER NOT NULL DEFAULT 0,
  -- letzter In-Game-Herzschlag (NULL = gerade nicht im Spiel)
  last_beat_at INTEGER,
  session_ms INTEGER NOT NULL DEFAULT 0,
  best_session_ms INTEGER NOT NULL DEFAULT 0,
  last_day INTEGER,
  streak INTEGER NOT NULL DEFAULT 0,
  best_streak INTEGER NOT NULL DEFAULT 0
);
`)
  // Erfolge für Freunde sichtbar (Standard: ja). Liegt am Konto, geht also mit ihm.
  if (hasTable(db, 'users') && !hasColumn(db, 'users', 'achievements_visible')) {
    db.exec('ALTER TABLE users ADD COLUMN achievements_visible INTEGER NOT NULL DEFAULT 1')
  }
}

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

/** Migration 12 (siehe oben). Exportiert für den Idempotenz-Test. */
export function migrateTeamV3(db: DatabaseSync): void {
  db.exec(`
CREATE TABLE IF NOT EXISTS team_roles (
  id TEXT PRIMARY KEY,
  -- NULL bei festen Rollen = übersetzter Standardname.
  name TEXT,
  color TEXT NOT NULL,
  rank INTEGER NOT NULL UNIQUE CHECK (rank BETWEEN 1 AND 1000),
  -- JSON-Liste der Rechte (shared/team.ts).
  permissions TEXT NOT NULL,
  -- Höchstdauer befristeter Strafen in Minuten, NULL = unbegrenzt.
  max_sanction_minutes INTEGER,
  builtin INTEGER NOT NULL DEFAULT 0,
  public INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

-- Mehrere Rollen je Mitglied; die ranghöchste ist die Hauptrolle.
CREATE TABLE IF NOT EXISTS team_members (
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  role_id TEXT NOT NULL REFERENCES team_roles(id) ON DELETE CASCADE,
  granted_at INTEGER NOT NULL,
  granted_by TEXT NOT NULL,
  note TEXT,
  PRIMARY KEY (uuid, role_id)
);
CREATE INDEX IF NOT EXISTS team_members_role ON team_members(role_id);

-- Stellenausschreibungen: Texte + Formular als JSON (je Sprache), verknüpfte Rolle optional.
CREATE TABLE IF NOT EXISTS team_jobs (
  id TEXT PRIMARY KEY CHECK (length(id) BETWEEN 3 AND 48),
  status TEXT NOT NULL CHECK (status IN ('draft', 'open', 'closed')),
  role_id TEXT REFERENCES team_roles(id) ON DELETE SET NULL,
  sort INTEGER NOT NULL DEFAULT 0,
  cooldown_days INTEGER NOT NULL DEFAULT 30 CHECK (cooldown_days BETWEEN 0 AND 365),
  texts TEXT NOT NULL,
  form TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  created_by TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

-- Bewerbungen. Löschen des Kontos löscht sie (CASCADE). retain_until = Löschfrist (s. applications.ts).
CREATE TABLE IF NOT EXISTS team_applications (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL REFERENCES team_jobs(id) ON DELETE RESTRICT,
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  name TEXT NOT NULL,
  discord TEXT NOT NULL,
  age_group TEXT NOT NULL CHECK (age_group IN ('under14', '14-15', '16-17', '18+')),
  -- JSON: Antworten + Formular zum Zeitpunkt der Bewerbung (spätere Änderungen an der Stelle ändern nichts).
  answers TEXT NOT NULL,
  form TEXT NOT NULL,
  lang TEXT NOT NULL CHECK (lang IN ('en', 'de', 'es')),
  status TEXT NOT NULL CHECK (status IN ('new', 'review', 'interview', 'accepted', 'rejected', 'withdrawn')),
  response TEXT,
  role_granted TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  decided_at INTEGER,
  decided_by TEXT,
  retain_until INTEGER
);
CREATE INDEX IF NOT EXISTS team_applications_uuid ON team_applications(uuid, job_id);
CREATE INDEX IF NOT EXISTS team_applications_status ON team_applications(status, created_at);
-- Höchstens EINE offene Bewerbung je Stelle und Konto.
CREATE UNIQUE INDEX IF NOT EXISTS team_applications_open ON team_applications(job_id, uuid) WHERE status IN ('new', 'review', 'interview');

-- Abstimmung im Team: je Mitglied eine Stimme (+1/-1) mit Kommentar.
CREATE TABLE IF NOT EXISTS team_application_votes (
  application_id TEXT NOT NULL REFERENCES team_applications(id) ON DELETE CASCADE,
  uuid TEXT NOT NULL,
  vote INTEGER NOT NULL CHECK (vote IN (-1, 1)),
  comment TEXT,
  at INTEGER NOT NULL,
  PRIMARY KEY (application_id, uuid)
);

-- Interne Notizen + Verlauf (Statuswechsel, Stimmen, Rolle) je Bewerbung.
CREATE TABLE IF NOT EXISTS team_application_notes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  application_id TEXT NOT NULL REFERENCES team_applications(id) ON DELETE CASCADE,
  at INTEGER NOT NULL,
  actor TEXT NOT NULL,
  text TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS team_application_notes_app ON team_application_notes(application_id, at);

CREATE TABLE IF NOT EXISTS team_application_history (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  application_id TEXT NOT NULL REFERENCES team_applications(id) ON DELETE CASCADE,
  at INTEGER NOT NULL,
  actor TEXT NOT NULL,
  action TEXT NOT NULL,
  detail TEXT
);
CREATE INDEX IF NOT EXISTS team_application_history_app ON team_application_history(application_id, at);
`)

  // Feste Rollen (fehlende anlegen, Anpassungen bleiben).
  const now = Date.now()
  for (const r of BUILTIN_ROLES) {
    if (db.prepare('SELECT 1 AS x FROM team_roles WHERE id = ?').get(r.id)) continue
    let rank = r.rank
    while (rank > 1 && db.prepare('SELECT 1 AS x FROM team_roles WHERE rank = ?').get(rank)) rank--
    db.prepare(
      `INSERT INTO team_roles (id, name, color, rank, permissions, max_sanction_minutes, builtin, public, created_at, updated_at)
       VALUES (?, NULL, ?, ?, ?, ?, 1, 1, ?, ?)`,
    ).run(r.id, r.color, rank, JSON.stringify(r.permissions), r.maxSanctionMinutes, now, now)
  }

  // staff_roles (admin/moderator) → Rollen „admin“ bzw. „moderator“, verlustfrei (Zeitpunkt, Vergeber, Notiz).
  if (hasTable(db, 'staff_roles')) {
    db.exec(`
INSERT OR IGNORE INTO team_members (uuid, role_id, granted_at, granted_by, note)
SELECT uuid, CASE role WHEN 'admin' THEN 'admin' ELSE 'moderator' END, granted_at, granted_by, note FROM staff_roles;
DROP TABLE staff_roles;
`)
  }

  // Rang des Erstellers je Strafe (Rang-Regel beim Ändern). Altdaten: admin 900, moderator 500, system 0.
  if (!hasColumn(db, 'sanctions', 'created_rank')) db.exec('ALTER TABLE sanctions ADD COLUMN created_rank INTEGER')
  db.exec("UPDATE sanctions SET created_rank = CASE created_role WHEN 'admin' THEN 900 WHEN 'moderator' THEN 500 ELSE 0 END WHERE created_rank IS NULL")

  // Website-Login nur noch über Microsoft: Codes weg, alte (Team-)Sitzungen enden.
  db.exec('DROP TABLE IF EXISTS web_logins')
  db.exec('DELETE FROM web_sessions')
}

/** Migration 14 (siehe oben). Exportiert für den Idempotenz-Test. */
export function migrateCircuits(db: DatabaseSync): void {
  db.exec(`
CREATE TABLE IF NOT EXISTS circuits (
  id TEXT PRIMARY KEY CHECK (length(id) BETWEEN 1 AND 48),
  rev INTEGER NOT NULL CHECK (rev >= 1),
  status TEXT NOT NULL CHECK (status IN ('draft', 'published', 'hidden')),
  category TEXT NOT NULL,
  difficulty INTEGER NOT NULL CHECK (difficulty BETWEEN 1 AND 3),
  -- Wirksame Versionen (höchste aus since und Blöcken) bzw. until.
  min_version TEXT,
  max_version TEXT,
  sort INTEGER NOT NULL DEFAULT 0,
  -- Schaltung im Client-Format (JSON, geprüft, ohne rev/author).
  data TEXT NOT NULL,
  -- SHA-256 des kanonischen Inhalts (Duplikat-Erkennung).
  content_hash TEXT NOT NULL,
  -- Ersteller bei angenommenen Einreichungen; NULL = Team.
  author_uuid TEXT,
  author_name TEXT,
  source TEXT NOT NULL CHECK (source IN ('seed', 'team', 'submission')),
  -- Prüfsumme der Seed-Datei (nur source = seed); edited = 1 nach Änderung im Team-Bereich → Seed lässt sie in Ruhe.
  seed_hash TEXT,
  edited INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  published_at INTEGER,
  created_by TEXT NOT NULL,
  updated_by TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS circuits_status ON circuits(status, sort, id);
CREATE INDEX IF NOT EXISTS circuits_hash ON circuits(content_hash);
CREATE INDEX IF NOT EXISTS circuits_author ON circuits(author_uuid);

CREATE TABLE IF NOT EXISTS circuit_tombstones (
  id TEXT PRIMARY KEY,
  deleted_at INTEGER NOT NULL,
  deleted_by TEXT NOT NULL
);

-- Einreichungen. Konto gelöscht → Einreichungen weg (CASCADE); Löschfrist nach der Entscheidung s. circuits.ts.
CREATE TABLE IF NOT EXISTS circuit_submissions (
  id TEXT PRIMARY KEY,
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  -- Spielername bei der Einreichung.
  name TEXT NOT NULL,
  title TEXT NOT NULL,
  description TEXT NOT NULL,
  category TEXT NOT NULL,
  lang TEXT NOT NULL CHECK (lang IN ('en', 'de', 'es')),
  data TEXT NOT NULL,
  content_hash TEXT NOT NULL,
  source_format TEXT NOT NULL CHECK (source_format IN ('json', 'litematic', 'schem', 'nbt')),
  status TEXT NOT NULL CHECK (status IN ('pending', 'approved', 'rejected')),
  reason TEXT,
  circuit_id TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  decided_at INTEGER,
  decided_by TEXT
);
CREATE INDEX IF NOT EXISTS circuit_submissions_uuid ON circuit_submissions(uuid, created_at);
CREATE INDEX IF NOT EXISTS circuit_submissions_status ON circuit_submissions(status, created_at);
CREATE INDEX IF NOT EXISTS circuit_submissions_hash ON circuit_submissions(content_hash);
`)

  // Recht circuits.manage für die Standardrollen ergänzen (Anpassungen der Admins bleiben erhalten).
  if (hasTable(db, 'team_roles')) {
    for (const id of ['owner', 'admin', 'senior_moderator', 'content']) {
      const row = db.prepare('SELECT permissions FROM team_roles WHERE id = ? AND builtin = 1').get(id) as { permissions: string } | undefined
      if (!row) continue
      let perms: string[] = []
      try {
        const parsed = JSON.parse(row.permissions) as unknown
        if (Array.isArray(parsed)) perms = parsed.filter((p): p is string => typeof p === 'string')
      } catch {
        perms = []
      }
      if (perms.includes('circuits.manage')) continue
      perms.push('circuits.manage')
      db.prepare('UPDATE team_roles SET permissions = ?, updated_at = ? WHERE id = ?').run(JSON.stringify(perms), Date.now(), id)
    }
  }

  // Meldungen: Art `circuit` + Spalte circuit_id.
  if (hasTable(db, 'chat_reports') && !hasColumn(db, 'chat_reports', 'circuit_id')) {
    db.exec(`
CREATE TABLE chat_reports_v14 (
  id TEXT PRIMARY KEY,
  reporter_uuid TEXT REFERENCES users(uuid) ON DELETE SET NULL,
  target_uuid TEXT CHECK (target_uuid IS NULL OR length(target_uuid) = 32),
  kind TEXT NOT NULL CHECK (kind IN ('message', 'image', 'player', 'group', 'share', 'circuit')),
  conversation_id TEXT,
  message_id TEXT,
  attachment_id TEXT,
  share_id TEXT,
  circuit_id TEXT,
  reason TEXT NOT NULL CHECK (reason IN ('insult_hate', 'spam', 'inappropriate', 'scam_phishing', 'harassment', 'other')),
  note BLOB,
  evidence BLOB,
  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'in_review', 'resolved')),
  outcome TEXT CHECK (outcome IS NULL OR outcome IN ('actioned', 'dismissed')),
  low_trust INTEGER NOT NULL DEFAULT 0,
  assigned_to TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  resolved_at INTEGER,
  resolved_by TEXT,
  evidence_purged_at INTEGER,
  CHECK ((status = 'resolved') = (outcome IS NOT NULL))
);
INSERT INTO chat_reports_v14 (id, reporter_uuid, target_uuid, kind, conversation_id, message_id, attachment_id, share_id, circuit_id, reason,
  note, evidence, status, outcome, low_trust, assigned_to, created_at, updated_at, resolved_at, resolved_by, evidence_purged_at)
SELECT id, reporter_uuid, target_uuid, kind, conversation_id, message_id, attachment_id, share_id, NULL, reason,
  note, evidence, status, outcome, low_trust, assigned_to, created_at, updated_at, resolved_at, resolved_by, evidence_purged_at
FROM chat_reports;

CREATE TABLE chat_report_notes_v14 (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  report_id TEXT NOT NULL REFERENCES chat_reports_v14(id) ON DELETE CASCADE,
  at INTEGER NOT NULL,
  actor TEXT NOT NULL,
  text BLOB NOT NULL
);
INSERT INTO chat_report_notes_v14 (id, report_id, at, actor, text) SELECT id, report_id, at, actor, text FROM chat_report_notes;

CREATE TABLE chat_evidence_files_v14 (
  report_id TEXT NOT NULL REFERENCES chat_reports_v14(id) ON DELETE CASCADE,
  attachment_id TEXT NOT NULL,
  mime TEXT NOT NULL,
  width INTEGER NOT NULL,
  height INTEGER NOT NULL,
  bytes INTEGER NOT NULL,
  key_id TEXT NOT NULL,
  PRIMARY KEY (report_id, attachment_id)
);
INSERT INTO chat_evidence_files_v14 (report_id, attachment_id, mime, width, height, bytes, key_id)
SELECT report_id, attachment_id, mime, width, height, bytes, key_id FROM chat_evidence_files;

DROP TABLE chat_evidence_files;
DROP TABLE chat_report_notes;
DROP TABLE chat_reports;
ALTER TABLE chat_reports_v14 RENAME TO chat_reports;
ALTER TABLE chat_report_notes_v14 RENAME TO chat_report_notes;
ALTER TABLE chat_evidence_files_v14 RENAME TO chat_evidence_files;
CREATE INDEX chat_reports_status ON chat_reports(status, created_at);
CREATE INDEX chat_reports_target ON chat_reports(target_uuid, created_at);
CREATE INDEX chat_reports_reporter ON chat_reports(reporter_uuid, status);
CREATE INDEX chat_reports_share ON chat_reports(share_id);
CREATE INDEX chat_reports_circuit ON chat_reports(circuit_id);
CREATE INDEX chat_report_notes_report ON chat_report_notes(report_id);
`)
  }
}

/** Migration 15 (siehe oben). Exportiert für den Idempotenz-Test. */
export function migrateTeamPage(db: DatabaseSync): void {
  if (!hasColumn(db, 'users', 'skin_url')) db.exec('ALTER TABLE users ADD COLUMN skin_url TEXT')
  if (!hasColumn(db, 'users', 'skin_model')) db.exec("ALTER TABLE users ADD COLUMN skin_model TEXT CHECK (skin_model IS NULL OR skin_model IN ('classic', 'slim'))")
  if (!hasColumn(db, 'users', 'skin_at')) db.exec('ALTER TABLE users ADD COLUMN skin_at INTEGER')
  db.exec(`
-- Öffentliche Team-Seite: nur wer hier steht, wird gezeigt. role_id = Gruppe (NULL, wenn die Rolle gelöscht wurde).
CREATE TABLE IF NOT EXISTS team_page_members (
  uuid TEXT PRIMARY KEY REFERENCES users(uuid) ON DELETE CASCADE,
  role_id TEXT REFERENCES team_roles(id) ON DELETE SET NULL,
  sort INTEGER NOT NULL DEFAULT 0,
  -- {"en": "...", "de": "...", "es": "..."}
  titles TEXT NOT NULL DEFAULT '{}',
  discord TEXT,
  -- [{"label": "...", "url": "https://..."}]
  links TEXT NOT NULL DEFAULT '[]',
  added_at INTEGER NOT NULL,
  added_by TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS team_page_members_role ON team_page_members(role_id, sort);
`)
  // Recht team.page für Owner/Admin ergänzen (angepasste Rechte bleiben erhalten).
  if (hasTable(db, 'team_roles')) {
    for (const id of ['owner', 'admin']) {
      const row = db.prepare('SELECT permissions FROM team_roles WHERE id = ? AND builtin = 1').get(id) as { permissions: string } | undefined
      if (!row) continue
      let perms: string[] = []
      try {
        const parsed = JSON.parse(row.permissions) as unknown
        if (Array.isArray(parsed)) perms = parsed.filter((p): p is string => typeof p === 'string')
      } catch {
        perms = []
      }
      if (perms.includes('team.page')) continue
      perms.push('team.page')
      db.prepare('UPDATE team_roles SET permissions = ?, updated_at = ? WHERE id = ?').run(JSON.stringify(perms), Date.now(), id)
    }
  }
}

/** Migration 16 (siehe oben). Exportiert für den Idempotenz-Test. */
export function migrateSharedPacks(db: DatabaseSync): void {
  db.exec(`
CREATE TABLE IF NOT EXISTS shared_packs (
  id TEXT PRIMARY KEY CHECK (length(id) = 22),
  -- 8 Zeichen Crockford-Base32, angezeigt als TRS-XXXX-XXXX.
  code TEXT NOT NULL UNIQUE CHECK (length(code) = 8),
  owner_uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  name TEXT NOT NULL,
  summary TEXT,
  pack_version TEXT NOT NULL,
  mc_version TEXT NOT NULL,
  loader TEXT NOT NULL CHECK (loader IN ('vanilla', 'forge', 'neoforge', 'fabric', 'quilt')),
  loader_version TEXT,
  index_files INTEGER NOT NULL,
  own_jars INTEGER NOT NULL,
  other_files INTEGER NOT NULL,
  revision INTEGER NOT NULL CHECK (revision >= 1),
  bytes INTEGER NOT NULL,
  sha256 TEXT NOT NULL,
  duration TEXT NOT NULL CHECK (duration IN ('1d', '7d', '30d', 'forever')),
  installs INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  expires_at INTEGER
);
CREATE INDEX IF NOT EXISTS shared_packs_owner ON shared_packs(owner_uuid, updated_at);
CREATE INDEX IF NOT EXISTS shared_packs_expires ON shared_packs(expires_at);

CREATE TABLE IF NOT EXISTS shared_pack_recipients (
  pack_id TEXT NOT NULL REFERENCES shared_packs(id) ON DELETE CASCADE,
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  from_uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  dismissed_at INTEGER,
  PRIMARY KEY (pack_id, uuid)
);
CREATE INDEX IF NOT EXISTS shared_pack_recipients_uuid ON shared_pack_recipients(uuid, created_at);

CREATE TABLE IF NOT EXISTS shared_pack_uploads (
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS shared_pack_uploads_uuid ON shared_pack_uploads(uuid, at);
`)

  // Meldungen: Art `pack` + Spalte pack_id.
  if (hasTable(db, 'chat_reports') && !hasColumn(db, 'chat_reports', 'pack_id')) {
    db.exec(`
CREATE TABLE chat_reports_v16 (
  id TEXT PRIMARY KEY,
  reporter_uuid TEXT REFERENCES users(uuid) ON DELETE SET NULL,
  target_uuid TEXT CHECK (target_uuid IS NULL OR length(target_uuid) = 32),
  kind TEXT NOT NULL CHECK (kind IN ('message', 'image', 'player', 'group', 'share', 'circuit', 'pack')),
  conversation_id TEXT,
  message_id TEXT,
  attachment_id TEXT,
  share_id TEXT,
  circuit_id TEXT,
  pack_id TEXT,
  reason TEXT NOT NULL CHECK (reason IN ('insult_hate', 'spam', 'inappropriate', 'scam_phishing', 'harassment', 'other')),
  note BLOB,
  evidence BLOB,
  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'in_review', 'resolved')),
  outcome TEXT CHECK (outcome IS NULL OR outcome IN ('actioned', 'dismissed')),
  low_trust INTEGER NOT NULL DEFAULT 0,
  assigned_to TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  resolved_at INTEGER,
  resolved_by TEXT,
  evidence_purged_at INTEGER,
  CHECK ((status = 'resolved') = (outcome IS NOT NULL))
);
INSERT INTO chat_reports_v16 (id, reporter_uuid, target_uuid, kind, conversation_id, message_id, attachment_id, share_id, circuit_id, pack_id,
  reason, note, evidence, status, outcome, low_trust, assigned_to, created_at, updated_at, resolved_at, resolved_by, evidence_purged_at)
SELECT id, reporter_uuid, target_uuid, kind, conversation_id, message_id, attachment_id, share_id, circuit_id, NULL,
  reason, note, evidence, status, outcome, low_trust, assigned_to, created_at, updated_at, resolved_at, resolved_by, evidence_purged_at
FROM chat_reports;

CREATE TABLE chat_report_notes_v16 (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  report_id TEXT NOT NULL REFERENCES chat_reports_v16(id) ON DELETE CASCADE,
  at INTEGER NOT NULL,
  actor TEXT NOT NULL,
  text BLOB NOT NULL
);
INSERT INTO chat_report_notes_v16 (id, report_id, at, actor, text) SELECT id, report_id, at, actor, text FROM chat_report_notes;

CREATE TABLE chat_evidence_files_v16 (
  report_id TEXT NOT NULL REFERENCES chat_reports_v16(id) ON DELETE CASCADE,
  attachment_id TEXT NOT NULL,
  mime TEXT NOT NULL,
  width INTEGER NOT NULL,
  height INTEGER NOT NULL,
  bytes INTEGER NOT NULL,
  key_id TEXT NOT NULL,
  PRIMARY KEY (report_id, attachment_id)
);
INSERT INTO chat_evidence_files_v16 (report_id, attachment_id, mime, width, height, bytes, key_id)
SELECT report_id, attachment_id, mime, width, height, bytes, key_id FROM chat_evidence_files;

DROP TABLE chat_evidence_files;
DROP TABLE chat_report_notes;
DROP TABLE chat_reports;
ALTER TABLE chat_reports_v16 RENAME TO chat_reports;
ALTER TABLE chat_report_notes_v16 RENAME TO chat_report_notes;
ALTER TABLE chat_evidence_files_v16 RENAME TO chat_evidence_files;
CREATE INDEX chat_reports_status ON chat_reports(status, created_at);
CREATE INDEX chat_reports_target ON chat_reports(target_uuid, created_at);
CREATE INDEX chat_reports_reporter ON chat_reports(reporter_uuid, status);
CREATE INDEX chat_reports_share ON chat_reports(share_id);
CREATE INDEX chat_reports_circuit ON chat_reports(circuit_id);
CREATE INDEX chat_reports_pack ON chat_reports(pack_id);
CREATE INDEX chat_report_notes_report ON chat_report_notes(report_id);
`)
  }
}

/** Standardrollen um Rechte ergänzen (angepasste Rechte bleiben erhalten, fehlende Rollen werden übersprungen). */
function grantBuiltin(db: DatabaseSync, grants: Record<string, string[]>): void {
  if (!hasTable(db, 'team_roles')) return
  for (const [id, add] of Object.entries(grants)) {
    const row = db.prepare('SELECT permissions FROM team_roles WHERE id = ? AND builtin = 1').get(id) as { permissions: string } | undefined
    if (!row) continue
    let perms: string[] = []
    try {
      const parsed = JSON.parse(row.permissions) as unknown
      if (Array.isArray(parsed)) perms = parsed.filter((p): p is string => typeof p === 'string')
    } catch {
      perms = []
    }
    const missing = add.filter((p) => !perms.includes(p))
    if (missing.length === 0) continue
    db.prepare('UPDATE team_roles SET permissions = ?, updated_at = ? WHERE id = ?').run(JSON.stringify([...perms, ...missing]), Date.now(), id)
  }
}

/** Migration 17 (siehe oben). Exportiert für den Idempotenz-Test. */
export function migrateIssues(db: DatabaseSync): void {
  db.exec(`
CREATE TABLE IF NOT EXISTS issues (
  -- Öffentliche Nummer (#57).
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  type TEXT NOT NULL CHECK (type IN ('bug', 'feature')),
  area TEXT NOT NULL CHECK (area IN ('launcher', 'client', 'website')),
  status TEXT NOT NULL CHECK (status IN ('open', 'planned', 'in_progress', 'in_review', 'done', 'rejected', 'duplicate')),
  title TEXT NOT NULL,
  description TEXT NOT NULL,
  -- NULL = Konto gelöscht.
  author_uuid TEXT REFERENCES users(uuid) ON DELETE SET NULL,
  -- Ersteller war beim Anlegen im Team (Abzeichen).
  author_team INTEGER NOT NULL DEFAULT 0,
  source TEXT NOT NULL CHECK (source IN ('web', 'client')),
  -- JSON {modVersion, mcVersion, loader, mods} (öffentlich); das Log extra (nur Team + Ersteller).
  meta TEXT,
  log TEXT,
  priority TEXT CHECK (priority IS NULL OR priority IN ('low', 'medium', 'high', 'critical')),
  assignee_uuid TEXT REFERENCES users(uuid) ON DELETE SET NULL,
  fixed_in TEXT,
  duplicate_of INTEGER REFERENCES issues(id) ON DELETE SET NULL,
  locked INTEGER NOT NULL DEFAULT 0,
  up INTEGER NOT NULL DEFAULT 0,
  down INTEGER NOT NULL DEFAULT 0,
  score INTEGER NOT NULL DEFAULT 0,
  comments INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  edited_at INTEGER,
  activity_at INTEGER NOT NULL,
  closed_at INTEGER,
  deleted_at INTEGER,
  deleted_by TEXT,
  deleted_reason TEXT
);
CREATE INDEX IF NOT EXISTS issues_score ON issues(deleted_at, score DESC, id DESC);
CREATE INDEX IF NOT EXISTS issues_created ON issues(deleted_at, created_at DESC);
CREATE INDEX IF NOT EXISTS issues_activity ON issues(deleted_at, activity_at DESC);
CREATE INDEX IF NOT EXISTS issues_status ON issues(status, closed_at);
CREATE INDEX IF NOT EXISTS issues_author ON issues(author_uuid, created_at);
CREATE INDEX IF NOT EXISTS issues_assignee ON issues(assignee_uuid);

-- Je Konto eine Stimme (+1/-1), änderbar.
CREATE TABLE IF NOT EXISTS issue_votes (
  issue_id INTEGER NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  vote INTEGER NOT NULL CHECK (vote IN (-1, 1)),
  at INTEGER NOT NULL,
  PRIMARY KEY (issue_id, uuid)
);
CREATE INDEX IF NOT EXISTS issue_votes_uuid ON issue_votes(uuid);

CREATE TABLE IF NOT EXISTS issue_follows (
  issue_id INTEGER NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  at INTEGER NOT NULL,
  PRIMARY KEY (issue_id, uuid)
);
CREATE INDEX IF NOT EXISTS issue_follows_uuid ON issue_follows(uuid, at);

CREATE TABLE IF NOT EXISTS issue_comments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  issue_id INTEGER NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
  author_uuid TEXT REFERENCES users(uuid) ON DELETE SET NULL,
  team INTEGER NOT NULL DEFAULT 0,
  -- NULL = gelöscht (Platzhalter bleibt).
  body TEXT,
  created_at INTEGER NOT NULL,
  edited_at INTEGER,
  deleted_at INTEGER,
  deleted_by TEXT CHECK (deleted_by IS NULL OR deleted_by IN ('author', 'team'))
);
CREATE INDEX IF NOT EXISTS issue_comments_issue ON issue_comments(issue_id, id);
CREATE INDEX IF NOT EXISTS issue_comments_author ON issue_comments(author_uuid);

-- Hochgeladene Bilder: erst lose (issue_id NULL, 1 h gültig), dann an Issue bzw. Kommentar gebunden.
CREATE TABLE IF NOT EXISTS issue_uploads (
  id TEXT PRIMARY KEY CHECK (length(id) = 22),
  owner_uuid TEXT REFERENCES users(uuid) ON DELETE SET NULL,
  issue_id INTEGER REFERENCES issues(id) ON DELETE CASCADE,
  comment_id INTEGER REFERENCES issue_comments(id) ON DELETE CASCADE,
  sort INTEGER NOT NULL DEFAULT 0,
  mime TEXT NOT NULL CHECK (mime IN ('image/png', 'image/jpeg')),
  width INTEGER NOT NULL,
  height INTEGER NOT NULL,
  bytes INTEGER NOT NULL,
  thumb_mime TEXT NOT NULL CHECK (thumb_mime IN ('image/png', 'image/jpeg')),
  thumb_width INTEGER NOT NULL,
  thumb_height INTEGER NOT NULL,
  thumb_bytes INTEGER NOT NULL,
  sha256 TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS issue_uploads_issue ON issue_uploads(issue_id, comment_id, sort);
CREATE INDEX IF NOT EXISTS issue_uploads_owner ON issue_uploads(owner_uuid, created_at);

CREATE TABLE IF NOT EXISTS issue_tags (
  issue_id INTEGER NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
  tag TEXT NOT NULL,
  PRIMARY KEY (issue_id, tag)
);
CREATE INDEX IF NOT EXISTS issue_tags_tag ON issue_tags(tag);

-- Interne Notizen des Teams (nie öffentlich).
CREATE TABLE IF NOT EXISTS issue_notes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  issue_id INTEGER NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
  at INTEGER NOT NULL,
  actor TEXT NOT NULL,
  text TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS issue_notes_issue ON issue_notes(issue_id, at);

-- Öffentlicher Verlauf (Status, Zuständig, Priorität, „Erledigt in“, Zusammenführen …).
CREATE TABLE IF NOT EXISTS issue_history (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  issue_id INTEGER NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
  at INTEGER NOT NULL,
  actor TEXT,
  action TEXT NOT NULL,
  from_value TEXT,
  to_value TEXT
);
CREATE INDEX IF NOT EXISTS issue_history_issue ON issue_history(issue_id, at);

-- Tagesgrenzen (bleiben beim Löschen, nach 24 h weg).
CREATE TABLE IF NOT EXISTS issue_actions (
  uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('issue', 'comment', 'upload')),
  at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS issue_actions_uuid ON issue_actions(uuid, kind, at);
`)

  grantBuiltin(db, {
    owner: ['issues.manage', 'issues.moderate'],
    admin: ['issues.manage', 'issues.moderate'],
    senior_moderator: ['issues.manage', 'issues.moderate'],
    moderator: ['issues.moderate'],
    supporter: ['issues.manage'],
  })

  // Meldungen: Arten `issue` + `issue_comment`, Spalten issue_id + issue_comment_id.
  if (hasTable(db, 'chat_reports') && !hasColumn(db, 'chat_reports', 'issue_id')) {
    db.exec(`
CREATE TABLE chat_reports_v17 (
  id TEXT PRIMARY KEY,
  reporter_uuid TEXT REFERENCES users(uuid) ON DELETE SET NULL,
  target_uuid TEXT CHECK (target_uuid IS NULL OR length(target_uuid) = 32),
  kind TEXT NOT NULL CHECK (kind IN ('message', 'image', 'player', 'group', 'share', 'circuit', 'pack', 'issue', 'issue_comment')),
  conversation_id TEXT,
  message_id TEXT,
  attachment_id TEXT,
  share_id TEXT,
  circuit_id TEXT,
  pack_id TEXT,
  issue_id INTEGER,
  issue_comment_id INTEGER,
  reason TEXT NOT NULL CHECK (reason IN ('insult_hate', 'spam', 'inappropriate', 'scam_phishing', 'harassment', 'other')),
  note BLOB,
  evidence BLOB,
  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'in_review', 'resolved')),
  outcome TEXT CHECK (outcome IS NULL OR outcome IN ('actioned', 'dismissed')),
  low_trust INTEGER NOT NULL DEFAULT 0,
  assigned_to TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  resolved_at INTEGER,
  resolved_by TEXT,
  evidence_purged_at INTEGER,
  CHECK ((status = 'resolved') = (outcome IS NOT NULL))
);
INSERT INTO chat_reports_v17 (id, reporter_uuid, target_uuid, kind, conversation_id, message_id, attachment_id, share_id, circuit_id, pack_id,
  issue_id, issue_comment_id, reason, note, evidence, status, outcome, low_trust, assigned_to, created_at, updated_at, resolved_at, resolved_by,
  evidence_purged_at)
SELECT id, reporter_uuid, target_uuid, kind, conversation_id, message_id, attachment_id, share_id, circuit_id, pack_id,
  NULL, NULL, reason, note, evidence, status, outcome, low_trust, assigned_to, created_at, updated_at, resolved_at, resolved_by,
  evidence_purged_at
FROM chat_reports;

CREATE TABLE chat_report_notes_v17 (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  report_id TEXT NOT NULL REFERENCES chat_reports_v17(id) ON DELETE CASCADE,
  at INTEGER NOT NULL,
  actor TEXT NOT NULL,
  text BLOB NOT NULL
);
INSERT INTO chat_report_notes_v17 (id, report_id, at, actor, text) SELECT id, report_id, at, actor, text FROM chat_report_notes;

CREATE TABLE chat_evidence_files_v17 (
  report_id TEXT NOT NULL REFERENCES chat_reports_v17(id) ON DELETE CASCADE,
  attachment_id TEXT NOT NULL,
  mime TEXT NOT NULL,
  width INTEGER NOT NULL,
  height INTEGER NOT NULL,
  bytes INTEGER NOT NULL,
  key_id TEXT NOT NULL,
  PRIMARY KEY (report_id, attachment_id)
);
INSERT INTO chat_evidence_files_v17 (report_id, attachment_id, mime, width, height, bytes, key_id)
SELECT report_id, attachment_id, mime, width, height, bytes, key_id FROM chat_evidence_files;

DROP TABLE chat_evidence_files;
DROP TABLE chat_report_notes;
DROP TABLE chat_reports;
ALTER TABLE chat_reports_v17 RENAME TO chat_reports;
ALTER TABLE chat_report_notes_v17 RENAME TO chat_report_notes;
ALTER TABLE chat_evidence_files_v17 RENAME TO chat_evidence_files;
CREATE INDEX chat_reports_status ON chat_reports(status, created_at);
CREATE INDEX chat_reports_target ON chat_reports(target_uuid, created_at);
CREATE INDEX chat_reports_reporter ON chat_reports(reporter_uuid, status);
CREATE INDEX chat_reports_share ON chat_reports(share_id);
CREATE INDEX chat_reports_circuit ON chat_reports(circuit_id);
CREATE INDEX chat_reports_pack ON chat_reports(pack_id);
CREATE INDEX chat_reports_issue ON chat_reports(issue_id);
CREATE INDEX chat_reports_issue_comment ON chat_reports(issue_comment_id);
CREATE INDEX chat_report_notes_report ON chat_report_notes(report_id);
`)
  }
}

/** Migration 18 (siehe oben). Exportiert für den Idempotenz-Test. */
export function migrateLauncherLoginBlog(db: DatabaseSync): void {
  db.exec(`
-- Anmelde-Anfragen der Website an den TRS Launcher (§29). Gespeichert werden nur Hashes von Link-Token und
-- Browser-Wert; die Zeile verschwindet nach dem Einlösen bzw. kurz nach dem Ablauf.
CREATE TABLE IF NOT EXISTS launcher_logins (
  id TEXT PRIMARY KEY CHECK (length(id) = 22),
  token_hash TEXT NOT NULL UNIQUE,
  code TEXT NOT NULL UNIQUE CHECK (length(code) = 6),
  browser_hash TEXT NOT NULL,
  -- grobe Angabe wie „Firefox · Windows“ (nie der ganze User-Agent)
  browser TEXT,
  return_to TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'denied')),
  uuid TEXT REFERENCES users(uuid) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  decided_at INTEGER,
  CHECK ((status = 'approved') = (uuid IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS launcher_logins_expires ON launcher_logins(expires_at);
CREATE INDEX IF NOT EXISTS launcher_logins_browser ON launcher_logins(browser_hash);

-- Blog-Beiträge (News). texts = {"en": {"title", "summary", "body"}, "de": {…}, "es": {…}} (Englisch Pflicht zum
-- Veröffentlichen). status 'published' mit publish_at in der Zukunft = geplant.
CREATE TABLE IF NOT EXISTS blog_posts (
  id TEXT PRIMARY KEY CHECK (length(id) = 22),
  slug TEXT NOT NULL UNIQUE CHECK (length(slug) BETWEEN 3 AND 80),
  status TEXT NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published')),
  publish_at INTEGER,
  texts TEXT NOT NULL DEFAULT '{}',
  cover_id TEXT,
  author_uuid TEXT REFERENCES users(uuid) ON DELETE SET NULL,
  rev INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL,
  created_by TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  updated_by TEXT NOT NULL,
  CHECK (status = 'draft' OR publish_at IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS blog_posts_public ON blog_posts(status, publish_at);

-- Bilder der Beiträge (neu kodiert, öffentlich, sobald der Beitrag öffentlich ist).
CREATE TABLE IF NOT EXISTS blog_media (
  id TEXT PRIMARY KEY CHECK (length(id) = 22),
  post_id TEXT NOT NULL REFERENCES blog_posts(id) ON DELETE CASCADE,
  mime TEXT NOT NULL CHECK (mime IN ('image/png', 'image/jpeg')),
  width INTEGER NOT NULL,
  height INTEGER NOT NULL,
  bytes INTEGER NOT NULL,
  thumb_mime TEXT NOT NULL CHECK (thumb_mime IN ('image/png', 'image/jpeg')),
  thumb_width INTEGER NOT NULL,
  thumb_height INTEGER NOT NULL,
  thumb_bytes INTEGER NOT NULL,
  sha256 TEXT NOT NULL,
  uploaded_by TEXT,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS blog_media_post ON blog_media(post_id, created_at);
`)
  grantBuiltin(db, { owner: ['blog.write', 'blog.publish'], admin: ['blog.write', 'blog.publish'], content: ['blog.write'] })
}
