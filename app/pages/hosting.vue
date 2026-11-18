<script setup lang="ts">
import {
  type CraftingAccess,
  type CraftingState,
  NAME_MAX,
  defaultServerType,
  hostingCountdown,
  inCooldown,
  isTransitional,
  remainingSeconds,
  secondsUntil,
  validServerName,
} from '~/utils/craftinghosting'

// Kostenloser Server beim Partner CraftingHosting: ohne Server das Formular zum Anlegen,
// sonst Zustand, Adresse, Laufzeit, Starten/Stoppen/Beitreten, Zugang und Konsole.
const store = useCraftingHostingStore()
const trs = useTrsStore()
const accounts = useAccountsStore()

const LOGO = '/partners/craftinghosting.png'
const mobile = computed(() => platformCaps.value.platform === 'android' || platformCaps.value.platform === 'ios')
const server = computed(() => store.server)
const info = computed(() => store.info)

// Countdown jede Sekunde.
const now = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  ticker = setInterval(() => (now.value = Date.now()), 1000)
  if (trs.enabled && accounts.active) void store.watch()
})
onUnmounted(() => {
  if (ticker) clearInterval(ticker)
  store.unwatch()
})

// TRS eingeschaltet bzw. Konto gewechselt: neu laden.
watch(
  () => [trs.enabled, accounts.active?.id] as const,
  ([enabled, id], old) => {
    if (old && old[1] !== id) store.reset()
    if (enabled && id) void store.watch()
    else store.unwatch()
  },
)

// --- Anlegen ---------------------------------------------------------------------------
const form = reactive({ name: '', serverType: '', version: '', access: 'friends' as CraftingAccess })
watch(
  info,
  (i) => {
    if (!i || form.serverType) return
    Object.assign(form, defaultServerType(i))
  },
  { immediate: true },
)
const versions = computed(() => info.value?.serverTypes.find((t) => t.id === form.serverType)?.versions ?? [])
watch(
  () => form.serverType,
  () => {
    if (!versions.value.includes(form.version)) form.version = versions.value[0] ?? ''
  },
)
const nameOk = computed(() => validServerName(form.name))
const canCreate = computed(() => nameOk.value && !!form.serverType && !!form.version && !info.value?.full && !store.busy && !mobile.value)

async function create() {
  if (!canCreate.value) return
  await store.create({ name: form.name.trim(), serverType: form.serverType, version: form.version, access: form.access })
}

function versionLabel(v: string) {
  return v === 'latest' ? t('craftinghosting.latest') : v
}

// --- Server ----------------------------------------------------------------------------
const state = computed<CraftingState | null>(() => server.value?.state ?? null)
const typeName = computed(() => info.value?.serverTypes.find((t) => t.id === server.value?.serverType)?.name ?? server.value?.serverType ?? '')
const cooling = computed(() => inCooldown(server.value, now.value))
const cooldownLeft = computed(() => secondsUntil(server.value?.session.cooldownUntil, now.value))
const runLeft = computed(() => remainingSeconds(server.value, store.loadedAt, now.value))
const stateClass: Record<CraftingState, string> = {
  installing: 'bg-lamp-900 text-lamp-300',
  offline: 'bg-base-800 text-base-300',
  starting: 'bg-lamp-900 text-lamp-300',
  running: 'bg-ok/15 text-ok',
  stopping: 'bg-lamp-900 text-lamp-300',
  failed: 'bg-redstone-900/50 text-redstone-300',
  suspended: 'bg-redstone-900/50 text-redstone-300',
}
const canStart = computed(() => !mobile.value && !cooling.value && (state.value === 'offline' || state.value === 'failed'))
const canStop = computed(() => state.value === 'running' || state.value === 'starting')
const canJoin = computed(
  () => !mobile.value || platformCaps.value.gameLaunch,
)
const joinDisabled = computed(
  () => store.joining || !!store.busy || cooling.value || state.value === 'installing' || state.value === 'stopping' || state.value === 'suspended',
)

const copied = ref(false)
async function copyAddress() {
  if (!server.value?.address) return
  try {
    await navigator.clipboard.writeText(server.value.address)
    copied.value = true
    setTimeout(() => (copied.value = false), 1500)
  } catch {
    useToasts().error(t('errors.unexpected'))
  }
}

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

const commandText = ref('')
async function sendCommand() {
  if (await store.command(commandText.value)) commandText.value = ''
}
</script>

