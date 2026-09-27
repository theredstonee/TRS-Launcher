<script setup lang="ts">
// Eine Schaltung im Detail (§25): große Vorschau (drehen, Schichten), Erklärung, Materialliste, Versionen,
// Download als Strukturdatei (.nbt) bzw. JSON, Anleitung zum Laden und – angemeldet – Melden.
import { breadcrumbLd, localizedUrl } from '#shared/seo'
import { circuitBlock } from '#shared/circuits'
import { circuitText } from '~/utils/circuit-i18n'
import type { SiteCircuit } from '~/utils/circuits/types'

const { c, lang, fill, date } = useCircuitText()
const { m } = useLang()
const lp = useLocalePath()
const route = useRoute()
const siteUrl = useSiteUrl()
const id = computed(() => String(route.params.id))

const { data, error: fetchError } = await useFetch<SiteCircuit>(() => `/v1/site/circuits/${id.value}`, { key: `site-circuit-${id.value}` })
if (fetchError.value || !data.value) throw createError({ statusCode: 404, statusMessage: 'Not found', fatal: true })
const s = computed(() => data.value!)
const circuit = computed(() => s.value.circuit)
const name = computed(() => circuitText(circuit.value.texts, lang.value, 'name') || circuit.value.id)
const desc = computed(() => circuitText(circuit.value.texts, lang.value, 'desc'))
const note = computed(() => (circuit.value.server === 'note' ? circuitText(circuit.value.texts, lang.value, 'note') : ''))
const versions = computed(() =>
  s.value.maxVersion ? fill(c.value.common.range, { from: s.value.minVersion, to: s.value.maxVersion }) : fill(c.value.common.since, { v: s.value.minVersion }))
const layer = ref(-1)
const swatch = (key: string) => circuitBlock(key)?.color ?? '#8b8ba2'

usePageSeo(() => ({
  path: `/circuits/${id.value}`,
  title: fill(c.value.seo.detail.title, { name: name.value }).slice(0, 65),
  description: (desc.value || c.value.seo.detail.fallback).replace(/\s+/g, ' ').slice(0, 160),
  jsonLd: [
    breadcrumbLd(siteUrl, lang.value, [
      { name: m.value.nav.home, path: '/' },
      { name: c.value.list.title, path: '/circuits' },
      { name: name.value, path: `/circuits/${id.value}` },
    ]),
    {
      '@type': 'CreativeWork',
      name: name.value,
      description: desc.value.slice(0, 500),
      url: localizedUrl(siteUrl, `/circuits/${id.value}`, lang.value),
      genre: c.value.categories[circuit.value.category],
      inLanguage: lang.value,
      dateModified: circuit.value.updatedAt,
      ...(s.value.publishedAt ? { datePublished: s.value.publishedAt } : {}),
      author: circuit.value.author ? { '@type': 'Person', name: circuit.value.author.name } : { '@type': 'Organization', name: 'TRS Launcher' },
      isAccessibleForFree: true,
      about: { '@type': 'VideoGame', name: 'Minecraft' },
    },
  ],
}))

