<script setup lang="ts">
// „Meine Einreichungen“ (§25.5): Status, Antwort des Teams, Link zur angenommenen Schaltung. Nur im Browser.
import type { MySubmission } from '~/utils/circuits/types'

const { c, fill, date } = useCircuitText()
const lp = useLocalePath()
const { account, loaded, load, api, loginUrl } = useAccount()

usePageSeo(() => ({ path: '/circuits/mine', title: c.value.seo.mine.title, description: c.value.mine.lead.slice(0, 160), noindex: true }))

const items = ref<MySubmission[]>([])
const limits = ref<{ today: number, maxPerDay: number } | null>(null)
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  if (await load()) {
    try {
      const r = await api<{ submissions: MySubmission[], limits: { today: number, maxPerDay: number } }>('/v1/me/circuit-submissions')
      items.value = r.submissions
      limits.value = r.limits
    } catch (e) {
      error.value = fill(c.value.submit.errors.generic!, { error: apiMessage(e) })
    }
  }
  loading.value = false
})
</script>

<template>
  <div class="mx-auto max-w-3xl px-4 pt-14 sm:px-6">
    <NuxtLink :to="lp('/circuits')" class="inline-flex items-center gap-1.5 text-sm text-base-400 hover:text-base-50"><SiteIcon name="back" class="size-4" />{{ c.detail.back }}</NuxtLink>
    <header class="mt-4">
      <h1 class="display text-5xl leading-tight text-base-50">{{ c.mine.title }}</h1>
      <p class="mt-3 text-lg text-base-400">{{ c.mine.lead }}</p>
    </header>

    <div v-if="!loaded || loading" class="mt-8 space-y-3"><div v-for="i in 2" :key="i" class="skeleton h-24 rounded-xl" /></div>
    <div v-else-if="!account" class="card mt-8 p-6">
      <p class="text-base-300">{{ c.submit.signIn }}</p>
      <a :href="loginUrl()" class="btn btn-primary mt-4"><SiteIcon name="user" class="size-4" />{{ c.common.signIn }}</a>
    </div>
    <template v-else>
      <p v-if="error" role="alert" class="mt-6 text-sm text-redstone-300">{{ error }}</p>
      <div class="mt-6 flex flex-wrap items-center justify-between gap-3">
        <p v-if="limits" class="text-sm text-base-400 tabular-nums">{{ fill(c.submit.limit, { today: limits.today, max: limits.maxPerDay }) }}</p>
        <NuxtLink :to="lp('/circuits/submit')" class="btn btn-primary"><SiteIcon name="plus" class="size-4" />{{ c.mine.submit }}</NuxtLink>
      </div>
      <div v-if="!items.length" class="card mt-6 p-6"><p class="text-base-300">{{ c.mine.empty }}</p></div>
      <ul v-else class="mt-6 space-y-4">
        <li v-for="x in items" :key="x.id">
          <article class="card sub-card p-5" :data-status="x.status">
            <div class="flex flex-wrap items-center gap-3">
              <h2 class="heading flex-1 text-xl text-base-50">{{ x.name }}</h2>
              <span class="tone" :class="x.status === 'approved' ? 'tone-ok' : x.status === 'rejected' ? 'tone-danger' : 'tone-warn'">{{ c.mine.status[x.status] }}</span>
            </div>
            <p class="mt-1 text-xs text-base-400">
              {{ c.categories[x.category] }} · {{ fill(c.mine.sent, { date: date(x.createdAt) }) }}<template v-if="x.decidedAt"> · {{ fill(c.mine.decided, { date: date(x.decidedAt) }) }}</template>
            </p>
            <div v-if="x.reason" class="mt-4 rounded-md border border-base-800 bg-base-950 px-4 py-3">
              <p class="text-xs font-semibold tracking-wide text-base-400 uppercase">{{ c.mine.reason }}</p>
              <p class="mt-1 text-sm whitespace-pre-line text-base-100">{{ x.reason }}</p>
            </div>
            <NuxtLink v-if="x.circuitId" :to="lp(`/circuits/${x.circuitId}`)" class="btn btn-ghost mt-4 text-sm"><SiteIcon name="arrow" class="size-4" />{{ c.mine.view }}</NuxtLink>
          </article>
        </li>
      </ul>
    </template>
  </div>
</template>

<style scoped>
.sub-card[data-status='approved'] {
  border-left: 3px solid var(--color-ok);
}
.sub-card[data-status='rejected'] {
  border-left: 3px solid var(--color-redstone-600);
}
.sub-card[data-status='pending'] {
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
.tone-ok { background: color-mix(in srgb, var(--color-ok) 18%, transparent); color: var(--color-ok); }
.tone-danger { background: color-mix(in srgb, var(--color-redstone-500) 18%, transparent); color: var(--color-redstone-300); }
</style>
