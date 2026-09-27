<script setup lang="ts">
// Anmeldung mit Microsoft (§23.1): Knopf führt zu /auth/microsoft/login (Weiterleitung, kein Skript von Microsoft).
// Fehler kommen als ?error=<code> zurück; ?return=/pfad = Rücksprung nach der Anmeldung (prüft der Server).
const { t, fill } = useTeamText()
const lp = useLocalePath()
const route = useRoute()
const { account, loaded, load } = useAccount()

usePageSeo(() => ({ path: '/login', title: t.value.seo.login.title, description: t.value.login.lead.slice(0, 160), noindex: true }))

const returnTo = computed(() => {
  const r = route.query.return
  return typeof r === 'string' && /^\/(?![/\\])/.test(r) && r.length <= 200 ? r : '/applications'
})
const errorCode = computed(() => (typeof route.query.error === 'string' ? route.query.error : ''))
const errorText = computed(() => (errorCode.value ? t.value.login.errors[errorCode.value] ?? t.value.login.errors.ms_failed : ''))
const msEnabled = ref(true)
onMounted(async () => {
  void load()
  try {
    msEnabled.value = (await $fetch<{ microsoft: boolean }>('/v1/web/login')).microsoft
  } catch {
    // Knopf bleibt sichtbar; der Server leitet notfalls mit ms_disabled zurück.
  }
})
const href = computed(() => `/auth/microsoft/login?return=${encodeURIComponent(returnTo.value)}`)
</script>

<template>
  <div class="mx-auto max-w-xl px-4 pt-16 sm:px-6">
    <div class="card login-card p-8">
      <p class="text-xs tracking-[0.18em] text-redstone-300 uppercase">TRS Launcher</p>
      <h1 class="display mt-2 text-4xl text-base-50">{{ t.login.title }}</h1>
      <p class="mt-3 text-base-300">{{ t.login.lead }}</p>

      <p v-if="errorText" role="alert" class="mt-5 flex gap-2 rounded-md border border-redstone-800 bg-redstone-900/30 px-3 py-2 text-sm text-redstone-200">
        <SiteIcon name="warn" class="mt-0.5 size-4 shrink-0" />{{ errorText }}
      </p>

      <div v-if="loaded && account" class="mt-6 flex flex-wrap items-center gap-3">
        <PlayerHead :uuid="account.uuid" :name="account.name" :skin="account.skin" :size="40" />
        <p class="flex-1 text-sm text-base-200">{{ fill(t.login.signedIn, { name: account.name }) }}</p>
        <NuxtLink :to="returnTo" class="btn btn-primary">{{ t.login.continue }}<SiteIcon name="arrow" class="size-4" /></NuxtLink>
      </div>
      <template v-else>
        <a v-if="msEnabled" :href="href" class="ms-btn mt-6"><MsLogo class="size-5" />{{ t.login.button }}</a>
        <p v-else class="mt-6 text-sm text-lamp-300">{{ t.login.disabled }}</p>
      </template>

      <p class="mt-6 text-xs text-base-400">
        <SiteIcon name="shield" class="mr-1 inline size-3.5 align-[-2px]" />
        <NuxtLink :to="`${lp('/privacy')}#${t.login.anchor}`" class="hover:text-base-100 hover:underline">{{ t.login.privacy }}</NuxtLink>
      </p>
    </div>
  </div>
</template>

<style scoped>
.login-card {
  background-image: var(--deepslate);
  background-size: 48px 48px;
}
/* Microsoft-Knopf nach den Marken-Vorgaben: dunkel, Logo links, Schrift Segoe/Systemschrift. */
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
  transition: background-color 0.15s;
}
.ms-btn:hover {
  background: #3a3a3a;
}
</style>
