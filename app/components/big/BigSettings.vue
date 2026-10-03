<script setup lang="ts">
// Big Picture „Einstellungen“ (nur das Nötigste): Konto wechseln, Arbeitsspeicher je
// Instanz, Start im Big-Picture-Modus, Big Picture verlassen und Launcher beenden.
import { isTauri } from '@tauri-apps/api/core'
import { getCurrentWindow } from '@tauri-apps/api/window'

const accounts = useAccountsStore()
const instances = useInstancesStore()
const settings = useSettingsStore()
const store = useBigPictureStore()
const toasts = useToasts()

const switching = ref<string | null>(null)
async function activate(id: string) {
  if (switching.value || accounts.active?.id === id) return
  switching.value = id
  try {
    await accounts.setActive(id)
  } catch (e) {
    toasts.error(e)
  } finally {
    switching.value = null
  }
}

// Instanz für den Speicher-Regler: blättern mit ‹ ›.
const memoryIndex = ref(0)
const memoryInstance = computed(() => instances.items[Math.min(memoryIndex.value, instances.items.length - 1)] ?? null)
function shift(delta: number) {
  const n = instances.items.length
  if (n) memoryIndex.value = (((memoryIndex.value + delta) % n) + n) % n
}

const ui = computed(() => settings.current?.ui)
async function setUi(key: 'bigPictureOnStart' | 'bigPictureAuto', value: boolean) {
  const base = settings.current ?? (await settings.load().catch(() => null))
  if (!base) return
  try {
    await settings.save({ ...base, ui: { ...base.ui, [key]: value } })
  } catch (e) {
    toasts.error(e)
  }
}

function quit() {
  if (isTauri()) void getCurrentWindow().close()
}
</script>

<template>
  <div class="space-y-12">
    <section aria-labelledby="bp-accounts">
      <h2 id="bp-accounts" class="bp-heading mb-6">{{ t('bigPicture.settings.accounts') }}</h2>
      <div v-if="accounts.items.length" class="grid gap-6" style="grid-template-columns: repeat(auto-fill, minmax(20rem, 1fr))">
        <button
          v-for="a in accounts.items"
          :key="a.id"
          type="button"
          class="bp-tile flex-row! items-center gap-5 p-5"
          :class="{ 'account-active': a.active }"
          :aria-pressed="a.active"
          @click="activate(a.id)"
        >
          <SkinHead :skin-url="a.skinUrl" :name="a.name" :size="64" />
          <span class="min-w-0 flex-1">
            <span class="block truncate text-2xl font-semibold text-base-50">{{ a.name }}</span>
            <span class="block text-base" :class="a.active ? 'text-redstone-300' : 'text-base-400'">
              {{ switching === a.id ? t('bigPicture.settings.switching') : a.active ? t('bigPicture.settings.activeAccount') : t('bigPicture.settings.useAccount') }}
            </span>
          </span>
        </button>
      </div>
      <p v-else class="bp-empty py-0">{{ t('bigPicture.settings.noAccounts') }}</p>
    </section>

    <section v-if="memoryInstance" aria-labelledby="bp-memory" class="max-w-4xl">
      <h2 id="bp-memory" class="bp-heading mb-6">{{ t('bigPicture.settings.memory') }}</h2>
      <div class="rounded-2xl border-2 border-base-800 bg-base-850/90 p-6">
        <div class="mb-6 flex items-center gap-4">
          <button v-if="instances.items.length > 1" type="button" class="bp-btn size-14 shrink-0 px-0 text-3xl" :aria-label="t('bigPicture.settings.previousInstance')" @click="shift(-1)">‹</button>
          <span class="flex min-w-0 flex-1 items-center gap-4">
            <InstanceIcon :instance="memoryInstance" :size="48" class="shrink-0" />
            <span class="min-w-0">
              <span class="block truncate text-2xl font-semibold text-base-50">{{ memoryInstance.name }}</span>
              <span class="block truncate text-base text-base-400"><span class="font-mono">{{ memoryInstance.gameVersion }}</span> {{ loaderLabels[memoryInstance.loader.kind] }}</span>
            </span>
          </span>
          <button v-if="instances.items.length > 1" type="button" class="bp-btn size-14 shrink-0 px-0 text-3xl" :aria-label="t('bigPicture.settings.nextInstance')" @click="shift(1)">›</button>
        </div>
        <BigMemory :key="memoryInstance.id" :instance="memoryInstance" />
      </div>
    </section>

    <section aria-labelledby="bp-mode" class="max-w-4xl">
      <h2 id="bp-mode" class="bp-heading mb-6">{{ t('bigPicture.settings.mode') }}</h2>
      <div class="space-y-4">
        <button type="button" role="switch" class="switch-row" :aria-checked="!!ui?.bigPictureOnStart" @click="setUi('bigPictureOnStart', !ui?.bigPictureOnStart)">
          <span class="min-w-0 flex-1">
            <span class="block text-xl font-semibold text-base-50">{{ t('settings.bigPicture.onStartTitle') }}</span>
            <span class="block text-base text-base-400">{{ t('settings.bigPicture.onStartDescription') }}</span>
          </span>
          <span class="knob" :class="{ on: ui?.bigPictureOnStart }" aria-hidden="true"><span /></span>
        </button>
        <button v-if="isLinux" type="button" role="switch" class="switch-row" :aria-checked="ui?.bigPictureAuto !== false" @click="setUi('bigPictureAuto', ui?.bigPictureAuto === false)">
          <span class="min-w-0 flex-1">
            <span class="block text-xl font-semibold text-base-50">{{ t('settings.bigPicture.autoTitle') }}</span>
            <span class="block text-base text-base-400">{{ t('settings.bigPicture.autoDescription') }}</span>
          </span>
          <span class="knob" :class="{ on: ui?.bigPictureAuto !== false }" aria-hidden="true"><span /></span>
        </button>
      </div>
      <div class="mt-8 flex flex-wrap gap-4">
        <button type="button" class="bp-btn bp-btn-primary" @click="store.close()">{{ t('bigPicture.exit') }}</button>
        <button type="button" class="bp-btn" @click="quit">{{ t('bigPicture.quit') }}</button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.account-active {
  border-color: var(--color-redstone-500);
  background: color-mix(in srgb, var(--color-redstone-900) 45%, var(--color-base-850));
}
.switch-row {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 1.5rem;
  padding: 1.25rem 1.5rem;
  border: 2px solid var(--color-base-800);
  border-radius: 1rem;
  background: color-mix(in srgb, var(--color-base-850) 90%, transparent);
  text-align: left;
  transition: border-color 0.15s ease;
}
.switch-row:hover {
  border-color: var(--color-base-600);
}
.knob {
  position: relative;
  width: 4.5rem;
  height: 2.5rem;
  flex-shrink: 0;
  border-radius: 9999px;
  background: var(--color-base-700);
  transition: background-color 0.2s ease;
}
.knob > span {
  position: absolute;
  top: 0.25rem;
  left: 0.25rem;
  width: 2rem;
  height: 2rem;
  border-radius: 9999px;
  background: #fff;
  transition: transform 0.2s ease;
}
.knob.on {
  background: var(--color-redstone-500);
}
.knob.on > span {
  transform: translateX(2rem);
}
</style>
