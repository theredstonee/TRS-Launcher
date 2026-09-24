/**
 * CurseForge liefert Changelogs nur einzeln je Datei. Diese Hilfe lädt sie
 * beim Aufklappen nach und merkt sie sich (HTML – nur über MarkdownView mit `html`).
 */
export function useLazyChangelogs(projectId: () => string) {
  const texts = ref<Record<string, string>>({})
  const states = ref<Record<string, 'loading' | 'done'>>({})

  async function load(fileId: string) {
    if (states.value[fileId]) return
    states.value = { ...states.value, [fileId]: 'loading' }
    try {
      const text = await backend.curseforge.changelog(projectId(), fileId)
      texts.value = { ...texts.value, [fileId]: text }
    } catch {
      // Ohne Changelog geht es auch – dann erscheint „kein Changelog“.
    } finally {
      states.value = { ...states.value, [fileId]: 'done' }
    }
  }

  return {
    load,
    state: (fileId: string) => states.value[fileId] ?? null,
    text: (fileId: string) => texts.value[fileId]?.trim() || null,
  }
}
