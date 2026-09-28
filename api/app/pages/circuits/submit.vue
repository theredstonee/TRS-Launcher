<script setup lang="ts">
// Schaltung einreichen (§25.5): Datei hochladen → Server wandelt um (Vorschau + Hinweise) → Name, Kategorie,
// Beschreibung, Sprache → Einreichung. Nur angemeldet (Microsoft), nur im Browser.
import { CIRCUIT_CATEGORIES, CIRCUIT_LANGS, MAX_DESC, MAX_NAME } from '#shared/circuits'
import type { ImportResult } from '~/utils/circuits/types'
import { circuitErrorDetail, uploadCircuitFile } from '~/utils/circuits/types'

const { c, lang, fill } = useCircuitText()
const lp = useLocalePath()
const { account, loaded, load, api, loginUrl } = useAccount()

usePageSeo(() => ({ path: '/circuits/submit', title: c.value.seo.submit.title, description: c.value.submit.lead.slice(0, 160), noindex: true }))

const limits = ref<{ today: number, maxPerDay: number } | null>(null)
onMounted(async () => {
  if (await load()) {
    try {
      limits.value = (await api<{ limits: { today: number, maxPerDay: number } }>('/v1/me/circuit-submissions')).limits
    } catch {
      limits.value = null
    }
  }
})

// --- Datei ------------------------------------------------------------------------------------------------
const fileInput = shallowRef<HTMLInputElement | null>(null)
const result = ref<ImportResult | null>(null)
const converting = ref(false)
const error = ref('')
const dragging = ref(false)

function errorText(e: unknown): string {
  const code = apiCode(e)
  const t = c.value.submit.errors
  if (code === 'invalid_circuit') return fill(t.invalid_circuit!, { detail: circuitErrorDetail(e) })
  return t[code] ?? fill(t.generic!, { error: apiMessage(e) })
}

