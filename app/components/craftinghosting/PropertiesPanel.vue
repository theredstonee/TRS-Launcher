<script setup lang="ts">
import {
  KNOWN_PROPERTIES,
  MOTD_FOOTER,
  MOTD_MAX,
  PROPERTY_DIFFICULTIES,
  PROPERTY_GAMEMODES,
  PROPERTY_GROUPS,
  type PropertyField,
  type PropertyGroup,
  isKnownProperty,
  levelTypeId,
  matchesProperty,
  propertiesPatch,
  propertyField,
  propertyGroup,
  validPropertyValue,
} from '~/utils/craftinghosting'

// Alle Schlüssel aus server.properties: bekannte mit Beschriftung und passender Eingabe,
// unbekannte als Text. Gesperrte (TRS/Partner) nur zum Ansehen. Gespeichert wird nur, was sich änderte.
const store = useCraftingHostingStore()
const expanded = ref(false)
const query = ref('')
const form = ref<Record<string, string>>({})

const loaded = computed(() => store.properties)
const locked = computed(() => new Set(loaded.value?.locked ?? []))

watch(
  () => store.properties,
  (p) => (form.value = p ? { ...p.properties } : {}),
  { immediate: true },
)

function expand() {
  expanded.value = !expanded.value
  if (expanded.value && !store.properties && store.server?.lastStartAt) void store.loadProperties()
}
watch(query, (q) => {
  if (q.trim() && !expanded.value) expand()
})

const KNOWN_ORDER: string[] = Object.keys(KNOWN_PROPERTIES)
function label(key: string): string {
  return isKnownProperty(key) ? t(`craftinghosting.props.keys.${key}`) : key
}

interface Row {
  key: string
  label: string
  field: PropertyField
  locked: boolean
  valid: boolean
  changed: boolean
}

const rows = computed<Row[]>(() => {
  const before = loaded.value?.properties ?? {}
  return Object.keys(form.value)
    .sort((a, b) => {
      const ia = KNOWN_ORDER.indexOf(a)
      const ib = KNOWN_ORDER.indexOf(b)
      if (ia >= 0 || ib >= 0) return (ia < 0 ? 999 : ia) - (ib < 0 ? 999 : ib)
      return a.localeCompare(b)
    })
    .map((key) => {
      const value = form.value[key] ?? ''
      const field = propertyField(key, before[key] ?? value)
      return {
        key,
        label: label(key),
        field,
        locked: locked.value.has(key),
        valid: locked.value.has(key) || field.kind === 'readonly' || validPropertyValue(field, value),
        changed: before[key] !== value,
      }
    })
    .filter((r) => matchesProperty(query.value, r.key, r.label, form.value[r.key] ?? ''))
})

const groups = computed(() =>
  PROPERTY_GROUPS.map((g) => ({ id: g, rows: rows.value.filter((r) => propertyGroup(r.key) === g) })).filter((g) => g.rows.length),
)

const patch = computed(() => (loaded.value ? propertiesPatch(loaded.value.properties, form.value, loaded.value.locked) : {}))
const changes = computed(() => Object.keys(patch.value).length)
const invalid = computed(() => rows.value.some((r) => r.changed && !r.valid))

function setValue(key: string, e: Event) {
  form.value[key] = (e.target as HTMLInputElement | HTMLSelectElement).value
}
function setBool(key: string, on: boolean) {
  form.value[key] = on ? 'true' : 'false'
}

/** Auswahl samt aktuellem Wert, falls der Server einen unbekannten nutzt (z. B. alte Schreibweise). */
function options(row: Row): string[] {
  if (row.field.kind !== 'select') return []
  const opts = [...row.field.options]
  const current = form.value[row.key] ?? ''
  if (!current || opts.includes(current)) return opts
  // Gleicher Wert in anderer Schreibweise (`minecraft\:flat`, `FLAT`) ersetzt den Eintrag.
  const norm = (s: string) => s.replace(/\\/g, '').replace(/^minecraft:/, '').toLowerCase()
  const same = opts.findIndex((o) => norm(o) === norm(current))
  if (same >= 0) opts[same] = current
  else opts.unshift(current)
  return opts
}
function optionLabel(key: string, value: string): string {
  const mode = PROPERTY_GAMEMODES.find((m) => m === value)
  if (key === 'gamemode' && mode) return t(`craftinghosting.settings.gamemodes.${mode}`)
  const diff = PROPERTY_DIFFICULTIES.find((d) => d === value)
  if (key === 'difficulty' && diff) return t(`craftinghosting.settings.difficulties.${diff}`)
  const level = levelTypeId(value)
  if (key === 'level-type' && level) return t(`craftinghosting.props.levelTypes.${level}`)
  return value
}

