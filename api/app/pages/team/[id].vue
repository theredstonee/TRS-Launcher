<script setup lang="ts">
// Stelle im Detail + Bewerbungsformular (§23.3). Texte kommen als Klartext (kein HTML/Markdown aus der Datenbank).
// Bewerben nur angemeldet (Microsoft); Discord-Name + Altersgruppe immer, dazu die Fragen der Stelle.
import { breadcrumbLd } from '#shared/seo'

const { t, lang, fill, date } = useTeamText()
const { m } = useLang()
const lp = useLocalePath()
const route = useRoute()
const siteUrl = useSiteUrl()
const id = computed(() => String(route.params.id))

const { data, error: fetchError } = await useFetch<{ job: JobView }>(() => `/v1/site/jobs/${id.value}`, { key: `site-job-${id.value}` })
if (fetchError.value || !data.value) throw createError({ statusCode: 404, statusMessage: 'Not found', fatal: true })
const job = computed(() => data.value!.job)
const texts = computed(() => inLang(job.value.texts, lang.value))

usePageSeo(() => ({
  path: `/team/${id.value}`,
  title: fill(t.value.seo.job.title, { title: texts.value?.title ?? id.value }).slice(0, 65),
  description: (texts.value?.summary || t.value.seo.team.description).slice(0, 160),
  noindex: job.value.status !== 'open',
  jsonLd: [breadcrumbLd(siteUrl, lang.value, [
    { name: m.value.nav.home, path: '/' },
    { name: m.value.nav.team, path: '/team' },
    { name: texts.value?.title ?? id.value, path: `/team/${id.value}` },
  ])],
}))

// --- Bewerben (nur im Browser) ----------------------------------------------------------------------------
const { account, loaded, load, api } = useAccount()
const eligibility = ref<Eligibility | null>(null)
const form = reactive<{ discord: string, ageGroup: AgeGroup | '', answers: Record<string, AnswerValue>, website: string }>({ discord: '', ageGroup: '', answers: {}, website: '' })
const errors = ref<Record<string, string>>({})
const busy = ref(false)
const failure = ref('')
const sent = ref(false)

onMounted(async () => {
  const me = await load()
  if (me && job.value.status === 'open') {
    try {
      eligibility.value = (await api<{ eligibility: Eligibility }>(`/v1/team/jobs/${id.value}/eligibility`)).eligibility
    } catch {
      eligibility.value = null
    }
  }
  for (const f of job.value.form) if (f.type === 'multi') form.answers[f.id] = []
})
const loginHref = computed(() => `/auth/microsoft/login?return=${encodeURIComponent(route.fullPath)}`)
const blockedText = computed(() => {
  const e = eligibility.value
  if (!e || e.canApply || !e.reason) return ''
  return fill(t.value.job.blocked[e.reason] ?? '', { date: date(e.retryAt) })
})

const label = (f: FormField) => inLang(f.label, lang.value) ?? f.id
const help = (f: FormField) => inLang(f.help, lang.value)
const optLabel = (o: { id: string, label: Localized }) => inLang(o.label, lang.value) ?? o.id
const textLen = (v: unknown) => (typeof v === 'string' ? [...v.trim()].length : 0)
const maxOf = (f: FormField) => f.max ?? (f.type === 'short' ? 100 : f.type === 'long' ? 1000 : undefined)

function toggleMulti(f: FormField, id: string) {
  const cur = Array.isArray(form.answers[f.id]) ? (form.answers[f.id] as string[]) : []
  form.answers[f.id] = cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id]
}

/** Dieselben Regeln wie der Server (dort wird ohnehin geprüft). */
function validate(): boolean {
  const e: Record<string, string> = {}
  if (!discordOk(form.discord)) e.discord = t.value.job.discordInvalid
  if (!form.ageGroup) e.ageGroup = t.value.job.errors.required!
  for (const f of job.value.form) {
    const v = form.answers[f.id]
    const empty = v === undefined || v === '' || v === null || (Array.isArray(v) && !v.length)
    if (empty) {
      if (f.required) e[f.id] = t.value.job.errors.required!
      continue
    }
    if (f.type === 'short' || f.type === 'long') {
      const n = textLen(v)
      if (f.min !== undefined && n < f.min) e[f.id] = t.value.job.errors.too_short!
      else if (n > (maxOf(f) ?? Infinity)) e[f.id] = t.value.job.errors.too_long!
    } else if (f.type === 'number') {
      const n = Number(v)
      if (!Number.isInteger(n)) e[f.id] = t.value.job.errors.invalid!
      else if (f.min !== undefined && n < f.min) e[f.id] = t.value.job.errors.too_small!
      else if (f.max !== undefined && n > f.max) e[f.id] = t.value.job.errors.too_large!
    } else if (f.type === 'multi' && Array.isArray(v)) {
      if (f.min !== undefined && v.length < f.min) e[f.id] = t.value.job.errors.too_few!
      else if (f.max !== undefined && v.length > f.max) e[f.id] = t.value.job.errors.too_many!
    }
  }
  errors.value = e
  return Object.keys(e).length === 0
}

