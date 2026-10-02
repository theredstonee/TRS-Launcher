import { rmSync } from 'node:fs'
import { join } from 'node:path'
import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { capeView, capeWearers, type CapeRow, type CapeView } from './capes'
import { notifyShareRemoved, releaseHoldings, shareHolders } from './capeshares'
import { finishChatPurge, prepareChatPurge } from './chat'
import { forgetCircuitAuthor } from './circuits'
import { endHostingFor } from './hosting'
import { forgetIssueAuthor, removeUploadFiles, type UploadRow } from './issues'
import { activeEventsFor } from './liveevents'
import { purgeModeration } from './moderation'
import { emitCape } from './playerevents'
import { packsOf, removePackFiles } from './packs'
import { packUploadTmpDirs } from './packupload'
import { removeShareFiles, sharesOf } from './shares'
import { forbidden } from './errors'
import { legacyRole, myTeamView, rankOf, teamOf, type MyTeamView } from './team'

export interface UserRow {
  uuid: string
  name: string
  name_lower: string
  created_at: number
  last_login_at: number
  show_badge: number
  show_cape: number
  presence_visibility: 'friends' | 'nobody'
  share_server: number
  active_cape_id: string | null
  show_cosmetics: number
  chat_read_receipts: number
  chat_typing: number
}

export interface Settings {
  showBadge: boolean
  showCapeToOthers: boolean
  presenceVisibility: 'friends' | 'nobody'
  shareServer: boolean
  showCosmeticsToOthers: boolean
  /** Lesebestätigungen senden und sehen (gegenseitig: aus = auch keine fremden sehen). */
  chatReadReceipts: boolean
  /** „Tippt gerade“ senden und sehen (gegenseitig). */
  chatTypingIndicator: boolean
}

export type StaffRole = 'admin' | 'moderator'

export interface MeView {
  uuid: string
  name: string
  admin: boolean
  /** Altes Raster (§22.1): `admin` ab Admin-Rang, `moderator` für alle anderen Team-Mitglieder, sonst `null`. */
  role: StaffRole | null
  /** Team-Rollen und Rechte (§24.2), `null` = kein Team-Mitglied. */
  team: MyTeamView | null
  createdAt: string
  settings: Settings
  activeCape: CapeView | null
  /** Events, die für dich aktiv sind (global an ODER für dich freigegeben), z. B. `["halloween"]` (§32). */
  events: string[]
}

export function getUser(ctx: AppContext, uuid: string): UserRow | undefined {
  return one<UserRow>(ctx.db, 'SELECT * FROM users WHERE uuid = ?', uuid)
}

/** Aktuellster Nutzer mit diesem Namen (Namen können wandern – wir kennen nur den zuletzt gesehenen). */
export function getUserByName(ctx: AppContext, name: string): UserRow | undefined {
  return one<UserRow>(
    ctx.db,
    'SELECT * FROM users WHERE name_lower = ? ORDER BY last_login_at DESC LIMIT 1',
    name.toLowerCase(),
  )
}

/**
 * UUIDs mit aktivem Konto-Bann (§22.3). Als Unterabfrage mit genau EINEM Parameter: die aktuelle Zeit
 * (`ctx.now()`), z. B. `u.uuid NOT IN (${ACTIVE_BANS})`.
 */
export const ACTIVE_BANS = "SELECT uuid FROM sanctions WHERE kind = 'account_ban' AND lifted_at IS NULL AND (expires_at IS NULL OR expires_at > ?)"

export function isBanned(ctx: AppContext, uuid: string): boolean {
  return one(
    ctx.db,
    "SELECT 1 AS x FROM sanctions WHERE uuid = ? AND kind = 'account_ban' AND lifted_at IS NULL AND (expires_at IS NULL OR expires_at > ?)",
    uuid, ctx.now(),
  ) !== undefined
}

/**
 * Altes Rollen-Raster für Clients und Anzeigen: `admin` (Owner aus `ADMIN_UUIDS` oder Rang ≥ Admin), `moderator`
 * (jedes andere Team-Mitglied) oder `null`. Rechte selbst: `teamOf` in team.ts.
 */
