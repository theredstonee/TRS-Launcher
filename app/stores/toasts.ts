import { defineStore } from 'pinia'
// Relativ importiert, damit tests/toasts.test.ts den Store ohne Nuxt laden kann.
import { addToast, MAX_VISIBLE_TOASTS, type Toast, type ToastAction, type ToastKind } from '../utils/toastQueue'

export const useToasts = defineStore('toasts', () => {
  const items = ref<Toast[]>([])
  const timers = new Map<number, ReturnType<typeof setTimeout>>()
  let nextId = 1

  function push(kind: ToastKind, text: string, action?: ToastAction) {
    const result = addToast(items.value, kind, text, nextId, MAX_VISIBLE_TOASTS, action)
    if (result.id === nextId) nextId++
    items.value = result.items
    for (const id of result.dropped) clearTimer(id)
    // Gleiche Meldung erneut: Zähler hoch und Zeit neu starten.
    clearTimer(result.id)
    // Fehler bleiben länger stehen – die will man lesen können.
    timers.set(
      result.id,
      setTimeout(() => dismiss(result.id), kind === 'error' ? 8000 : 4000),
    )
  }

  function clearTimer(id: number) {
    const timer = timers.get(id)
    if (timer !== undefined) clearTimeout(timer)
    timers.delete(id)
  }

  function dismiss(id: number) {
    clearTimer(id)
    items.value = items.value.filter((t) => t.id !== id)
  }

  /** Fehler wegen einer Strafe (§22): Klick öffnet „Meine Strafen“. */
  function sanctionAction(e: unknown): ToastAction | undefined {
    if (typeof e === 'string' || !(e instanceof BackendError) || !e.params?.sanctionKind) return undefined
    const id = Number(e.params.sanctionId) || null
    return { label: t('sanctions.banner.details'), run: () => useSanctionsStore().open(id) }
  }

  return {
    items,
    dismiss,
    /** `action`: Klick auf den Toast (z. B. Instanz öffnen). */
    ok: (text: string, action?: ToastAction) => push('ok', text, action),
    info: (text: string, action?: ToastAction) => push('info', text, action),
    error: (e: unknown, action?: ToastAction) => push('error', typeof e === 'string' ? e : errorMessage(e), action ?? sanctionAction(e)),
  }
})