async function submit() {
  failure.value = ''
  if (!validate()) return
  busy.value = true
  const answers: Record<string, AnswerValue> = {}
  for (const f of job.value.form) {
    const v = form.answers[f.id]
    if (v === undefined || v === '' || (Array.isArray(v) && !v.length)) continue
    answers[f.id] = f.type === 'number' ? Number(v) : typeof v === 'string' ? v.trim() : v
  }
  try {
    await api(`/v1/team/jobs/${id.value}/applications`, {
      method: 'POST',
      body: { discord: form.discord.trim(), ageGroup: form.ageGroup, answers, lang: lang.value, ...(form.website ? { website: form.website } : {}) },
    })
    sent.value = true
  } catch (err) {
    const data = (err as { data?: { error?: { code?: string, retryAt?: string | null, fields?: { id: string, error: string }[] } } }).data?.error
    const blocked: Record<string, string> = { application_open: 'open_application', job_closed: 'closed', already_member: 'member', cooldown: 'cooldown', too_many_open: 'too_many_open' }
    const key = data?.code ? blocked[data.code] : undefined
    if (data?.code === 'invalid_answers' && data.fields) {
      const e: Record<string, string> = {}
      for (const f of data.fields) e[f.id] = f.id === 'discord' ? t.value.job.discordInvalid : t.value.job.errors[f.error] ?? t.value.job.errors.invalid!
      errors.value = e
    } else if (key) {
      failure.value = fill(t.value.job.blocked[key]!, { date: date(data?.retryAt ?? null) })
    } else {
      failure.value = fill(t.value.job.failed, { error: apiMessage(err) })
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pt-10 sm:px-6">
    <NuxtLink :to="lp('/team')" class="inline-flex items-center gap-1.5 text-sm text-base-400 hover:text-base-50"><SiteIcon name="back" class="size-4" />{{ t.job.back }}</NuxtLink>

    <div class="mt-6 grid gap-10 lg:grid-cols-[minmax(0,1fr)_26rem]">
      <article class="min-w-0">
        <div class="flex flex-wrap items-center gap-3">
          <h1 class="display text-5xl leading-tight text-base-50">{{ texts?.title }}</h1>
          <RoleBadge v-if="job.role" :role="job.role" />
        </div>
        <p v-if="texts?.summary" class="mt-3 text-lg text-base-300">{{ texts.summary }}</p>
        <p v-if="job.status !== 'open'" class="mt-5 rounded-md border border-lamp-900 bg-lamp-900/20 px-3 py-2 text-sm text-lamp-300">{{ t.job.closed }}</p>

        <section v-if="texts?.description" class="mt-8">
          <h2 class="section-title">{{ t.job.about }}</h2>
          <p class="mt-3 leading-relaxed whitespace-pre-line text-base-300">{{ texts.description }}</p>
        </section>
        <section v-if="texts?.tasks.length" class="mt-8">
          <h2 class="section-title">{{ t.job.tasks }}</h2>
          <ul class="mt-3 space-y-2">
            <li v-for="x in texts.tasks" :key="x" class="flex gap-3 text-base-200"><span class="dust mt-2.5" aria-hidden="true" />{{ x }}</li>
          </ul>
        </section>
        <section v-if="texts?.requirements.length" class="mt-8">
          <h2 class="section-title">{{ t.job.requirements }}</h2>
          <ul class="mt-3 space-y-2">
            <li v-for="x in texts.requirements" :key="x" class="flex gap-3 text-base-200"><SiteIcon name="check" class="mt-0.5 size-4 shrink-0 text-ok" />{{ x }}</li>
          </ul>
        </section>
      </article>

      <aside id="apply" class="lg:sticky lg:top-24 lg:self-start">
        <div class="card apply-card p-6">
          <h2 class="heading text-2xl text-base-50">{{ t.job.applyTitle }}</h2>

          <p v-if="job.status !== 'open'" class="mt-3 text-sm text-base-400">{{ t.job.closed }}</p>
          <div v-else-if="!loaded" class="skeleton mt-4 h-24 rounded-lg" />
          <template v-else-if="!account">
            <p class="mt-2 text-sm text-base-400">{{ t.job.signInHint }}</p>
            <a :href="loginHref" class="ms-btn mt-5"><MsLogo class="size-5" />{{ t.job.signInToApply }}</a>
          </template>
          <div v-else-if="sent" class="mt-4">
            <p class="flex gap-2 text-sm text-ok"><SiteIcon name="check" class="mt-0.5 size-4 shrink-0" />{{ t.job.sent }}</p>
            <NuxtLink :to="lp('/applications')" class="btn btn-primary mt-4 w-full">{{ t.job.toMine }}</NuxtLink>
          </div>
          <div v-else-if="blockedText" class="mt-4">
            <p class="text-sm text-lamp-300">{{ blockedText }}</p>
            <NuxtLink :to="lp('/applications')" class="btn btn-ghost mt-4 w-full">{{ t.job.toMine }}</NuxtLink>
          </div>
          <form v-else class="mt-4 space-y-5" novalidate @submit.prevent="submit">
            <p class="flex items-center gap-2 text-sm text-base-300">
              <PlayerHead :uuid="account.uuid" :name="account.name" :skin="account.skin" :size="24" />{{ fill(t.job.as, { name: account.name }) }}
            </p>
            <!-- Honigtopf gegen Bots: für Menschen unsichtbar. -->
            <div class="hp" aria-hidden="true">
              <label for="job-website">Website</label>
              <input id="job-website" v-model="form.website" tabindex="-1" autocomplete="off" />
            </div>

            <fieldset class="space-y-4">
              <legend class="text-xs font-semibold tracking-wide text-base-400 uppercase">{{ t.job.standard }}</legend>
              <div>
                <label class="label" for="f-discord">{{ t.job.discord }} <span class="text-redstone-300">*</span></label>
                <input id="f-discord" v-model="form.discord" class="field" maxlength="40" autocomplete="off" spellcheck="false" :aria-invalid="!!errors.discord" aria-describedby="f-discord-help" />
                <p id="f-discord-help" class="mt-1 text-xs" :class="errors.discord ? 'text-redstone-300' : 'text-base-400'">{{ errors.discord || t.job.discordHelp }}</p>
              </div>
              <div>
                <p class="label">{{ t.job.ageGroup }} <span class="text-redstone-300">*</span></p>
                <div class="grid grid-cols-2 gap-1.5" role="radiogroup" :aria-label="t.job.ageGroup">
                  <label v-for="g in AGE_GROUPS" :key="g" class="choice" :data-on="form.ageGroup === g">
                    <input v-model="form.ageGroup" type="radio" name="age" :value="g" class="sr-only" />{{ t.job.ages[g] }}
                  </label>
                </div>
                <p class="mt-1 text-xs" :class="errors.ageGroup ? 'text-redstone-300' : 'text-base-400'">{{ errors.ageGroup || t.job.ageHint }}</p>
                <p v-if="form.ageGroup === 'under14' || form.ageGroup === '14-15'" class="mt-1 text-xs text-lamp-300">{{ t.job.under16 }}</p>
              </div>
            </fieldset>

            <fieldset v-if="job.form.length" class="space-y-4">
              <legend class="text-xs font-semibold tracking-wide text-base-400 uppercase">{{ t.job.questions }}</legend>
              <div v-for="f in job.form" :key="f.id">
                <p v-if="f.type === 'single' || f.type === 'multi' || f.type === 'yesno'" class="label">{{ label(f) }} <span v-if="f.required" class="text-redstone-300">*</span><span v-else class="text-base-500"> ({{ t.job.optional }})</span></p>
                <label v-else class="label" :for="`f-${f.id}`">{{ label(f) }} <span v-if="f.required" class="text-redstone-300">*</span><span v-else class="text-base-500"> ({{ t.job.optional }})</span></label>

                <input v-if="f.type === 'short'" :id="`f-${f.id}`" v-model="form.answers[f.id] as string" class="field" :maxlength="(maxOf(f) ?? 200) + 20" :aria-invalid="!!errors[f.id]" />
                <textarea v-else-if="f.type === 'long'" :id="`f-${f.id}`" v-model="form.answers[f.id] as string" class="field min-h-28" :maxlength="(maxOf(f) ?? 4000) + 50" :aria-invalid="!!errors[f.id]" />
                <input v-else-if="f.type === 'number'" :id="`f-${f.id}`" v-model="form.answers[f.id] as string" type="number" inputmode="numeric" step="1" :min="f.min" :max="f.max" class="field" :aria-invalid="!!errors[f.id]" />
                <div v-else-if="f.type === 'yesno'" class="grid grid-cols-2 gap-1.5" role="radiogroup">
                  <label class="choice" :data-on="form.answers[f.id] === true"><input v-model="form.answers[f.id]" type="radio" :name="f.id" :value="true" class="sr-only" />{{ t.job.yes }}</label>
                  <label class="choice" :data-on="form.answers[f.id] === false"><input v-model="form.answers[f.id]" type="radio" :name="f.id" :value="false" class="sr-only" />{{ t.job.no }}</label>
                </div>
                <div v-else-if="f.type === 'single'" class="space-y-1.5" role="radiogroup">
                  <label v-for="o in f.options" :key="o.id" class="choice choice-list" :data-on="form.answers[f.id] === o.id"><input v-model="form.answers[f.id]" type="radio" :name="f.id" :value="o.id" class="sr-only" />{{ optLabel(o) }}</label>
                </div>
                <div v-else-if="f.type === 'multi'" class="space-y-1.5">
                  <label v-for="o in f.options" :key="o.id" class="choice choice-list" :data-on="Array.isArray(form.answers[f.id]) && (form.answers[f.id] as string[]).includes(o.id)">
                    <input type="checkbox" class="sr-only" :checked="Array.isArray(form.answers[f.id]) && (form.answers[f.id] as string[]).includes(o.id)" @change="toggleMulti(f, o.id)" />{{ optLabel(o) }}
                  </label>
                </div>

                <p class="mt-1 flex gap-2 text-xs" :class="errors[f.id] ? 'text-redstone-300' : 'text-base-400'">
                  <span class="flex-1">{{ errors[f.id] || help(f) }}</span>
                  <span v-if="(f.type === 'short' || f.type === 'long') && maxOf(f)" class="tabular-nums">{{ fill(t.job.chars, { n: textLen(form.answers[f.id]), max: maxOf(f)! }) }}</span>
                </p>
              </div>
            </fieldset>

            <p class="text-xs text-base-400">{{ t.job.privacy }} <NuxtLink :to="`${lp('/privacy')}#${t.job.anchor}`" class="text-redstone-300 hover:underline">{{ t.job.privacyLink }}</NuxtLink></p>
            <p v-if="failure" role="alert" class="text-sm text-redstone-300">{{ failure }}</p>
            <button type="submit" class="btn btn-primary h-11 w-full" :disabled="busy">{{ busy ? t.job.sending : t.job.submit }}</button>
          </form>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.apply-card {
  border-top: 3px solid var(--color-redstone-500);
}
.dust {
  width: 0.45rem;
  height: 0.45rem;
  flex-shrink: 0;
  background: var(--color-redstone-400);
  box-shadow: 0 0 8px var(--color-redstone-500);
}
.choice {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-950);
  border-radius: 0.375rem;
  padding: 0.5rem 0.75rem;
  font-size: 0.875rem;
  color: var(--color-base-200);
  cursor: pointer;
}
.choice-list {
  justify-content: flex-start;
}
.choice-list::before {
  content: '';
  width: 0.85rem;
  height: 0.85rem;
  flex-shrink: 0;
  border: 1.5px solid var(--color-base-500);
  border-radius: 0.2rem;
}
.choice-list[data-on='true']::before {
  border-color: var(--color-redstone-400);
  background: var(--color-redstone-500);
  box-shadow: inset 0 0 0 2px var(--color-base-950);
}
.choice:hover {
  border-color: var(--color-base-600);
}
.choice[data-on='true'] {
  border-color: var(--color-redstone-500);
  background: color-mix(in srgb, var(--color-redstone-600) 18%, transparent);
  color: var(--color-base-50);
}
.choice:focus-within {
  outline: 2px solid var(--color-redstone-400);
  outline-offset: 2px;
}
.hp {
  position: absolute;
  left: -10000px;
  width: 1px;
  height: 1px;
  overflow: hidden;
}
.ms-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  height: 3rem;
  width: 100%;
  border: 1px solid #8c8c8c;
  background: #2f2f2f;
  color: #fff;
  font: 600 15px 'Segoe UI', system-ui, sans-serif;
  border-radius: 0.25rem;
}
.ms-btn:hover {
  background: #3a3a3a;
}
</style>