export function staffRole(ctx: AppContext, uuid: string): StaffRole | null {
  const rank = rankOf(ctx, uuid)
  return rank > 0 ? legacyRole(rank) : null
}

export function isAdmin(ctx: AppContext, uuid: string): boolean {
  return staffRole(ctx, uuid) === 'admin'
}

/** Legt den Nutzer beim ersten Login an bzw. aktualisiert Namen + Login-Zeit. */
export function upsertOnLogin(ctx: AppContext, uuid: string, name: string): UserRow {
  const t = ctx.now()
  run(
    ctx.db,
    `INSERT INTO users (uuid, name, name_lower, created_at, last_login_at) VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, name_lower = excluded.name_lower, last_login_at = excluded.last_login_at`,
    uuid, name, name.toLowerCase(), t, t,
  )
  // Namen-Verlauf für die Spieler-Akte (§22.4).
  run(
    ctx.db,
    `INSERT INTO name_history (uuid, name, first_seen, last_seen) VALUES (?, ?, ?, ?)
     ON CONFLICT(uuid, name) DO UPDATE SET last_seen = excluded.last_seen`,
    uuid, name, t, t,
  )
  return getUser(ctx, uuid)!
}

export function settingsOf(u: UserRow): Settings {
  return {
    showBadge: u.show_badge === 1,
    showCapeToOthers: u.show_cape === 1,
    presenceVisibility: u.presence_visibility,
    shareServer: u.share_server === 1,
    showCosmeticsToOthers: u.show_cosmetics === 1,
    chatReadReceipts: u.chat_read_receipts === 1,
    chatTypingIndicator: u.chat_typing === 1,
  }
}

export function meView(ctx: AppContext, u: UserRow): MeView {
  const cape = u.active_cape_id
    ? one<CapeRow>(ctx.db, 'SELECT * FROM capes WHERE id = ?', u.active_cape_id)
    : undefined
  return {
    uuid: u.uuid,
    name: u.name,
    admin: isAdmin(ctx, u.uuid),
    role: staffRole(ctx, u.uuid),
    team: teamView(ctx, u.uuid),
    createdAt: new Date(u.created_at).toISOString(),
    settings: settingsOf(u),
    activeCape: cape ? capeView(ctx, cape) : null,
    events: activeEventsFor(ctx, u.uuid),
  }
}

function teamView(ctx: AppContext, uuid: string): MyTeamView | null {
  const staff = teamOf(ctx, uuid)
  return staff ? myTeamView(ctx, staff) : null
}

export function updateSettings(ctx: AppContext, uuid: string, patch: Partial<Settings>): UserRow {
  const sets: string[] = []
  const params: (string | number)[] = []
  if (patch.showBadge !== undefined) {
    sets.push('show_badge = ?')
    params.push(patch.showBadge ? 1 : 0)
  }
  if (patch.showCapeToOthers !== undefined) {
    sets.push('show_cape = ?')
    params.push(patch.showCapeToOthers ? 1 : 0)
  }
  if (patch.presenceVisibility !== undefined) {
    sets.push('presence_visibility = ?')
    params.push(patch.presenceVisibility)
  }
  if (patch.shareServer !== undefined) {
    sets.push('share_server = ?')
    params.push(patch.shareServer ? 1 : 0)
  }
  if (patch.showCosmeticsToOthers !== undefined) {
    sets.push('show_cosmetics = ?')
    params.push(patch.showCosmeticsToOthers ? 1 : 0)
  }
  if (patch.chatReadReceipts !== undefined) {
    sets.push('chat_read_receipts = ?')
    params.push(patch.chatReadReceipts ? 1 : 0)
  }
  if (patch.chatTypingIndicator !== undefined) {
    sets.push('chat_typing = ?')
    params.push(patch.chatTypingIndicator ? 1 : 0)
  }
  // Spaltennamen stammen ausschließlich aus der festen Liste oben, Werte gehen als Parameter.
  if (sets.length > 0) run(ctx.db, `UPDATE users SET ${sets.join(', ')} WHERE uuid = ?`, ...params, uuid)
  if (patch.shareServer === false) ctx.presence.stripServer(uuid)
  return getUser(ctx, uuid)!
}

