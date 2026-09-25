import type { AppContext } from './context'
import { all } from './db'
import type { EmoteDef } from './emotes'
import { broadcastPresence } from './friends'
import { liveBadge, seesLiveBadges, visualsOf } from './lookup'
import type { GameInfo } from './presence'
import type { PresenceBody } from './schemas'
import { getUser, isBanned, type UserRow } from './users'
import type { PlayerEvent } from './watch'

/**
 * Schickt ein Ereignis über `subject` an alle Streams, die ihn beobachten.
 * Wer von `subject` blockiert wurde, bekommt nichts (wie beim Lookup).
 * `build(self)` wird höchstens je einmal für „selbst“ und „andere“ aufgerufen.
 * `accept(viewer)` kann andere Beobachter zusätzlich ausschließen.
 */
export function emitToWatchers(
  ctx: AppContext,
  subject: string,
  build: (self: boolean) => PlayerEvent,
  accept?: (viewer: string) => boolean,
): number {
  const watchers = ctx.watch.watchersOf(subject)
  if (watchers.length === 0 || isBanned(ctx, subject)) return 0
  const blocked = new Set(
    all<{ blocked: string }>(ctx.db, 'SELECT blocked FROM blocks WHERE blocker = ?', subject).map((r) => r.blocked),
  )
  let forSelf: PlayerEvent | undefined
  let forOthers: PlayerEvent | undefined
  let sent = 0
  for (const w of watchers) {
    const self = w.viewer === subject
    if (!self && (blocked.has(w.viewer) || (accept && !accept(w.viewer)))) continue
    const e = self ? (forSelf ??= build(true)) : (forOthers ??= build(false))
    try {
      w.send(e)
      sent++
    } catch {
      // Ein kaputter Stream darf die anderen nicht stören.
    }
  }
  return sent
}

export function emitEmote(ctx: AppContext, subject: string, emote: EmoteDef, at: number): number {
  const e: PlayerEvent = { type: 'emote', uuid: subject, emote: emote.id, durationMs: emote.durationMs, at: new Date(at).toISOString() }
  return emitToWatchers(ctx, subject, () => e)
}

export function emitSkin(ctx: AppContext, subject: string): number {
  const e: PlayerEvent = { type: 'skin', uuid: subject, at: new Date(ctx.now()).toISOString() }
  return emitToWatchers(ctx, subject, () => e)
}

export function emitCape(ctx: AppContext, subject: string): number {
  return emitToWatchers(ctx, subject, (self) => ({ type: 'cape', uuid: subject, cape: visualsOf(ctx, subject, self).cape }))
}

export function emitCosmetics(ctx: AppContext, subject: string): number {
  return emitToWatchers(ctx, subject, (self) => ({
    type: 'cosmetics',
    uuid: subject,
    cosmetics: visualsOf(ctx, subject, self).cosmetics,
  }))
}

/**
 * Abzeichen von `subject` hat sich (vielleicht) geändert – Präsenz `in-game`
 * begonnen/beendet oder `showBadge` umgeschaltet. „An“ erfahren andere nur,
 * wenn sie selbst gerade im Spiel sind (wie beim Lookup); „aus“ bekommen alle
 * Beobachter, damit kein altes Abzeichen hängen bleibt.
 */
export function emitBadge(ctx: AppContext, subject: string): number {
  const u = getUser(ctx, subject)
  if (!u) return 0
  const on = liveBadge(ctx, u.show_badge === 1, subject, true)
  const e: PlayerEvent = { type: 'badge', uuid: subject, badge: on }
  return emitToWatchers(ctx, subject, () => e, on ? (viewer) => seesLiveBadges(ctx, viewer) : undefined)
}

/**
 * Ändert die Präsenz über `change` (liefert „für Freunde sichtbar geändert?“)
 * und verschickt danach die passenden Ereignisse: `presence` an Freunde,
 * `badge` an Beobachter, wenn `in-game` begonnen oder geendet hat.
 */
export function updatePresence(ctx: AppContext, uuid: string, change: () => boolean): boolean {
  const wasInGame = ctx.presence.isInGame(uuid)
  const changed = change()
  afterPresenceChange(ctx, uuid, changed, wasInGame)
  return changed
}

/** Ereignisse nach einer Präsenzänderung (auch nach dem Ablauf im Sweep). */
export function afterPresenceChange(ctx: AppContext, uuid: string, changed: boolean, wasInGame: boolean): void {
  if (changed) broadcastPresence(ctx, uuid)
  if (ctx.presence.isInGame(uuid) !== wasInGame) emitBadge(ctx, uuid)
}

/**
 * `POST /v1/presence`: Launcher und Mod melden getrennt (`via`). Ohne `via`
 * (alte Clients ≤ 0.5) gilt: `in-game` kommt vom Mod, `online` vom Launcher –
 * der damit auch sagt, dass kein Spiel dieses Kontos läuft (eine liegen
 * gebliebene Meldung des Mods fällt weg, wie früher „die neueste zählt“) –
 * und `offline` nimmt alles zurück.
 */
export function reportPresence(ctx: AppContext, user: Pick<UserRow, 'uuid' | 'share_server'>, body: PresenceBody): void {
  const uuid = user.uuid
  updatePresence(ctx, uuid, () => {
    if (body.state === 'offline') {
      return body.via ? ctx.presence.clear(uuid, body.via) : ctx.presence.delete(uuid)
    }
    let game: GameInfo | null = null
    if (body.game) {
      game = { version: body.game.version, loader: body.game.loader }
      // Server nur speichern, wenn der Nutzer das erlaubt und gerade spielt.
      if (body.game.server && user.share_server === 1 && body.state === 'in-game') {
        game.server = body.game.server.toLowerCase()
      }
    }
    if (!body.via && body.state === 'online') {
      const cleared = ctx.presence.clear(uuid, 'client')
      return ctx.presence.set(uuid, 'online', game, 'launcher') || cleared
    }
    return ctx.presence.set(uuid, body.state, game, body.via ?? (body.state === 'in-game' ? 'client' : 'launcher'))
  })
}
