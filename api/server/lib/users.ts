import { rmSync } from 'node:fs'
import { join } from 'node:path'
import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { capeView, capeWearers, type CapeRow, type CapeView } from './capes'
import { notifyShareRemoved, releaseHoldings, shareHolders } from './capeshares'
import { emitCape } from './playerevents'
import { forbidden } from './errors'

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
}

export interface Settings {
  showBadge: boolean
  showCapeToOthers: boolean
  presenceVisibility: 'friends' | 'nobody'
  shareServer: boolean
  showCosmeticsToOthers: boolean
}

export interface MeView {
  uuid: string
  name: string
  admin: boolean
  createdAt: string
  settings: Settings
  activeCape: CapeView | null
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

export function isBanned(ctx: AppContext, uuid: string): boolean {
  return one(ctx.db, 'SELECT 1 AS x FROM bans WHERE uuid = ?', uuid) !== undefined
}

export function isAdmin(ctx: AppContext, uuid: string): boolean {
  return ctx.config.adminUuids.has(uuid)
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
  return getUser(ctx, uuid)!
}

export function settingsOf(u: UserRow): Settings {
  return {
    showBadge: u.show_badge === 1,
    showCapeToOthers: u.show_cape === 1,
    presenceVisibility: u.presence_visibility,
    shareServer: u.share_server === 1,
    showCosmeticsToOthers: u.show_cosmetics === 1,
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
    createdAt: new Date(u.created_at).toISOString(),
    settings: settingsOf(u),
    activeCape: cape ? capeView(ctx, cape) : null,
  }
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
  // Spaltennamen stammen ausschließlich aus der festen Liste oben, Werte gehen als Parameter.
  if (sets.length > 0) run(ctx.db, `UPDATE users SET ${sets.join(', ')} WHERE uuid = ?`, ...params, uuid)
  if (patch.shareServer === false) ctx.presence.stripServer(uuid)
  return getUser(ctx, uuid)!
}

/**
 * DSGVO Art. 17: löscht Konto, Sitzungen, Freundschaften, Anfragen, Blockaden, Uploads, Einlösungen, Meldungen
 * und alle Sync-Daten (Skins samt Bildern, Grabsteine, Presets, Einstellungen – per ON DELETE CASCADE).
 */
export function deleteUser(ctx: AppContext, uuid: string): void {
  // Geteilte Umhänge, die dieser Nutzer hielt, samt allem, was er weitergeteilt hat.
  releaseHoldings(ctx, uuid)
  const uploads = all<{ id: string }>(ctx.db, "SELECT id FROM capes WHERE owner_uuid = ? AND kind = 'upload'", uuid)
  const cosmetics = all<{ id: string }>(ctx.db, "SELECT id FROM cosmetics WHERE owner_uuid = ? AND kind = 'upload'", uuid)
  // Wer eigene Uploads geteilt bekommen hat, verliert sie mit dem Konto (Zeilen per FK weg).
  const sharedOut = uploads.map(({ id }) => ({ id, holders: shareHolders(ctx, id), worn: capeWearers(ctx, id) }))
  tx(ctx.db, () => {
    run(ctx.db, 'DELETE FROM users WHERE uuid = ?', uuid)
    run(ctx.db, 'DELETE FROM admin_log WHERE target = ?', uuid)
  })
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
