<script setup lang="ts">
import { encode } from 'uqr'

// Pitch-Links (§38): geheime Links zur Pitch-Präsentation erzeugen, widerrufen, löschen; Vorschau für das Team.
// Den vollen Link (und den QR-Code) gibt es nur direkt nach dem Erzeugen – der Server speichert nur den Hash.
// Nur mit pitch.manage – geprüft wird serverseitig.
const { a, fill, when, num } = useAdminText()
const { api } = useAdmin()
const p = computed(() => a.value.pitch)

type PitchMode = 'loop' | 'manual'
type PitchLang = 'de' | 'en'
interface PitchLink {
  id: number
  label: string
  mode: PitchMode
  lang: PitchLang
  tokenHint: string
  status: 'active' | 'expired' | 'revoked'
  createdBy: string
  createdByName: string | null
  createdAt: string
  expiresAt: string | null
  revokedAt: string | null
  views: number
  lastViewAt: string | null
}

const MODES: PitchMode[] = ['loop', 'manual']
const LANGS: PitchLang[] = ['de', 'en']
const EXPIRIES = ['1', '7', '30', '90', 'never'] as const

const links = ref<PitchLink[]>([])
const loading = ref(true)
const error = ref('')
const busy = ref('')
const rowMsg = ref<Record<number, string>>({})

const form = reactive({ label: '', mode: 'loop' as PitchMode, lang: 'de' as PitchLang, expiry: '30' as (typeof EXPIRIES)[number] })
const labelInput = shallowRef<HTMLInputElement | null>(null)

/** Frisch erzeugter Link: nur bis „Fertig“ oder Neuladen der Seite sichtbar. */
const fresh = ref<{ link: PitchLink, url: string } | null>(null)
const copied = ref(false)
const copyError = ref('')
const urlInput = shallowRef<HTMLInputElement | null>(null)

const confirm = ref<{ kind: 'revoke' | 'delete', link: PitchLink } | null>(null)

function human(e: unknown): string {
  const code = apiCode(e)
  return p.value.errors[code] ?? fill(a.value.common.failed, { error: apiMessage(e) })
}

