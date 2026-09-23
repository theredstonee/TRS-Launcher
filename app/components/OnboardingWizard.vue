<script setup lang="ts">
import type { Locale } from '~/utils/i18n'
import type { ImportCandidate, Instance, LoaderKind } from '~/types'

// Einrichtung beim ersten Start: anmelden, andere Launcher übernehmen, erste Instanz.
// Jeder Schritt ist überspringbar; Escape beendet den Assistenten.

type StepKey = 'language' | 'welcome' | 'login' | 'import' | 'instance' | 'done'
type Preset = 'vanilla' | 'fabric' | 'custom'

/** Reihenfolge der Schritte; die Beschriftung kommt aus `onboarding.steps.<key>`. */
const stepOrder: StepKey[] = ['language', 'welcome', 'login', 'import', 'instance', 'done']
const steps = computed(() => stepOrder.map((key) => ({ key, label: t(`onboarding.steps.${key}`) })))

const onboarding = useOnboardingStore()
const accounts = useAccountsStore()
const instances = useInstancesStore()
const meta = useMetaStore()
const games = useGamesStore()
const toasts = useToasts()

const root = ref<HTMLElement | null>(null)
const step = ref<StepKey>('language')
const stepIndex = computed(() => stepOrder.indexOf(step.value))

// --- Anmelden ---------------------------------------------------------------
const loggingIn = ref(false)
const loginError = ref<string | null>(null)
const notApproved = ref(false)

async function login() {
  loginError.value = null
  notApproved.value = false
  loggingIn.value = true
  try {
    await accounts.loginBrowser()
  } catch (e) {
    if (!isCancelled(e)) {
      loginError.value = errorMessage(e)
      notApproved.value = e instanceof BackendError && e.kind === 'auth_not_approved'
    }
  } finally {
    loggingIn.value = false
  }
}

function cancelLogin() {
  backend.cancelLogin().catch(() => {})
}

// --- Importieren ------------------------------------------------------------
const candidates = ref<ImportCandidate[]>([])
const scanning = ref(false)
const scanned = ref(false)
const importError = ref<string | null>(null)
const importing = ref<{ id: string; percent: number } | null>(null)
const imported = ref<Set<string>>(new Set())

async function scan() {
  if (scanned.value || scanning.value) return
  scanning.value = true
  importError.value = null
  try {
    candidates.value = await backend.scanImports()
  } catch (e) {
    importError.value = errorMessage(e)
  } finally {
    scanning.value = false
    scanned.value = true
  }
}

async function runImport(candidate: ImportCandidate) {
  if (importing.value) return
  importError.value = null
  importing.value = { id: candidate.id, percent: 0 }
  try {
    const instance = await backend.importInstance(candidate.id, null, null, (p) => {
      if (importing.value) importing.value.percent = Math.floor(p.percent)
    })
    imported.value = new Set(imported.value).add(candidate.id)
    latestInstance.value = instance
    await instances.load()
    toasts.ok(t('onboarding.import.importedToast', { name: instance.name }))
  } catch (e) {
    importError.value = errorMessage(e)
  } finally {
    importing.value = null
  }
}

function loaderText(c: { loader: { kind: LoaderKind; version: string | null } }) {
  return c.loader.version ? `${loaderLabels[c.loader.kind]} ${c.loader.version}` : loaderLabels[c.loader.kind]
}

// --- Erste Instanz ----------------------------------------------------------
const preset = ref<Preset>('vanilla')
const latestRelease = ref<string | null>(null)
const manifestError = ref<string | null>(null)
const creating = ref<'create' | 'pack' | null>(null)
const customOpen = ref(false)
/** Zuletzt angelegte oder importierte Instanz – für die Zusammenfassung. */
const latestInstance = ref<Instance | null>(null)

async function loadLatest() {
  if (latestRelease.value) return
  manifestError.value = null
  try {
    latestRelease.value = (await meta.loadManifest()).latest.release
  } catch (e) {
    manifestError.value = errorMessage(e)
  }
}