async function takeFile(file: File | undefined) {
  if (!file) return
  error.value = ''
  if (file.size > 2 * 1024 * 1024) {
    error.value = c.value.submit.errors.payload_too_large!
    return
  }
  converting.value = true
  try {
    result.value = await uploadCircuitFile('/v1/circuits/convert', file, account.value?.csrf)
    const n = result.value.circuit.texts?.en?.name ?? ''
    if (!form.name) form.name = n === 'Imported circuit' ? '' : n
    if (result.value.format === 'json') form.category = result.value.circuit.category
  } catch (e) {
    result.value = null
    error.value = errorText(e)
  } finally {
    converting.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}
function onDrop(e: DragEvent) {
  dragging.value = false
  void takeFile(e.dataTransfer?.files?.[0])
}

// --- Angaben + Einreichen -----------------------------------------------------------------------------------
const form = reactive({ name: '', category: 'basics' as string, description: '', lang: lang.value as string, consent: false })
const busy = ref(false)
const done = ref<string | null>(null)
const valid = computed(() => !!result.value && form.name.trim().length > 0 && form.description.trim().length > 0 && form.consent)

async function send() {
  if (!result.value || !valid.value) return
  busy.value = true
  error.value = ''
  try {
    await api('/v1/circuits/submissions', {
      method: 'POST',
      body: {
        circuit: result.value.circuit,
        name: form.name.trim().slice(0, MAX_NAME),
        category: form.category,
        description: form.description.trim().slice(0, MAX_DESC),
        lang: form.lang,
        format: result.value.format,
      },
    })
    done.value = form.name.trim()
    if (limits.value) limits.value.today++
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}
function again() {
  done.value = null
  result.value = null
  Object.assign(form, { name: '', description: '', consent: false })
}
const warnText = (w: ImportResult['warnings'][number]) =>
  w.action === 'solid'
    ? fill(c.value.submit.warnSolid, { block: w.block, n: w.count })
    : w.action === 'converted'
      ? fill(c.value.submit.warnConverted, { block: w.block, n: w.count, as: c.value.blocks[w.as ?? ''] ?? w.as ?? '' })
      : fill(c.value.submit.warnSkipped, { block: w.block, n: w.count })
const limitReached = computed(() => !!limits.value && limits.value.today >= limits.value.maxPerDay)
</script>

<template>
  <div class="mx-auto max-w-5xl px-4 pt-14 sm:px-6">
    <NuxtLink :to="lp('/circuits')" class="inline-flex items-center gap-1.5 text-sm text-base-400 hover:text-base-50"><SiteIcon name="back" class="size-4" />{{ c.detail.back }}</NuxtLink>
    <header class="mt-4 max-w-3xl">
      <h1 class="display text-5xl leading-tight text-base-50">{{ c.submit.title }}</h1>
      <p class="mt-3 text-lg text-base-400">{{ c.submit.lead }}</p>
    </header>

    <div v-if="!loaded" class="skeleton mt-8 h-40 rounded-xl" />
    <div v-else-if="!account" class="card mt-8 p-6">
      <p class="text-base-300">{{ c.submit.signIn }}</p>
      <a :href="loginUrl()" class="btn btn-primary mt-4"><SiteIcon name="user" class="size-4" />{{ c.common.signIn }}</a>
    </div>

    <div v-else-if="done" class="card mt-8 p-6">
      <p class="flex items-center gap-2 text-lg text-base-50"><SiteIcon name="check" class="size-5 text-ok" />{{ fill(c.submit.done, { name: done }) }}</p>
      <div class="mt-5 flex flex-wrap gap-2">
        <NuxtLink :to="lp('/circuits/mine')" class="btn btn-primary"><SiteIcon name="inbox" class="size-4" />{{ c.submit.toMine }}</NuxtLink>
        <button type="button" class="btn btn-ghost" :disabled="limitReached" @click="again">{{ c.submit.another }}</button>
      </div>
    </div>

    <template v-else>
      <p v-if="limits" class="mt-6 text-sm text-base-400 tabular-nums">{{ fill(c.submit.limit, { today: limits.today, max: limits.maxPerDay }) }}</p>
      <p v-if="error" role="alert" class="mt-4 rounded-md border border-redstone-600/50 bg-redstone-900/40 px-4 py-3 text-sm text-redstone-300">{{ error }}</p>

      <div class="mt-6 grid gap-6 lg:grid-cols-2">
        <section class="card p-6">
          <h2 class="heading text-xl text-base-50">{{ c.submit.fileTitle }}</h2>
          <p class="mt-1 text-sm text-base-400">{{ c.submit.fileLead }}</p>
          <label
            class="drop mt-4"
            :data-drag="dragging"
            @dragover.prevent="dragging = true"
            @dragleave="dragging = false"
            @drop.prevent="onDrop"
          >
            <input ref="fileInput" type="file" class="sr-only" accept=".litematic,.schem,.nbt,.json,application/json,application/octet-stream" @change="takeFile(($event.target as HTMLInputElement).files?.[0])" />
            <SiteIcon name="import" class="size-7 text-base-400" />
            <span class="btn btn-ghost pointer-events-none">{{ result ? c.submit.otherFile : c.submit.choose }}</span>
            <span class="text-xs text-base-400">{{ converting ? c.submit.converting : `${c.submit.drop} · ${c.submit.formats}` }}</span>
          </label>

          <div v-if="result" class="mt-5">
            <div class="stage overflow-hidden rounded-lg border border-base-800">
              <CircuitIso :circuit="result.circuit" :height="260" controls />
            </div>
            <p class="mt-2 text-xs text-base-400 tabular-nums">
              {{ result.format }} · {{ fill(c.common.size, result.size) }} · {{ fill(c.common.blocks, { n: result.blockCount }) }}
            </p>
            <div v-if="result.warnings.length" class="mt-3 rounded-md border border-lamp-400/40 bg-lamp-900/30 px-3 py-2">
              <p class="text-xs font-semibold text-lamp-300">{{ c.submit.warnings }}</p>
              <ul class="mt-1 space-y-0.5 text-xs text-base-200">
                <li v-for="w in result.warnings" :key="`${w.block}-${w.action}`">{{ warnText(w) }}</li>
              </ul>
            </div>
          </div>
        </section>

        <form class="card p-6" @submit.prevent="send">
          <h2 class="heading text-xl text-base-50">{{ c.submit.detailsTitle }}</h2>
          <fieldset :disabled="!result || busy" class="mt-3 space-y-4">
            <div>
              <label class="label" for="c-name">{{ c.submit.name }}</label>
              <input id="c-name" v-model="form.name" class="field" :maxlength="MAX_NAME" :placeholder="c.submit.namePlaceholder" required />
            </div>
            <div class="grid gap-4 sm:grid-cols-2">
              <div>
                <label class="label" for="c-cat">{{ c.submit.category }}</label>
                <select id="c-cat" v-model="form.category" class="field">
                  <option v-for="cat in CIRCUIT_CATEGORIES" :key="cat" :value="cat">{{ c.categories[cat] }}</option>
                </select>
              </div>
              <div>
                <label class="label" for="c-lang">{{ c.submit.language }}</label>
                <select id="c-lang" v-model="form.lang" class="field">
                  <option v-for="l in CIRCUIT_LANGS" :key="l" :value="l">{{ l === 'en' ? 'English' : l === 'de' ? 'Deutsch' : 'Español' }}</option>
                </select>
              </div>
            </div>
            <div>
              <label class="label" for="c-desc">{{ c.submit.description }}</label>
              <textarea id="c-desc" v-model="form.description" class="field min-h-36" :maxlength="MAX_DESC" :placeholder="c.submit.descriptionPlaceholder" required />
              <p class="mt-1 text-right text-xs text-base-400 tabular-nums">{{ [...form.description].length }} / {{ MAX_DESC }}</p>
            </div>
            <label class="flex items-start gap-2 text-sm text-base-300">
              <input v-model="form.consent" type="checkbox" class="mt-1" />
              <span>{{ c.submit.consent }} <NuxtLink :to="`${lp('/privacy')}#${c.submit.privacyAnchor}`" class="underline hover:text-base-50">{{ c.submit.privacy }}</NuxtLink></span>
            </label>
            <button type="submit" class="btn btn-primary w-full" :disabled="!valid || busy || limitReached">
              <SiteIcon name="check" class="size-4" />{{ busy ? c.submit.sending : c.submit.send }}
            </button>
          </fieldset>
        </form>
      </div>
    </template>
  </div>
</template>

<style scoped>
.drop {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.6rem;
  border: 2px dashed var(--color-base-700);
  border-radius: 0.75rem;
  padding: 1.5rem 1rem;
  cursor: pointer;
  text-align: center;
  transition: border-color 0.15s, background-color 0.15s;
}
.drop:hover,
.drop[data-drag='true'] {
  border-color: var(--color-redstone-500);
  background: color-mix(in srgb, var(--color-redstone-500) 6%, transparent);
}
.stage {
  background-color: var(--color-base-950);
  background-image: var(--deepslate);
  background-size: 48px 48px;
}
</style>
