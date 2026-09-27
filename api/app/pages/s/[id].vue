<script setup lang="ts">
// Öffentliche Seite eines geteilten Screenshots (§23): nur Bild, Datum und Ablauf – kein Name, keine UUID.
// Nie indexieren; Vorschau für Discord & Co. über Open Graph (Bild absolut, mit Maßen).
import { setResponseStatus } from 'h3'

interface PublicShare {
  id: string
  url: string
  imageUrl: string
  mime: string
  width: number
  height: number
  createdAt: string
  expiresAt: string
}

const REASONS = ['inappropriate', 'insult_hate', 'harassment', 'scam_phishing', 'spam', 'other'] as const
type Reason = (typeof REASONS)[number]

const route = useRoute()
const id = computed(() => String(route.params.id ?? ''))
const valid = /^[A-Za-z0-9_-]{22}$/.test(id.value)
const { m, fill, date } = useLang()
const lp = useLocalePath()

const { data } = valid
  ? await useFetch<{ share: PublicShare }>(() => `/v1/shares/${id.value}`, { key: `share-${id.value}` })
  : { data: ref<{ share: PublicShare } | null>(null) }
const share = computed(() => data.value?.share ?? null)

if (!share.value && import.meta.server) {
  const event = useRequestEvent()
  if (event) setResponseStatus(event, 404)
}

usePageSeo(() => ({
  path: `/s/${id.value}`,
  title: `${m.value.share.title} · TRS Launcher`,
  description: share.value ? fill(m.value.share.shared, { date: date(share.value.createdAt) }) : m.value.share.goneTitle,
  image: share.value
    ? { url: share.value.imageUrl, width: share.value.width, height: share.value.height, alt: m.value.share.alt }
    : null,
  noindex: true,
}))
useHead({ meta: [{ name: 'referrer', content: 'no-referrer' }] })

// --- Melden (ohne Konto) --------------------------------------------------------------------------
const reporting = ref(false)
const reason = ref<Reason>('inappropriate')
const state = ref<'idle' | 'sending' | 'done' | 'failed'>('idle')

async function sendReport() {
  if (!share.value || state.value === 'sending') return
  state.value = 'sending'
  try {
    await $fetch(`/v1/shares/${share.value.id}/report`, { method: 'POST', body: { reason: reason.value } })
    state.value = 'done'
    reporting.value = false
  } catch {
    state.value = 'failed'
  }
}
</script>

<template>
  <div class="mx-auto max-w-5xl px-4 pt-10 pb-6 sm:px-6">
    <template v-if="share">
      <figure class="shot card overflow-hidden p-0">
        <a :href="share.imageUrl" target="_blank" rel="noopener noreferrer" class="block">
          <img
            :src="share.imageUrl"
            :width="share.width"
            :height="share.height"
            :alt="m.share.alt"
            class="h-auto w-full"
            decoding="async"
          />
        </a>
      </figure>

      <div class="mt-5 flex flex-wrap items-center gap-x-5 gap-y-2 text-sm text-base-300">
        <span class="inline-flex items-center gap-1.5"><SiteIcon name="clock" class="size-4 text-base-400" />{{ fill(m.share.shared, { date: date(share.createdAt) }) }}</span>
        <span>{{ fill(m.share.expires, { date: date(share.expiresAt) }) }}</span>
        <span class="flex-1" />
        <a :href="share.imageUrl" target="_blank" rel="noopener noreferrer" class="btn btn-ghost text-sm">
          <SiteIcon name="external" class="size-4" />{{ m.share.open }}
        </a>
        <button v-if="state !== 'done'" type="button" class="btn btn-ghost text-sm" :aria-expanded="reporting" @click="reporting = !reporting">
          <SiteIcon name="flag" class="size-4" />{{ m.share.report }}
        </button>
      </div>

      <p v-if="state === 'done'" role="status" class="mt-4 text-sm text-emerald-300">{{ m.share.thanks }}</p>

      <form v-if="reporting" class="card mt-4 max-w-xl p-5" @submit.prevent="sendReport">
        <h2 class="text-lg font-semibold text-base-50">{{ m.share.reportTitle }}</h2>
        <p class="mt-1 text-sm text-base-300">{{ m.share.reportLead }}</p>
        <label class="mt-4 block text-xs text-base-400" for="share-reason">{{ m.share.reason }}</label>
        <select id="share-reason" v-model="reason" class="field mt-1 w-full">
          <option v-for="r in REASONS" :key="r" :value="r">{{ m.share.reasons[r] }}</option>
        </select>
        <p v-if="state === 'failed'" role="alert" class="mt-3 text-sm text-redstone-300">{{ m.share.failed }}</p>
        <div class="mt-4 flex gap-2">
          <button type="submit" class="btn btn-danger text-sm" :disabled="state === 'sending'">{{ m.share.send }}</button>
          <button type="button" class="btn btn-ghost text-sm" @click="reporting = false">{{ m.share.cancel }}</button>
        </div>
      </form>
    </template>

    <section v-else class="mx-auto max-w-xl py-20 text-center">
      <SiteIcon name="clock" class="mx-auto size-10 text-base-400" />
      <h1 class="display mt-4 text-3xl text-base-50">{{ m.share.goneTitle }}</h1>
      <p class="mt-3 text-base-300">{{ m.share.goneText }}</p>
    </section>

    <footer class="mt-12 flex flex-wrap items-center gap-3 border-t border-base-800 pt-6 text-sm text-base-400">
      <span class="w-full sm:w-auto sm:flex-1">{{ m.share.madeWith }}</span>
      <NuxtLink :to="lp('/privacy')" class="hover:text-base-100">{{ m.share.privacy }}</NuxtLink>
      <NuxtLink :to="lp('/download')" class="btn btn-primary text-sm"><SiteIcon name="download" class="size-4" />{{ m.share.getLauncher }}</NuxtLink>
    </footer>
  </div>
</template>

<style scoped>
.shot img {
  image-rendering: auto;
  max-height: 78vh;
  object-fit: contain;
  background: #000;
}
</style>
