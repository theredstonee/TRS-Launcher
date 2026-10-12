import type { Ref, WatchSource } from 'vue'

/**
 * Elemente gleiten an ihren neuen Platz, wenn sich `source` ändert (Filtern,
 * Sortieren, Gruppen): vor dem Neuzeichnen messen, danach von der alten Stelle
 * aus hinschieben (FLIP über Motion). `source` sollte nur Reihenfolge/Mitglieder
 * abbilden (z. B. verbundene IDs) – nicht jede Kleinigkeit einer Zeile.
 * Große Listen und unsichtbare Elemente bleiben ruhig, siehe `utils/motionFx.ts`.
 */
export function useFlip(
  container: Ref<HTMLElement | null | undefined>,
  source: WatchSource,
  options: { selector?: string; enter?: FlipEnter; clip?: () => Element | null | undefined } = {},
) {
  const items = (root: HTMLElement) => (options.selector ? root.querySelectorAll(options.selector) : root.children)
  let snapshot: FlipSnapshot | null = null
  // „pre“: läuft vor dem Neuzeichnen – das DOM zeigt noch die alte Anordnung.
  watch(
    source,
    () => {
      const root = container.value
      snapshot = root && motionAllowed() ? flipMeasure(items(root)) : null
    },
    { flush: 'pre' },
  )
  watch(
    source,
    () => {
      const root = container.value
      const before = snapshot
      snapshot = null
      if (root && before) flipPlay(before, items(root), { enter: options.enter, clip: options.clip?.() ?? null })
    },
    { flush: 'post' },
  )
}