async function createInstance() {
  if (creating.value) return
  if (preset.value === 'custom') {
    customOpen.value = true
    return
  }
  const version = latestRelease.value
  if (!version) return
  const kind: LoaderKind = preset.value === 'fabric' ? 'fabric' : 'vanilla'
  const parsed = newInstanceSchema.safeParse({
    name: `${loaderLabels[kind]} ${version}`,
    gameVersion: version,
    loader: { kind, version: null },
  })
  if (!parsed.success) {
    toasts.error(firstIssue(parsed.error))
    return
  }

  creating.value = 'create'
  try {
    const instance = await instances.create(parsed.data)
    latestInstance.value = instance
    if (kind === 'fabric') {
      creating.value = 'pack'
      try {
        const files = await backend.installPerformancePack(instance.id)
        toasts.ok(t('onboarding.instance.packInstalled', files.length))
      } catch (e) {
        // Die Instanz steht trotzdem – das Paket lässt sich später unter „Inhalte“ nachinstallieren.
        toasts.error(e)
      }
    }
    goTo('done')
  } catch (e) {
    toasts.error(e)
  } finally {
    creating.value = null
  }
}

function onCustomCreated(instance: Instance) {
  customOpen.value = false
  latestInstance.value = instance
  goTo('done')
}

const presetCards = computed(() => [
  {
    key: 'vanilla' as const,
    title: 'Vanilla',
    text: t('onboarding.instance.vanillaText'),
    version: latestRelease.value,
  },
  {
    key: 'fabric' as const,
    title: t('onboarding.instance.fabricTitle'),
    text: t('onboarding.instance.fabricText'),
    version: latestRelease.value,
  },
  {
    key: 'custom' as const,
    title: t('onboarding.instance.customTitle'),
    text: t('onboarding.instance.customText'),
    version: null,
  },
])

const createLabel = computed(() => {
  if (preset.value === 'custom') return t('onboarding.instance.chooseVersion')
  if (creating.value === 'pack') return t('onboarding.instance.installingPack')
  if (creating.value === 'create') return t('onboarding.instance.creating')
  return t('onboarding.instance.create')
})

// --- Fertig -----------------------------------------------------------------
const summaryInstance = computed(() => latestInstance.value ?? instances.items[0] ?? null)

function finish(play: boolean) {
  const id = summaryInstance.value?.id
  onboarding.finish()
  navigateTo('/')
  if (play && id) games.launch(id)
}

// --- Sprache ----------------------------------------------------------------
// Erster Start: die Windows-Sprache vorschlagen (sonst Englisch) und gleich
// übernehmen; bei „Einrichtung erneut starten“ die eingestellte Sprache.
const settings = useSettingsStore()
const language = ref<Locale>(currentLocale.value)
const detected = ref(false)

async function chooseLanguage(code: Locale) {
  language.value = code
  try {
    await settings.setLanguage(code)
  } catch (e) {
    toasts.error(e)
  }
}

onMounted(async () => {
  if (!onboarding.firstRun) {
    language.value = settings.current?.ui.language ?? currentLocale.value
    return
  }
  const code = detectSystemLocale()
  detected.value = code !== 'en'
  await chooseLanguage(code)
})

// --- Navigation -------------------------------------------------------------
function goTo(key: StepKey) {
  step.value = key
  if (key === 'import') scan()
  if (key === 'instance') loadLatest()
}

function next() {
  if (step.value === 'language') goTo('welcome')
  else if (step.value === 'welcome') goTo('login')
  else if (step.value === 'login') goTo('import')
  // Wer schon eine Instanz hat (z. B. gerade importiert), braucht keine neue.
  else if (step.value === 'import') goTo(instances.items.length ? 'done' : 'instance')
  else if (step.value === 'instance') goTo('done')
}

function skip() {
  if (loggingIn.value) cancelLogin()
  onboarding.finish()
}