// --- Melden (nur angemeldet, im Browser) ------------------------------------------------------------
const { account, loaded, load, api, loginUrl } = useAccount()
onMounted(() => void load())
const reporting = ref(false)
const reason = ref('inappropriate')
const reportBusy = ref(false)
const reportMsg = ref('')
const reportErr = ref('')
async function sendReport() {
  reportBusy.value = true
  reportErr.value = ''
  try {
    await api('/v1/reports', { method: 'POST', body: { kind: 'circuit', circuitId: circuit.value.id, reason: reason.value } })
    reportMsg.value = c.value.detail.reportDone
    reporting.value = false
  } catch (e) {
    reportErr.value = apiCode(e) === 'already_reported' ? c.value.detail.already : fill(c.value.submit.errors.generic!, { error: apiMessage(e) })
  } finally {
    reportBusy.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pt-10 sm:px-6">
    <NuxtLink :to="lp('/circuits')" class="inline-flex items-center gap-1.5 text-sm text-base-400 hover:text-base-50"><SiteIcon name="back" class="size-4" />{{ c.detail.back }}</NuxtLink>

    <div class="mt-5 grid gap-8 lg:grid-cols-[minmax(0,1fr)_22rem]">
      <section class="min-w-0">
        <div class="flex flex-wrap items-center gap-2">
          <NuxtLink :to="lp(`/circuits?category=${circuit.category}`)" class="badge bg-base-800 text-base-200">{{ c.categories[circuit.category] }}</NuxtLink>
          <span class="diff" :data-d="circuit.difficulty">{{ c.difficulty[circuit.difficulty] }}</span>
        </div>
        <h1 class="display mt-3 text-4xl leading-tight text-base-50 sm:text-5xl">{{ name }}</h1>
        <p v-if="circuit.author" class="mt-2 flex items-center gap-2 text-sm text-base-400">
          <PlayerHead :uuid="circuit.author.uuid" :name="circuit.author.name" :skin="circuit.author.skin ?? null" :size="20" />{{ fill(c.common.by, { name: circuit.author.name }) }}
        </p>

        <div class="card stage mt-6 overflow-hidden p-3">
          <ClientOnly>
            <CircuitIso v-model:layer="layer" :circuit="circuit" :height="420" controls markers :label="fill(c.common.preview, { name })" />
            <template #fallback><div class="h-[460px]" /></template>
          </ClientOnly>
        </div>

        <p v-if="desc" class="mt-6 text-base leading-relaxed whitespace-pre-line text-base-200">{{ desc }}</p>
        <div v-if="note" class="mt-5 flex gap-3 rounded-lg border border-lamp-400/40 bg-lamp-900/40 px-4 py-3 text-sm text-lamp-300">
          <SiteIcon name="warn" class="mt-0.5 size-4 shrink-0" />
          <p><strong class="font-semibold">{{ c.detail.serverNote }}:</strong> {{ note }}</p>
        </div>

        <section class="mt-8">
          <h2 class="heading text-xl text-base-50">{{ c.detail.howTitle }}</h2>
          <ol class="mt-3 list-decimal space-y-1.5 pl-5 text-sm text-base-300">
            <li v-for="(step, i) in c.detail.howSteps" :key="i">{{ step }}</li>
          </ol>
          <p class="mt-4 text-sm text-base-400">{{ c.list.clientText }}</p>
        </section>
      </section>

      <aside class="space-y-5 lg:sticky lg:top-24 lg:self-start">
        <div class="card p-5">
          <div class="flex flex-wrap gap-2">
            <a :href="`/v1/circuits/${circuit.id}/export?format=nbt`" class="btn btn-primary flex-1" download><SiteIcon name="download" class="size-4" />{{ c.detail.downloadNbt }}</a>
            <a :href="`/v1/circuits/${circuit.id}/export?format=json`" class="btn btn-ghost" download>{{ c.detail.downloadJson }}</a>
          </div>
          <dl class="facts mt-5">
            <dt>{{ c.detail.versions }}</dt><dd>{{ versions }}</dd>
            <dt>{{ c.detail.size }}</dt><dd class="tabular-nums">{{ fill(c.common.size, s.size) }}</dd>
            <dt>{{ c.detail.category }}</dt><dd>{{ c.categories[circuit.category] }}</dd>
            <dt>{{ c.detail.difficulty }}</dt><dd>{{ c.difficulty[circuit.difficulty] }}</dd>
            <dt>{{ c.detail.author }}</dt><dd>{{ circuit.author?.name ?? c.common.team }}</dd>
            <dt>{{ c.detail.updated }}</dt><dd>{{ date(circuit.updatedAt) }}</dd>
          </dl>
        </div>

        <div class="card p-5">
          <h2 class="section-title">{{ c.detail.materials }}</h2>
          <ul class="mt-3 space-y-1.5 text-sm">
            <li v-for="x in s.materials" :key="x.key" class="flex items-center gap-2">
              <span class="swatch" :style="{ background: swatch(x.key) }" aria-hidden="true" />
              <span class="flex-1 text-base-200">{{ c.blocks[x.key] ?? x.key }}</span>
              <span class="text-base-400 tabular-nums">{{ x.count }}×</span>
            </li>
          </ul>
          <p class="mt-3 text-xs text-base-400 tabular-nums">{{ fill(c.common.blocks, { n: s.blockCount }) }}</p>
        </div>

        <div class="px-1">
          <p v-if="reportMsg" class="text-sm text-ok" role="status">{{ reportMsg }}</p>
          <template v-else-if="loaded">
            <button v-if="account" type="button" class="inline-flex items-center gap-1.5 text-xs text-base-400 hover:text-base-50" @click="reporting = true"><SiteIcon name="flag" class="size-3.5" />{{ c.detail.report }}</button>
            <a v-else :href="loginUrl()" class="inline-flex items-center gap-1.5 text-xs text-base-400 hover:text-base-50"><SiteIcon name="flag" class="size-3.5" />{{ c.detail.reportSignIn }}</a>
          </template>
        </div>
      </aside>
    </div>

    <div v-if="reporting" class="confirm-backdrop" role="dialog" aria-modal="true" :aria-label="c.detail.reportTitle" @click.self="reporting = false">
      <form class="card w-full max-w-md p-6" @submit.prevent="sendReport">
        <h2 class="heading text-xl text-base-50">{{ c.detail.reportTitle }}</h2>
        <p class="mt-1 text-sm text-base-400">{{ c.detail.reportLead }}</p>
        <label class="label mt-4" for="report-reason">{{ c.detail.reportReason }}</label>
        <select id="report-reason" v-model="reason" class="field">
          <option v-for="(label, key) in c.detail.reasons" :key="key" :value="key">{{ label }}</option>
        </select>
        <p v-if="reportErr" role="alert" class="mt-3 text-sm text-redstone-300">{{ reportErr }}</p>
        <div class="mt-5 flex justify-end gap-2">
          <button type="button" class="btn btn-ghost" @click="reporting = false">{{ m.common.close }}</button>
          <button type="submit" class="btn btn-danger" :disabled="reportBusy"><SiteIcon name="flag" class="size-4" />{{ c.detail.reportSend }}</button>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.stage {
  background-color: var(--color-base-950);
  background-image: var(--deepslate);
  background-size: 48px 48px;
}
.facts {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 0.45rem 1rem;
  font-size: 0.875rem;
}
.facts dt {
  color: var(--color-base-400);
}
.facts dd {
  color: var(--color-base-100);
  text-align: right;
}
.swatch {
  width: 0.85rem;
  height: 0.85rem;
  border-radius: 3px;
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.35);
}
.diff {
  border-radius: 999px;
  padding: 0.1rem 0.6rem;
  font-size: 0.75rem;
  font-weight: 600;
  background: color-mix(in srgb, var(--color-ok) 16%, transparent);
  color: var(--color-ok);
}
.diff[data-d='2'] {
  background: color-mix(in srgb, var(--color-lamp-400) 16%, transparent);
  color: var(--color-lamp-300);
}
.diff[data-d='3'] {
  background: color-mix(in srgb, var(--color-redstone-500) 18%, transparent);
  color: var(--color-redstone-300);
}
.confirm-backdrop {
  position: fixed;
  inset: 0;
  z-index: 50;
  display: grid;
  place-items: center;
  padding: 1rem;
  background: color-mix(in srgb, black 60%, transparent);
}
</style>
