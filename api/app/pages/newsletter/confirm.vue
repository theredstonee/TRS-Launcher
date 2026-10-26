<script setup lang="ts">
// Bestätigungs-Link aus der Mail (§40.2): Der Token wird erst im Browser per POST eingelöst – Link-Vorschauen von
// Mail-Programmen (GET ohne JavaScript) bestätigen so nichts aus Versehen.
const { n, fill } = useNewsletterText()
const lp = useLocalePath()
const route = useRoute()
useHead({ title: () => `${n.value.confirm.title} · TRS Launcher`, meta: [{ name: 'robots', content: 'noindex, nofollow' }, { name: 'referrer', content: 'no-referrer' }] })

const state = ref<'working' | 'ok' | 'team' | 'invalid' | 'expired'>('working')
const email = ref('')

onMounted(async () => {
  const token = typeof route.query.token === 'string' ? route.query.token : ''
  if (!/^[ct][A-Za-z0-9_-]{43}$/.test(token)) {
    state.value = 'invalid'
    return
  }
  try {
    const r = await apiFetch<{ kind: 'newsletter' | 'team', email: string }>('/v1/newsletter/confirm', { method: 'POST', body: { token } })
    email.value = r.email
    state.value = r.kind === 'team' ? 'team' : 'ok'
  } catch (e) {
    state.value = apiCode(e) === 'token_expired' ? 'expired' : 'invalid'
  }
})
</script>

<template>
  <div class="mx-auto grid max-w-xl px-4 pt-20 sm:px-6">
    <section class="card p-8 text-center">
      <template v-if="state === 'working'">
        <div class="skeleton mx-auto h-10 w-48 rounded-lg" />
        <p class="mt-4 text-base-400" role="status">{{ n.confirm.working }}</p>
      </template>
      <template v-else-if="state === 'ok' || state === 'team'">
        <span class="nl-badge"><SiteIcon name="check" class="size-7" /></span>
        <h1 class="display mt-4 text-4xl text-base-50">{{ state === 'team' ? n.confirm.teamTitle : n.confirm.okTitle }}</h1>
        <p class="mt-3 text-base-300">{{ fill(state === 'team' ? n.confirm.teamText : n.confirm.okText, { email }) }}</p>
        <NuxtLink :to="lp('/')" class="btn btn-primary mt-6">{{ n.confirm.home }}</NuxtLink>
      </template>
      <template v-else>
        <span class="nl-badge nl-bad"><SiteIcon name="warn" class="size-7" /></span>
        <h1 class="display mt-4 text-3xl text-base-50">{{ n.confirm.title }}</h1>
        <p class="mt-3 text-base-300" role="alert">{{ state === 'expired' ? n.confirm.expired : n.confirm.invalid }}</p>
        <NuxtLink :to="lp('/newsletter')" class="btn btn-primary mt-6">{{ n.confirm.again }}</NuxtLink>
      </template>
    </section>
  </div>
</template>

<style scoped>
.nl-badge {
  display: inline-grid;
  place-items: center;
  width: 3.5rem;
  height: 3.5rem;
  border-radius: 999px;
  color: var(--color-base-50);
  background: var(--color-redstone-600);
  box-shadow: 0 0 24px color-mix(in srgb, var(--color-redstone-500) 60%, transparent);
}
.nl-bad {
  background: var(--color-base-800);
  box-shadow: none;
  color: var(--color-lamp-300, #ffb84d);
}
</style>
