<script setup lang="ts">
const { t, fill } = useTeamText()
const { account, load, api } = useAccount()
const route = useRoute()
const lp = useLocalePath()
const busy = ref(false)
const done = ref(false)
const error = ref('')
const token = computed(() => typeof route.query.token === 'string' ? route.query.token : '')
const loginHref = computed(() => `/login?return=${encodeURIComponent(route.fullPath)}`)

onMounted(async () => {
  await load()
  if (!token.value) error.value = t.value.myApps.confirmEmailInvalid
})

async function confirm() {
  if (!account.value || !token.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    await api(`/v1/me/applications/confirm`, { method: 'POST', body: { token: token.value } })
    done.value = true
  } catch (cause) {
    error.value = fill(t.value.myApps.confirmEmailFailed, { error: apiMessage(cause) })
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-xl px-4 pt-16 sm:px-6">
    <section class="card space-y-4 p-6">
      <h1 class="display text-3xl text-base-50">{{ t.myApps.confirmEmailTitle }}</h1>
      <p class="text-sm text-base-300">{{ done ? t.myApps.confirmEmailDone : t.myApps.confirmEmailLead }}</p>
      <p v-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
      <NuxtLink v-if="done" :to="lp('/applications')" class="btn btn-primary">{{ t.myApps.title }}</NuxtLink>
      <template v-else-if="account">
        <p class="text-xs text-base-400">{{ t.job.as.replace('{name}', account.name) }}</p>
        <button class="btn btn-primary" :disabled="busy || !token" @click="confirm">{{ busy ? t.myApps.confirmingEmail : t.myApps.confirmEmailButton }}</button>
      </template>
      <template v-else>
        <p class="text-sm text-base-400">{{ t.myApps.signIn }}</p>
        <a :href="loginHref" class="btn btn-primary">{{ t.account.signIn }}</a>
      </template>
    </section>
  </div>
</template>