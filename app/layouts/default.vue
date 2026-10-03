<script setup lang="ts">
import { isTauri } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
// Spiel-Events, Accounts und Instanzen einmal zentral laden – unabhängig von der Seite.
const games = useGamesStore()
const accounts = useAccountsStore()
const instances = useInstancesStore()
const onboarding = useOnboardingStore()
const settings = useSettingsStore()
const ui = useUiStore()
const trs = useTrsStore()
const packs = usePacksStore()
const sanctions = useSanctionsStore()
const applications = useApplicationsStore()
const whatsNew = useWhatsNewStore()
const curseforge = useCurseForgeStore()
const router = useRouter()
// Event-Theme (Halloween) früh anwenden, auch bevor eine Seite den Store anfasst.
const events = useEventsStore()

const bigPicture = useBigPictureStore()

/** Strg+K öffnet überall die Suche; Strg+N legt eine Instanz an; F11 schaltet Big Picture. */
function onKey(e: KeyboardEvent) {
  if (e.key === BIG_PICTURE_KEY && !e.ctrlKey && !e.altKey && !e.metaKey && !e.shiftKey) {
    e.preventDefault()
    if (!e.repeat) bigPicture.toggle()
    return
  }
  if (!(e.ctrlKey || e.metaKey) || e.altKey) return
  // In Big Picture gibt es weder Suche noch den Dialog „Neue Instanz“.
  if (isBig.value) return
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
// Big Picture: eigener Rahmen ohne Titelleiste und Seitenleiste (Dialoge bleiben hier).
const isBig = computed(() => route.path === BIG_PICTURE_PATH)
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
  void useBisectStore().init()
  void useClipsStore().init()
  void useHostingStore().init()
  void useLocalServersStore().init()
  // Darstellung (Theme, Akzent) und Oberflächen-Schalter früh laden.
  settings.load().catch(() => {})
  // Erst wenn beides geladen ist, entscheiden, ob der Einrichtungs-Assistent kommt.
  await Promise.allSettled([accounts.load(), instances.load()])
  onboarding.openIfFirstRun()
  // Big Picture beim Start (Einstellung oder Steam Deck/gamescope) – nicht über den Einrichtungs-Assistenten.
  if (!onboarding.open) void bigPicture.autoStart()
  // Nach einem Update einmal zeigen, was neu ist (beim allerersten Start nicht).
  void whatsNew.check(onboarding.open)
  await trs.init()
})

// „Im Launcher öffnen“ auf der Website (`trs-launcher://pack/<Code>`): Dialog „Modpack per Code“ mit Vorschau.
let unlistenPackLink: (() => void) | null = null
onMounted(async () => {
  if (!isTauri()) return
  const open = (code: unknown) => {
    const c = typeof code === 'string' ? normalizePackCode(code) : null
    if (c) packs.openCode(c)
  }
  unlistenPackLink = await listen<string>('open-pack-link', (e) => open(e.payload))
  open(await backend.packs.takePendingLink().catch(() => null))
})
onBeforeUnmount(() => unlistenPackLink?.())

// „Mit TRS Launcher anmelden“ auf der Website (`trs-launcher://web-login/<Token>`): Bestätigungsdialog – bestätigt
// wird dort erst nach einem Klick.
const webLogin = useWebLoginStore()
let unlistenWebLogin: (() => void) | null = null
onMounted(async () => {
  if (!isTauri()) return
  const open = (token: unknown) => {
    if (isWebLoginToken(token)) webLogin.open(token)
  }
  unlistenWebLogin = await listen<string>('open-web-login', (e) => open(e.payload))
  open(await backend.webLogin.takePending().catch(() => null))
})
onBeforeUnmount(() => unlistenWebLogin?.())

// Geteilte Modpacks: Updates und „An dich geschickt“ laden, sobald die TRS-Dienste an sind.
watch(
  () => trs.enabled,
  (on) => {
    if (!on) return
    void packs.checkUpdates(true)
    void packs.loadInbox()
  },
)

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
    <BigShell v-if="isBig">
      <slot />
    </BigShell>
    <TitleBar v-if="!isBig" />
    <!-- Der Assistent überdeckt alles unter der Titelleiste; die Fenstersteuerung bleibt bedienbar. -->
    <div v-if="!isBig" class="relative flex min-h-0 flex-1 flex-col">
      <div class="flex min-h-0 flex-1">
        <SideNav />
        <!-- Redstone-Schaltung hinter allen Seiten (die Startseite hat ihre eigene im Kopfbereich). -->
        <div class="relative flex min-w-0 flex-1 flex-col">
          <div v-if="appBackground" class="app-bg" aria-hidden="true">
            <RedstoneScene fill />
          </div>
        <!-- Seitenwechsel: ein kurzer Redstone-Impuls läuft oben entlang. -->
        <div :key="route.path" class="route-signal" aria-hidden="true" />
        <!-- Hinweis auf eigene Strafen: über dem Inhalt, ohne Seiten mit voller Höhe (Sozial) zu verdrängen. -->
        <div v-if="sanctions.banner.length" class="deepslate relative shrink-0" :class="{ 'deepslate-over-scene': appBackground }">
          <SanctionBanner />
        </div>
        <main ref="main" class="deepslate relative min-h-0 min-w-0 flex-1 overflow-y-auto" :class="{ 'deepslate-over-scene': appBackground }">
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
    <ModpackInstallDialog v-if="ui.modpackInstall" :key="ui.modpackInstall.platform + ui.modpackInstall.pack.projectId" :request="ui.modpackInstall" @close="ui.modpackInstall = null" />
    <WebLoginDialog v-if="webLogin.request" :key="webLogin.request.seq" :token="webLogin.request.token" @close="webLogin.close()" />
    <PackCodeDialog v-if="packs.codeDialog" :key="packs.codeDialog.code" :initial-code="packs.codeDialog.code" @close="packs.codeDialog = null" />
    <MyPacksDialog v-if="packs.mineOpen" @close="packs.mineOpen = false" />
    <PresetReportDialog />
    <CommandPalette v-if="ui.palette" @close="ui.palette = false" />
    <TrsConsentDialog v-if="trs.consentOpen" />
    <MySanctionsDialog v-if="sanctions.dialogOpen && !trs.consentOpen" />
    <MyApplicationsDialog v-if="applications.dialogOpen && !trs.consentOpen" />
    <WhatsNewDialog v-if="whatsNew.open && !onboarding.open && !trs.consentOpen" />
    <CurseForgeBlockedDialog v-if="curseforge.blockedFor" :key="curseforge.blockedFor" :instance-id="curseforge.blockedFor" @close="curseforge.closeBlocked()" />
    <!-- Hexe und Fledermäuse über jeder Seite, nur solange das Event läuft. -->
    <HalloweenFly v-if="events.halloween" />
    <ToastHost />
    <SocialToastHost />
    <JoinServerDialog />
    <HostingJoinDialog />
    <HostingModsDialog />
    <CrashHelperDialog />
    <ExtraLaunchDialog />
    <BisectPanel />
    <DuplicateModsDialog />
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
