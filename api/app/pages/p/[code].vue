<script setup lang="ts">
// Öffentliche Seite eines geteilten Modpacks (§27): Name, Inhalt, Ersteller und der Code zum Abtippen/Kopieren.
// Herunterladen geht nur im Launcher (mit TRS-Konto). Nie indexieren.
import { setResponseStatus } from 'h3'

interface PublicPack {
  id: string
  code: string
  url: string
  name: string
  summary: string | null
  packVersion: string
  revision: number
  mcVersion: string
  loader: { kind: 'vanilla' | 'forge' | 'neoforge' | 'fabric' | 'quilt', version: string | null }
  modrinthFiles: number
  ownJars: number
  otherFiles: number
  bytes: number
  owner: { uuid: string, name: string }
  createdAt: string
  updatedAt: string
  expiresAt: string | null
}

const LOADERS: Record<PublicPack['loader']['kind'], string> = {
  vanilla: 'Vanilla', forge: 'Forge', neoforge: 'NeoForge', fabric: 'Fabric', quilt: 'Quilt',
}
const REASONS = ['inappropriate', 'scam_phishing', 'spam', 'insult_hate', 'harassment', 'other'] as const

const route = useRoute()
const code = computed(() => String(route.params.code ?? '').slice(0, 32))
const valid = /^[A-Za-z0-9 -]{8,16}$/.test(code.value)
const { m, fill, date } = useLang()
const lp = useLocalePath()

const { data } = valid
  ? await useApiFetch<{ pack: PublicPack }>(() => `/v1/packs/code/${encodeURIComponent(code.value)}`, { key: `pack-${code.value}` })
  : { data: ref<{ pack: PublicPack } | null>(null) }
const pack = computed(() => data.value?.pack ?? null)

if (!pack.value && import.meta.server) {
  const event = useRequestEvent()
  if (event) setResponseStatus(event, 404)
}

const loaderLine = computed(() => {
  const p = pack.value
  if (!p) return ''
  const l = LOADERS[p.loader.kind]
  return `Minecraft ${p.mcVersion} · ${p.loader.version && p.loader.kind !== 'vanilla' ? `${l} ${p.loader.version}` : l}`
})

usePageSeo(() => ({
  path: `/p/${code.value}`,
  title: `${pack.value ? pack.value.name : m.value.pack.title} · TRS Launcher`,
  description: pack.value ? `${loaderLine.value} · ${fill(m.value.pack.mods, { n: pack.value.modrinthFiles })}` : m.value.pack.goneTitle,
  noindex: true,
}))
useHead({ meta: [{ name: 'referrer', content: 'no-referrer' }] })

// --- Code kopieren ---------------------------------------------------------------------------------
const copied = ref(false)
const codeEl = shallowRef<HTMLElement | null>(null)
async function copy() {
  if (!pack.value) return
  try {
    await navigator.clipboard.writeText(pack.value.code)
    copied.value = true
    setTimeout(() => (copied.value = false), 2000)
  } catch {
    // Zwischenablage gesperrt: Code markieren, damit Strg+C reicht.
    const el = codeEl.value
    const sel = window.getSelection()
    if (el && sel) {
      const range = document.createRange()
      range.selectNodeContents(el)
      sel.removeAllRanges()
      sel.addRange(range)
    }
  }
}

