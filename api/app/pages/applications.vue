<script setup lang="ts">
// „Meine Bewerbungen“ (§24.3): Status und Antwort des Teams, offene Bewerbungen zurückziehen. Nur im Browser.
const { t, lang, fill, date } = useTeamText()
const { m } = useLang()
const lp = useLocalePath()
const { account, loaded, load, api } = useAccount()

usePageSeo(() => ({ path: '/applications', title: t.value.seo.applications.title, description: t.value.myApps.lead, noindex: true }))

const items = ref<MyApplication[]>([])
const loading = ref(true)
const error = ref('')
const withdrawing = ref<MyApplication | null>(null)
const busy = ref(false)

async function refresh() {
  error.value = ''
  try {
    items.value = (await api<{ applications: MyApplication[] }>('/v1/me/applications')).applications
  } catch (e) {
    error.value = fill(t.value.job.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
onMounted(async () => {
  if (await load()) await refresh()
  else loading.value = false
})

async function withdraw() {
  const x = withdrawing.value
  if (!x) return
  busy.value = true
  try {
    const r = await api<{ application: MyApplication }>(`/v1/me/applications/${x.id}/withdraw`, { method: 'POST' })
    items.value = items.value.map((i) => (i.id === r.application.id ? r.application : i))
    withdrawing.value = null
  } catch (e) {
    error.value = fill(t.value.job.failed, { error: apiMessage(e) })
  } finally {
    busy.value = false
  }
}
const steps: ApplicationStatus[] = ['new', 'review', 'interview']
const stepIndex = (s: ApplicationStatus) => steps.indexOf(s)
</script>

<template>
  <div class="mx-auto max-w-3xl px-4 pt-14 sm:px-6">
    <header>
      <h1 class="display text-5xl leading-tight text-base-50">{{ t.myApps.title }}</h1>
      <p class="mt-3 text-lg text-base-400">{{ t.myApps.lead }}</p>
    </header>

    <div v-if="!loaded || loading" class="mt-8 space-y-3"><div v-for="i in 2" :key="i" class="skeleton h-28 rounded-xl" /></div>
    <div v-else-if="!account" class="card mt-8 p-6">
      <p class="text-base-300">{{ t.myApps.signIn }}</p>
      <NuxtLink :to="`${lp('/login')}${lp('/login').includes('?') ? '&' : '?'}return=/applications`" class="btn btn-primary mt-4"><SiteIcon name="user" class="size-4" />{{ t.account.signIn }}</NuxtLink>
    </div>
    <template v-else>
      <p v-if="error" role="alert" class="mt-6 text-sm text-redstone-300">{{ error }}</p>
      <div v-if="!items.length" class="card mt-8 p-6">
        <p class="text-base-300">{{ t.myApps.empty }}</p>
        <NuxtLink :to="lp('/team')" class="btn btn-primary mt-4"><SiteIcon name="briefcase" class="size-4" />{{ t.myApps.browse }}</NuxtLink>
      </div>
      <ul v-else class="mt-8 space-y-4">
        <li v-for="x in items" :key="x.id">
          <article class="card app-card p-5" :data-status="x.status">
            <div class="flex flex-wrap items-center gap-3">
              <NuxtLink :to="lp(`/team/${x.job.id}`)" class="heading flex-1 text-xl text-base-50 hover:underline">{{ inLang(x.job.title, lang) ?? x.job.id }}</NuxtLink>
              <span class="tone status" :class="applicationTone(x.status)">{{ t.myApps.status[x.status] }}</span>
            </div>
            <p class="mt-1 text-xs text-base-400">{{ fill(t.myApps.sentAt, { date: date(x.createdAt) }) }} · {{ fill(t.myApps.updatedAt, { date: date(x.updatedAt) }) }}</p>
            <!-- Fortschritt: eingegangen → in Prüfung → Gespräch -->
            <ol v-if="stepIndex(x.status) >= 0" class="steps mt-4" :aria-label="t.myApps.status[x.status]">
              <li v-for="(s, i) in steps" :key="s" :data-done="i <= stepIndex(x.status)">{{ t.myApps.status[s] }}</li>
            </ol>
            <div v-if="x.response" class="mt-4 rounded-md border border-base-800 bg-base-950 px-4 py-3">
              <p class="text-xs font-semibold tracking-wide text-base-400 uppercase">{{ t.myApps.response }}</p>
              <p class="mt-1 text-sm whitespace-pre-line text-base-100">{{ x.response }}</p>
            </div>
            <button v-if="x.canWithdraw" type="button" class="btn btn-ghost mt-4 text-sm" @click="withdrawing = x"><SiteIcon name="close" class="size-4" />{{ t.myApps.withdraw }}</button>
          </article>
        </li>
      </ul>
    </template>

    <div v-if="withdrawing" class="confirm-backdrop" role="dialog" aria-modal="true" :aria-label="t.myApps.withdraw" @click.self="withdrawing = null">
      <div class="card w-full max-w-md p-6">
        <p class="text-base-100">{{ fill(t.myApps.confirmWithdraw, { job: inLang(withdrawing.job.title, lang) ?? withdrawing.job.id }) }}</p>
        <div class="mt-5 flex justify-end gap-2">
          <button type="button" class="btn btn-ghost" @click="withdrawing = null">{{ m.common.close }}</button>
          <button type="button" class="btn btn-danger" :disabled="busy" @click="withdraw">{{ t.myApps.withdraw }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.app-card[data-status='accepted'] {
  border-left: 3px solid var(--color-ok);
}
.app-card[data-status='rejected'] {
  border-left: 3px solid var(--color-redstone-600);
}
.app-card[data-status='new'],
.app-card[data-status='review'],
.app-card[data-status='interview'] {
  border-left: 3px solid var(--color-lamp-400);
}
.tone {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  padding: 0.15rem 0.6rem;
  font-size: 0.75rem;
  font-weight: 600;
}
.tone-warn { background: color-mix(in srgb, var(--color-lamp-400) 18%, transparent); color: var(--color-lamp-300); }
.tone-info { background: color-mix(in srgb, #3b82f6 18%, transparent); color: #93c5fd; }
.tone-ok { background: color-mix(in srgb, var(--color-ok) 18%, transparent); color: var(--color-ok); }
.tone-danger { background: color-mix(in srgb, var(--color-redstone-500) 18%, transparent); color: var(--color-redstone-300); }
.tone-muted { background: var(--color-base-800); color: var(--color-base-300); }
.steps {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0.35rem;
  font-size: 0.75rem;
}
.steps li {
  border-top: 3px solid var(--color-base-800);
  padding-top: 0.35rem;
  color: var(--color-base-500);
}
.steps li[data-done='true'] {
  border-color: var(--color-redstone-500);
  color: var(--color-base-100);
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
