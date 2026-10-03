<script setup lang="ts">
import type { ConfigEntry } from '~/utils/config/types'
import { outOfRange } from '~/utils/config/editor'
import { configEditorKey } from '~/utils/config/ui'

// Ein Eintrag im Einfach-Modus: Schalter, Zahl (mit Regler), Auswahl, Text,
// Liste oder Rohtext – je nach erkanntem Typ.
const props = defineProps<{ entry: ConfigEntry }>()
const ctx = inject(configEditorKey)!

const value = computed(() => ctx.value(props.entry))
const changed = computed(() => ctx.changed(props.entry))
const problem = computed(() => ctx.problem(props.entry))
const label = computed(() => props.entry.key || '—')
const inputId = computed(() => `cfg-${props.entry.id.replace(/[^\w-]/g, '_')}`)

const bool = computed({
  get: () => value.value === true,
  set: (v: boolean) => ctx.set(props.entry, v),
})
const text = computed({
  get: () => (typeof value.value === 'string' ? value.value : String(value.value)),
  set: (v: string) => ctx.set(props.entry, v),
})
const items = computed(() => (Array.isArray(value.value) ? value.value : []))

const slider = computed(() => {
  const { min, max, kind, integer } = props.entry
  if (kind !== 'number' || min === undefined || max === undefined || !(max > min)) return null
  if (max - min > 10_000) return null
  // Schrittweite als glatte Zehnerpotenz (0.01, 0.1, 1 …).
  return { min, max, step: integer ? 1 : 10 ** Math.floor(Math.log10((max - min) / 100)) }
})

// Eigener Entwurf fürs Zahlenfeld, damit „1.0“ beim Tippen nicht zu „1“ springt.
const numberDraft = ref(typeof value.value === 'number' ? String(value.value) : '')
watch(value, (v) => {
  if (typeof v === 'number' && Number.isFinite(v) && Number(numberDraft.value) !== v) numberDraft.value = String(v)
})
function onNumber(e: Event) {
  const input = e.target as HTMLInputElement
  numberDraft.value = input.value
  ctx.set(props.entry, input.value.trim() === '' ? Number.NaN : input.valueAsNumber)
}
function setItem(i: number, v: string) {
  const next = [...items.value]
  next[i] = v
  ctx.set(props.entry, next)
}
function removeItem(i: number) {
  ctx.set(
    props.entry,
    items.value.filter((_, j) => j !== i),
  )
}
const listBox = ref<HTMLElement | null>(null)
function addItem() {
  ctx.set(props.entry, [...items.value, ''])
  nextTick(() => listBox.value?.querySelector<HTMLInputElement>('li:last-child input')?.focus())
}

const numberText = computed(() => (typeof value.value === 'number' && Number.isFinite(value.value) ? String(value.value) : ''))
const enumOptions = computed(() => {
  const options = props.entry.options ?? []
  return options.includes(text.value) ? options : [text.value, ...options]
})
const hints = computed(() => {
  const out: string[] = []
  const { min, max, defaultText } = props.entry
  if (min !== undefined && max !== undefined) out.push(t('configEditor.range', { min, max }))
  else if (min !== undefined) out.push(t('configEditor.min', { min }))
  else if (max !== undefined) out.push(t('configEditor.max', { max }))
  if (defaultText) out.push(t('configEditor.default', { value: defaultText }))
  return out.join(' · ')
})
const warnRange = computed(() => outOfRange(props.entry, value.value))
const stacked = computed(() => props.entry.kind === 'list')
</script>

