import type { AppContext } from './context'
import { all } from './db'
import type { EmoteDef } from './emotes'
import { visualsOf } from './lookup'
import { isBanned } from './users'
import type { PlayerEvent } from './watch'

/**
 * Schickt ein Ereignis über `subject` an alle Streams, die ihn beobachten.
 * Wer von `subject` blockiert wurde, bekommt nichts (wie beim Lookup).
 * `build(self)` wird höchstens je einmal für „selbst“ und „andere“ aufgerufen.
 */
export function emitToWatchers(ctx: AppContext, subject: string, build: (self: boolean) => PlayerEvent): number {
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
    if (!self && blocked.has(w.viewer)) continue
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
