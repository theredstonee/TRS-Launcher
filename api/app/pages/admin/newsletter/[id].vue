<script setup lang="ts">
import {
  CAMPAIGN_LOOKS,
  NEWSLETTER_LANGS,
  type Block,
  type BlockType,
  type CampaignContent,
  type CampaignLook,
  type CampaignView,
  type Localized,
  type NewsletterLang,
} from '#shared/newsletter'

// Newsletter-Editor (§40.4): Bausteine mit Texten je Sprache, Drag & Drop, Live-Vorschau (Desktop/Handy, Look
// umschaltbar, gerendert vom Server wie die echte Mail), Testmail, Planen, Senden mit Bestätigung (Empfänger-Zahl).
// `rev` schützt vor dem Überschreiben fremder Änderungen (409 stale → deren Fassung wird geladen).
const route = useRoute()
const { a, when, num } = useAdminText()
const { n, fill } = useNewsletterText()
const { api, can } = useAdmin()
const t = computed(() => n.value.adm)
const e = computed(() => n.value.adm.editor)
const f = computed(() => n.value.adm.editor.fields)

const id = computed(() => String(route.params.id ?? ''))
const canManage = computed(() => can('newsletter.manage'))
const canSend = computed(() => can('newsletter.send'))

interface Draft {
  name: string
  look: CampaignLook
  content: CampaignContent
}
interface Sources {
  versions: { version: string, date: string | null, title: { en: string, de: string } | null }[]
  items: { kind: 'cape' | 'cosmetic', id: string, name: string, image: string | null, unlock: string }[]
  creators: { uuid: string, name: string, tier: string }[]
  autoLook: 'redstone' | 'halloween' | 'winter' | 'fox'
  mailAvailable: boolean
}

const campaign = ref<CampaignView | null>(null)
const draft = ref<Draft>({ name: '', look: 'auto', content: { subject: {}, preheader: {}, blocks: [] } })
const saved = ref('')
const sources = ref<Sources | null>(null)
const loading = ref(true)
const busy = ref('')
const error = ref('')
const notice = ref('')
const editLang = ref<NewsletterLang>('de')

const snapshot = () => JSON.stringify(draft.value)
const dirty = computed(() => snapshot() !== saved.value)
const editable = computed(() => canManage.value && !!campaign.value && ['draft', 'scheduled', 'cancelled'].includes(campaign.value.status))

function human(err: unknown): string {
  return t.value.errors[apiCode(err)] ?? fill(a.value.common.failed, { error: apiMessage(err) })
}

// --- Bausteine -------------------------------------------------------------------------------------
const newBlockId = () => Math.random().toString(36).slice(2, 10)
/** Fehlende Felder anlegen, damit v-model überall ein Objekt hat. */
function normalize(b: Block): Block {
  const x = b as Block & Record<string, unknown>
  const loc = (k: string) => {
    if (!x[k] || typeof x[k] !== 'object') x[k] = {}
  }
  switch (b.type) {
    case 'title': loc('kicker'); loc('text'); break
    case 'text': loc('text'); break
    case 'button': loc('label'); break
    case 'update': loc('title'); loc('button'); b.items ??= {}; break
    case 'images': for (const i of b.images) { i.alt ??= {}; i.caption ??= {} } break
    case 'cosmetic': loc('kicker'); loc('title'); loc('text'); b.item ??= null; break
    case 'creator': loc('kicker'); loc('text'); break
  }
  return b
}
function fromCampaign(c: CampaignView) {
  campaign.value = c
  draft.value = {
    name: c.name,
    look: c.look,
    content: { subject: { ...c.content.subject }, preheader: { ...c.content.preheader }, blocks: c.content.blocks.map((b) => normalize(structuredClone(b))) },
  }
  saved.value = snapshot()
}

function addBlock(type: BlockType) {
  const id = newBlockId()
  let b: Block
  switch (type) {
    case 'title': b = { id, type, size: draft.value.content.blocks.length ? 'h2' : 'h1', kicker: {}, text: {} }; break
    case 'text': b = { id, type, text: {} }; break
    case 'button': b = { id, type, label: {}, url: '/download' }; break
    case 'divider': b = { id, type }; break
    case 'update': b = { id, type, version: sources.value?.versions[0]?.version ?? '0.0.0', items: {}, title: {}, button: {} }; break
    case 'images': b = { id, type, images: [{ src: '', alt: {}, caption: {} }] }; break
    case 'cosmetic': b = { id, type, item: null, kicker: {}, title: {}, text: {} }; break
    case 'creator': b = { id, type, uuid: sources.value?.creators[0]?.uuid ?? '', kicker: {}, text: {} }; break
  }
  draft.value.content.blocks.push(b)
  addOpen.value = false
  if (b.type === 'update' && sources.value?.versions[0]) void loadUpdate(b)
}
function removeBlock(i: number) {
  draft.value.content.blocks.splice(i, 1)
}
function move(i: number, d: -1 | 1) {
  const list = draft.value.content.blocks
  const j = i + d
  if (j < 0 || j >= list.length) return
  ;[list[i], list[j]] = [list[j]!, list[i]!]
}
const addOpen = ref(false)
const BLOCK_ORDER: BlockType[] = ['title', 'text', 'update', 'images', 'cosmetic', 'creator', 'button', 'divider']
const BLOCK_ICONS: Record<BlockType, string> = { title: 'pencil', text: 'note', button: 'arrow', divider: 'minus', update: 'download', images: 'image', cosmetic: 'ticket', creator: 'star' }