<template>
  <div class="mx-auto max-w-5xl p-6 mobile:p-4" data-testid="hosting-page">
    <!-- Kopf mit Partner-Logo -->
    <div class="mb-6 flex items-center gap-4 mobile:mb-4">
      <img :src="LOGO" alt="CraftingHosting" class="size-12 shrink-0 object-contain" draggable="false" />
      <div class="min-w-0 flex-1">
        <span class="badge bg-sky-900/50 text-sky-300">{{ t('craftinghosting.partner') }}</span>
        <h1 class="mt-1 text-xl font-semibold tracking-tight">{{ t('craftinghosting.title') }}</h1>
        <p class="mt-0.5 text-sm text-base-400">{{ t('craftinghosting.subtitle') }}</p>
      </div>
      <button v-if="store.loaded" class="btn btn-ghost mobile:hidden" :disabled="store.loading" @click="store.load()">
        {{ t('common.actions.refresh') }}
      </button>
    </div>

    <TrsGate what="hosting">
      <div v-if="!store.loaded && store.loading" class="skeleton h-40" />

      <div v-else-if="!store.loaded && store.problem" class="card px-4 py-4 text-sm" data-testid="hosting-problem">
        <p class="font-semibold text-base-50">{{ t('craftinghosting.unavailableTitle') }}</p>
        <p class="mt-0.5 text-base-400">{{ store.problem.text }}</p>
        <button class="btn btn-ghost mt-3 px-3 py-1.5 text-xs" :disabled="store.loading" @click="store.load()">{{ t('common.actions.retry') }}</button>
      </div>

      <template v-else-if="store.loaded">
        <p v-if="store.problem" class="mb-4 rounded-lg bg-lamp-900/40 px-3 py-2 text-sm text-lamp-300">{{ store.problem.text }}</p>
        <p v-if="mobile" class="mb-4 rounded-lg bg-base-900 px-3 py-2 text-sm text-base-300 ring-1 ring-base-800">{{ t('craftinghosting.mobileNote') }}</p>

        <!-- Noch kein Server: anlegen -->
        <div v-if="!server" class="grid gap-4 lg:grid-cols-[1fr_20rem]">
          <form class="card space-y-4 p-5" data-testid="hosting-create" @submit.prevent="create">
            <h2 class="font-semibold text-base-50">{{ t('craftinghosting.create.title') }}</h2>
            <div>
              <label for="ch-name" class="mb-1 block text-xs text-base-400">{{ t('craftinghosting.create.name') }}</label>
              <input
                id="ch-name"
                v-model="form.name"
                class="field"
                :maxlength="NAME_MAX"
                :placeholder="t('craftinghosting.create.namePlaceholder')"
                autocomplete="off"
                spellcheck="false"
              />
              <p class="mt-1 text-xs" :class="form.name && !nameOk ? 'text-redstone-300' : 'text-base-500'">{{ t('craftinghosting.create.nameHint') }}</p>
            </div>
            <div class="grid grid-cols-2 gap-3 mobile:grid-cols-1">
              <div>
                <label for="ch-type" class="mb-1 block text-xs text-base-400">{{ t('craftinghosting.create.type') }}</label>
                <select id="ch-type" v-model="form.serverType" class="field">
                  <option v-for="type in info?.serverTypes ?? []" :key="type.id" :value="type.id">{{ type.name }}</option>
                </select>
              </div>
              <div>
                <label for="ch-version" class="mb-1 block text-xs text-base-400">{{ t('craftinghosting.create.version') }}</label>
                <select id="ch-version" v-model="form.version" class="field font-mono">
                  <option v-for="v in versions" :key="v" :value="v">{{ versionLabel(v) }}</option>
                </select>
              </div>
            </div>
            <fieldset>
              <legend class="mb-1 text-xs text-base-400">{{ t('craftinghosting.access.label') }}</legend>
              <div class="grid gap-2 sm:grid-cols-2">
                <label
                  v-for="mode in ['friends', 'open'] as const"
                  :key="mode"
                  class="flex cursor-pointer items-start gap-2.5 rounded-lg border px-3 py-2.5 text-sm"
                  :class="form.access === mode ? 'border-redstone-500/60 bg-base-800' : 'border-base-800 hover:bg-base-800/60'"
                >
                  <input v-model="form.access" type="radio" name="ch-access" :value="mode" class="mt-0.5 accent-redstone-500" />
                  <span>
                    <span class="block font-semibold text-base-50">{{ t(`craftinghosting.access.${mode}`) }}</span>
                    <span class="block text-xs text-base-400">{{ t(`craftinghosting.access.${mode}Hint`) }}</span>
                  </span>
                </label>
              </div>
            </fieldset>
            <p v-if="info?.full" class="rounded-lg bg-lamp-900/40 px-3 py-2 text-sm text-lamp-300">{{ t('craftinghosting.create.full') }}</p>
            <button type="submit" class="btn btn-primary" :disabled="!canCreate" data-testid="hosting-create-submit">
              {{ store.busy === 'create' ? t('craftinghosting.create.creating') : t('craftinghosting.create.submit') }}
            </button>
          </form>

          <aside class="space-y-4">
            <div v-if="info" class="card p-4 text-sm">
              <h3 class="mb-2 font-semibold text-base-50">{{ t('craftinghosting.plan.title') }}</h3>
              <ul class="space-y-1 text-base-300">
                <li>{{ t('craftinghosting.plan.memory', { gb: Math.round(info.plan.memoryMb / 1024) }) }}</li>
                <li>{{ t('craftinghosting.plan.cpu', { percent: info.plan.cpuPercent }) }}</li>
                <li>{{ t('craftinghosting.plan.disk', { gb: Math.round(info.plan.diskMb / 1024) }) }}</li>
              </ul>
            </div>
            <div v-if="info" class="card p-4 text-sm">
              <h3 class="mb-2 font-semibold text-base-50">{{ t('craftinghosting.rules.title') }}</h3>
              <ul class="list-disc space-y-1 pl-4 text-base-300">
                <li>{{ t('craftinghosting.rules.runs') }}</li>
                <li>{{ t('craftinghosting.rules.session', { hours: info.limits.sessionMaxHours, pause: info.limits.cooldownHours }) }}</li>
                <li>{{ t('craftinghosting.rules.idle', { days: info.limits.deleteAfterIdleDays }) }}</li>
                <li>{{ t('craftinghosting.rules.eula') }}</li>
              </ul>
            </div>
          </aside>
        </div>

        <!-- Eigener Server -->
        <div v-else class="space-y-4" data-testid="hosting-server">
          <div class="card p-5">
            <div class="flex flex-wrap items-center gap-3">
              <div class="min-w-0 flex-1">
                <div class="flex items-center gap-2">
                  <h2 class="truncate text-lg font-semibold text-base-50">{{ server.name }}</h2>
                  <span class="badge" :class="stateClass[server.state]" data-testid="hosting-state">
                    <span v-if="isTransitional(server.state)" class="mr-1 inline-block size-1.5 animate-lamp rounded-full bg-current" />
                    {{ t(`craftinghosting.state.${server.state}`) }}
                  </span>
                </div>
                <p class="mt-0.5 text-sm text-base-400">{{ typeName }} · {{ versionLabel(server.version) }}</p>
              </div>
              <div class="flex flex-wrap gap-2">
                <button v-if="canJoin" class="btn btn-primary" :disabled="joinDisabled" data-testid="hosting-join" @click="store.join()">
                  {{ store.joining ? t('craftinghosting.actions.joining') : t('craftinghosting.actions.join') }}
                </button>
                <button v-if="canStop" class="btn btn-ghost" :disabled="!!store.busy" data-testid="hosting-stop" @click="store.stop()">
                  {{ t('common.actions.stop') }}
                </button>
                <button v-else class="btn btn-ghost" :disabled="!canStart || !!store.busy" data-testid="hosting-start" @click="store.requestStart()">
                  {{ t('common.actions.start') }}
                </button>
                <button class="btn btn-ghost text-redstone-300" :disabled="!!store.busy" data-testid="hosting-delete" @click="confirmDelete = true">
                  {{ t('common.actions.delete') }}
                </button>
              </div>
            </div>

            <!-- Adresse -->
            <div class="mt-4 flex flex-wrap items-center gap-2">
              <code v-if="server.address" class="rounded-lg bg-base-900 px-3 py-1.5 font-mono text-sm text-base-100 ring-1 ring-base-800">{{ server.address }}</code>
              <span v-else class="text-sm text-base-400">{{ t('craftinghosting.noAddress') }}</span>
              <button v-if="server.address" class="btn btn-ghost px-2.5 py-1 text-xs" @click="copyAddress">
                {{ copied ? t('common.status.copied') : t('common.actions.copy') }}
              </button>
            </div>

            <!-- Hinweise je Zustand -->
            <p v-if="server.state === 'installing'" class="mt-3 text-sm text-base-300">{{ t('craftinghosting.notes.installing') }}</p>
            <p v-else-if="server.state === 'failed'" class="mt-3 text-sm text-redstone-300">{{ t('craftinghosting.notes.failed') }}</p>
            <p v-else-if="server.state === 'suspended'" class="mt-3 text-sm text-redstone-300">{{ t('craftinghosting.notes.suspended') }}</p>
            <p v-if="cooling" class="mt-3 text-sm text-lamp-300" data-testid="hosting-cooldown">
              {{ t('craftinghosting.cooldown', { time: hostingCountdown(cooldownLeft) }) }}
            </p>
            <p v-else-if="server.session.active" class="mt-3 text-sm text-base-300">
              {{ t('craftinghosting.remaining', { time: hostingCountdown(runLeft) }) }}
            </p>
            <p v-if="!server.session.active && server.session.lastStopReason" class="mt-1 text-xs text-base-500">
              {{ t(`craftinghosting.stopReason.${server.session.lastStopReason}`) }}
            </p>
          </div>

          <div class="grid gap-4 md:grid-cols-2">
            <!-- Zugang -->
            <div class="card p-4">
              <h3 class="mb-2 text-sm font-semibold text-base-50">{{ t('craftinghosting.access.label') }}</h3>
              <div class="flex rounded-full bg-base-900 p-1 ring-1 ring-base-800" role="radiogroup" :aria-label="t('craftinghosting.access.label')">
                <button
                  v-for="mode in ['friends', 'open'] as const"
                  :key="mode"
                  class="seg flex-1 rounded-full text-xs"
                  :class="{ 'seg-on': server.access === mode }"
                  role="radio"
                  :aria-checked="server.access === mode"
                  :disabled="!!store.busy"
                  @click="store.setAccess(mode)"
                >
                  {{ t(`craftinghosting.access.${mode}`) }}
                </button>
              </div>
              <p class="mt-2 text-xs text-base-400">{{ t(`craftinghosting.access.${server.access}Hint`) }}</p>
            </div>

            <!-- Ressourcen -->
            <div class="card p-4 text-sm">
              <h3 class="mb-2 font-semibold text-base-50">{{ t('craftinghosting.plan.title') }}</h3>
              <ul class="space-y-1 text-base-300">
                <li>{{ t('craftinghosting.plan.memory', { gb: Math.round(server.resources.memoryMb / 1024) }) }}</li>
                <li>{{ t('craftinghosting.plan.cpu', { percent: server.resources.cpuPercent }) }}</li>
                <li>{{ t('craftinghosting.plan.disk', { gb: Math.round(server.resources.diskMb / 1024) }) }}</li>
              </ul>
            </div>
          </div>

          <!-- Konsole (ohne Ausgabe) -->
          <form class="card p-4" @submit.prevent="sendCommand">
            <h3 class="mb-2 text-sm font-semibold text-base-50">{{ t('craftinghosting.console.title') }}</h3>
            <div class="flex gap-2">
              <input
                v-model="commandText"
                class="field flex-1 font-mono"
                maxlength="257"
                :placeholder="t('craftinghosting.console.placeholder')"
                :disabled="server.state !== 'running'"
                autocomplete="off"
                spellcheck="false"
              />
              <button type="submit" class="btn btn-ghost" :disabled="server.state !== 'running' || !commandText.trim() || !!store.busy">
                {{ t('craftinghosting.console.send') }}
              </button>
            </div>
            <p class="mt-2 text-xs text-base-500">{{ t('craftinghosting.console.note') }}</p>
          </form>

          <p class="text-xs text-base-500">{{ t('craftinghosting.rules.idle', { days: info?.limits.deleteAfterIdleDays ?? 30 }) }}</p>
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
      <button class="mt-2 text-sm text-redstone-300 underline underline-offset-2 hover:text-redstone-200" @click="openEula">
        {{ t('craftinghosting.eula.link') }}
      </button>
      <label class="mt-4 flex cursor-pointer items-center gap-2 text-sm text-base-100">
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