// --- Fokus & Tastatur -------------------------------------------------------
function focusPrimary() {
  nextTick(() => {
    const el = root.value?.querySelector<HTMLElement>('[data-primary]:not([disabled])')
    el?.focus()
  })
}

// Wechselt innerhalb eines Schritts die Hauptaktion (z. B. nach dem Login), rückt der Fokus nach.
watch([() => accounts.active?.id, loggingIn, scanning, latestRelease, creating], focusPrimary)

const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'

function onKey(e: KeyboardEvent) {
  // Der Dialog für eigene Versionen behandelt Escape und Tab selbst.
  if (customOpen.value) return
  if (e.key === 'Escape') {
    e.preventDefault()
    skip()
    return
  }
  if (e.key !== 'Tab' || !root.value) return
  const items = Array.from(root.value.querySelectorAll<HTMLElement>(FOCUSABLE))
  if (!items.length) return
  const first = items[0]!
  const last = items[items.length - 1]!
  const active = document.activeElement as HTMLElement | null
  if (!active || !root.value.contains(active)) {
    e.preventDefault()
    first.focus()
  } else if (e.shiftKey && active === first) {
    e.preventDefault()
    last.focus()
  } else if (!e.shiftKey && active === last) {
    e.preventDefault()
    first.focus()
  }
}

let previousFocus: HTMLElement | null = null
onMounted(() => {
  previousFocus = document.activeElement as HTMLElement | null
  window.addEventListener('keydown', onKey)
  focusPrimary()
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  if (loggingIn.value) cancelLogin()
  previousFocus?.focus?.()
})
</script>

