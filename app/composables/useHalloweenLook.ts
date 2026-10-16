/**
 * Halloween-2026-Look: darf gerade geflackert und gerauscht werden?
 * „Flackern & Rauschen“ (Einstellungen → Darstellung, ab Werk an) UND Animationen an –
 * bei „Animationen aus“/„Bewegung reduzieren“ flackert nie etwas.
 */
export function useHalloweenLook() {
  const settings = useSettingsStore()
  const { reduced } = useMotion()
  const enabled = computed(() => settings.current?.ui.halloweenFlicker !== false)
  const flicker = computed(() => enabled.value && !reduced.value)
  return { enabled, flicker, reduced }
}
