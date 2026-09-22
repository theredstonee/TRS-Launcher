import { defineStore } from 'pinia'

export interface Toast {
  id: number
  kind: 'ok' | 'error' | 'info'
  text: string
}

export const useToasts = defineStore('toasts', () => {
  const items = ref<Toast[]>([])
  let nextId = 1

  function push(kind: Toast['kind'], text: string) {
    const id = nextId++
    items.value.push({ id, kind, text })
    // Fehler bleiben länger stehen – die will man lesen können.
    setTimeout(() => dismiss(id), kind === 'error' ? 8000 : 4000)
  }

  function dismiss(id: number) {
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