// Drag & Drop (Maus/Stift; Tastatur und Touch nutzen die Pfeile).
const dragging = ref<number | null>(null)
const dropAt = ref<number | null>(null)
function onDragStart(ev: DragEvent, i: number) {
  if (!ev.dataTransfer || !editable.value) return
  dragging.value = i
  ev.dataTransfer.effectAllowed = 'move'
  ev.dataTransfer.setData('text/plain', String(i))
  const row = (ev.target as HTMLElement).closest('.nb-block') as HTMLElement | null
  if (row) ev.dataTransfer.setDragImage(row, 24, 24)
}
function onDragOver(ev: DragEvent, i: number) {
  if (dragging.value === null) return
  ev.preventDefault()
  const r = (ev.currentTarget as HTMLElement).getBoundingClientRect()
  dropAt.value = ev.clientY > r.top + r.height / 2 ? i + 1 : i
}
function onDrop(ev: DragEvent) {
  ev.preventDefault()
  const from = dragging.value
  const at = dropAt.value
  dragging.value = null
  dropAt.value = null
  if (from === null || at === null) return
  const list = draft.value.content.blocks
  const [b] = list.splice(from, 1)
  list.splice(at > from ? at - 1 : at, 0, b!)
}
function onDragEnd() {
  dragging.value = null
  dropAt.value = null
}

/** Lokalisiertes Feld für die aktuelle Schreibsprache (Platzhalter = Text der anderen Sprache). */
function hint(l: Localized | undefined): string {
  if (!l) return ''
  return l.de || l.en || l.es || ''
}
const langFilled = (lang: NewsletterLang) => !!draft.value.content.subject[lang]?.trim()

// Update-Baustein: aus dem Changelog laden.
async function loadUpdate(b: Extract<Block, { type: 'update' }>) {
  busy.value = `u:${b.id}`
  try {
    const r = await api<{ block: Extract<Block, { type: 'update' }> }>(`/v1/admin/newsletter/updates/${b.version}`)
    b.title = r.block.title ?? {}
    b.accent = r.block.accent ?? null
    b.motif = r.block.motif ?? null
    b.items = r.block.items
  } catch (err) {
    error.value = human(err)
  } finally {
    busy.value = ''
  }
}
function updateItems(b: Extract<Block, { type: 'update' }>) {
  b.items[editLang.value] ??= []
  return b.items[editLang.value]!
}

// Kosmetik-Karte: Teil wählen, Code erzeugen.
function itemKey(b: Extract<Block, { type: 'cosmetic' }>): string {
  return b.item ? `${b.item.kind}:${b.item.id}` : ''
}
function setItem(b: Extract<Block, { type: 'cosmetic' }>, key: string) {
  if (!key) {
    b.item = null
    return
  }
  const [kind, itemId] = key.split(':') as ['cape' | 'cosmetic', string]
  b.item = { kind, id: itemId }
  const src = sources.value?.items.find((x) => x.kind === kind && x.id === itemId)
  if (src && !b.title[editLang.value]) b.title[editLang.value] = src.name
}
const codeForm = ref<Record<string, { maxUses: number, expires: string }>>({})
function codeDraft(blockId: string) {
  codeForm.value[blockId] ??= { maxUses: 500, expires: toLocalInput(new Date(Date.now() + 30 * 86400_000).toISOString()) }
  return codeForm.value[blockId]!
}
async function generateCode(b: Extract<Block, { type: 'cosmetic' }>) {
  if (dirty.value && !(await save())) return
  const c = codeDraft(b.id)
  busy.value = `c:${b.id}`
  try {
    const r = await api<{ campaign: CampaignView }>(`/v1/admin/newsletter/campaigns/${id.value}/code`, {
      method: 'POST',
      body: { blockId: b.id, maxUses: c.maxUses, rev: campaign.value!.rev, ...(c.expires ? { expiresAt: fromLocalInput(c.expires) } : {}) },
    })
    fromCampaign(r.campaign)
  } catch (err) {
    error.value = human(err)
  } finally {
    busy.value = ''
  }
}
const itemImage = (b: Extract<Block, { type: 'cosmetic' }>) => sources.value?.items.find((x) => b.item && x.kind === b.item.kind && x.id === b.item.id)?.image ?? null

// --- Laden + Speichern -------------------------------------------------------------------------------
async function load() {
  loading.value = true
  error.value = ''
  try {
    const [c, s] = await Promise.all([
      api<{ campaign: CampaignView }>(`/v1/admin/newsletter/campaigns/${id.value}`),
      api<Sources>('/v1/admin/newsletter/sources'),
    ])
    sources.value = s
    fromCampaign(c.campaign)
    void refreshPreview()
  } catch (err) {
    error.value = human(err)
  } finally {
    loading.value = false
  }
}
onMounted(load)