/**
 * DSGVO Art. 17: löscht Konto, Sitzungen, Freundschaften, Anfragen, Blockaden, Uploads, Einlösungen, Meldungen
 * und alle Sync-Daten (Skins samt Bildern, Grabsteine, Presets, Einstellungen – per ON DELETE CASCADE).
 * Chat: alle DMs (beide Seiten), eigene Nachrichten, Reaktionen und Bilder in Gruppen, leere Gruppen;
 * eigene Gruppen gehen an das dienstälteste Mitglied. Meldungen gegen das Konto und eine aktive
 * Stummschaltung bleiben (berechtigtes Interesse, befristet – siehe moderation.ts).
 */
export function deleteUser(ctx: AppContext, uuid: string): void {
  // Geteilte Umhänge, die dieser Nutzer hielt, samt allem, was er weitergeteilt hat.
  releaseHoldings(ctx, uuid)
  const uploads = all<{ id: string }>(ctx.db, "SELECT id FROM capes WHERE owner_uuid = ? AND kind = 'upload'", uuid)
  const cosmetics = all<{ id: string }>(ctx.db, "SELECT id FROM cosmetics WHERE owner_uuid = ? AND kind = 'upload'", uuid)
  // Wer eigene Uploads geteilt bekommen hat, verliert sie mit dem Konto (Zeilen per FK weg).
  const sharedOut = uploads.map(({ id }) => ({ id, holders: shareHolders(ctx, id), worn: capeWearers(ctx, id) }))
  const chat = prepareChatPurge(ctx, uuid)
  // Geteilte Screenshots (§23): Zeilen per FK weg, Dateien danach.
  const sharedImages = sharesOf(ctx, uuid)
  // Geteilte Modpacks (§27): Zeilen per FK weg, Dateien danach. Offene Stück-Uploads ebenso.
  const sharedPacks = packsOf(ctx, uuid)
  const uploadDirs = packUploadTmpDirs(ctx, uuid)
  // Gehostete Welten schließen, aus fremden austragen (Rest per ON DELETE CASCADE).
  endHostingFor(ctx, uuid)
  // Issues (§28): Issues/Kommentare bleiben ohne Ersteller, Logs und eigene Bilder gehen (Dateien danach).
  let issueFiles: UploadRow[] = []
  tx(ctx.db, () => {
    issueFiles = forgetIssueAuthor(ctx, uuid)
    run(ctx.db, 'DELETE FROM users WHERE uuid = ?', uuid)
    // Einträge zu Meldungen (ref) bleiben mit der Meldung bis zu deren Ablauf.
    run(ctx.db, 'DELETE FROM admin_log WHERE target = ? AND ref IS NULL', uuid)
    purgeModeration(ctx, uuid)
    // Schaltungen (§25): Ersteller-Name weg, Einreichungen per FK weg.
    forgetCircuitAuthor(ctx, uuid)
  })
  finishChatPurge(ctx, chat)
  removeShareFiles(ctx, sharedImages)
  removePackFiles(ctx, sharedPacks)
  for (const dir of uploadDirs) rmSync(dir, { recursive: true, force: true })
  removeUploadFiles(ctx, issueFiles)
  for (const s of sharedOut) {
    for (const u of s.worn) if (u !== uuid) emitCape(ctx, u)
    notifyShareRemoved(ctx, s.id, s.holders)
  }
  for (const { id } of uploads) {
    rmSync(join(ctx.capeDir, `${id}.png`), { force: true })
  }
  for (const { id } of cosmetics) {
    rmSync(join(ctx.cosmeticDir, `${id}.png`), { force: true })
  }
  ctx.presence.delete(uuid)
  ctx.events.kick(uuid)
  ctx.watch.kick(uuid)
}

export function assertNotBanned(ctx: AppContext, uuid: string): void {
  if (isBanned(ctx, uuid)) throw forbidden('banned', 'This account is banned')
}

