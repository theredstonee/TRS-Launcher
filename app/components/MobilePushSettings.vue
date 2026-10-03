<script setup lang="ts">
import type { PushCategory, PushDevice, PushSettings, PushStatus } from '~/utils/push'

// Einstellungen → Benachrichtigungen (nur am Handy): Push an/aus, Weg der Zustellung (UnifiedPush-Verteiler oder
// Abholen), Kategorien, Textvorschau im Chat (ab Werk aus), eigene Geräte. Jede Änderung gleicht das Gerät sofort
// mit dem Server ab – der Kern liefert danach den neuen Stand.
const trs = useTrsStore()
const toasts = useToasts()

const status = ref<PushStatus | null>(null)
const busy = ref(false)
const devices = ref<PushDevice[] | null>(null)
const devicesError = ref<string | null>(null)
const removing = ref<string | null>(null)

const ios = computed(() => platformCaps.value.platform === 'ios')
const settings = computed(() => status.value?.settings ?? null)
const distributorName = computed(() => {
  const s = status.value
  if (!s?.distributor) return null
  return s.distributors.find((d) => d.id === s.distributor)?.name ?? s.distributor
})
const errorText = computed(() => (status.value?.error ? userErrorText(status.value.error) : null))

async function apply(request: () => Promise<PushStatus>) {
  if (busy.value) return
  busy.value = true
  try {
    const before = status.value?.deviceId
    status.value = await request()
    if (status.value.deviceId !== before) void loadDevices()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = false
  }
}

function set(patch: Partial<PushSettings>) {
  const current = settings.value
  if (!current) return
  void apply(() => backend.push.setSettings({ ...current, ...patch }))
}

function setCategory(cat: string, on: boolean) {
  const s = status.value
  if (!s) return
  void apply(() => backend.push.setSettings(withCategory(s.settings, s.defaults, cat, on)))
}

function choose(distributor: string | null) {
  void apply(() => backend.push.chooseDistributor(distributor))
}

async function loadDevices() {
  if (!trs.enabled) return
  devicesError.value = null
  try {
    devices.value = await backend.push.devices()
  } catch (e) {
    devicesError.value = errorMessage(e)
  }
}

async function remove(device: PushDevice) {
  removing.value = device.id
  try {
    status.value = await backend.push.removeDevice(device.id)
    await loadDevices()
  } catch (e) {
    toasts.error(e)
  } finally {
    removing.value = null
  }
}

/** Verteiler-App öffnen; zurück in der App prüft der Abgleich die Verbindung erneut (siehe unten). */
function openDistributor() {
  void backend.push.openDistributor().catch((e) => toasts.error(e))
}

/** Zurück aus ntfy & Co.: Stand neu holen (neue Adresse, Verbindung, Recht für Benachrichtigungen). */
function onVisible() {
  if (document.visibilityState === 'visible') void apply(() => backend.push.status())
}
onMounted(() => document.addEventListener('visibilitychange', onVisible))
onBeforeUnmount(() => document.removeEventListener('visibilitychange', onVisible))

function open(url: string) {
  void backend.openExternalUrl(url).catch((e) => toasts.error(e))
}

onMounted(() => {
  void apply(() => backend.push.status())
  void loadDevices()
})
watch(
  () => trs.enabled,
  (on) => {
    if (on) void apply(() => backend.push.status()).then(loadDevices)
  },
)
</script>