/** Inhalt für den Server: leere Bilder weg. */
function cleaned(): CampaignContent {
  const c = structuredClone(toRaw(draft.value.content))
  c.blocks = c.blocks.map((b) => (b.type === 'images' ? { ...b, images: b.images.filter((i) => i.src.trim()) } : b)).filter((b) => b.type !== 'images' || b.images.length > 0)
  return c
}

async function save(): Promise<boolean> {
  if (!campaign.value) return false
  busy.value = 'save'
  error.value = ''
  notice.value = ''
  try {
    const r = await api<{ campaign: CampaignView }>(`/v1/admin/newsletter/campaigns/${id.value}`, {
      method: 'PATCH',
      body: { rev: campaign.value.rev, name: draft.value.name.trim() || t.value.untitled, look: draft.value.look, content: cleaned() },
    })
    fromCampaign(r.campaign)
    notice.value = e.value.saved
    return true
  } catch (err) {
    if (apiCode(err) === 'stale') {
      const cur = (err as { data?: { error?: { details?: { current?: CampaignView } } } }).data?.error?.details?.current
      if (cur) fromCampaign(cur)
      error.value = e.value.stale
    } else error.value = human(err)
    return false
  } finally {
    busy.value = ''
  }
}

// --- Vorschau ---------------------------------------------------------------------------------------
const device = ref<'desktop' | 'mobile'>('desktop')
const previewLang = ref<NewsletterLang>('de')
const previewLook = ref<CampaignLook | ''>('')
const previewHtml = ref('')
const previewSubject = ref('')
const previewBusy = ref(false)
const previewError = ref('')
const frame = shallowRef<HTMLIFrameElement | null>(null)
const frameHeight = ref(1200)
let timer: ReturnType<typeof setTimeout> | null = null
let seq = 0

async function refreshPreview() {
  const my = ++seq
  previewBusy.value = true
  try {
    const r = await api<{ html: string, subject: string, look: string }>('/v1/admin/newsletter/preview', {
      method: 'POST',
      body: { content: cleaned(), look: previewLook.value || draft.value.look, lang: previewLang.value },
    })
    if (my !== seq) return
    // Links in der Vorschau in neuem Tab öffnen.
    previewHtml.value = r.html.replace('<head>', '<head><base target="_blank">')
    previewSubject.value = r.subject
    previewError.value = ''
  } catch (err) {
    if (my === seq) previewError.value = human(err)
  } finally {
    if (my === seq) previewBusy.value = false
  }
}
watch([draft, previewLang, previewLook], () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => void refreshPreview(), 600)
}, { deep: true })
function onFrameLoad() {
  try {
    const h = frame.value?.contentDocument?.documentElement.scrollHeight
    if (h) frameHeight.value = h + 4
  } catch {
    // ohne Zugriff bleibt die Höhe
  }
}
watch(device, () => setTimeout(onFrameLoad, 50))

// --- Test, Planen, Senden -------------------------------------------------------------------------------
async function sendTest() {
  if (dirty.value && !(await save())) return
  busy.value = 'test'
  error.value = ''
  notice.value = ''
  try {
    const r = await api<{ to: string }>(`/v1/admin/newsletter/campaigns/${id.value}/test`, { method: 'POST', body: { lang: previewLang.value, ...(previewLook.value ? { look: previewLook.value } : {}) } })
    notice.value = fill(e.value.testSent, { to: r.to })
  } catch (err) {
    error.value = human(err)
  } finally {
    busy.value = ''
  }
}

const sendLangs = ref<NewsletterLang[]>([])
const scheduleAt = ref('')
const confirmSend = ref<null | { mode: 'now' | 'schedule', count: number }>(null)
const langsParam = () => (sendLangs.value.length && sendLangs.value.length < 3 ? sendLangs.value : null)

