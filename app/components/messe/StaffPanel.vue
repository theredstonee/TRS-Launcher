<script setup lang="ts">
// Personal-Panel des Messe-PCs (Strg+Umschalt+M, nach PIN): Leerlaufzeit, Sprache, Präsentation, Zurücksetzen,
// Personal-Modus (Sperren aus), Vollbild, PIN ändern, Beenden.
import { getCurrentWindow } from '@tauri-apps/api/window'
import { isTauri } from '@tauri-apps/api/core'
import { clampTimeout, MAX_TIMEOUT_S, MIN_TIMEOUT_S, type MesseConfig, type MesseLang, messeStaffUnlocked } from '~/utils/messe'

const props = defineProps<{ config: MesseConfig; gameRunning: boolean }>()
const emit = defineEmits<{
  close: []
  update: [config: MesseConfig]
  startPitch: []
  reset: []
  changePin: []
  quit: []
}>()

const timeout = ref(props.config.timeoutS)
const unlocked = computed({ get: () => messeStaffUnlocked.value, set: (v: boolean) => (messeStaffUnlocked.value = v) })
const fullscreen = ref<boolean | null>(null)

function saveTimeout() {
  timeout.value = clampTimeout(timeout.value)
  if (timeout.value !== props.config.timeoutS) emit('update', { ...props.config, timeoutS: timeout.value })
}
function setLang(lang: MesseLang) {
  if (lang !== props.config.lang) emit('update', { ...props.config, lang })
}
async function toggleFullscreen() {
  if (!isTauri()) return
  const win = getCurrentWindow()
  const next = !(await win.isFullscreen().catch(() => false))
  await win.setFullscreen(next).catch(() => {})
  fullscreen.value = next
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.preventDefault()
    emit('close')
  }
}
onMounted(async () => {
  window.addEventListener('keydown', onKey)
  if (isTauri()) fullscreen.value = await getCurrentWindow().isFullscreen().catch(() => null)
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div class="messe-staff-backdrop" data-testid="messe-staff" @mousedown.self="emit('close')">
    <section class="card messe-staff" role="dialog" aria-modal="true" :aria-label="t('messe.staff.title')">
      <header class="flex items-center justify-between gap-3">
        <h2 class="text-lg font-semibold">{{ t('messe.staff.title') }}</h2>
        <button type="button" class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.close') }}</button>
      </header>

      <p v-if="gameRunning" class="rounded-lg bg-base-800 px-3 py-2 text-sm text-base-300">{{ t('messe.staff.gameRunning') }}</p>

      <label class="messe-staff-row">
        <span>
          <span class="block font-medium">{{ t('messe.staff.timeout') }}</span>
          <span class="block text-xs text-base-400">{{ t('messe.staff.timeoutHint', { min: MIN_TIMEOUT_S, max: MAX_TIMEOUT_S }) }}</span>
        </span>
        <input v-model.number="timeout" class="field w-24 text-right" type="number" :min="MIN_TIMEOUT_S" :max="MAX_TIMEOUT_S" step="10" data-testid="messe-timeout" @change="saveTimeout">
      </label>

      <div class="messe-staff-row">
        <span class="font-medium">{{ t('messe.staff.language') }}</span>
        <div class="flex gap-1.5">
          <button type="button" class="btn" :class="config.lang === 'de' ? 'btn-primary' : 'btn-ghost'" @click="setLang('de')">Deutsch</button>
          <button type="button" class="btn" :class="config.lang === 'en' ? 'btn-primary' : 'btn-ghost'" @click="setLang('en')">English</button>
        </div>
      </div>

      <div class="messe-staff-row">
        <span>
          <span class="block font-medium">{{ t('messe.staff.unlock') }}</span>
          <span class="block text-xs text-base-400">{{ t('messe.staff.unlockHint') }}</span>
        </span>
        <ToggleSwitch v-model="unlocked" data-testid="messe-unlock" :label="t('messe.staff.unlock')" />
      </div>

      <div v-if="fullscreen !== null" class="messe-staff-row">
        <span class="font-medium">{{ t('messe.staff.fullscreen') }}</span>
        <ToggleSwitch :model-value="fullscreen" :label="t('messe.staff.fullscreen')" @update:model-value="toggleFullscreen" />
      </div>

      <div class="grid grid-cols-2 gap-2 pt-1">
        <button type="button" class="btn btn-primary justify-center" data-testid="messe-start-pitch" @click="emit('startPitch')">{{ t('messe.staff.startPitch') }}</button>
        <button type="button" class="btn btn-ghost justify-center" :title="t('messe.staff.resetHint')" @click="emit('reset')">{{ t('messe.staff.reset') }}</button>
        <button type="button" class="btn btn-ghost justify-center" @click="emit('changePin')">{{ t('messe.staff.changePin') }}</button>
        <button type="button" class="btn btn-danger justify-center" data-testid="messe-quit" @click="emit('quit')">{{ t('messe.staff.quit') }}</button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.messe-staff-backdrop {
  position: fixed;
  inset: 0;
  z-index: 2147482500;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgb(0 0 0 / 0.6);
}
.messe-staff {
  display: flex;
  width: min(32rem, calc(100vw - 2rem));
  flex-direction: column;
  gap: 0.9rem;
  padding: 1.25rem;
}
.messe-staff-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}
</style>