<template>
  <div data-testid="push-settings">
    <h3 class="section-heading">{{ t('settings.push.title') }}</h3>
    <p class="mb-2 text-xs leading-relaxed text-base-400">{{ t('settings.push.intro') }}</p>

    <div v-if="!status" class="skeleton h-14" />
    <template v-else>
      <SettingRow :title="t('settings.push.enabled')" :description="t('settings.push.enabledHint')">
        <ToggleSwitch
          :model-value="status.settings.enabled"
          :label="t('settings.push.enabled')"
          :disabled="busy"
          data-testid="push-enabled"
          @update:model-value="(v: boolean) => set({ enabled: v })"
        />
      </SettingRow>

      <!-- Zustand und was als Nächstes zu tun ist -->
      <div v-if="status.settings.enabled" class="mt-2 rounded-xl border border-base-800 bg-base-850 p-4 text-sm text-base-200" role="status" data-testid="push-state" :data-state="status.state">
        <template v-if="status.state === 'signedOut'">
          <p>{{ t('settings.push.state.signedOut') }}</p>
          <button v-if="trs.undecided" type="button" class="btn btn-ghost mt-3 px-3 py-1.5 text-xs" @click="trs.askConsent()">{{ t('trsGate.consent.button') }}</button>
        </template>

        <template v-else-if="status.state === 'permission'">
          <p>{{ t('settings.push.state.permission') }}</p>
          <button type="button" class="btn btn-primary mt-3 px-3 py-1.5 text-xs" :disabled="busy" @click="set({ enabled: true })">{{ t('settings.push.allow') }}</button>
          <p class="mt-2 text-xs text-base-400">{{ t('settings.push.permissionHint') }}</p>
        </template>

        <template v-else-if="status.state === 'noDistributor'">
          <p>{{ t('settings.push.state.noDistributor') }}</p>
          <div class="mt-3 flex flex-wrap gap-2">
            <button type="button" class="btn btn-primary px-3 py-1.5 text-xs" @click="open(NTFY_FDROID)">{{ t('settings.push.ntfyFdroid') }}</button>
            <button type="button" class="btn btn-ghost px-3 py-1.5 text-xs" @click="open(NTFY_PLAY)">{{ t('settings.push.ntfyPlay') }}</button>
          </div>
          <p class="mt-3 text-xs text-base-400">{{ t('settings.push.afterInstall') }}</p>
          <div class="mt-3 flex flex-wrap gap-2">
            <button type="button" class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="busy" @click="apply(() => backend.push.status())">{{ t('settings.push.retry') }}</button>
            <button type="button" class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="busy" data-testid="push-poll" @click="choose(null)">{{ t('settings.push.pollInstead') }}</button>
          </div>
          <p class="mt-1 text-xs text-base-400">{{ t('settings.push.pollInsteadHint') }}</p>
        </template>

        <template v-else-if="status.state === 'chooseDistributor'">
          <p>{{ t('settings.push.state.chooseDistributor') }}</p>
        </template>

        <template v-else-if="status.state === 'waiting'">
          <p>{{ distributorName ? t('settings.push.state.waitingFor', { name: distributorName }) : t('settings.push.state.waiting') }}</p>
          <button type="button" class="btn btn-ghost mt-3 px-3 py-1.5 text-xs" :disabled="busy" @click="apply(() => backend.push.status())">{{ t('settings.push.retry') }}</button>
        </template>

        <template v-else-if="status.state === 'distributorInactive'">
          <p>{{ t('settings.push.state.distributorInactive', { name: distributorName ?? 'ntfy' }) }}</p>
          <div class="mt-3 flex flex-wrap gap-2">
            <button type="button" class="btn btn-primary px-3 py-1.5 text-xs" data-testid="push-open-distributor" @click="openDistributor">
              {{ t('settings.push.openDistributor', { name: distributorName ?? 'ntfy' }) }}
            </button>
            <button type="button" class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="busy" @click="apply(() => backend.push.status())">{{ t('settings.push.retry') }}</button>
          </div>
        </template>

        <template v-else-if="status.state === 'registered'">
          <p class="flex items-center gap-2">
            <span class="size-2 shrink-0 rounded-full bg-emerald-400" aria-hidden="true" />
            {{ distributorName ? t('settings.push.state.registered', { name: distributorName }) : t('settings.push.state.registeredPlain') }}
          </p>
        </template>

        <template v-else-if="status.state === 'polling'">
          <p class="flex items-center gap-2">
            <span class="size-2 shrink-0 rounded-full bg-emerald-400" aria-hidden="true" />
            {{ ios ? t('settings.push.state.pollingIos') : t('settings.push.state.pollingAndroid') }}
          </p>
        </template>

        <template v-else-if="status.state === 'error'">
          <p class="text-redstone-300">{{ errorText ? t('settings.push.state.errorWith', { error: errorText }) : t('settings.push.state.error') }}</p>
          <button type="button" class="btn btn-ghost mt-3 px-3 py-1.5 text-xs" :disabled="busy" @click="apply(() => backend.push.status())">{{ t('settings.push.retry') }}</button>
        </template>

        <!-- Verteiler wählen bzw. wechseln (Android) -->
        <div v-if="!ios && status.distributors.length && (status.state === 'chooseDistributor' || status.distributors.length > 1 || status.settings.pollFallback)" class="mt-3 flex flex-wrap gap-2" data-testid="push-distributors">
          <button
            v-for="d in status.distributors"
            :key="d.id"
            type="button"
            class="btn px-3 py-1.5 text-xs"
            :class="d.id === status.distributor && status.state === 'registered' ? 'btn-primary' : 'btn-ghost'"
            :disabled="busy"
            @click="choose(d.id)"
          >
            {{ t('settings.push.useDistributor', { name: d.name }) }}
          </button>
        </div>
      </div>
      <p v-else class="mt-2 text-sm text-base-400">{{ t('settings.push.state.off') }}</p>

      <template v-if="status.settings.enabled">
        <h3 class="section-heading mt-6">{{ t('settings.push.categoriesTitle') }}</h3>
        <SettingRow v-for="cat in status.categories" :key="cat" :title="t(`settings.push.categories.${cat as PushCategory}`)">
          <ToggleSwitch
            :model-value="categoryOn(status.settings, status.defaults, cat)"
            :label="t(`settings.push.categories.${cat as PushCategory}`)"
            :disabled="busy"
            @update:model-value="(v: boolean) => setCategory(cat, v)"
          />
        </SettingRow>

        <SettingRow :title="t('settings.push.previewTitle')" :description="t('settings.push.previewHint')">
          <ToggleSwitch :model-value="status.settings.preview" :label="t('settings.push.previewTitle')" :disabled="busy" data-testid="push-preview" @update:model-value="(v: boolean) => set({ preview: v })" />
        </SettingRow>
        <SettingRow :title="t('settings.push.whilePlayingTitle')" :description="t('settings.push.whilePlayingHint')">
          <ToggleSwitch :model-value="status.settings.pushWhilePlaying" :label="t('settings.push.whilePlayingTitle')" :disabled="busy" @update:model-value="(v: boolean) => set({ pushWhilePlaying: v })" />
        </SettingRow>
      </template>

      <template v-if="trs.enabled">
        <h3 class="section-heading mt-6">{{ t('settings.push.devicesTitle') }}</h3>
        <p v-if="devicesError" role="alert" class="text-sm text-redstone-300">{{ devicesError }}</p>
        <ul v-else-if="devices?.length" class="space-y-2" data-testid="push-devices">
          <li v-for="d in devices" :key="d.id" class="flex items-center gap-3 rounded-lg border border-base-800 bg-base-850 px-3 py-2">
            <span class="grid size-8 shrink-0 place-items-center rounded-lg bg-base-800 text-base-300">
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.phone" /></svg>
            </span>
            <div class="min-w-0 flex-1">
              <p class="truncate text-sm font-medium text-base-50">{{ d.deviceName }}</p>
              <p v-if="d.thisDevice" class="mt-0.5"><span class="rounded bg-redstone-900/50 px-1.5 py-0.5 text-[11px] text-redstone-200">{{ t('settings.push.thisDevice') }}</span></p>
              <p class="text-xs text-base-400">
                {{ d.kind === 'unifiedpush' ? t('settings.push.kindUnifiedpush', { host: d.endpointHost ?? '?' }) : t('settings.push.kindPoll') }}
                <template v-if="d.lastSeenAt"> · {{ t('settings.push.lastSeen', { date: dateTime(d.lastSeenAt) }) }}</template>
              </p>
              <p v-if="d.failing" class="text-xs text-lamp-300">{{ t('settings.push.failing') }}</p>
            </div>
            <button type="button" class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="removing === d.id" @click="remove(d)">{{ t('settings.push.remove') }}</button>
          </li>
        </ul>
        <p v-else-if="devices" class="text-sm text-base-400">{{ t('settings.push.noDevices') }}</p>
      </template>
    </template>
  </div>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.section-heading {
  @apply mb-2 text-base font-semibold text-base-50;
}
</style>