async function askSend(mode: 'now' | 'schedule') {
  if (dirty.value && !(await save())) return
  busy.value = 'audience'
  error.value = ''
  try {
    const l = langsParam()
    const r = await api<{ count: number }>(`/v1/admin/newsletter/campaigns/${id.value}/audience${l ? `?langs=${l.join(',')}` : ''}`)
    confirmSend.value = { mode, count: r.count }
  } catch (err) {
    error.value = human(err)
  } finally {
    busy.value = ''
  }
}
async function doSend() {
  const c = confirmSend.value
  if (!c) return
  busy.value = 'send'
  try {
    if (c.mode === 'now') {
      const r = await api<{ campaign: CampaignView, queued: number }>(`/v1/admin/newsletter/campaigns/${id.value}/send`, { method: 'POST', body: { langs: langsParam(), expectedCount: c.count } })
      fromCampaign(r.campaign)
      notice.value = fill(e.value.queued, { n: num(r.queued) })
    } else {
      const r = await api<{ campaign: CampaignView }>(`/v1/admin/newsletter/campaigns/${id.value}/schedule`, { method: 'POST', body: { at: fromLocalInput(scheduleAt.value), langs: langsParam() } })
      fromCampaign(r.campaign)
    }
    confirmSend.value = null
  } catch (err) {
    confirmSend.value = null
    error.value = human(err)
  } finally {
    busy.value = ''
  }
}
async function cancelSend() {
  busy.value = 'cancel'
  try {
    fromCampaign((await api<{ campaign: CampaignView }>(`/v1/admin/newsletter/campaigns/${id.value}/cancel`, { method: 'POST' })).campaign)
  } catch (err) {
    error.value = human(err)
  } finally {
    busy.value = ''
  }
}
// Laufenden Versand beobachten.
let poll: ReturnType<typeof setInterval> | null = null
watch(() => campaign.value?.status, (s) => {
  if (poll) clearInterval(poll)
  poll = null
  if (s === 'sending') {
    poll = setInterval(async () => {
      try {
        const c = (await api<{ campaign: CampaignView }>(`/v1/admin/newsletter/campaigns/${id.value}`)).campaign
        if (!dirty.value) fromCampaign(c)
        else campaign.value = c
      } catch {
        // nächster Versuch
      }
    }, 5000)
  }
})
onBeforeUnmount(() => {
  if (poll) clearInterval(poll)
  if (timer) clearTimeout(timer)
})
const confirmText = computed(() => {
  const c = confirmSend.value
  if (!c) return ''
  const subject = previewSubject.value || draft.value.content.subject.de || ''
  if (c.mode === 'now') return fill(e.value.confirmText, { subject, n: num(c.count) })
  const l = langsParam()
  return fill(e.value.confirmSchedule, { subject, date: when(fromLocalInput(scheduleAt.value)), langs: l ? ` (${l.map((x) => t.value.langs[x]).join(', ')})` : '' })
})
const lookLabel = (l: CampaignLook) => (l === 'auto' && sources.value ? `${t.value.looks.auto} – ${fill(t.value.autoIs, { look: t.value.looks[sources.value.autoLook]! })}` : t.value.looks[l])
</script>

