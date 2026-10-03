<script setup lang="ts">
import type { RemoteSettings } from '~/types'

// Einstellungen → Fernbedienung (am PC): an/aus (ab Werk aus), was das Handy darf, Handy koppeln (QR-Code + Code,
// 2 min gültig), gekoppelte Handys entfernen. Gespeichert wird wie alle Einstellungen automatisch.
const model = defineModel<RemoteSettings>({ required: true })

const remote = useRemoteStore()
const settings = useSettingsStore()
const trs = useTrsStore()
const toasts = useToasts()

const starting = ref(false)
const error = ref<string | null>(null)
const removing = ref<string | null>(null)
/** Ablauf des Codes nach der eigenen Uhr (Server sagt „noch 120 s“). */
const deadline = ref(0)
const now = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | undefined

const remaining = computed(() => Math.max(0, Math.ceil((deadline.value - now.value) / 1000)))
const expired = computed(() => !!remote.pairCode && remaining.value === 0)
const countdown = computed(() => `${Math.floor(remaining.value / 60)}:${String(remaining.value % 60).padStart(2, '0')}`)

async function loadPhones() {
  try {
    await remote.loadPhones()
  } catch (e) {
    error.value = errorMessage(e)
  }
}

watch(
  () => model.value.enabled && trs.enabled,
  (on) => {
    error.value = null
    if (on) void loadPhones()
    else remote.cancelPairing()
  },
  { immediate: true },
)

// Gekoppelt: der Code verschwindet (Ereignis `remote_pairing`), kurze Bestätigung.
watch(
  () => remote.phones.length,
  (n, before) => {
    if (before !== undefined && n > before) toasts.ok(t('remote.settings.paired'))
  },
)

/** Die Einstellungen speichern verzögert – vor dem Koppeln kurz warten, bis „an“ wirklich gespeichert ist. */
async function savedEnabled(): Promise<boolean> {
  for (let i = 0; i < 30 && !settings.current?.remote?.enabled; i++) await new Promise((r) => setTimeout(r, 100))
  return !!settings.current?.remote?.enabled
}

async function startPairing() {
  if (starting.value) return
  starting.value = true
  error.value = null
  try {
    if (!(await savedEnabled())) throw new Error(t('remote.settings.notSaved'))
    const code = await remote.startPairing()
    deadline.value = Date.now() + code.expiresIn * 1000
    now.value = Date.now()
    clearInterval(ticker)
    ticker = setInterval(() => (now.value = Date.now()), 1000)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    starting.value = false
  }
}

function cancelPairing() {
  clearInterval(ticker)
  remote.cancelPairing()
}

async function removePhone(id: string) {
  removing.value = id
  try {
    await remote.removePhone(id)
  } catch (e) {
    toasts.error(e)
  } finally {
    removing.value = null
  }
}

onBeforeUnmount(() => {
  clearInterval(ticker)
  remote.cancelPairing()
})
</script>

<template>
  <div>
    <h3 class="section-heading">{{ t('remote.settings.title') }}</h3>
    <p class="mb-2 text-xs leading-relaxed text-base-400">{{ t('remote.settings.intro') }}</p>

    <div v-if="!trs.enabled" class="mb-2 rounded-md border border-base-700 bg-base-900 px-3 py-2 text-sm text-base-300">
      {{ t('remote.settings.needsTrs') }}
      <button type="button" class="btn btn-ghost mt-2 px-3 py-1.5 text-xs" @click="trs.askConsent()">{{ t('trsGate.consent.button') }}</button>
    </div>

    <SettingRow :title="t('remote.settings.enabled')" :description="t('remote.settings.enabledHint')">
      <ToggleSwitch v-model="model.enabled" :label="t('remote.settings.enabled')" data-testid="remote-enabled" />
    </SettingRow>

    <template v-if="model.enabled">
      <SettingRow :title="t('remote.settings.allowLaunch')" :description="t('remote.settings.allowLaunchHint')">
        <ToggleSwitch v-model="model.allowLaunch" :label="t('remote.settings.allowLaunch')" data-testid="remote-allow-launch" />
      </SettingRow>
      <SettingRow :title="t('remote.settings.allowInstall')" :description="t('remote.settings.allowInstallHint')">
        <ToggleSwitch v-model="model.allowInstall" :label="t('remote.settings.allowInstall')" data-testid="remote-allow-install" />
      </SettingRow>

      <h3 class="section-heading mt-6">{{ t('remote.settings.pairTitle') }}</h3>
      <div v-if="remote.pairCode" class="flex flex-wrap items-center gap-5 rounded-xl border border-base-800 bg-base-850 p-4" data-testid="remote-pair-code">
        <QrCode v-if="!expired" :text="remote.pairCode.link" :size="156" />
        <div class="min-w-0 flex-1">
          <p class="text-sm text-base-200">{{ t('remote.settings.pairSteps') }}</p>
          <p class="display mt-3 text-3xl font-bold tracking-[0.2em] text-base-50 select-all" :class="{ 'opacity-40 line-through': expired }">{{ remote.pairCode.code }}</p>
          <p class="mt-1 text-xs text-base-400">
            {{ expired ? t('remote.settings.expired') : t('remote.settings.expiresIn', { time: countdown }) }}
          </p>
          <div class="mt-3 flex gap-2">
            <button v-if="expired" type="button" class="btn btn-primary px-3 py-1.5 text-xs" :disabled="starting" @click="startPairing">{{ t('remote.settings.newCode') }}</button>
            <button type="button" class="btn btn-ghost px-3 py-1.5 text-xs" @click="cancelPairing">{{ t('common.actions.cancel') }}</button>
          </div>
          <p class="mt-3 text-xs text-lamp-300">{{ t('remote.settings.pairWarning') }}</p>
        </div>
      </div>
      <button
        v-else
        type="button"
        class="btn btn-primary"
        :disabled="starting || !trs.enabled"
        data-testid="remote-pair"
        @click="startPairing"
      >
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.qr" /></svg>
        {{ starting ? t('remote.settings.creating') : t('remote.settings.pair') }}
      </button>
      <p v-if="error" role="alert" class="mt-2 text-sm text-redstone-300">{{ error }}</p>

      <h3 class="section-heading mt-6">{{ t('remote.settings.phones') }}</h3>
      <ul v-if="remote.phones.length" class="space-y-2" data-testid="remote-phones">
        <li v-for="p in remote.phones" :key="p.id" class="flex items-center gap-3 rounded-lg border border-base-800 bg-base-850 px-3 py-2">
          <span class="grid size-8 shrink-0 place-items-center rounded-lg bg-base-800 text-base-300">
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.phone" /></svg>
          </span>
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-medium text-base-50">{{ p.name }}</p>
            <p class="text-xs text-base-400">
              {{ t('remote.settings.pairedAt', { date: dateTime(p.pairedAt) }) }}
              <template v-if="p.lastSeenAt"> · {{ t('remote.settings.lastSeen', { date: dateTime(p.lastSeenAt) }) }}</template>
            </p>
          </div>
          <button type="button" class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="removing === p.id" @click="removePhone(p.id)">{{ t('remote.settings.remove') }}</button>
        </li>
      </ul>
      <p v-else-if="remote.phonesLoaded" class="text-sm text-base-400">{{ t('remote.settings.noPhones') }}</p>
    </template>
  </div>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.section-heading {
  @apply mb-2 text-base font-semibold text-base-50;
}
</style>
