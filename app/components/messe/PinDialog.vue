<script setup lang="ts">
// Personal-PIN des Messe-PCs: beim ersten Mal festlegen (zweimal eingeben), danach prüfen.
// Eigenes Ziffernfeld für Touch-Bildschirme am Stand; nach zu vielen Fehlversuchen kurz gesperrt.
import {
  createPinRecord,
  isValidPin,
  PIN_LOCK_MS,
  PIN_MAX_TRIES,
  readPinRecord,
  verifyPin,
  writePinRecord,
} from '~/utils/messe'

const props = defineProps<{ purpose: 'staff' | 'quit'; change?: boolean }>()
const emit = defineEmits<{ ok: []; cancel: [] }>()

const existing = readPinRecord()
// Neu festlegen: noch keine PIN oder „PIN ändern“ (dann ist die alte schon bestätigt)
const setting = !existing || props.change === true
const pin = ref('')
const repeat = ref('')
const step = ref<'pin' | 'repeat'>('pin')
const error = ref('')
const busy = ref(false)
const now = ref(Date.now())
const input = useTemplateRef<HTMLInputElement>('input')

const lockLeft = computed(() => Math.max(0, Math.ceil((pinLock.until - now.value) / 1000)))
let clock = 0

const title = computed(() => (setting ? t('messe.pin.setTitle') : t('messe.pin.title')))
const hint = computed(() => {
  if (setting) return step.value === 'repeat' ? t('messe.pin.repeat') : t('messe.pin.setHint')
  return props.purpose === 'quit' ? t('messe.pin.hintQuit') : t('messe.pin.hintStaff')
})
const value = computed({
  get: () => (step.value === 'repeat' ? repeat.value : pin.value),
  set: (v: string) => {
    const digits = v.replace(/\D/g, '').slice(0, 8)
    if (step.value === 'repeat') repeat.value = digits
    else pin.value = digits
  },
})

function press(d: string) {
  if (lockLeft.value) return
  error.value = ''
  value.value = value.value + d
  input.value?.focus()
}
function backspace() {
  value.value = value.value.slice(0, -1)
  input.value?.focus()
}

async function submit() {
  if (busy.value || lockLeft.value) return
  error.value = ''
  if (setting) {
    if (step.value === 'pin') {
      if (!isValidPin(pin.value)) return void (error.value = t('messe.pin.invalid'))
      step.value = 'repeat'
      return
    }
    if (repeat.value !== pin.value) {
      repeat.value = ''
      pin.value = ''
      step.value = 'pin'
      error.value = t('messe.pin.mismatch')
      return
    }
    busy.value = true
    writePinRecord(await createPinRecord(pin.value))
    busy.value = false
    emit('ok')
    return
  }
  busy.value = true
  const ok = existing ? await verifyPin(pin.value, existing) : false
  busy.value = false
  if (ok) {
    pinLock.tries = 0
    emit('ok')
    return
  }
  pin.value = ''
  pinLock.tries++
  if (pinLock.tries >= PIN_MAX_TRIES) {
    pinLock.tries = 0
    pinLock.until = Date.now() + PIN_LOCK_MS
    now.value = Date.now()
  }
  error.value = t('messe.pin.wrong')
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.preventDefault()
    emit('cancel')
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKey)
  clock = window.setInterval(() => (now.value = Date.now()), 500)
  void nextTick(() => input.value?.focus())
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  clearInterval(clock)
})
</script>

<script lang="ts">
// Fehlversuche gelten über das Schließen des Dialogs hinaus (bis zum Neustart).
const pinLock = { tries: 0, until: 0 }
</script>

<template>
  <div class="messe-pin-backdrop" data-testid="messe-pin" @mousedown.self="emit('cancel')">
    <form class="card messe-pin" role="dialog" aria-modal="true" :aria-label="title" @submit.prevent="submit">
      <h2 class="text-lg font-semibold">{{ title }}</h2>
      <p class="text-sm text-base-400">{{ hint }}</p>
      <input
        ref="input"
        v-model="value"
        class="field messe-pin-input"
        type="password"
        inputmode="numeric"
        autocomplete="off"
        maxlength="8"
        :aria-label="step === 'repeat' ? t('messe.pin.repeat') : t('messe.pin.pin')"
        :disabled="lockLeft > 0"
        data-testid="messe-pin-input"
      >
      <p v-if="lockLeft" class="text-sm text-red-400" role="alert">{{ t('messe.pin.locked', { seconds: lockLeft }) }}</p>
      <p v-else-if="error" class="text-sm text-red-400" role="alert">{{ error }}</p>
      <div class="messe-keypad">
        <button v-for="d in ['1', '2', '3', '4', '5', '6', '7', '8', '9']" :key="d" type="button" class="btn btn-ghost" @click="press(d)">{{ d }}</button>
        <button type="button" class="btn btn-ghost" :aria-label="t('messe.pin.clear')" @click="backspace">⌫</button>
        <button type="button" class="btn btn-ghost" @click="press('0')">0</button>
        <button type="submit" class="btn btn-primary" :disabled="busy || lockLeft > 0" data-testid="messe-pin-ok">{{ t('messe.pin.confirm') }}</button>
      </div>
      <button type="button" class="btn btn-ghost w-full" @click="emit('cancel')">{{ t('common.actions.cancel') }}</button>
    </form>
  </div>
</template>

<style scoped>
.messe-pin-backdrop {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgb(0 0 0 / 0.7);
  z-index: 2147482600;
}
.messe-pin {
  display: flex;
  width: min(22rem, calc(100vw - 2rem));
  flex-direction: column;
  gap: 0.75rem;
  padding: 1.25rem;
}
.messe-pin-input {
  font-size: 1.5rem;
  letter-spacing: 0.4em;
  text-align: center;
}
.messe-keypad {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0.5rem;
}
.messe-keypad .btn {
  justify-content: center;
  min-height: 3rem;
  font-size: 1.15rem;
}
</style>