<template>
  <div class="adm-page nl-editor">
    <NuxtLink to="/admin/newsletter" class="inline-flex items-center gap-1.5 text-sm text-base-400 hover:text-base-50"><SiteIcon name="back" class="size-4" />{{ e.back }}</NuxtLink>
    <div v-if="loading" class="skeleton mt-4 h-96 rounded-xl" />
    <p v-else-if="!campaign" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
    <template v-else>
      <header class="mt-3 flex flex-wrap items-center gap-3">
        <input v-model="draft.name" class="field nl-name min-w-0 flex-1" maxlength="100" :aria-label="e.name" :disabled="!editable" />
        <span class="tone" :class="campaign.status === 'sent' ? 'tone-ok' : campaign.status === 'scheduled' ? 'tone-info' : campaign.status === 'sending' ? 'tone-warn' : 'tone-muted'">{{ t.statuses[campaign.status] }}</span>
        <span v-if="dirty && editable" class="text-xs text-lamp-300">{{ e.unsaved }}</span>
        <button v-if="editable" type="button" class="btn btn-primary" :disabled="busy === 'save' || !dirty" data-testid="nl-save" @click="save">{{ busy === 'save' ? e.saving : e.save }}</button>
      </header>
      <p class="mt-1 text-xs text-base-500">{{ fill(t.editedBy, { date: when(campaign.updatedAt), name: campaign.updatedBy.kind === 'key' ? `${campaign.updatedBy.name} (API)` : campaign.updatedBy.name }) }}</p>
      <p v-if="!editable && canManage" class="nl-info mt-3">{{ e.locked }} <span v-if="campaign.status === 'sending' || campaign.status === 'sent'">{{ fill(t.progress, { sent: num(campaign.sent), total: num(campaign.total), failed: num(campaign.failed) }) }}</span></p>
      <p v-if="campaign.status === 'scheduled' && campaign.scheduledAt" class="nl-info mt-3">{{ fill(t.scheduledFor, { date: when(campaign.scheduledAt) }) }}</p>
      <p v-if="sources && !sources.mailAvailable" class="nl-info nl-warn mt-3">{{ t.mailOff }}</p>
      <p v-if="error" role="alert" class="mt-3 text-sm text-redstone-300">{{ error }}</p>
      <p v-else-if="notice" role="status" class="mt-3 text-sm text-base-300">{{ notice }}</p>

      <div class="nl-grid mt-5">
        <!-- Linke Seite: Einstellungen + Bausteine -->
        <section class="min-w-0">
          <fieldset class="card grid gap-3 p-4" :disabled="!editable">
            <div class="flex flex-wrap items-center gap-2">
              <span class="label m-0">{{ e.editLang }}</span>
              <div class="nl-seg" role="group">
                <button v-for="l in NEWSLETTER_LANGS" :key="l" type="button" :aria-pressed="editLang === l" @click="editLang = l">
                  {{ t.langs[l] }}<span v-if="!langFilled(l)" class="nl-missing">· {{ e.missing }}</span>
                </button>
              </div>
            </div>
            <p class="text-xs text-base-500">{{ e.langHint }}</p>
            <div>
              <label class="label" for="nl-subject">{{ e.subject }} ({{ editLang.toUpperCase() }})</label>
              <input id="nl-subject" v-model="draft.content.subject[editLang]" class="field" maxlength="150" :placeholder="hint(draft.content.subject)" />
            </div>
            <div>
              <label class="label" for="nl-pre">{{ e.preheader }} ({{ editLang.toUpperCase() }})</label>
              <input id="nl-pre" v-model="draft.content.preheader[editLang]" class="field" maxlength="200" :placeholder="hint(draft.content.preheader)" />
              <p class="mt-1 text-xs text-base-500">{{ e.preheaderHint }}</p>
            </div>
            <div>
              <label class="label" for="nl-look">{{ e.look }}</label>
              <select id="nl-look" v-model="draft.look" class="field">
                <option v-for="l in CAMPAIGN_LOOKS" :key="l" :value="l">{{ lookLabel(l) }}</option>
              </select>
            </div>
          </fieldset>

          <div class="mt-5 flex flex-wrap items-center gap-2">
            <h2 class="text-lg font-semibold text-base-50">{{ e.blocks }}</h2>
            <p class="text-xs text-base-500">{{ e.dragHint }}</p>
          </div>
          <p v-if="!draft.content.blocks.length" class="adm-empty mt-3"><SiteIcon name="blocks" class="size-6" />{{ e.emptyBlocks }}</p>
          <ol class="mt-3 grid gap-3" @dragover.prevent @drop="onDrop">
            <li
              v-for="(b, i) in draft.content.blocks"
              :key="b.id"
              class="nb-block card"
              :class="{ 'is-dragging': dragging === i, 'line-before': dragging !== null && dropAt === i, 'line-after': dragging !== null && dropAt === i + 1 && i === draft.content.blocks.length - 1 }"
              :data-testid="`nl-block-${b.type}`"
              @dragover="onDragOver($event, i)"
              @drop.stop="onDrop"
            >
              <div class="nb-head">
                <span v-if="editable" class="nb-grip" draggable="true" role="img" :aria-label="e.dragHint" @dragstart="onDragStart($event, i)" @dragend="onDragEnd"><SiteIcon name="grip" class="size-4" /></span>
                <SiteIcon :name="BLOCK_ICONS[b.type]" class="size-4 text-redstone-400" />
                <span class="text-sm font-semibold text-base-100">{{ e.types[b.type] }}</span>
                <div v-if="editable" class="ml-auto flex gap-1">
                  <button type="button" class="btn-icon size-8" :disabled="i === 0" :aria-label="e.moveUp" :title="e.moveUp" @click="move(i, -1)"><SiteIcon name="up" class="size-4" /></button>
                  <button type="button" class="btn-icon size-8" :disabled="i === draft.content.blocks.length - 1" :aria-label="e.moveDown" :title="e.moveDown" @click="move(i, 1)"><SiteIcon name="chevron" class="size-4" /></button>
                  <button type="button" class="btn-icon size-8 text-redstone-300" :aria-label="e.remove" :title="e.remove" @click="removeBlock(i)"><SiteIcon name="trash" class="size-4" /></button>
                </div>
              </div>
              <fieldset class="nb-body" :disabled="!editable">
                <!-- Titel -->
                <template v-if="b.type === 'title'">
                  <div class="nl-seg w-fit" role="group" :aria-label="f.size">
                    <button type="button" :aria-pressed="b.size === 'h1'" @click="b.size = 'h1'">{{ f.h1 }}</button>
                    <button type="button" :aria-pressed="b.size === 'h2'" @click="b.size = 'h2'">{{ f.h2 }}</button>
                  </div>
                  <label class="label">{{ f.kicker }}<input v-model="b.kicker![editLang]" class="field mt-1" maxlength="80" :placeholder="hint(b.kicker)" /></label>
                  <label class="label">{{ f.titleText }}<input v-model="b.text[editLang]" class="field mt-1" maxlength="140" :placeholder="hint(b.text)" /></label>
                </template>
                <!-- Text -->
                <template v-else-if="b.type === 'text'">
                  <label class="label">{{ f.text }}<textarea v-model="b.text[editLang]" class="field mt-1 min-h-28 font-mono text-sm" maxlength="5000" :placeholder="hint(b.text)" /></label>
                  <label class="flex items-center gap-2 text-sm text-base-300"><input v-model="b.muted" type="checkbox" />{{ f.muted }}</label>
                </template>
                <!-- Knopf -->
                <template v-else-if="b.type === 'button'">
                  <label class="label">{{ f.label }}<input v-model="b.label[editLang]" class="field mt-1" maxlength="60" :placeholder="hint(b.label)" /></label>
                  <label class="label">{{ f.url }}<input v-model="b.url" class="field mt-1" maxlength="800" /></label>
                </template>
                <!-- Update -->
                <template v-else-if="b.type === 'update'">
                  <div class="flex flex-wrap items-end gap-2">
                    <label class="label min-w-40 flex-1">{{ f.version }}
                      <select v-model="b.version" class="field mt-1">
                        <option v-for="v in sources?.versions ?? []" :key="v.version" :value="v.version">{{ v.version }}{{ v.title ? ` – ${v.title.de || v.title.en}` : '' }}</option>
                        <option v-if="!(sources?.versions ?? []).some((v) => v.version === b.version)" :value="b.version">{{ b.version }}</option>
                      </select>
                    </label>
                    <button type="button" class="btn btn-ghost" :disabled="busy === `u:${b.id}`" @click="loadUpdate(b)"><SiteIcon name="refresh" class="size-4" />{{ f.load }}</button>
                  </div>
                  <p class="label">{{ f.items }} ({{ editLang.toUpperCase() }})</p>
                  <div v-for="(it, k) in updateItems(b)" :key="k" class="grid gap-1.5 sm:grid-cols-[1fr_2fr_auto]">
                    <input v-model="it.title" class="field" maxlength="200" :placeholder="f.itemTitle" />
                    <input v-model="it.text" class="field" maxlength="600" :placeholder="f.itemText" />
                    <button type="button" class="btn-icon" :aria-label="e.remove" @click="updateItems(b).splice(k, 1)"><SiteIcon name="close" class="size-4" /></button>
                  </div>
                  <button type="button" class="btn btn-ghost w-fit" @click="updateItems(b).push({ title: '', text: '' })"><SiteIcon name="plus" class="size-4" />{{ f.addItem }}</button>
                  <label class="label">{{ f.downloadLabel }}<input v-model="b.button![editLang]" class="field mt-1" maxlength="60" /></label>
                </template>
                <!-- Bilder -->
                <template v-else-if="b.type === 'images'">
                  <div v-for="(img, k) in b.images" :key="k" class="grid gap-1.5 rounded-lg border border-base-800 p-3">
                    <label class="label">{{ f.image }}<input v-model="img.src" class="field mt-1" maxlength="800" placeholder="https://…" /></label>
                    <div class="grid gap-1.5 sm:grid-cols-2">
                      <label class="label">{{ f.caption }}<input v-model="img.caption![editLang]" class="field mt-1" maxlength="160" :placeholder="hint(img.caption)" /></label>
                      <label class="label">{{ f.alt }}<input v-model="img.alt![editLang]" class="field mt-1" maxlength="160" :placeholder="hint(img.alt)" /></label>
                    </div>
                    <button v-if="b.images.length > 1" type="button" class="btn btn-ghost w-fit text-redstone-300" @click="b.images.splice(k, 1)">{{ f.removeImage }}</button>
                  </div>
                  <button v-if="b.images.length < 2" type="button" class="btn btn-ghost w-fit" @click="b.images.push({ src: '', alt: {}, caption: {} })"><SiteIcon name="plus" class="size-4" />{{ f.addImage }}</button>
                  <p class="text-xs text-base-500">{{ e.imagesHint }}</p>
                </template>
                <!-- Kosmetik-/Code-Karte -->
                <template v-else-if="b.type === 'cosmetic'">
                  <div class="flex gap-3">
                    <img v-if="b.image || itemImage(b)" :src="b.image || itemImage(b)!" alt="" class="nb-thumb" />
                    <label class="label min-w-0 flex-1">{{ f.item }}
                      <select class="field mt-1" :value="itemKey(b)" @change="setItem(b, ($event.target as HTMLSelectElement).value)">
                        <option value="">{{ f.none }}</option>
                        <option v-for="x in sources?.items ?? []" :key="`${x.kind}:${x.id}`" :value="`${x.kind}:${x.id}`">{{ x.name }} ({{ x.kind }}, {{ x.unlock }})</option>
                      </select>
                    </label>
                  </div>
                  <label class="label">{{ f.ownImage }}<input v-model="b.image" class="field mt-1" maxlength="800" placeholder="https://…" /></label>
                  <label class="label">{{ f.kicker }}<input v-model="b.kicker![editLang]" class="field mt-1" maxlength="80" :placeholder="hint(b.kicker)" /></label>
                  <label class="label">{{ f.titleText }}<input v-model="b.title[editLang]" class="field mt-1" maxlength="140" :placeholder="hint(b.title)" /></label>
                  <label class="label">{{ f.text }}<textarea v-model="b.text![editLang]" class="field mt-1 min-h-20 text-sm" maxlength="1000" :placeholder="hint(b.text)" /></label>
                  <div class="nb-code">
                    <p class="label m-0">{{ f.code }}</p>
                    <p v-if="b.code" class="font-mono text-lg tracking-widest text-base-50">{{ b.code }} <span v-if="b.codeExpiresAt" class="text-xs tracking-normal text-base-400">· {{ f.expires }} {{ when(b.codeExpiresAt) }}</span></p>
                    <template v-else>
                      <p class="text-sm text-base-400">{{ f.noCode }}</p>
                      <div class="flex flex-wrap items-end gap-2">
                        <label class="label">{{ f.maxUses }}<input v-model.number="codeDraft(b.id).maxUses" type="number" min="1" max="100000" class="field mt-1 w-32" /></label>
                        <label class="label">{{ f.expires }}<input v-model="codeDraft(b.id).expires" type="datetime-local" class="field mt-1" /></label>
                        <button type="button" class="btn btn-ghost" :disabled="!b.item || busy === `c:${b.id}`" @click="generateCode(b)"><SiteIcon name="ticket" class="size-4" />{{ f.generate }}</button>
                      </div>
                    </template>
                  </div>
                </template>
                <!-- Creator-Spotlight -->
                <template v-else-if="b.type === 'creator'">
                  <label class="label">{{ f.creator }}
                    <select v-model="b.uuid" class="field mt-1">
                      <option value="" disabled>{{ f.pickCreator }}</option>
                      <option v-for="c in sources?.creators ?? []" :key="c.uuid" :value="c.uuid">{{ c.name }} ({{ c.tier }})</option>
                    </select>
                  </label>
                  <label class="label">{{ f.kicker }}<input v-model="b.kicker![editLang]" class="field mt-1" maxlength="80" placeholder="Creator-Spotlight" /></label>
                  <label class="label">{{ f.text }}<textarea v-model="b.text[editLang]" class="field mt-1 min-h-20 text-sm" maxlength="1000" :placeholder="hint(b.text)" /></label>
                  <label class="label">{{ f.creatorUrl }}<input v-model="b.url" class="field mt-1" maxlength="800" placeholder="https://…" /></label>
                </template>
                <p v-else class="text-sm text-base-500">—</p>
              </fieldset>
            </li>
          </ol>
          <div v-if="editable" class="relative mt-3">
            <button type="button" class="btn btn-ghost w-full border border-dashed border-base-700" :aria-expanded="addOpen" data-testid="nl-add" @click="addOpen = !addOpen"><SiteIcon name="plus" class="size-4" />{{ e.add }}</button>
            <div v-if="addOpen" class="nb-add mt-2 grid grid-cols-2 gap-2 sm:grid-cols-4">
              <button v-for="type in BLOCK_ORDER" :key="type" type="button" class="nb-add-item" :data-testid="`nl-add-${type}`" :disabled="(type === 'creator' && !sources?.creators.length) || (type === 'update' && !sources?.versions.length)" @click="addBlock(type)">
                <SiteIcon :name="BLOCK_ICONS[type]" class="size-5" /><span>{{ e.types[type] }}</span>
              </button>
            </div>
          </div>

          <!-- Senden -->
          <section v-if="campaign && (canManage || canSend)" class="card mt-6 grid gap-3 p-4">
            <div class="flex flex-wrap gap-2">
              <button v-if="canManage" type="button" class="btn btn-ghost" :disabled="busy === 'test'" data-testid="nl-test" @click="sendTest"><SiteIcon name="mail" class="size-4" />{{ e.test }}</button>
              <button v-if="canSend && (campaign.status === 'scheduled' || campaign.status === 'sending')" type="button" class="btn btn-ghost text-redstone-300" :disabled="busy === 'cancel'" @click="cancelSend">{{ e.cancelSend }}</button>
            </div>
            <template v-if="canSend && editable">
              <fieldset class="flex flex-wrap items-center gap-3 text-sm text-base-200">
                <legend class="label">{{ e.audience }}</legend>
                <label v-for="l in NEWSLETTER_LANGS" :key="l" class="flex items-center gap-1.5"><input v-model="sendLangs" type="checkbox" :value="l" />{{ t.langs[l] }}</label>
                <span class="text-xs text-base-500">({{ sendLangs.length ? '' : e.allLangs }})</span>
              </fieldset>
              <div class="flex flex-wrap items-end gap-2">
                <label class="label">{{ e.scheduleAt }}<input v-model="scheduleAt" type="datetime-local" class="field mt-1" /></label>
                <button type="button" class="btn btn-ghost" :disabled="!scheduleAt || busy === 'audience'" @click="askSend('schedule')"><SiteIcon name="clock" class="size-4" />{{ e.schedule }}</button>
                <button type="button" class="btn btn-primary ml-auto" :disabled="busy === 'audience'" data-testid="nl-send" @click="askSend('now')"><SiteIcon name="arrow" class="size-4" />{{ e.sendNow }}</button>
              </div>
            </template>
          </section>
        </section>

        <!-- Rechte Seite: Vorschau -->
        <aside class="nl-preview">
          <div class="flex flex-wrap items-center gap-2">
            <div class="nl-seg" role="group" :aria-label="e.preview">
              <button type="button" :aria-pressed="device === 'desktop'" data-testid="nl-desktop" @click="device = 'desktop'">{{ e.desktop }}</button>
              <button type="button" :aria-pressed="device === 'mobile'" data-testid="nl-mobile" @click="device = 'mobile'">{{ e.mobile }}</button>
            </div>
            <select v-model="previewLang" class="field nl-small" :aria-label="e.previewLang">
              <option v-for="l in NEWSLETTER_LANGS" :key="l" :value="l">{{ t.langs[l] }}</option>
            </select>
            <select v-model="previewLook" class="field nl-small" :aria-label="e.look" data-testid="nl-preview-look">
              <option value="">{{ e.look }}: {{ t.looks[draft.look] }}</option>
              <option v-for="l in CAMPAIGN_LOOKS.filter((x) => x !== 'auto')" :key="l" :value="l">{{ t.looks[l] }}</option>
            </select>
            <span v-if="previewBusy" class="text-xs text-base-500">{{ e.refreshing }}</span>
          </div>
          <p v-if="previewSubject" class="nl-subject mt-2"><b>{{ e.subject }}:</b> {{ previewSubject }}</p>
          <p v-if="previewError" class="mt-2 text-xs text-redstone-300">{{ previewError }}</p>
          <div class="nl-frame-wrap mt-2" :data-device="device">
            <iframe
              ref="frame"
              :srcdoc="previewHtml"
              :title="e.preview"
              sandbox="allow-same-origin allow-popups allow-popups-to-escape-sandbox"
              :style="{ height: `${frameHeight}px` }"
              data-testid="nl-frame"
              @load="onFrameLoad"
            />
          </div>
        </aside>
      </div>
    </template>

    <AdminConfirm
      v-if="confirmSend"
      :title="e.confirmTitle"
      :text="confirmText"
      :confirm-label="confirmSend.mode === 'now' ? fill(e.confirmButton, { n: num(confirmSend.count) }) : e.schedule"
      :busy="busy === 'send'"
      danger
      @cancel="confirmSend = null"
      @confirm="doSend"
    />
  </div>
