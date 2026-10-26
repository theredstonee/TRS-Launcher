<script setup lang="ts">
// Abmelden (§40.2): GET zeigt nur diese Seite mit Knopf (Link-Vorschauen melden niemanden ab). Mail-Programme
// nutzen dieselbe Adresse per POST (RFC 8058 One-Click, server/routes/newsletter/unsubscribe.post.ts).
const { n, fill } = useNewsletterText()
const lp = useLocalePath()
const route = useRoute()
useHead({ title: () => `${n.value.unsubscribe.title} · TRS Launcher`, meta: [{ name: 'robots', content: 'noindex, nofollow' }, { name: 'referrer', content: 'no-referrer' }] })

const token = computed(() => (typeof route.query.token === 'string' ? route.query.token : ''))
const valid = computed(() => /^\d{1,12}\.[0-9a-f]{40}$/.test(token.value))
const state = ref<'idle' | 'working' | 'done' | 'invalid'>('idle')
const email = ref('')

async function unsubscribe() {
  state.value = 'working'
  try {
    const r = await apiFetch<{ email: string }>('/v1/newsletter/unsubscribe', { method: 'POST', body: { token: token.value } })
    email.value = r.email
    state.value = 'done'
  } catch {
    state.value = 'invalid'
  }
}
</script>

<template>
  <div class="mx-auto grid max-w-xl px-4 pt-20 sm:px-6">
    <section class="card p-8 text-center">
      <h1 class="display text-4xl text-base-50">{{ n.unsubscribe.title }}</h1>
      <template v-if="!valid || state === 'invalid'">
        <p class="mt-4 text-base-300" role="alert">{{ n.unsubscribe.invalid }}</p>
      </template>
      <template v-else-if="state === 'done'">
        <p class="mt-4 text-base-200" role="status">{{ fill(n.unsubscribe.done, { email }) }}</p>
        <NuxtLink :to="lp('/newsletter')" class="btn btn-ghost mt-6">{{ n.unsubscribe.resubscribe }}</NuxtLink>
      </template>
      <template v-else>
        <p class="mt-4 text-base-300">{{ n.unsubscribe.text }}</p>
        <button type="button" class="btn btn-primary mt-6" :disabled="state === 'working'" @click="unsubscribe">
          {{ state === 'working' ? n.unsubscribe.working : n.unsubscribe.button }}
        </button>
      </template>
    </section>
  </div>
</template>
