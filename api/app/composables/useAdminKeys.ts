// Tastenkürzel im Team-Bereich: `/` Suche, `j`/`k` nächster/voriger Eintrag, `a` annehmen,
// `r` ablehnen, `x` auswählen, Enter öffnen, `?` Hilfe. Nie in Eingabefeldern und nicht, solange
// ein Dialog offen ist (der hat eigene Tasten).

type Handlers = Partial<Record<string, (e: KeyboardEvent) => void>>

function typing(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null
  return !!el && (el.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName))
}

export function useAdminKeys(handlers: Handlers) {
  function onKey(e: KeyboardEvent) {
    if (e.defaultPrevented || e.ctrlKey || e.metaKey || e.altKey || typing(e.target)) return
    if (document.querySelector('[aria-modal="true"]')) return
    const fn = handlers[e.key]
    if (!fn) return
    e.preventDefault()
    fn(e)
  }
  onMounted(() => window.addEventListener('keydown', onKey))
  onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
}

/**
 * Aktiver Eintrag einer Liste für `j`/`k` (mit Scrollen zum Eintrag `[data-row="<index>"]`).
 * `open` = Enter, `onA`/`onR` = Aktionen auf dem aktiven Eintrag, `toggle` = `x`.
 */
export function useListKeys<T>(items: Ref<T[]>, opts: { open?: (item: T) => void, onA?: (item: T) => void, onR?: (item: T) => void, toggle?: (item: T) => void } = {}) {
  const active = ref(-1)
  watch(() => items.value.length, (n) => {
    if (active.value >= n) active.value = n - 1
  })
  function move(d: 1 | -1) {
    const n = items.value.length
    if (!n) return
    active.value = Math.max(0, Math.min(n - 1, active.value + d))
    void nextTick(() => document.querySelector<HTMLElement>(`[data-row="${active.value}"]`)?.scrollIntoView({ block: 'nearest' }))
  }
  const current = () => items.value[active.value]
  useAdminKeys({
    j: () => move(1),
    k: () => move(-1),
    Enter: () => {
      const it = current()
      if (it && opts.open) opts.open(it)
    },
    a: () => {
      const it = current()
      if (it && opts.onA) opts.onA(it)
    },
    r: () => {
      const it = current()
      if (it && opts.onR) opts.onR(it)
    },
    x: () => {
      const it = current()
      if (it && opts.toggle) opts.toggle(it)
    },
  })
  return { active }
}