</template>

<style scoped>
.nl-editor {
  max-width: 1500px;
}
.nl-grid {
  display: grid;
  gap: 1.5rem;
}
@media (min-width: 1200px) {
  .nl-grid {
    grid-template-columns: minmax(0, 1fr) 700px;
    align-items: start;
  }
  .nl-preview {
    position: sticky;
    top: 4.5rem;
  }
}
.nl-name {
  font-size: 1.15rem;
  font-weight: 600;
}
.nl-info {
  padding: 0.6rem 0.85rem;
  border-radius: 0.6rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-900);
  font-size: 0.875rem;
  color: var(--color-base-200);
}
.nl-warn {
  border-color: color-mix(in srgb, #ffb84d 40%, transparent);
  color: #ffd48a;
}
.nl-seg {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 0.2rem;
  padding: 0.2rem;
  border-radius: 0.55rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-950);
}
.nl-seg button {
  padding: 0.3rem 0.65rem;
  border-radius: 0.4rem;
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--color-base-400);
}
.nl-seg button[aria-pressed='true'] {
  background: var(--color-redstone-600);
  color: var(--color-base-50);
}
.nl-missing {
  margin-left: 0.25rem;
  font-weight: 400;
  opacity: 0.7;
}
.nl-small {
  width: auto;
  height: 2.1rem;
  padding-top: 0;
  padding-bottom: 0;
  font-size: 0.8rem;
}
.nb-block {
  position: relative;
  padding: 0;
  transition: opacity 0.15s;
}
.nb-block.is-dragging {
  opacity: 0.4;
}
.nb-block.line-before::before,
.nb-block.line-after::after {
  content: '';
  position: absolute;
  left: 0.5rem;
  right: 0.5rem;
  height: 3px;
  border-radius: 999px;
  background: var(--color-redstone-500);
  box-shadow: 0 0 8px var(--color-redstone-500);
}
.nb-block.line-before::before {
  top: -0.45rem;
}
.nb-block.line-after::after {
  bottom: -0.45rem;
}
.nb-head {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid var(--color-base-800);
}
.nb-grip {
  display: grid;
  place-items: center;
  width: 1.5rem;
  height: 2rem;
  border-radius: 0.35rem;
  color: var(--color-base-600);
  cursor: grab;
}
.nb-grip:hover {
  color: var(--color-base-200);
  background: var(--color-base-800);
}
.nb-body {
  display: grid;
  gap: 0.6rem;
  padding: 0.75rem;
}
.nb-thumb {
  width: 4.5rem;
  height: 4.5rem;
  object-fit: contain;
  border-radius: 0.5rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-950);
  image-rendering: pixelated;
}
.nb-code {
  display: grid;
  gap: 0.4rem;
  padding: 0.65rem 0.8rem;
  border-radius: 0.6rem;
  border: 1px dashed var(--color-redstone-600);
}
.nb-add-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.35rem;
  padding: 0.75rem 0.5rem;
  border-radius: 0.6rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-900);
  font-size: 0.8rem;
  color: var(--color-base-200);
}
.nb-add-item:hover:not(:disabled) {
  border-color: var(--color-redstone-600);
  color: var(--color-base-50);
}
.nb-add-item:disabled {
  opacity: 0.4;
}
.nl-subject {
  font-size: 0.8rem;
  color: var(--color-base-300);
}
.nl-frame-wrap {
  display: flex;
  justify-content: center;
  padding: 1rem;
  border-radius: 0.9rem;
  border: 1px solid var(--color-base-800);
  background: #050507;
}
.nl-frame-wrap iframe {
  width: 100%;
  max-width: 660px;
  border: 0;
  background: #000;
}
.nl-frame-wrap[data-device='mobile'] iframe {
  max-width: 375px;
  border-radius: 1.5rem;
  border: 8px solid #1d1d26;
}
</style>