<template>
  <section
    ref="root"
    role="dialog"
    aria-modal="true"
    aria-labelledby="onboarding-title"
    class="onboarding absolute inset-0 z-40 flex flex-col overflow-y-auto"
  >
    <!-- Schrittanzeige: echte Reihenfolge, die Leitung lädt pro Schritt auf. -->
    <header class="mx-auto w-full max-w-2xl px-6 pt-8">
      <div class="flex items-center justify-between gap-4">
        <ol class="flex flex-wrap gap-x-4 gap-y-1 text-xs">
          <li
            v-for="(s, i) in steps"
            :key="s.key"
            :aria-current="i === stepIndex ? 'step' : undefined"
            :class="i === stepIndex ? 'text-base-50' : i < stepIndex ? 'text-base-400' : 'text-base-600'"
          >
            <span class="display tabular-nums" :class="i === stepIndex ? 'text-redstone-300' : ''">{{ i + 1 }}</span>
            {{ s.label }}
          </li>
        </ol>
        <button v-if="step !== 'language' && step !== 'welcome' && step !== 'done'" class="shrink-0 text-xs text-base-400 hover:text-base-50" @click="skip">
          {{ t('onboarding.skipSetup') }}
        </button>
      </div>
      <RedstoneWire class="mt-3" :segments="30" :percent="((stepIndex + 1) / stepOrder.length) * 100" />
    </header>

    <div class="mx-auto flex w-full max-w-2xl flex-1 flex-col justify-center px-6 py-10">
      <Transition name="step" mode="out-in" @after-enter="focusPrimary">
        <!-- 1 · Sprache -->
        <div v-if="step === 'language'" key="language">
          <h1 id="onboarding-title" class="display text-4xl leading-tight">{{ t('language.onboardingTitle') }}</h1>
          <p class="mt-2 max-w-lg text-sm text-base-400">{{ t('language.onboardingText') }}</p>
          <p v-if="detected" class="mt-1 text-xs text-base-600">{{ t('language.detected') }}</p>
          <div class="onboarding-languages mt-6">
            <LanguagePicker :model-value="language" :search="false" compact @update:model-value="chooseLanguage" />
          </div>
          <div class="mt-6 flex gap-3">
            <button data-primary class="btn btn-primary h-11 px-6 text-base" @click="next">{{ t('common.actions.continue') }}</button>
            <button class="btn btn-ghost h-11 px-5" @click="skip">{{ t('common.actions.skip') }}</button>
          </div>
        </div>

        <!-- 2 · Willkommen -->
        <div v-else-if="step === 'welcome'" key="welcome">
          <h1 id="onboarding-title" class="display text-6xl leading-tight text-base-50">TRS Launcher</h1>
          <p class="mt-3 max-w-lg text-base-200">{{ t('onboarding.welcome.text') }}</p>
          <div class="mt-8 flex gap-3">
            <button data-primary class="btn btn-primary h-11 px-6 text-base" @click="next">{{ t('onboarding.welcome.start') }}</button>
            <button class="btn btn-ghost h-11 px-5" @click="skip">{{ t('common.actions.skip') }}</button>
          </div>
        </div>

        <!-- 3 · Anmelden -->
        <div v-else-if="step === 'login'" key="login">
          <h1 id="onboarding-title" class="display text-4xl leading-tight">{{ t('onboarding.login.title') }}</h1>
          <p class="mt-2 max-w-lg text-sm text-base-400">{{ t('onboarding.login.text') }}</p>

          <div v-if="accounts.active" class="card mt-6 flex items-center gap-3 p-4">
            <SkinHead :skin-url="accounts.active.skinUrl" :name="accounts.active.name" :size="48" />
            <div class="min-w-0">
              <p class="truncate font-medium">{{ accounts.active.name }}</p>
              <p class="text-xs text-ok">{{ t('onboarding.login.signedIn') }}</p>
            </div>
          </div>

          <div v-else-if="loggingIn" class="card mt-6 p-4">
            <p class="text-sm text-base-200">{{ t('accounts.login.browserOpened') }}</p>
            <p class="mt-3 flex items-center gap-2 text-xs text-base-400">
              <span class="size-2 animate-pulse rounded-full bg-redstone-400" /> {{ t('accounts.login.waiting') }}
            </p>
          </div>

          <div
            v-if="loginError"
            role="alert"
            class="card mt-4 px-4 py-3 text-sm"
            :class="notApproved ? 'border-warn/40 text-warn' : 'border-redstone-600/50 text-redstone-300'"
          >
            {{ loginError }}
            <p v-if="notApproved" class="mt-1 text-xs text-base-400">{{ t('accounts.login.notApprovedHint') }}</p>
          </div>

          <div class="mt-8 flex gap-3">
            <template v-if="accounts.active">
              <button data-primary class="btn btn-primary h-11 px-6 text-base" @click="next">{{ t('common.actions.continue') }}</button>
            </template>
            <template v-else-if="loggingIn">
              <button data-primary class="btn btn-ghost h-11 px-5" @click="cancelLogin">{{ t('common.actions.cancel') }}</button>
            </template>
            <template v-else>
              <button data-primary class="btn btn-primary h-11 px-6 text-base" @click="login">{{ t('accounts.login.signInMicrosoft') }}</button>
              <button class="btn btn-ghost h-11 px-5" @click="next">{{ t('common.actions.later') }}</button>
            </template>
          </div>
        </div>

        <!-- 3 · Importieren -->
        <div v-else-if="step === 'import'" key="import">
          <h1 id="onboarding-title" class="display text-4xl leading-tight">{{ t('onboarding.import.title') }}</h1>
          <p class="mt-2 max-w-lg text-sm text-base-400">{{ t('onboarding.import.text') }}</p>

          <div v-if="scanning" class="mt-6 space-y-2">
            <div v-for="i in 3" :key="i" class="skeleton h-14" />
          </div>
          <p v-else-if="!candidates.length && !importError" class="card mt-6 px-4 py-6 text-center text-sm text-base-400">
            {{ t('onboarding.import.none') }}
          </p>
          <ul v-else-if="candidates.length" class="-mr-2 mt-6 max-h-80 space-y-1.5 overflow-y-auto pr-2">
            <li v-for="c in candidates" :key="c.id" class="flex items-center gap-3 rounded-md border border-base-700 bg-base-900 px-3 py-2">
              <div class="min-w-0 flex-1">
                <p class="truncate text-sm font-medium">{{ c.name }}</p>
                <p class="truncate text-xs text-base-400">
                  {{ importSourceLabel(c.source) }} · <span class="font-mono text-base-200">{{ c.gameVersion }}</span> {{ loaderText(c) }}
                  <template v-if="c.modCount"> · {{ t('onboarding.import.modCount', c.modCount) }}</template>
                  <template v-if="c.worldCount"> · {{ t('onboarding.import.worldCount', c.worldCount) }}</template>
                </p>
                <RedstoneWire v-if="importing?.id === c.id" :percent="importing.percent" :segments="28" class="mt-1.5" />
              </div>
              <span v-if="imported.has(c.id)" class="shrink-0 text-xs text-ok">{{ t('onboarding.import.imported') }}</span>
              <span v-else-if="importing?.id === c.id" class="display shrink-0 text-sm tabular-nums text-redstone-300">{{ importing.percent }} %</span>
              <button v-else class="btn btn-ghost shrink-0 px-3 py-1.5 text-xs" :disabled="!!importing" @click="runImport(c)">{{ t('common.actions.import') }}</button>
            </li>
          </ul>

          <p v-if="importError" role="alert" class="mt-3 text-sm text-redstone-300">{{ importError }}</p>

          <div class="mt-8 flex gap-3">
            <button data-primary class="btn btn-primary h-11 px-6 text-base" :disabled="!!importing" @click="next">{{ t('common.actions.continue') }}</button>
          </div>
        </div>

        <!-- 4 · Erste Instanz -->
        <div v-else-if="step === 'instance'" key="instance">
          <h1 id="onboarding-title" class="display text-4xl leading-tight">{{ t('onboarding.instance.title') }}</h1>
          <p class="mt-2 max-w-lg text-sm text-base-400">{{ t('onboarding.instance.text') }}</p>

          <div role="radiogroup" :aria-label="t('onboarding.instance.presetLabel')" class="mt-6 grid grid-cols-1 gap-3 sm:grid-cols-3">
            <button
              v-for="card in presetCards"
              :key="card.key"
              type="button"
              role="radio"
              :aria-checked="preset === card.key"
              :disabled="!!creating"
              class="flex min-h-36 flex-col rounded-lg border p-4 text-left transition-colors disabled:cursor-not-allowed"
              :class="preset === card.key
                ? 'border-redstone-500 bg-redstone-900/50'
                : 'border-base-800 bg-base-900 hover:border-base-600'"
              @click="preset = card.key"
            >
              <span class="font-semibold text-base-50">{{ card.title }}</span>
              <span class="mt-1 text-xs text-base-400">{{ card.text }}</span>
              <span class="mt-auto pt-3">
                <template v-if="card.key !== 'custom'">
                  <span v-if="card.version" class="display text-lg text-redstone-300">{{ card.version }}</span>
                  <span v-else-if="!manifestError" class="skeleton block h-5 w-16" />
                </template>
                <span v-else class="text-xs text-base-400">{{ t('onboarding.instance.allVersions') }}</span>
              </span>
            </button>
          </div>

          <p v-if="manifestError && preset !== 'custom'" role="alert" class="mt-3 text-sm text-redstone-300">
            {{ manifestError }}
          </p>
          <p v-if="creating" role="status" class="mt-4 flex items-center gap-2 text-xs text-base-400">
            <span class="size-2 animate-pulse rounded-full bg-redstone-400" />
            {{ creating === 'pack' ? t('onboarding.instance.creatingPack') : t('onboarding.instance.creatingInstance') }}
          </p>

          <div class="mt-8 flex gap-3">
            <button
              data-primary
              class="btn btn-primary h-11 px-6 text-base"
              :disabled="!!creating || (preset !== 'custom' && !latestRelease)"
              @click="createInstance"
            >
              {{ createLabel }}
            </button>
            <button class="btn btn-ghost h-11 px-5" :disabled="!!creating" @click="next">{{ t('common.actions.later') }}</button>
          </div>
        </div>

        <!-- 5 · Fertig -->
        <div v-else key="done">
          <h1 id="onboarding-title" class="display text-5xl leading-tight">{{ t('onboarding.done.title') }}</h1>
          <p class="mt-2 text-sm text-base-400">{{ t('onboarding.done.text') }}</p>

          <ul class="mt-6 space-y-2">
            <li class="card flex items-center gap-3 p-3">
              <template v-if="accounts.active">
                <SkinHead :skin-url="accounts.active.skinUrl" :name="accounts.active.name" :size="40" />
                <div class="min-w-0">
                  <p class="truncate font-medium">{{ accounts.active.name }}</p>
                  <p class="text-xs text-base-400">{{ t('onboarding.done.account') }}</p>
                </div>
              </template>
              <template v-else>
                <span class="flex size-10 shrink-0 items-center justify-center rounded bg-base-800 text-base-400">–</span>
                <div class="min-w-0">
                  <p class="font-medium text-base-200">{{ t('onboarding.done.notSignedIn') }}</p>
                  <p class="text-xs text-base-400">{{ t('onboarding.done.notSignedInHint') }}</p>
                </div>
              </template>
            </li>
            <li class="card flex items-center gap-3 p-3">
              <template v-if="summaryInstance">
                <span class="display flex size-10 shrink-0 items-center justify-center rounded bg-base-800 text-lg text-redstone-400">
                  {{ summaryInstance.name.charAt(0).toUpperCase() }}
                </span>
                <div class="min-w-0">
                  <p class="truncate font-medium">{{ summaryInstance.name }}</p>
                  <p class="truncate text-xs text-base-400">
                    <span class="font-mono text-base-200">{{ summaryInstance.gameVersion }}</span> {{ loaderText(summaryInstance) }}
                  </p>
                </div>
              </template>
              <template v-else>
                <span class="flex size-10 shrink-0 items-center justify-center rounded bg-base-800 text-base-400">–</span>
                <div class="min-w-0">
                  <p class="font-medium text-base-200">{{ t('onboarding.done.noInstance') }}</p>
                  <p class="text-xs text-base-400">{{ t('onboarding.done.noInstanceHint') }}</p>
                </div>
              </template>
            </li>
          </ul>

          <div class="mt-8 flex gap-3">
            <button data-primary class="btn btn-primary h-11 px-6 text-base" @click="finish(false)">{{ t('onboarding.done.home') }}</button>
            <button v-if="summaryInstance" class="btn btn-ghost h-11 px-5" @click="finish(true)">
              <svg viewBox="0 0 24 24" class="size-4" fill="currentColor"><path d="M7 4v16l13-8z" /></svg>
              {{ t('onboarding.done.playNow') }}
            </button>
          </div>
        </div>
      </Transition>
    </div>

    <CreateInstanceDialog v-if="customOpen" @close="customOpen = false; focusPrimary()" @created="onCustomCreated" />
  </section>
</template>

<style scoped>
/* Deepslate-Kacheln wie auf der Startrampe, nur ruhiger. */
.onboarding {
  background:
    radial-gradient(90% 70% at 0% 0%, rgb(224 40 30 / 0.12), transparent 60%),
    repeating-linear-gradient(0deg, rgb(255 255 255 / 0.02) 0 1px, transparent 1px 32px),
    repeating-linear-gradient(90deg, rgb(255 255 255 / 0.02) 0 1px, transparent 1px 32px),
    var(--color-base-950);
}

.step-enter-active,
.step-leave-active {
  transition: opacity 0.14s ease, transform 0.14s ease;
}
.step-enter-from {
  opacity: 0;
  transform: translateX(8px);
}
.step-leave-to {
  opacity: 0;
  transform: translateX(-8px);
}
</style>
