import { defineStore } from 'pinia'

const DONE_KEY = 'trs.onboarding.done'

// localStorage kann im Webview werfen (z. B. gesperrter Speicher) – dann gilt:
// Einrichtung gilt als nicht erledigt, Merken klappt eben nicht.
function readDone(): boolean {
  try {
    return localStorage.getItem(DONE_KEY) === '1'
  } catch {
    return false
  }
}

function writeDone(done: boolean) {
  try {
    if (done) localStorage.setItem(DONE_KEY, '1')
    else localStorage.removeItem(DONE_KEY)
  } catch {
    // Nicht speicherbar – der Assistent erscheint dann beim nächsten Start ggf. erneut.
  }
}

/** Steuert den Einrichtungs-Assistenten beim ersten Start. */
export const useOnboardingStore = defineStore('onboarding', () => {
  const open = ref(false)

  /**
   * Nur aufrufen, wenn Accounts UND Instanzen geladen sind – sonst würde der
   * Assistent bei bestehenden Nutzern kurz aufblitzen.
   */
  function openIfFirstRun() {
    const accounts = useAccountsStore()
    const instances = useInstancesStore()
    if (!accounts.loaded || !instances.loaded) return
    if (accounts.items.length || instances.items.length) return
    if (readDone()) return
    open.value = true
  }

  /** Schließt und merkt sich, dass die Einrichtung erledigt (oder übersprungen) ist. */
  function finish() {
    writeDone(true)
    open.value = false
  }

  /** Aus den Einstellungen: Merker löschen und neu starten. */
  function restart() {
    writeDone(false)
    open.value = true
  }

  return { open, openIfFirstRun, finish, restart }
})