// --- Melden (nur angemeldet) ----------------------------------------------------------------------
const { account, load, api, loginUrl } = useAccount()
onMounted(() => void load())
const reporting = ref(false)
const reason = ref<(typeof REASONS)[number]>('inappropriate')
const reportBusy = ref(false)
const reportMsg = ref('')
const reportErr = ref('')
async function sendReport() {
  if (!pack.value || reportBusy.value) return
  reportBusy.value = true
  reportErr.value = ''
  try {
    await api('/v1/reports', { method: 'POST', body: { kind: 'pack', packId: pack.value.id, reason: reason.value } })
    reportMsg.value = m.value.pack.reportDone
    reporting.value = false
  } catch (e) {
    reportErr.value = apiCode(e) === 'already_reported' ? m.value.pack.already : fill(m.value.pack.failed, { error: apiMessage(e) })
  } finally {
    reportBusy.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-4xl px-4 pt-10 pb-6 sm:px-6">
    <template v-if="pack">
      <p class="text-xs font-medium tracking-wide text-base-400 uppercase">{{ m.pack.title }}</p>
      <h1 class="display mt-2 text-4xl leading-tight text-base-50 sm:text-5xl">{{ pack.name }}</h1>
      <p class="mt-2 flex items-center gap-2 text-sm text-base-300">
        <PlayerHead :uuid="pack.owner.uuid" :name="pack.owner.name" :size="20" />{{ fill(m.pack.by, { name: pack.owner.name }) }}
      </p>
      <p v-if="pack.summary" class="mt-4 max-w-2xl text-base-200">{{ pack.summary }}</p>

      <div class="mt-6 grid gap-5 md:grid-cols-[minmax(0,1fr)_18rem]">
        <section class="card p-5">
          <p class="text-sm text-base-200">{{ loaderLine }}</p>
          <ul class="mt-3 space-y-1.5 text-sm text-base-300">
            <li class="flex items-center gap-2"><SiteIcon name="blocks" class="size-4 text-base-400" />{{ fill(m.pack.mods, { n: pack.modrinthFiles }) }}</li>
            <li v-if="pack.otherFiles" class="flex items-center gap-2"><SiteIcon name="note" class="size-4 text-base-400" />{{ fill(m.pack.files, { n: pack.otherFiles }) }}</li>
            <li v-if="pack.ownJars" class="flex items-center gap-2 text-lamp-300"><SiteIcon name="warn" class="size-4" />{{ fill(m.pack.own, { n: pack.ownJars }) }}</li>
          </ul>
          <p v-if="pack.ownJars" class="mt-3 rounded-md border border-base-800 bg-base-950 px-3 py-2 text-xs text-base-300">{{ m.pack.ownWarn }}</p>
          <div class="mt-4 flex flex-wrap gap-x-5 gap-y-1 text-xs text-base-400">
            <span>{{ fill(m.pack.version, { v: pack.packVersion }) }}</span>
            <span>{{ fill(m.pack.updated, { date: date(pack.updatedAt) }) }}</span>
            <span>{{ pack.expiresAt ? fill(m.pack.expires, { date: date(pack.expiresAt) }) : m.pack.forever }}</span>
          </div>
        </section>

        <aside class="card flex flex-col gap-3 p-5">
          <p class="text-xs font-medium tracking-wide text-base-400 uppercase">{{ m.pack.codeLabel }}</p>
          <p ref="codeEl" class="code font-mono text-2xl text-base-50 select-all">{{ pack.code }}</p>
          <a :href="`trs-launcher://pack/${pack.code}`" class="btn btn-primary" data-testid="open-in-launcher">
            <SiteIcon name="external" class="size-4" />{{ m.pack.openInLauncher }}
          </a>
          <button type="button" class="btn btn-ghost" @click="copy">
            <SiteIcon :name="copied ? 'check' : 'copy'" class="size-4" />{{ copied ? m.pack.copied : m.pack.copy }}
          </button>
          <p class="text-xs text-base-400">{{ m.pack.openHint }}</p>
          <span class="sr-only" role="status">{{ copied ? m.pack.copied : '' }}</span>
        </aside>
      </div>

      <section class="mt-8">
        <h2 class="text-lg font-semibold text-base-50">{{ m.pack.howTitle }}</h2>
        <ol class="mt-3 list-decimal space-y-1.5 pl-5 text-sm text-base-300">
          <li v-for="(step, i) in m.pack.how" :key="i">{{ step }}</li>
        </ol>
      </section>

      <div class="mt-6 flex flex-wrap items-center gap-3 text-sm">
        <template v-if="account">
          <button v-if="!reportMsg" type="button" class="btn btn-ghost text-sm" :aria-expanded="reporting" @click="reporting = !reporting">
            <SiteIcon name="flag" class="size-4" />{{ m.pack.report }}
          </button>
        </template>
        <a v-else :href="loginUrl()" class="btn btn-ghost text-sm"><SiteIcon name="flag" class="size-4" />{{ m.pack.reportLogin }}</a>
        <p v-if="reportMsg" role="status" class="text-emerald-300">{{ reportMsg }}</p>
      </div>

      <form v-if="reporting" class="card mt-4 max-w-xl p-5" @submit.prevent="sendReport">
        <h2 class="text-lg font-semibold text-base-50">{{ m.pack.reportTitle }}</h2>
        <p class="mt-1 text-sm text-base-300">{{ m.pack.reportLead }}</p>
        <label class="mt-4 block text-xs text-base-400" for="pack-reason">{{ m.share.reason }}</label>
        <select id="pack-reason" v-model="reason" class="field mt-1 w-full">
          <option v-for="r in REASONS" :key="r" :value="r">{{ m.share.reasons[r] }}</option>
        </select>
        <p v-if="reportErr" role="alert" class="mt-3 text-sm text-redstone-300">{{ reportErr }}</p>
        <div class="mt-4 flex gap-2">
          <button type="submit" class="btn btn-danger text-sm" :disabled="reportBusy">{{ m.pack.send }}</button>
          <button type="button" class="btn btn-ghost text-sm" @click="reporting = false">{{ m.pack.cancel }}</button>
        </div>
      </form>
    </template>

    <section v-else class="mx-auto max-w-xl py-20 text-center">
      <SiteIcon name="clock" class="mx-auto size-10 text-base-400" />
      <h1 class="display mt-4 text-3xl text-base-50">{{ m.pack.goneTitle }}</h1>
      <p class="mt-3 text-base-300">{{ m.pack.goneText }}</p>
    </section>

    <footer class="mt-12 flex flex-wrap items-center gap-3 border-t border-base-800 pt-6 text-sm text-base-400">
      <span class="w-full sm:w-auto sm:flex-1">{{ m.share.madeWith }}</span>
      <NuxtLink :to="lp('/privacy')" class="hover:text-base-100">{{ m.share.privacy }}</NuxtLink>
      <NuxtLink :to="lp('/download')" class="btn btn-primary text-sm"><SiteIcon name="download" class="size-4" />{{ m.pack.getLauncher }}</NuxtLink>
    </footer>
  </div>
</template>

<style scoped>
.code {
  letter-spacing: 0.06em;
}
</style>
