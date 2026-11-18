<script setup lang="ts">
import { addonKind } from '~/utils/craftinghosting'

// Kostenloser Server beim Partner CraftingHosting: ohne Server der Assistent zum Anlegen,
// sonst Kopfkarte und Reiter (Übersicht, Konsole, Einstellungen, Dateien, Plugins/Mods, Welt).
const store = useCraftingHostingStore()
const trs = useTrsStore()
const accounts = useAccountsStore()
const route = useRoute()
const router = useRouter()

const LOGO = '/partners/craftinghosting.png'
const mobile = computed(() => platformCaps.value.platform === 'android' || platformCaps.value.platform === 'ios')
const server = computed(() => store.server)
const info = computed(() => store.info)

onMounted(() => {
  if (trs.enabled && accounts.active) void store.watch()
})
onUnmounted(() => store.unwatch())

// TRS eingeschaltet bzw. Konto gewechselt: neu laden.
watch(
  () => [trs.enabled, accounts.active?.id] as const,
  ([enabled, id], old) => {
    if (old && old[1] !== id) store.reset()
    if (enabled && id) void store.watch()
    else store.unwatch()
  },
)

// --- Reiter (stehen in ?tab=, überleben also ein Neuladen) --------------------------------
const TABS = ['overview', 'console', 'settings', 'files', 'addons', 'world'] as const
type Tab = (typeof TABS)[number]
// Plugins (Paper/Purpur) bzw. Mods (Fabric/Forge); Vanilla hat keinen solchen Reiter.
const addons = computed(() => addonKind(server.value?.serverType))
const tabs = computed(() => TABS.filter((id) => id !== 'addons' || !!addons.value))
const tab = computed<Tab>({
  get: () => {
    // Alte Links auf „Mods & Welt“.
    const want = route.query.tab === 'content' ? (addons.value ? 'addons' : 'world') : route.query.tab
    return tabs.value.find((x) => x === want) ?? 'overview'
  },
  set: (v) => void router.replace({ query: { ...route.query, tab: v === 'overview' ? undefined : v } }),
})
function tabLabel(id: Tab): string {
  if (id === 'addons') return t(addons.value === 'plugins' ? 'craftinghosting.tabs.plugins' : 'craftinghosting.tabs.mods')
  return t(`craftinghosting.tabs.${id}`)
}
const tabRefs = useTemplateRef<HTMLButtonElement[]>('tabRefs')
function onTabKey(e: KeyboardEvent) {
  const list = tabs.value
  const i = list.indexOf(tab.value)
  const next = e.key === 'ArrowRight' ? (i + 1) % list.length : e.key === 'ArrowLeft' ? (i - 1 + list.length) % list.length : e.key === 'Home' ? 0 : e.key === 'End' ? list.length - 1 : -1
  if (next < 0) return
  e.preventDefault()
  tab.value = list[next]!
  void nextTick(() => tabRefs.value?.[next]?.focus())
}

// --- Löschen und EULA ----------------------------------------------------------------------
const confirmDelete = ref(false)
async function remove() {
  if (await store.remove()) confirmDelete.value = false
}

const eulaChecked = ref(false)
watch(
  () => store.eulaOpen,
  (open) => {
    if (open) eulaChecked.value = false
  },
)
function openEula() {
  if (info.value) void backend.openExternalUrl(info.value.eulaUrl).catch((e) => useToasts().error(e))
}
</script>

