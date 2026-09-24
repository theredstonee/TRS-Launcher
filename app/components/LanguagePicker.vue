<script setup lang="ts">
import type { LanguageInfo, Locale } from '~/utils/i18n'

// Sprachliste: Suche, Flagge, Name in der Sprache
// selbst + englischer Name, Übersetzungsstand und Beta-Hinweis. Wird in den
// Einstellungen und im ersten Schritt der Einrichtung benutzt.
const model = defineModel<Locale>({ required: true })
const props = withDefaults(defineProps<{ search?: boolean; compact?: boolean }>(), { search: true, compact: false })

const query = ref('')
const completion = ref<Partial<Record<Locale, number>>>({})

onMounted(async () => {
  try {
    completion.value = await loadCompletion()
  } catch {
    // Ohne Prozentangaben funktioniert die Auswahl trotzdem.
  }
})

/** Name der Sprache in der gerade eingestellten Sprache (für die Suche, z. B. „Spanisch“). */
function localName(code: Locale): string {
  try {
    return new Intl.DisplayNames([currentLocale.value], { type: 'language' }).of(code) ?? ''
  } catch {
    return ''
  }
}

function matches(l: LanguageInfo, q: string): boolean {
  if (!q) return true
  return [l.native, l.english, l.code, localName(l.code)].some((s) => s.toLowerCase().includes(q))
}

const q = computed(() => query.value.trim().toLowerCase())
const standard = computed(() => languageList.filter((l) => !l.beta && matches(l, q.value)))
const beta = computed(() => languageList.filter((l) => l.beta && matches(l, q.value)))

function percent(l: LanguageInfo): number | null {
  return l.code === 'en' ? 100 : (completion.value[l.code] ?? null)
}

function choose(code: Locale) {
  model.value = code
}

// Pfeiltasten wie bei einer Radiogruppe.
function onKey(e: KeyboardEvent) {
  if (!['ArrowDown', 'ArrowUp'].includes(e.key)) return
  const list = [...standard.value, ...beta.value]
  const index = list.findIndex((l) => l.code === model.value)
  const next = list[(index + (e.key === 'ArrowDown' ? 1 : list.length - 1)) % list.length]
  if (!next) return
  e.preventDefault()
  choose(next.code)
  nextTick(() => (e.currentTarget as HTMLElement | null)?.querySelector<HTMLElement>(`[data-lang="${next.code}"]`)?.focus())
}
</script>

<template>
  <div class="space-y-4">
    <input
      v-if="props.search"
      v-model="query"
      type="search"
      class="field"
      maxlength="40"
      :placeholder="t('language.searchPlaceholder')"
      :aria-label="t('language.searchLabel')"
    />

    <div role="radiogroup" :aria-label="t('language.title')" class="space-y-4" @keydown="onKey">
      <section v-if="standard.length">
        <h4 class="mb-2 text-xs font-semibold tracking-wide text-base-400 uppercase">{{ t('language.standard') }}</h4>
        <ul class="space-y-1.5">
          <li v-for="l in standard" :key="l.code">
            <button
              type="button"
              role="radio"
              :data-lang="l.code"
              :aria-checked="model === l.code"
              :tabindex="model === l.code ? 0 : -1"
              class="lang-row"
              :class="[model === l.code ? 'lang-row-active' : '', props.compact ? 'py-2' : 'py-2.5']"
              @click="choose(l.code)"
            >
              <span class="lang-radio" :class="{ 'lang-radio-on': model === l.code }"><span v-if="model === l.code" /></span>
              <img :src="`/flags/${l.flag}.svg`" alt="" class="lang-flag" draggable="false" />
              <span class="min-w-0 flex-1 truncate">
                <span class="text-sm font-semibold text-base-50">{{ l.native }}</span>
                <span v-if="l.native !== l.english" class="ml-2 text-xs text-base-400">{{ l.english }}</span>
              </span>
              <span v-if="percent(l) !== null && percent(l)! < 100" class="shrink-0 text-xs text-base-400 tabular-nums">
                {{ t('language.complete', { percent: percent(l)! }) }}
              </span>
            </button>
          </li>
        </ul>
      </section>

      <section v-if="beta.length">
        <h4 class="mb-1 flex items-center gap-2 text-xs font-semibold tracking-wide text-base-400 uppercase">
          {{ t('language.betaGroup') }}
          <span class="badge bg-lamp-900 text-lamp-300 normal-case">{{ t('language.beta') }}</span>
        </h4>
        <p class="mb-2 text-xs leading-relaxed text-base-400">{{ t('language.betaHint') }}</p>
        <ul class="space-y-1.5">
          <li v-for="l in beta" :key="l.code">
            <button
              type="button"
              role="radio"
              :data-lang="l.code"
              :aria-checked="model === l.code"
              :tabindex="model === l.code ? 0 : -1"
              class="lang-row"
              :class="[model === l.code ? 'lang-row-active' : '', props.compact ? 'py-2' : 'py-2.5']"
              @click="choose(l.code)"
            >
              <span class="lang-radio" :class="{ 'lang-radio-on': model === l.code }"><span v-if="model === l.code" /></span>
              <img :src="`/flags/${l.flag}.svg`" alt="" class="lang-flag" draggable="false" />
              <span class="min-w-0 flex-1 truncate">
                <span class="text-sm font-semibold text-base-50">{{ l.native }}</span>
                <span class="ml-2 text-xs text-base-400">{{ l.english }}</span>
              </span>
              <span v-if="percent(l) !== null" class="shrink-0 text-xs text-base-400 tabular-nums">
                {{ t('language.complete', { percent: percent(l)! }) }}
              </span>
            </button>
          </li>
        </ul>
      </section>

      <p v-if="!standard.length && !beta.length" class="py-6 text-center text-sm text-base-400">
        {{ t('language.noResults', { query: query.trim() }) }}
      </p>
    </div>
  </div>
</template>

<style scoped>
.lang-row {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 0.75rem;
  border-radius: 0.6rem;
  border: 1px solid var(--color-base-800);
  padding-left: 0.75rem;
  padding-right: 0.75rem;
  text-align: left;
  transition: border-color 0.15s, background-color 0.15s;
}
.lang-row:hover {
  border-color: var(--color-base-600);
}
.lang-row:focus-visible {
  outline: 2px solid var(--color-redstone-500);
  outline-offset: 2px;
}
.lang-row-active {
  border-color: var(--color-redstone-500);
  background: color-mix(in srgb, var(--color-redstone-900) 30%, transparent);
}
.lang-radio {
  display: grid;
  place-items: center;
  width: 1rem;
  height: 1rem;
  flex-shrink: 0;
  border-radius: 9999px;
  border: 2px solid var(--color-base-600);
}
.lang-radio-on {
  border-color: var(--color-redstone-500);
}
.lang-radio-on > span {
  width: 0.5rem;
  height: 0.5rem;
  border-radius: 9999px;
  background: var(--color-redstone-500);
}
.lang-flag {
  width: 1.5rem;
  height: 1.125rem;
  flex-shrink: 0;
  border-radius: 0.2rem;
  object-fit: cover;
  box-shadow: 0 0 0 1px rgb(0 0 0 / 0.25);
}
</style>
