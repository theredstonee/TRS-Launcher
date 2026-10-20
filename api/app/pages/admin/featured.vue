<script setup lang="ts">
import {
  FEATURED_BADGES,
  LOADER_NAMES,
  SOURCE_NAMES,
  sameOriginImage,
  type AdminFeaturedPack,
  type FeaturedBadge,
} from '~/utils/featured'

// Empfohlene Modpacks (§37): Link einfügen, Texte EN/DE, Abzeichen, Anheften, Reihenfolge (Drag & Drop + Pfeile),
// Zeitraum. Nur mit featured.manage – geprüft wird serverseitig.
const { a, fill, when, num } = useAdminText()
const { api } = useAdmin()
const f = computed(() => a.value.featured)

interface Draft {
  en: { title: string, text: string }
  de: { title: string, text: string }
  badge: '' | FeaturedBadge
  startsAt: string
  endsAt: string
}

const packs = ref<AdminFeaturedPack[]>([])
const drafts = ref<Record<string, Draft>>({})
const loading = ref(true)
const error = ref('')
const notice = ref('')
const busy = ref('')
const link = ref('')
const rowMsg = ref<Record<string, string>>({})
const removing = ref<AdminFeaturedPack | null>(null)

/** Fehler in Menschensprache (Code → Text), sonst die Meldung des Servers. */
function human(e: unknown): string {
  const code = apiCode(e)
  return f.value.errors[code] ?? fill(a.value.common.failed, { error: apiMessage(e) })
}

/** ISO → Wert für `<input type="datetime-local">` (Ortszeit). */
function toLocal(iso: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}
function fromLocal(v: string): string | null {
  if (!v) return null
  const d = new Date(v)
  return Number.isNaN(d.getTime()) ? null : d.toISOString()
}

function draftOf(p: AdminFeaturedPack): Draft {
  return {
    en: { title: p.texts.en.title ?? '', text: p.texts.en.text ?? '' },
    de: { title: p.texts.de.title ?? '', text: p.texts.de.text ?? '' },
    badge: p.badge ?? '',
    startsAt: toLocal(p.startsAt),
    endsAt: toLocal(p.endsAt),
  }
}
function setPacks(list: AdminFeaturedPack[]) {
  const keep = drafts.value
  const next: Record<string, Draft> = {}
  // Ungespeicherte Eingaben anderer Packs bleiben stehen.
  for (const p of list) next[p.id] = keep[p.id] && dirty(p, keep[p.id]) ? keep[p.id]! : draftOf(p)
  drafts.value = next
  packs.value = list
}
function replacePack(p: AdminFeaturedPack) {
  const i = packs.value.findIndex((x) => x.id === p.id)
  if (i >= 0) packs.value[i] = p
  drafts.value[p.id] = draftOf(p)
}
function dirty(p: AdminFeaturedPack, d: Draft | undefined = drafts.value[p.id]): boolean {
  if (!d) return false
  return JSON.stringify(d) !== JSON.stringify(draftOf(p))
}