<template>
  <div class="mx-auto max-w-6xl p-6 mobile:p-4" data-testid="hosting-page">
    <!-- Kopf mit Partner-Würfel -->
    <header class="ch-hero relative mb-6 overflow-hidden rounded-2xl border border-base-800 px-6 py-5 mobile:mb-4 mobile:px-4">
      <span class="ch-hero-bg" aria-hidden="true" />
      <div class="relative flex items-center gap-5 mobile:gap-3">
        <div class="ch-cube relative shrink-0">
          <img :src="LOGO" alt="CraftingHosting" class="relative size-16 object-contain mobile:size-12" draggable="false" />
        </div>
        <div class="min-w-0 flex-1">
          <span class="badge bg-sky-900/50 text-sky-300 ring-1 ring-sky-700/40">{{ t('craftinghosting.partner') }}</span>
          <h1 class="display mt-1.5 text-3xl leading-none text-base-50 mobile:text-2xl">{{ t('craftinghosting.title') }}</h1>
          <p class="mt-1.5 max-w-xl text-sm text-base-200">{{ t('craftinghosting.subtitle') }}</p>
        </div>
        <button v-if="store.loaded" class="btn-icon shrink-0 mobile:hidden" :disabled="store.loading" :aria-label="t('common.actions.refresh')" :title="t('common.actions.refresh')" @click="store.load()">
          <svg viewBox="0 0 24 24" class="size-4" :class="{ 'animate-spin': store.loading }" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7" /></svg>
        </button>
      </div>
    </header>

    <TrsGate what="hosting">
      <div v-if="!store.loaded && store.loading" class="grid gap-4 lg:grid-cols-[1fr_19rem]">
        <div class="skeleton h-72" />
        <div class="skeleton h-48" />
      </div>

      <div v-else-if="!store.loaded && store.problem" class="card px-4 py-4 text-sm" data-testid="hosting-problem">
        <p class="font-semibold text-base-50">{{ t('craftinghosting.unavailableTitle') }}</p>
        <p class="mt-0.5 text-base-400">{{ store.problem.text }}</p>
        <button class="btn btn-ghost mt-3 px-3 py-1.5 text-xs" :disabled="store.loading" @click="store.load()">{{ t('common.actions.retry') }}</button>
      </div>

      <template v-else-if="store.loaded">
        <p v-if="store.problem" class="mb-4 rounded-lg bg-lamp-900/40 px-3 py-2 text-sm text-lamp-300">{{ store.problem.text }}</p>
        <p v-if="mobile" class="mb-4 rounded-lg bg-base-900 px-3 py-2 text-sm text-base-200 ring-1 ring-base-800">{{ t('craftinghosting.mobileNote') }}</p>

        <!-- Noch kein Server -->
        <CraftinghostingCreateWizard v-if="!server" :locked="mobile" />

        <!-- Eigener Server -->
        <div v-else class="space-y-4" data-testid="hosting-server">
          <CraftinghostingServerHeader @delete="confirmDelete = true" @address="tab = 'settings'" />

          <div class="flex gap-1 border-b border-base-800 mobile-scroll-x" role="tablist" :aria-label="t('craftinghosting.tabsLabel')" @keydown="onTabKey">
            <button
              v-for="id in tabs"
              :id="`ch-tab-${id}`"
              :key="id"
              ref="tabRefs"
              class="ch-tab relative -mb-px px-3.5 py-2.5 text-sm font-medium whitespace-nowrap outline-none focus-visible:rounded-md focus-visible:ring-2 focus-visible:ring-redstone-500"
              :class="tab === id ? 'text-base-50' : 'text-base-400 hover:text-base-50'"
              role="tab"
              :aria-selected="tab === id"
              :aria-controls="`ch-panel-${id}`"
              :tabindex="tab === id ? 0 : -1"
              :data-testid="`hosting-tab-${id}`"
              @click="tab = id"
            >
              {{ tabLabel(id) }}
              <span v-if="tab === id" class="ch-tab-line" aria-hidden="true" />
            </button>
          </div>

          <div :id="`ch-panel-${tab}`" role="tabpanel" :aria-labelledby="`ch-tab-${tab}`" tabindex="-1" class="outline-none">
            <CraftinghostingConsolePanel v-if="tab === 'console'" />
            <div v-else-if="tab === 'settings'" class="space-y-4">
              <CraftinghostingAddressCard />
              <CraftinghostingSettingsPanel />
              <CraftinghostingPropertiesPanel />
              <section class="card flex flex-wrap items-center justify-between gap-3 border-redstone-900/70 p-4" aria-labelledby="ch-danger-title">
                <div class="min-w-0">
                  <h3 id="ch-danger-title" class="text-sm font-semibold text-redstone-300">{{ t('craftinghosting.danger.title') }}</h3>
                  <p class="mt-0.5 text-xs text-base-400">{{ t('craftinghosting.danger.text') }}</p>
                </div>
                <button class="btn btn-danger" :disabled="!!store.busy" data-testid="hosting-delete-settings" @click="confirmDelete = true">
                  {{ t('common.actions.delete') }}
                </button>
              </section>
            </div>
            <CraftinghostingFilesPanel v-else-if="tab === 'files'" />
            <CraftinghostingAddonsPanel v-else-if="tab === 'addons'" @files="tab = 'files'" />
            <CraftinghostingWorldPanel v-else-if="tab === 'world'" />
            <CraftinghostingOverviewPanel v-else />
          </div>
        </div>
      </template>
    </TrsGate>

    <!-- Löschen bestätigen -->
    <BaseDialog v-if="confirmDelete && server" :title="t('craftinghosting.delete.title')" @close="confirmDelete = false">
      <p class="text-sm text-base-200">{{ t('craftinghosting.delete.text', { name: server.name }) }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="confirmDelete = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" :disabled="store.busy === 'delete'" data-testid="hosting-delete-confirm" @click="remove">
          {{ t('craftinghosting.delete.confirm') }}
        </button>
      </template>
    </BaseDialog>

    <!-- EULA vor dem ersten Start -->
    <BaseDialog v-if="store.eulaOpen" :title="t('craftinghosting.eula.title')" @close="store.cancelEula()">
      <p class="text-sm text-base-200">{{ t('craftinghosting.eula.text') }}</p>
      <button class="mt-2 text-sm text-redstone-300 underline underline-offset-2 hover:text-redstone-300" @click="openEula">
        {{ t('craftinghosting.eula.link') }}
      </button>
      <label class="mt-4 flex cursor-pointer items-center gap-2 text-sm text-base-50">
        <input v-model="eulaChecked" type="checkbox" class="accent-redstone-500" data-testid="hosting-eula-check" />
        {{ t('craftinghosting.eula.accept') }}
      </label>
      <template #actions>
        <button class="btn btn-ghost" @click="store.cancelEula()">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="!eulaChecked || !!store.busy" data-testid="hosting-eula-start" @click="store.confirmEula()">
          {{ t('craftinghosting.eula.start') }}
        </button>
      </template>
    </BaseDialog>
  </div>
</template>

<style scoped>
.ch-hero {
  background-color: var(--color-base-900);
}
.ch-hero-bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(60% 140% at 0% 50%, rgb(56 189 248 / 0.14), transparent 70%),
    radial-gradient(50% 120% at 100% 0%, color-mix(in srgb, var(--color-redstone-500) 14%, transparent), transparent 70%),
    linear-gradient(180deg, rgb(255 255 255 / 0.02), transparent);
}
/* Partner-Würfel mit leichtem Schein. */
.ch-cube::before {
  content: '';
  position: absolute;
  inset: -30%;
  border-radius: 9999px;
  background: radial-gradient(circle, rgb(56 189 248 / 0.35), transparent 65%);
  filter: blur(6px);
  animation: ch-breathe 4s ease-in-out infinite;
}
@keyframes ch-breathe {
  50% {
    opacity: 0.55;
    transform: scale(0.92);
  }
}
.ch-tab-line {
  position: absolute;
  left: 0.5rem;
  right: 0.5rem;
  bottom: 0;
  height: 2px;
  border-radius: 2px;
  background: var(--color-redstone-500);
  box-shadow: 0 0 10px var(--color-redstone-500);
}
</style>