async function save() {
  if (!changes.value || invalid.value) return
  await store.saveProperties(patch.value)
}
function discard() {
  form.value = loaded.value ? { ...loaded.value.properties } : {}
}

const groupIcon: Record<PropertyGroup, string> = {
  general: 'M4 6h16M4 12h16M4 18h10',
  gameplay: 'M6 12h4M8 10v4M15 11h.01M18 13h.01M7 6h10a4 4 0 0 1 4 4v4a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4v-4a4 4 0 0 1 4-4z',
  world: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3.6 9h16.8M3.6 15h16.8M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18',
  players: 'M16 19v-1a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v1M9 10a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM22 19v-1a4 4 0 0 0-3-3.9M16 4.1a3 3 0 0 1 0 5.8',
  network: 'M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M12 19.5h.01M2 9a15 15 0 0 1 20 0',
  other: 'M5 12h.01M12 12h.01M19 12h.01',
}
</script>

<template>
  <section class="card" aria-labelledby="ch-props-title" data-testid="hosting-properties">
    <div class="flex flex-wrap items-center gap-3 p-4">
      <button class="flex min-w-0 flex-1 items-center gap-3 text-left outline-none focus-visible:rounded-md focus-visible:ring-2 focus-visible:ring-redstone-500" :aria-expanded="expanded" aria-controls="ch-props-body" data-testid="hosting-properties-toggle" @click="expand">
        <span class="grid size-9 shrink-0 place-items-center rounded-lg bg-base-800 text-base-200 ring-1 ring-base-700">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M4 6h9M17 6h3M4 12h3M11 12h9M4 18h11M19 18h1M15 4v4M9 10v4M17 16v4" /></svg>
        </span>
        <span class="min-w-0">
          <span id="ch-props-title" class="block text-sm font-semibold text-base-50">{{ t('craftinghosting.props.title') }}</span>
          <span class="block text-xs text-base-400">{{ t('craftinghosting.props.text') }}</span>
        </span>
        <svg viewBox="0 0 24 24" class="ml-auto size-4 shrink-0 text-base-400 transition-transform motion-reduce:transition-none" :class="{ 'rotate-180': expanded }" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
      </button>
      <div class="relative w-full sm:w-64">
        <svg viewBox="0 0 24 24" class="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-base-500" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="11" cy="11" r="7" /><path d="M20 20l-3.5-3.5" /></svg>
        <input v-model="query" type="search" class="field pl-8 text-sm" :placeholder="t('craftinghosting.props.search')" :aria-label="t('craftinghosting.props.search')" data-testid="hosting-properties-search" />
      </div>
    </div>

    <div v-if="expanded" id="ch-props-body" class="border-t border-base-800">
      <p v-if="!loaded && (store.propertiesPending || !store.server?.lastStartAt)" class="p-4 text-sm text-base-400">{{ t('craftinghosting.settings.afterFirstStart') }}</p>
      <div v-else-if="!loaded" class="space-y-2 p-4">
        <div v-for="i in 5" :key="i" class="skeleton h-10" />
      </div>
      <template v-else>
        <p v-if="!groups.length" class="p-4 text-sm text-base-500">{{ t('craftinghosting.props.noMatch', { query: query.trim() }) }}</p>
        <div v-for="g in groups" :key="g.id" class="border-b border-base-800/70 last:border-b-0">
          <h4 class="flex items-center gap-2 bg-base-900/60 px-4 py-2 text-xs font-semibold tracking-wide text-base-300 uppercase">
            <svg viewBox="0 0 24 24" class="size-3.5 text-base-500" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="groupIcon[g.id]" /></svg>
            {{ t(`craftinghosting.props.groups.${g.id}`) }}
          </h4>
          <ul class="divide-y divide-base-800/50">
            <li v-for="row in g.rows" :key="row.key" class="grid items-center gap-x-4 gap-y-1.5 px-4 py-2.5 sm:grid-cols-[minmax(0,1fr)_minmax(0,18rem)]" :data-testid="`hosting-prop-${row.key}`">
              <div class="min-w-0">
                <label :for="`ch-prop-${row.key}`" class="flex items-center gap-1.5 text-sm" :class="row.locked ? 'text-base-400' : 'text-base-100'">
                  <span v-if="row.changed" class="size-1.5 shrink-0 rounded-full bg-lamp-300" :title="t('craftinghosting.files.unsaved')" aria-hidden="true" />
                  <span class="truncate">{{ row.label }}</span>
                  <svg v-if="row.locked" viewBox="0 0 24 24" class="size-3.5 shrink-0 text-lamp-300" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" role="img" :aria-label="t('craftinghosting.props.locked')"><title>{{ t('craftinghosting.props.locked') }}</title><rect x="5" y="11" width="14" height="9" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></svg>
                </label>
                <p class="truncate font-mono text-[11px] text-base-500">{{ row.key }}</p>
                <p v-if="row.field.kind === 'readonly'" class="text-[11px] text-base-500">{{ t('craftinghosting.props.whitelist') }}</p>
                <p v-else-if="row.field.kind === 'motd'" class="text-[11px] text-base-500">{{ t('craftinghosting.settings.motdFooter', { footer: MOTD_FOOTER }) }}</p>
              </div>
              <div class="min-w-0" :title="row.locked ? t('craftinghosting.props.locked') : undefined">
                <div v-if="row.field.kind === 'bool' || row.field.kind === 'readonly'" class="flex sm:justify-end">
                  <ToggleSwitch
                    :id="`ch-prop-${row.key}`"
                    :model-value="form[row.key] === 'true'"
                    :label="row.label"
                    :disabled="row.locked || row.field.kind === 'readonly'"
                    @update:model-value="setBool(row.key, $event)"
                  />
                </div>
                <select
                  v-else-if="row.field.kind === 'select'"
                  :id="`ch-prop-${row.key}`"
                  class="field text-sm"
                  :value="form[row.key]"
                  :disabled="row.locked"
                  @change="setValue(row.key, $event)"
                >
                  <option v-for="o in options(row)" :key="o" :value="o">{{ optionLabel(row.key, o) }}</option>
                </select>
                <div v-else-if="row.field.kind === 'motd'" class="relative">
                  <input
                    :id="`ch-prop-${row.key}`"
                    class="field pr-14 text-sm"
                    :class="{ 'ring-1 ring-redstone-500': !row.valid }"
                    :value="form[row.key]"
                    :maxlength="MOTD_MAX"
                    :disabled="row.locked"
                    autocomplete="off"
                    @input="setValue(row.key, $event)"
                  />
                  <span class="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-[11px] text-base-500 tabular-nums">{{ [...(form[row.key] ?? '')].length }}/{{ MOTD_MAX }}</span>
                </div>
                <input
                  v-else
                  :id="`ch-prop-${row.key}`"
                  class="field text-sm"
                  :class="[{ 'ring-1 ring-redstone-500': !row.valid }, row.field.kind === 'text' && !isKnownProperty(row.key) ? 'font-mono' : '']"
                  :type="row.field.kind === 'number' ? 'number' : 'text'"
                  :min="row.field.kind === 'number' ? row.field.min : undefined"
                  :max="row.field.kind === 'number' ? row.field.max : undefined"
                  :value="form[row.key]"
                  :disabled="row.locked"
                  maxlength="500"
                  autocomplete="off"
                  spellcheck="false"
                  @input="setValue(row.key, $event)"
                />
              </div>
            </li>
          </ul>
        </div>

        <div class="sticky bottom-0 flex flex-wrap items-center gap-2 rounded-b-xl border-t border-base-800 bg-base-900/95 px-4 py-3 backdrop-blur">
          <p class="mr-auto text-xs" :class="invalid ? 'text-redstone-300' : 'text-base-400'" aria-live="polite">
            {{ invalid ? t('craftinghosting.props.invalid') : changes ? t('craftinghosting.props.changes', { n: changes }) : t('craftinghosting.settings.nextStart') }}
          </p>
          <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="!changes" @click="discard">{{ t('craftinghosting.settings.discard') }}</button>
          <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="!changes || invalid || !!store.busy" data-testid="hosting-properties-save" @click="save">
            {{ t('common.actions.save') }}
          </button>
        </div>
      </template>
    </div>
  </section>
</template>
