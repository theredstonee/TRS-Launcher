<script setup lang="ts">
import { DOMAIN_SUFFIX, SUBDOMAIN_MAX, type SubdomainStatus, cleanSubdomainInput, validSubdomain } from '~/utils/craftinghosting'

// Feld für `<name>.trs-launcher.de` mit Live-Prüfung (400 ms nach dem Tippen).
// `unavailable` = die API bietet keine eigenen Adressen an (dns_unavailable).
const props = defineProps<{ modelValue: string; inputId: string; current?: string | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; status: [status: SubdomainStatus]; unavailable: [] }>()

const store = useCraftingHostingStore()
const status = ref<SubdomainStatus>('idle')
let timer: ReturnType<typeof setTimeout> | null = null
let seq = 0

function setStatus(next: SubdomainStatus) {
  status.value = next
  emit('status', next)
}

function check(name: string) {
  if (timer) clearTimeout(timer)
  const n = ++seq
  if (!name) return setStatus('idle')
  if (!validSubdomain(name)) return setStatus('invalid')
  if (props.current && name === props.current) return setStatus('available')
  setStatus('checking')
  timer = setTimeout(async () => {
    const answer = await store.checkSubdomain(name)
    if (n !== seq) return
    if (answer.kind === 'dns') {
      setStatus('idle')
      emit('unavailable')
    } else if (answer.kind === 'error') {
      setStatus('error')
    } else {
      setStatus(answer.check.available ? 'available' : (answer.check.reason ?? 'taken'))
    }
  }, 400)
}

watch(() => props.modelValue, check, { immediate: true })
onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
  seq++
})

function onInput(e: Event) {
  const el = e.target as HTMLInputElement
  const value = cleanSubdomainInput(el.value)
  if (el.value !== value) el.value = value
  emit('update:modelValue', value)
}

const tone = computed(() => {
  switch (status.value) {
    case 'available':
      return 'text-ok'
    case 'taken':
    case 'reserved':
    case 'invalid':
      return 'text-redstone-300'
    case 'error':
      return 'text-lamp-300'
    default:
      return 'text-base-400'
  }
})
</script>

<template>
  <div>
    <div
      class="flex items-stretch overflow-hidden rounded-md border bg-base-900 transition-colors focus-within:border-redstone-500"
      :class="status === 'available' ? 'border-ok/50' : ['taken', 'reserved', 'invalid'].includes(status) ? 'border-redstone-600/60' : 'border-base-700'"
    >
      <input
        :id="inputId"
        :value="modelValue"
        class="min-w-0 flex-1 bg-transparent px-3 py-2 font-mono text-sm text-base-50 outline-none placeholder:text-base-400"
        :maxlength="SUBDOMAIN_MAX"
        placeholder="meinewelt"
        autocomplete="off"
        autocapitalize="off"
        spellcheck="false"
        :aria-describedby="`${inputId}-status`"
        :aria-invalid="['taken', 'reserved', 'invalid'].includes(status)"
        data-testid="hosting-subdomain"
        @input="onInput"
      />
      <span class="flex items-center border-l border-base-800 bg-base-850 px-3 font-mono text-sm text-base-400 select-none">{{ DOMAIN_SUFFIX }}</span>
    </div>
    <p :id="`${inputId}-status`" class="mt-1.5 flex min-h-4 items-center gap-1.5 text-xs" :class="tone" aria-live="polite" data-testid="hosting-subdomain-status">
      <svg v-if="status === 'available'" viewBox="0 0 16 16" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="m3.5 8.5 3 3 6-7" /></svg>
      <svg v-else-if="['taken', 'reserved', 'invalid'].includes(status)" viewBox="0 0 16 16" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="m4 4 8 8M12 4l-8 8" /></svg>
      <span v-else-if="status === 'checking'" class="inline-block size-1.5 animate-lamp rounded-full bg-current" aria-hidden="true" />
      <span>{{ status === 'idle' ? t('craftinghosting.address.hint') : t(`craftinghosting.address.status.${status}`) }}</span>
    </p>
  </div>
</template>