<template>
  <div class="cfg-row" :class="{ 'cfg-row-changed': changed, 'flex-col items-stretch': stacked }">
    <div class="min-w-0 flex-1">
      <div class="flex items-center gap-2">
        <label :for="inputId" class="font-mono text-[13px] break-all text-base-50">{{ label }}</label>
        <span v-if="changed" class="size-1.5 shrink-0 rounded-full bg-lamp-400" :title="t('configEditor.changedOne')" />
      </div>
      <p v-if="entry.help" class="mt-0.5 text-xs leading-relaxed whitespace-pre-line text-base-400">{{ entry.help }}</p>
      <p v-if="hints" class="mt-0.5 text-[11px] text-base-600">{{ hints }}</p>
    </div>

    <div class="flex shrink-0 items-start gap-1.5" :class="stacked ? 'w-full' : 'w-[min(22rem,48%)] justify-end'">
      <div class="min-w-0 flex-1" :class="{ 'flex justify-end': entry.kind === 'bool' }">
        <ToggleSwitch v-if="entry.kind === 'bool'" :id="inputId" v-model="bool" :label="label" />

        <div v-else-if="entry.kind === 'number'" class="flex items-center gap-2">
          <input
            v-if="slider"
            type="range"
            class="min-w-0 flex-1 accent-redstone-500"
            :min="slider.min"
            :max="slider.max"
            :step="slider.step"
            :value="numberText"
            :aria-label="label"
            @input="onNumber"
          />
          <input
            :id="inputId"
            type="number"
            class="field h-8 py-0 font-mono text-xs"
            :class="[slider ? 'w-24' : 'w-full', { 'border-redstone-500': problem }]"
            :step="entry.integer ? 1 : 'any'"
            :value="numberDraft"
            @input="onNumber"
          />
        </div>

        <select v-else-if="entry.kind === 'enum'" :id="inputId" v-model="text" class="field h-8 py-0 font-mono text-xs">
          <option v-for="o in enumOptions" :key="o" :value="o">{{ o }}</option>
        </select>

        <div v-else-if="entry.kind === 'list'" ref="listBox" class="space-y-1">
          <ul v-if="items.length" class="space-y-1">
            <li v-for="(item, i) in items" :key="i" class="flex items-center gap-1">
              <input
                class="field h-7 py-0 font-mono text-xs"
                :value="item"
                :aria-label="`${label} ${i + 1}`"
                spellcheck="false"
                @input="setItem(i, ($event.target as HTMLInputElement).value)"
              />
              <button type="button" class="btn-icon size-7" :title="t('configEditor.removeItem')" :aria-label="t('configEditor.removeItem')" @click="removeItem(i)">
                <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M6 6l12 12M18 6 6 18" /></svg>
              </button>
            </li>
          </ul>
          <p v-else class="text-xs text-base-600">{{ t('configEditor.emptyList') }}</p>
          <button :id="inputId" type="button" class="inline-flex items-center gap-1 text-xs text-base-400 hover:text-base-50" @click="addItem">
            <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M12 5v14M5 12h14" /></svg>
            {{ t('configEditor.addItem') }}
          </button>
        </div>

        <button v-else-if="entry.kind === 'complex'" type="button" class="text-right text-xs text-sky-400 hover:underline" @click="ctx.jump(entry.line)">
          {{ t('configEditor.complex', { line: entry.line }) }}
        </button>

        <input
          v-else
          :id="inputId"
          v-model="text"
          class="field h-8 py-0 font-mono text-xs"
          :class="{ 'text-base-200 italic': entry.kind === 'raw' }"
          :title="entry.kind === 'raw' ? t('configEditor.raw') : undefined"
          spellcheck="false"
        />
      </div>
      <button v-if="changed" type="button" class="btn-icon size-7" :title="t('configEditor.reset')" :aria-label="t('configEditor.reset')" @click="ctx.reset(entry)">
        <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5" /></svg>
      </button>
    </div>
    <p v-if="problem" role="alert" class="w-full text-xs text-redstone-300">{{ t(`configEditor.problem.${problem}`) }}</p>
    <p v-else-if="warnRange" class="w-full text-xs text-lamp-300">{{ t('configEditor.outOfRange') }}</p>
  </div>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.cfg-row {
  @apply flex flex-wrap items-start gap-x-4 gap-y-1.5 border-b border-base-800 px-3 py-2.5 last:border-b-0;
}
.cfg-row-changed {
  box-shadow: inset 2px 0 0 var(--color-lamp-400);
  background: color-mix(in srgb, var(--color-lamp-900) 30%, transparent);
}
</style>
