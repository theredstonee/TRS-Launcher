import { defineStore } from 'pinia'
// Relativ importiert, damit tests/toasts.test.ts den Store ohne Nuxt laden kann.
import { addToast, type Toast, type ToastKind } from '../utils/toastQueue'

export const useToasts = defineStore('toasts', () => {
  const items = ref<Toast[]>([])
  const timers = new Map<number, ReturnType<typeof setTimeout>>()
  let nextId = 1

  function push(kind: ToastKind, text: string) {
    const result = addToast(items.value, kind, text, nextId)
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

  return {
    items,
    dismiss,
    ok: (text: string) => push('ok', text),
    info: (text: string) => push('info', text),
    error: (e: unknown) => push('error', typeof e === 'string' ? e : errorMessage(e)),
  }
})
