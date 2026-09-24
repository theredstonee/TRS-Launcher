<script setup lang="ts">
// Spiel-Events, Accounts und Instanzen einmal zentral laden – unabhängig von der Seite.
const games = useGamesStore()
const accounts = useAccountsStore()
const instances = useInstancesStore()
const onboarding = useOnboardingStore()
const settings = useSettingsStore()
const ui = useUiStore()
const trs = useTrsStore()
const whatsNew = useWhatsNewStore()
const router = useRouter()

/** Strg+K öffnet überall die Suche; Strg+N legt eine Instanz an. */
function onKey(e: KeyboardEvent) {
  if (!(e.ctrlKey || e.metaKey) || e.altKey) return
  const key = e.key.toLowerCase()
  if (key === 'k') {
    e.preventDefault()
    ui.togglePalette()
  } else if (key === 'n' && !e.shiftKey) {
    e.preventDefault()
    ui.creating = true
  }
}

// Jede Seite fängt oben an – sonst hängt die neue Seite auf der alten Scrollhöhe.
const main = useTemplateRef<HTMLElement>('main')
const route = useRoute()
// Die Startseite zeigt die Schaltung schon groß im Kopfbereich.
const appBackground = computed(() => settings.current?.ui.animatedBackground !== false && route.path !== '/')
watch(
  () => route.fullPath,
  () => main.value?.scrollTo({ top: 0 }),
)

function onCreated(instance: { id: string }) {
  ui.creating = false
  router.push(`/instances/${instance.id}`)
}

onMounted(async () => {
  ui.restore()
  window.addEventListener('keydown', onKey)
  games.init()
  // Darstellung (Theme, Akzent) und Oberflächen-Schalter früh laden.
  settings.load().catch(() => {})
  // Erst wenn beides geladen ist, entscheiden, ob der Einrichtungs-Assistent kommt.
  await Promise.allSettled([accounts.load(), instances.load()])
  onboarding.openIfFirstRun()
  // Nach einem Update einmal zeigen, was neu ist (beim allerersten Start nicht).
  void whatsNew.check(onboarding.open)
  await trs.init()
})

// TRS-Dienste: nach einem Account-Wechsel neu laden. Noch nicht entschieden?
// Dann einmal fragen, sobald ein Account da ist (nicht während der Einrichtung).
watch(
  () => accounts.active?.id,
  (id, before) => {
    if (before !== undefined && id !== before) void trs.init()
  },
)
watch(
  () => [trs.undecided, accounts.active?.id, onboarding.open] as const,
  ([undecided, account, onboardingOpen]) => {
    if (undecided && account && !onboardingOpen && !consentAsked) {
      consentAsked = true
      trs.askConsent()
    }
  },
)
let consentAsked = false

onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div class="flex h-full flex-col">
    <TitleBar />
    <!-- Der Assistent überdeckt alles unter der Titelleiste; die Fenstersteuerung bleibt bedienbar. -->
    <div class="relative flex min-h-0 flex-1 flex-col">
      <div class="flex min-h-0 flex-1">
        <SideNav />
        <!-- Redstone-Schaltung hinter allen Seiten (die Startseite hat ihre eigene im Kopfbereich). -->
        <div class="relative min-w-0 flex-1">
          <div v-if="appBackground" class="app-bg" aria-hidden="true">
            <RedstoneScene fill />
          </div>
        <!-- Seitenwechsel: ein kurzer Redstone-Impuls läuft oben entlang. -->
        <div :key="route.path" class="route-signal" aria-hidden="true" />
        <main ref="main" class="deepslate relative min-w-0 h-full overflow-y-auto" :class="{ 'deepslate-over-scene': appBackground }">
          <slot />
        </main>
        </div>
      </div>
      <Transition name="onboarding" appear>
        <OnboardingWizard v-if="onboarding.open" />
      </Transition>
    </div>
    <AppSettingsDialog v-if="settings.dialog" />
    <!-- Global, damit Seitenleiste und Befehlspalette sie überall öffnen können. -->
    <CreateInstanceDialog v-if="ui.creating" @close="ui.creating = false" @created="onCreated" />
    <ImportDialog v-if="ui.importing" @close="ui.importing = false" />
    <PresetReportDialog />
    <CommandPalette v-if="ui.palette" @close="ui.palette = false" />
    <TrsConsentDialog v-if="trs.consentOpen" />
    <WhatsNewDialog v-if="whatsNew.open && !onboarding.open && !trs.consentOpen" />
    <ToastHost />
  </div>
</template>

<style scoped>
/* Einmaliger Auftritt: kurz einblenden und leicht heranzoomen. */
.onboarding-enter-active {
  transition: opacity 0.28s ease-out, transform 0.28s cubic-bezier(0.2, 0.8, 0.2, 1);
}
.onboarding-leave-active {
  transition: opacity 0.16s ease-in;
}
.onboarding-enter-from {
  opacity: 0;
  transform: scale(0.985);
}
.onboarding-leave-to {
  opacity: 0;
}
</style>
