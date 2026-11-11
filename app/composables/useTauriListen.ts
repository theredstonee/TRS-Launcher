import { isTauri } from '@tauri-apps/api/core'
import { listen, type EventCallback, type UnlistenFn } from '@tauri-apps/api/event'

/**
 * Tauri-Event, solange die Komponente lebt. Ist sie schon weg, bevor `listen()` fertig ist,
 * wird sofort wieder abgemeldet.
 */
export function useTauriListen<T>(event: string, handler: EventCallback<T>) {
  let unlisten: UnlistenFn | null = null
  let disposed = false
  onMounted(async () => {
    if (!isTauri()) return
    const off = await listen<T>(event, handler)
    if (disposed) off()
    else unlisten = off
  })
  onBeforeUnmount(() => {
    disposed = true
    unlisten?.()
    unlisten = null
  })
}