async function load() {
  error.value = ''
  try {
    const r = await api<{ links: PitchLink[] }>('/v1/admin/pitch-links')
    links.value = r.links
  } catch (e) {
    error.value = human(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function create() {
  const label = form.label.trim()
  if (!label) return
  busy.value = 'create'
  error.value = ''
  try {
    const r = await api<{ link: PitchLink, url: string }>('/v1/admin/pitch-links', {
      method: 'POST',
      body: { label, mode: form.mode, lang: form.lang, expiresInDays: form.expiry === 'never' ? null : Number(form.expiry) },
    })
    fresh.value = r
    copied.value = false
    copyError.value = ''
    form.label = ''
    await load()
    void nextTick(() => urlInput.value?.select())
  } catch (e) {
    error.value = human(e)
  } finally {
    busy.value = ''
  }
}

async function copyFresh() {
  if (!fresh.value) return
  copyError.value = ''
  try {
    await navigator.clipboard.writeText(fresh.value.url)
    copied.value = true
    setTimeout(() => (copied.value = false), 2000)
  } catch {
    urlInput.value?.select()
    copyError.value = p.value.copyFailed
  }
}

/** Neuen Link mit denselben Einstellungen vorbereiten (der alte Link lässt sich nicht mehr anzeigen). */
function prefill(l: PitchLink) {
  form.label = l.label
  form.mode = l.mode
  form.lang = l.lang
  void nextTick(() => {
    labelInput.value?.focus()
    labelInput.value?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
}

async function doConfirm() {
  const c = confirm.value
  if (!c) return
  const id = c.link.id
  busy.value = `${c.kind}:${id}`
  rowMsg.value[id] = ''
  try {
    if (c.kind === 'revoke') await api(`/v1/admin/pitch-links/${id}/revoke`, { method: 'POST' })
    else await api(`/v1/admin/pitch-links/${id}`, { method: 'DELETE' })
    if (c.kind === 'delete' && fresh.value?.link.id === id) fresh.value = null
    confirm.value = null
    await load()
  } catch (e) {
    confirm.value = null
    rowMsg.value[id] = human(e)
    if (apiCode(e) === 'not_found') await load()
  } finally {
    busy.value = ''
  }
}

function previewUrl(mode: PitchMode, lang: PitchLang): string {
  return `/admin/pitch/preview?${new URLSearchParams({ mode, lang }).toString()}`
}

// --- QR-Code (uqr, lokal erzeugt – kein Dienst von außen) -------------------------------------------
const qr = computed(() => {
  if (!fresh.value) return null
  const r = encode(fresh.value.url, { ecc: 'M', border: 2 })
  let d = ''
  r.data.forEach((row, y) => row.forEach((on, x) => {
    if (on) d += `M${x},${y}h1v1h-1z`
  }))
  return { size: r.size, d }
})
function downloadQr() {
  const q = qr.value
  if (!q || !fresh.value) return
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${q.size} ${q.size}" width="${q.size * 10}" height="${q.size * 10}" shape-rendering="crispEdges"><rect width="${q.size}" height="${q.size}" fill="#fff"/><path d="${q.d}" fill="#000"/></svg>`
  const url = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }))
  const el = document.createElement('a')
  el.href = url
  el.download = `pitch-${fresh.value.link.id}-qr.svg`
  el.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

// --- Anzeige ----------------------------------------------------------------------------------------
const TONES: Record<PitchLink['status'], string> = { active: 'tone-ok', expired: 'tone-muted', revoked: 'tone-danger' }
function viewsText(n: number): string {
  if (n === 0) return p.value.noViews
  return n === 1 ? p.value.oneView : fill(p.value.views, { n: num(n) })
}
function lifeText(l: PitchLink): string {
  if (l.revokedAt) return fill(p.value.revokedAt, { date: when(l.revokedAt) })
  if (!l.expiresAt) return p.value.noExpiry
  return fill(l.status === 'expired' ? p.value.expiredAt : p.value.expiresAt, { date: when(l.expiresAt) })
}
</script>

<template>
  <div class="adm-page">
    <header>
      <h1 class="adm-title">{{ p.title }}</h1>
      <p class="adm-lead">{{ p.lead }}</p>
    </header>

    <form class="card mt-6 grid gap-4 p-5" @submit.prevent="create">
      <h2 class="text-sm font-semibold tracking-wide text-base-300 uppercase">{{ p.formTitle }}</h2>
      <div>
        <label class="label" for="pl-label">{{ p.label }}</label>
        <input
          id="pl-label"
          ref="labelInput"
          v-model="form.label"
          class="field w-full"
          maxlength="80"
          autocomplete="off"
          required
          :placeholder="p.labelPlaceholder"
        />
      </div>
      <div class="grid gap-4 sm:grid-cols-3">
        <div>
          <label class="label" for="pl-mode">{{ p.mode }}</label>
          <select id="pl-mode" v-model="form.mode" class="field w-full">
            <option v-for="m in MODES" :key="m" :value="m">{{ p.modes[m] }}</option>
          </select>
        </div>
        <div>
          <label class="label" for="pl-lang">{{ p.lang }}</label>
          <select id="pl-lang" v-model="form.lang" class="field w-full">
            <option v-for="l in LANGS" :key="l" :value="l">{{ p.langs[l] }}</option>
          </select>
        </div>
        <div>
          <label class="label" for="pl-expiry">{{ p.expiry }}</label>
          <select id="pl-expiry" v-model="form.expiry" class="field w-full">
            <option v-for="e in EXPIRIES" :key="e" :value="e">{{ p.expiries[e] }}</option>
          </select>
        </div>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <button type="submit" class="btn btn-primary" :disabled="busy === 'create' || !form.label.trim()">
          <SiteIcon name="plus" class="size-4" />{{ busy === 'create' ? p.creating : p.create }}
        </button>
        <a :href="previewUrl(form.mode, form.lang)" target="_blank" rel="noopener" class="btn btn-ghost" :title="p.previewHint">
          <SiteIcon name="play" class="size-4" />{{ p.preview }}
        </a>
        <span class="text-xs text-base-500">{{ p.previewHint }}</span>
      </div>
      <p v-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
    </form>

    <section v-if="fresh" class="pl-fresh card mt-6 p-5" aria-live="polite">
      <div class="flex flex-wrap items-start gap-5">
        <div class="min-w-0 flex-[1_1_18rem]">
          <h2 class="text-lg font-semibold text-base-50">{{ fill(p.freshTitle, { label: fresh.link.label }) }}</h2>
          <p class="mt-1 text-sm text-lamp-300">{{ p.freshHint }}</p>
          <label class="sr-only" for="pl-url">URL</label>
          <input id="pl-url" ref="urlInput" class="field mt-3 w-full font-mono text-sm" :value="fresh.url" readonly @focus="urlInput?.select()" />
          <div class="mt-3 flex flex-wrap items-center gap-2">
            <button type="button" class="btn btn-primary" @click="copyFresh">
              <SiteIcon :name="copied ? 'check' : 'copy'" class="size-4" />{{ copied ? p.copied : p.copy }}
            </button>
            <a :href="fresh.url" target="_blank" rel="noopener noreferrer" class="btn btn-ghost">
              {{ p.open }} <SiteIcon name="external" class="size-4" />
            </a>
            <button type="button" class="btn btn-ghost" @click="downloadQr">
              <SiteIcon name="download" class="size-4" />{{ p.downloadQr }}
            </button>
            <button type="button" class="btn btn-ghost ml-auto" @click="fresh = null">{{ p.done }}</button>
          </div>
          <p v-if="copyError" role="alert" class="mt-2 text-sm text-redstone-300">{{ copyError }}</p>
        </div>
        <svg
          v-if="qr"
          class="pl-qr"
          :viewBox="`0 0 ${qr.size} ${qr.size}`"
          shape-rendering="crispEdges"
          role="img"
          :aria-label="fill(p.qr, { label: fresh.link.label })"
        >
          <rect :width="qr.size" :height="qr.size" fill="#fff" />
          <path :d="qr.d" fill="#000" />
        </svg>
      </div>
    </section>

    <h2 class="mt-8 text-sm font-semibold tracking-wide text-base-300 uppercase">{{ p.listTitle }}</h2>
    <div v-if="loading" class="skeleton mt-3 h-40 rounded-xl" />
    <p v-else-if="!links.length" class="adm-empty mt-3"><SiteIcon name="link" class="size-6" />{{ p.empty }}</p>
    <ul v-else class="mt-3 grid gap-3">
      <li v-for="l in links" :key="l.id" class="card p-4" :class="{ 'pl-new': fresh?.link.id === l.id }">
        <div class="flex flex-wrap items-start gap-3">
          <div class="min-w-0 flex-[1_1_18rem]">
            <div class="flex flex-wrap items-center gap-2">
              <h3 class="truncate text-base font-semibold text-base-50">{{ l.label }}</h3>
              <span class="tone" :class="TONES[l.status]">{{ p.status[l.status] }}</span>
              <span class="tone tone-muted">{{ p.modeShort[l.mode] }} · {{ l.lang.toUpperCase() }}</span>
              <code class="text-xs text-base-400">{{ fill(p.token, { hint: l.tokenHint }) }}</code>
            </div>
            <p class="mt-1 text-sm text-base-300">
              <SiteIcon name="play" class="inline size-3.5 align-[-2px]" /> {{ viewsText(l.views) }}<span v-if="l.lastViewAt"> · {{ fill(p.lastView, { date: when(l.lastViewAt) }) }}</span>
            </p>
            <p class="mt-1 text-xs text-base-500">
              {{ fill(p.createdBy, { name: l.createdByName ?? (l.createdBy === 'api-key' ? p.unknownCreator : l.createdBy), date: when(l.createdAt) }) }} · {{ lifeText(l) }}
            </p>
          </div>
          <div class="flex shrink-0 flex-wrap items-center gap-1">
            <button v-if="fresh?.link.id === l.id" type="button" class="btn btn-ghost" @click="copyFresh">
              <SiteIcon name="copy" class="size-4" />{{ copied ? p.copied : p.copy }}
            </button>
            <button v-else type="button" class="btn btn-ghost" :title="p.copyGone" @click="prefill(l)">
              <SiteIcon name="refresh" class="size-4" />{{ p.newLink }}
            </button>
            <a :href="previewUrl(l.mode, l.lang)" target="_blank" rel="noopener" class="btn-icon" :aria-label="p.preview" :title="p.previewHint">
              <SiteIcon name="play" class="size-4" />
            </a>
            <button
              v-if="l.status !== 'revoked'"
              type="button"
              class="btn-icon"
              :aria-label="p.revoke"
              :title="p.revoke"
              :disabled="busy === `revoke:${l.id}`"
              @click="confirm = { kind: 'revoke', link: l }"
            >
              <SiteIcon name="ban" class="size-4" />
            </button>
            <button
              type="button"
              class="btn-icon text-redstone-300"
              :aria-label="p.delete"
              :title="p.delete"
              :disabled="busy === `delete:${l.id}`"
              @click="confirm = { kind: 'delete', link: l }"
            >
              <SiteIcon name="trash" class="size-4" />
            </button>
          </div>
        </div>
        <p v-if="fresh?.link.id !== l.id" class="mt-2 text-xs text-base-500">{{ p.copyGone }}</p>
        <p v-if="rowMsg[l.id]" role="status" class="mt-2 text-sm text-redstone-300">{{ rowMsg[l.id] }}</p>
      </li>
    </ul>

    <AdminConfirm
      v-if="confirm"
      :title="confirm.kind === 'revoke' ? p.revoke : p.delete"
      :text="fill(confirm.kind === 'revoke' ? p.confirmRevoke : p.confirmDelete, { label: confirm.link.label })"
      danger
      :busy="busy === `${confirm.kind}:${confirm.link.id}`"
      @cancel="confirm = null"
      @confirm="doConfirm"
    />
  </div>
</template>

<style scoped>
.pl-fresh {
  border-color: var(--color-redstone-600);
  box-shadow: 0 0 0 1px var(--color-redstone-600), 0 0 24px -8px var(--color-redstone-500);
}
.pl-qr {
  width: 11rem;
  height: 11rem;
  flex-shrink: 0;
  border-radius: 0.5rem;
  image-rendering: pixelated;
}
.pl-new {
  border-color: var(--color-redstone-700);
}
</style>