async function load() {
  error.value = ''
  try {
    const r = await api<{ packs: AdminFeaturedPack[] }>('/v1/admin/featured-packs')
    setPacks(r.packs)
  } catch (e) {
    error.value = human(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function add() {
  const url = link.value.trim()
  if (!url) return
  busy.value = 'add'
  error.value = ''
  notice.value = ''
  try {
    const r = await api<{ pack: AdminFeaturedPack }>('/v1/admin/featured-packs', { method: 'POST', body: { url } })
    link.value = ''
    notice.value = fill(f.value.added, { title: r.pack.title })
    await load()
  } catch (e) {
    error.value = human(e)
  } finally {
    busy.value = ''
  }
}

/** Fehler an einem Pack; bei veralteter Liste neu laden. */
async function rowError(id: string, e: unknown) {
  rowMsg.value[id] = human(e)
  if (['not_found', 'invalid_order'].includes(apiCode(e))) await load()
}

async function save(p: AdminFeaturedPack) {
  const d = drafts.value[p.id]
  if (!d) return
  busy.value = `s:${p.id}`
  rowMsg.value[p.id] = ''
  const text = (s: string) => s.trim() || null
  try {
    const r = await api<{ pack: AdminFeaturedPack }>(`/v1/admin/featured-packs/${p.id}`, {
      method: 'PATCH',
      body: {
        texts: {
          en: { title: text(d.en.title), text: text(d.en.text) },
          de: { title: text(d.de.title), text: text(d.de.text) },
        },
        badge: d.badge || null,
        startsAt: fromLocal(d.startsAt),
        endsAt: fromLocal(d.endsAt),
      },
    })
    replacePack(r.pack)
    rowMsg.value[p.id] = f.value.saved
  } catch (e) {
    await rowError(p.id, e)
  } finally {
    busy.value = ''
  }
}

async function togglePin(p: AdminFeaturedPack) {
  busy.value = `p:${p.id}`
  rowMsg.value[p.id] = ''
  try {
    await api(`/v1/admin/featured-packs/${p.id}`, { method: 'PATCH', body: { pinned: !p.pinned } })
    await load()
  } catch (e) {
    await rowError(p.id, e)
  } finally {
    busy.value = ''
  }
}

async function refresh(p: AdminFeaturedPack) {
  busy.value = `r:${p.id}`
  rowMsg.value[p.id] = ''
  try {
    const r = await api<{ pack: AdminFeaturedPack }>(`/v1/admin/featured-packs/${p.id}/refresh`, { method: 'POST' })
    const d = drafts.value[p.id]
    const keep = d && dirty(p, d) ? d : null
    replacePack(r.pack)
    if (keep) drafts.value[p.id] = keep
    rowMsg.value[p.id] = f.value.refreshed
  } catch (e) {
    await rowError(p.id, e)
  } finally {
    busy.value = ''
  }
}

async function remove() {
  const p = removing.value
  if (!p) return
  busy.value = `d:${p.id}`
  try {
    await api(`/v1/admin/featured-packs/${p.id}`, { method: 'DELETE' })
    removing.value = null
    await load()
  } catch (e) {
    removing.value = null
    await rowError(p.id, e)
  } finally {
    busy.value = ''
  }
}

// --- Reihenfolge ----------------------------------------------------------------------------------
async function saveOrder(ids: string[], focus?: string) {
  busy.value = 'order'
  error.value = ''
  try {
    const r = await api<{ packs: AdminFeaturedPack[] }>('/v1/admin/featured-packs/order', { method: 'PUT', body: { ids } })
    setPacks(r.packs)
    if (focus) void nextTick(() => document.getElementById(focus)?.focus())
  } catch (e) {
    error.value = human(e)
    if (['not_found', 'invalid_order'].includes(apiCode(e))) await load()
  } finally {
    busy.value = ''
  }
}

function move(id: string, delta: -1 | 1) {
  const ids = packs.value.map((p) => p.id)
  const i = ids.indexOf(id)
  const j = i + delta
  if (i < 0 || j < 0 || j >= ids.length) return
  ;[ids[i], ids[j]] = [ids[j]!, ids[i]!]
  void saveOrder(ids, `fp-${delta > 0 ? 'down' : 'up'}-${id}`)
}

// Drag & Drop (Maus/Stift; Touch und Tastatur nutzen die Pfeil-Knöpfe).
const dragging = ref<string | null>(null)
const dropAt = ref<number | null>(null)
function onDragStart(e: DragEvent, id: string) {
  if (!e.dataTransfer) return
  dragging.value = id
  e.dataTransfer.effectAllowed = 'move'
  e.dataTransfer.setData('text/plain', id)
  const row = (e.target as HTMLElement).closest('.fp-row') as HTMLElement | null
  if (row) e.dataTransfer.setDragImage(row, 24, 24)
}
function onDragEnd() {
  dragging.value = null
  dropAt.value = null
}
function onRowOver(e: DragEvent, index: number) {
  if (!dragging.value) return
  e.preventDefault()
  const r = (e.currentTarget as HTMLElement).getBoundingClientRect()
  dropAt.value = e.clientY > r.top + r.height / 2 ? index + 1 : index
}
function onDrop(e: DragEvent) {
  e.preventDefault()
  const id = dragging.value
  const at = dropAt.value
  onDragEnd()
  if (!id || at === null) return
  const ids = packs.value.map((p) => p.id)
  const from = ids.indexOf(id)
  if (from < 0) return
  ids.splice(from, 1)
  ids.splice(at > from ? at - 1 : at, 0, id)
  if (ids.join() !== packs.value.map((p) => p.id).join()) void saveOrder(ids)
}

// --- Anzeige --------------------------------------------------------------------------------------
function state(p: AdminFeaturedPack): { tone: string, label: string } {
  const now = Date.now()
  if (p.startsAt && new Date(p.startsAt).getTime() > now) return { tone: 'tone-info', label: fill(f.value.notStarted, { date: when(p.startsAt) }) }
  if (p.endsAt && new Date(p.endsAt).getTime() <= now) return { tone: 'tone-muted', label: fill(f.value.expired, { date: when(p.endsAt) }) }
  return { tone: 'tone-ok', label: f.value.active }
}
const LANGS = [
  { id: 'en', label: () => f.value.english },
  { id: 'de', label: () => f.value.german },
] as const
</script>

<template>
  <div class="adm-page">
    <header>
      <h1 class="adm-title">{{ f.title }}</h1>
      <p class="adm-lead">{{ f.lead }}</p>
    </header>

    <form class="card mt-6 p-5" @submit.prevent="add">
      <label class="label" for="fp-link">{{ f.linkLabel }}</label>
      <div class="flex flex-wrap gap-2">
        <input
          id="fp-link"
          v-model="link"
          class="field min-w-0 flex-1"
          type="url"
          inputmode="url"
          maxlength="500"
          autocomplete="off"
          spellcheck="false"
          :placeholder="f.linkPlaceholder"
        />
        <button type="submit" class="btn btn-primary" :disabled="busy === 'add' || !link.trim()">
          <SiteIcon name="plus" class="size-4" />{{ busy === 'add' ? f.adding : f.add }}
        </button>
      </div>
      <p v-if="error" role="alert" class="mt-3 text-sm text-redstone-300">{{ error }}</p>
      <p v-else-if="notice" role="status" class="mt-3 text-sm text-base-300">{{ notice }}</p>
    </form>

    <div v-if="loading" class="skeleton mt-6 h-48 rounded-xl" />
    <p v-else-if="!packs.length" class="adm-empty mt-6"><SiteIcon name="star" class="size-6" />{{ f.empty }}</p>
    <template v-else>
      <p class="mt-6 text-sm text-base-400">{{ f.orderHint }}</p>
      <ol class="mt-3 grid gap-3" @dragover.prevent @drop="onDrop">
        <li
          v-for="(p, i) in packs"
          :key="p.id"
          class="fp-row card p-4"
          :class="{ 'is-dragging': dragging === p.id, 'line-before': dragging && dropAt === i, 'line-after': dragging && dropAt === i + 1 && i === packs.length - 1 }"
          @dragover="onRowOver($event, i)"
          @drop.stop="onDrop"
        >
          <div class="flex flex-wrap items-start gap-3">
            <span
              class="fp-grip"
              draggable="true"
              role="img"
              :aria-label="fill(f.dragLabel, { title: p.title })"
              :title="fill(f.dragLabel, { title: p.title })"
              @dragstart="onDragStart($event, p.id)"
              @dragend="onDragEnd"
            ><SiteIcon name="grip" class="size-4" /></span>
            <img v-if="sameOriginImage(p.iconUrl)" :src="sameOriginImage(p.iconUrl)!" alt="" class="size-14 shrink-0 rounded-lg border border-base-800 bg-base-950 object-cover" loading="lazy" />
            <span v-else class="grid size-14 shrink-0 place-items-center rounded-lg border border-base-800 bg-base-950 text-base-600"><SiteIcon name="blocks" class="size-6" /></span>
            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-center gap-2">
                <h2 class="truncate text-lg font-semibold text-base-50">{{ p.title }}</h2>
                <span class="tone tone-muted">{{ SOURCE_NAMES[p.source] }}</span>
                <span class="tone" :class="state(p).tone">{{ state(p).label }}</span>
                <span v-if="p.pinned" class="tone tone-warn"><SiteIcon name="pin" class="size-3" />{{ f.pinned }}</span>
              </div>
              <p class="mt-1 text-xs text-base-400">
                <span v-if="p.author">{{ fill(f.by, { name: p.author }) }} · </span>{{ fill(f.downloads, { n: num(p.downloads) }) }}
                <span v-if="p.loaders.length"> · {{ p.loaders.map((l) => LOADER_NAMES[l] ?? l).join(', ') }}</span>
                <span v-if="p.gameVersions.length"> · {{ p.gameVersions.slice(0, 3).join(', ') }}</span>
              </p>
              <p class="mt-1 line-clamp-2 text-sm text-base-300">{{ p.summary }}</p>
            </div>
            <div class="flex shrink-0 items-center gap-1">
              <button :id="`fp-up-${p.id}`" type="button" class="btn-icon" :disabled="i === 0 || busy === 'order'" :aria-label="f.moveUp" :title="f.moveUp" @click="move(p.id, -1)">
                <SiteIcon name="up" class="size-4" />
              </button>
              <button :id="`fp-down-${p.id}`" type="button" class="btn-icon" :disabled="i === packs.length - 1 || busy === 'order'" :aria-label="f.moveDown" :title="f.moveDown" @click="move(p.id, 1)">
                <SiteIcon name="chevron" class="size-4" />
              </button>
              <button
                type="button"
                class="btn-icon"
                :class="{ 'fp-pin-on': p.pinned }"
                :aria-pressed="p.pinned"
                :aria-label="f.pin"
                :title="f.pinHint"
                :disabled="busy === `p:${p.id}`"
                @click="togglePin(p)"
              >
                <SiteIcon name="pin" class="size-4" />
              </button>
            </div>
          </div>

          <form v-if="drafts[p.id]" class="mt-4 grid gap-4 border-t border-base-800 pt-4" @submit.prevent="save(p)">
            <div class="grid gap-4 md:grid-cols-2">
              <fieldset v-for="l in LANGS" :key="l.id" class="grid gap-2">
                <legend class="mb-1 text-xs font-semibold tracking-wide text-base-300 uppercase">{{ l.label() }}</legend>
                <label class="label" :for="`fp-${p.id}-${l.id}-title`">{{ f.titleLabel }}</label>
                <input
                  :id="`fp-${p.id}-${l.id}-title`"
                  v-model="drafts[p.id]![l.id].title"
                  class="field"
                  maxlength="60"
                  :placeholder="fill(f.titlePlaceholder, { title: p.title })"
                />
                <label class="label" :for="`fp-${p.id}-${l.id}-text`">{{ f.textLabel }}</label>
                <textarea
                  :id="`fp-${p.id}-${l.id}-text`"
                  v-model="drafts[p.id]![l.id].text"
                  class="field min-h-20"
                  rows="2"
                  maxlength="200"
                  :placeholder="f.textPlaceholder"
                  @keydown.enter.prevent
                />
              </fieldset>
            </div>
            <div class="grid gap-4 sm:grid-cols-3">
              <div>
                <label class="label" :for="`fp-${p.id}-badge`">{{ f.badge }}</label>
                <select :id="`fp-${p.id}-badge`" v-model="drafts[p.id]!.badge" class="field">
                  <option value="">{{ f.badges.none }}</option>
                  <option v-for="b in FEATURED_BADGES" :key="b" :value="b">{{ f.badges[b] }}</option>
                </select>
              </div>
              <div>
                <label class="label" :for="`fp-${p.id}-from`">{{ f.window }} · {{ f.from }}</label>
                <input :id="`fp-${p.id}-from`" v-model="drafts[p.id]!.startsAt" type="datetime-local" class="field" />
              </div>
              <div>
                <label class="label" :for="`fp-${p.id}-to`">{{ f.to }}</label>
                <input :id="`fp-${p.id}-to`" v-model="drafts[p.id]!.endsAt" type="datetime-local" class="field" />
              </div>
            </div>
            <div class="flex flex-wrap items-center gap-2">
              <button type="submit" class="btn btn-primary" :disabled="busy === `s:${p.id}` || !dirty(p)">{{ f.save }}</button>
              <button type="button" class="btn btn-ghost" :disabled="busy === `r:${p.id}`" @click="refresh(p)">
                <SiteIcon name="refresh" class="size-4" />{{ f.refresh }}
              </button>
              <a :href="p.url" target="_blank" rel="noopener noreferrer" class="btn btn-ghost">
                {{ fill(f.view, { platform: SOURCE_NAMES[p.source] }) }} <SiteIcon name="external" class="size-4" />
              </a>
              <a v-if="p.active" href="/#featured-packs" target="_blank" rel="noopener" class="btn btn-ghost">{{ f.preview }}</a>
              <button type="button" class="btn btn-ghost ml-auto text-redstone-300" :disabled="busy === `d:${p.id}`" @click="removing = p">
                <SiteIcon name="trash" class="size-4" />{{ f.delete }}
              </button>
            </div>
            <p class="text-xs text-base-500">
              <span v-if="dirty(p)" class="text-lamp-300">{{ f.unsaved }} · </span>{{ fill(f.refreshedAt, { date: when(p.refreshedAt) }) }}
            </p>
            <p v-if="rowMsg[p.id]" role="status" class="text-sm text-base-300">{{ rowMsg[p.id] }}</p>
          </form>
        </li>
      </ol>
    </template>

    <AdminConfirm
      v-if="removing"
      :title="f.delete"
      :text="fill(f.confirmDelete, { title: removing.title })"
      danger
      :busy="busy === `d:${removing.id}`"
      @cancel="removing = null"
      @confirm="remove"
    />
  </div>
</template>

<style scoped>
.fp-row {
  position: relative;
  transition: opacity 0.15s;
}
.fp-row.is-dragging {
  opacity: 0.4;
}
.fp-row.line-before::before,
.fp-row.line-after::after {
  content: '';
  position: absolute;
  left: 0.5rem;
  right: 0.5rem;
  height: 3px;
  border-radius: 999px;
  background: var(--color-redstone-500);
  box-shadow: 0 0 8px var(--color-redstone-500);
}
.fp-row.line-before::before {
  top: -0.45rem;
}
.fp-row.line-after::after {
  bottom: -0.45rem;
}
.fp-grip {
  display: grid;
  place-items: center;
  width: 1.5rem;
  height: 3.5rem;
  margin-left: -0.35rem;
  border-radius: 0.35rem;
  color: var(--color-base-600);
  cursor: grab;
}
.fp-grip:hover {
  color: var(--color-base-200);
  background: var(--color-base-800);
}
.btn-icon.fp-pin-on {
  background: var(--color-redstone-600);
  color: var(--color-base-50);
}
.fp-grip:active {
  cursor: grabbing;
}
</style>
