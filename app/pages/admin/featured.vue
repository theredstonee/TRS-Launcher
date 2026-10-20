<script setup lang="ts">
import {
  FEATURED_TEXT_MAX,
  FEATURED_TITLE_MAX,
  featuredBadgeKeys,
  featuredBadges,
  featuredMove,
  featuredStatus,
  featuredVersion,
  isoToLocalInput,
  localInputToIso,
  looksLikePackLink,
  type AdminFeaturedPack,
  type FeaturedBadge,
  type FeaturedStatus,
} from '~/utils/featured'

// Team-Bereich „Empfohlene Modpacks“ (Recht featured.manage): Modrinth-/CurseForge-Link einfügen, je Eintrag
// eigene Texte (EN/DE), Abzeichen, Anheften und Zeitraum, Reihenfolge per Ziehen (oder Pfeile/Alt+↑↓), neu laden
// und entfernen. Die Liste liegt auf der TRS API – Änderungen sind sofort auf allen Startseiten (ohne Update).
// Der Server prüft das Recht selbst – hier nur die Oberfläche.
const toasts = useToasts()
const team = useTeam()
const router = useRouter()
const curseforge = useCurseForgeStore()

const packs = ref<AdminFeaturedPack[] | null>(null)
const link = ref('')
const linkError = ref<string | null>(null)
const busy = ref<string | null>(null)
const open = ref(new Set<string>())
const removing = ref<AdminFeaturedPack | null>(null)
const canManage = computed(() => team.can('featured.manage'))

interface Draft {
  enTitle: string
  enText: string
  deTitle: string
  deText: string
  badge: FeaturedBadge | ''
  from: string
  to: string
}
const drafts = reactive<Record<string, Draft>>({})

function draftOf(p: AdminFeaturedPack): Draft {
  return {
    enTitle: p.texts.en.title ?? '',
    enText: p.texts.en.text ?? '',
    deTitle: p.texts.de.title ?? '',
    deText: p.texts.de.text ?? '',
    badge: p.badge ?? '',
    from: isoToLocalInput(p.startsAt),
    to: isoToLocalInput(p.endsAt),
  }
}
function syncDraft(p: AdminFeaturedPack) {
  drafts[p.id] = draftOf(p)
}
function dirty(p: AdminFeaturedPack): boolean {
  const d = drafts[p.id]
  return !!d && JSON.stringify(d) !== JSON.stringify(draftOf(p))
}
function windowError(d: Draft): boolean {
  const from = localInputToIso(d.from)
  const to = localInputToIso(d.to)
  return !!from && !!to && Date.parse(from) >= Date.parse(to)
}
function tooLong(d: Draft): boolean {
  return [d.enTitle, d.deTitle].some((s) => s.trim().length > FEATURED_TITLE_MAX) || [d.enText, d.deText].some((s) => s.trim().length > FEATURED_TEXT_MAX)
}

/** Neue Liste vom Server; Entwürfe mit ungespeicherten Änderungen bleiben stehen. */
function setList(list: AdminFeaturedPack[]) {
  const before = new Map((packs.value ?? []).map((p) => [p.id, p]))
  packs.value = list
  for (const p of list) {
    const old = before.get(p.id)
    if (!drafts[p.id] || !old || !dirty(old)) syncDraft(p)
  }
}
/** Einen Eintrag ersetzen (Antwort von Ändern/Neu laden), Entwurf auf den neuen Stand. */
function replace(p: AdminFeaturedPack) {
  if (!packs.value) return
  packs.value = packs.value.map((x) => (x.id === p.id ? p : x))
  syncDraft(p)
}

async function load() {
  try {
    setList(await backend.trs.adminFeaturedPacks())
  } catch (e) {
    packs.value = []
    toasts.error(e)
  }
}
onMounted(() => {
  void load()
  void curseforge.load()
})

const statusClass: Record<FeaturedStatus, string> = {
  active: 'bg-ok/15 text-ok',
  scheduled: 'bg-lamp-900 text-lamp-300',
  expired: 'bg-base-800 text-base-400',
}
const statusKey: Record<FeaturedStatus, 'admin.featured.status.active' | 'admin.featured.status.scheduled' | 'admin.featured.status.expired'> = {
  active: 'admin.featured.status.active',
  scheduled: 'admin.featured.status.scheduled',
  expired: 'admin.featured.status.expired',
}
function statusOf(p: AdminFeaturedPack): FeaturedStatus {
  const byTime = featuredStatus(p)
  // Die API weiß es genauer (z. B. mehr als 20 aktive) – „aktiv“ nur, wenn sie zustimmt.
  return byTime === 'active' && !p.active ? 'expired' : byTime
}

// --- Hinzufügen ------------------------------------------------------------------------------

async function add() {
  if (!canManage.value || busy.value) return
  if (!looksLikePackLink(link.value)) {
    linkError.value = t('admin.featured.invalidLink')
    return
  }
  linkError.value = null
  busy.value = 'add'
  try {
    const pack = await backend.trs.adminAddFeaturedPack(link.value.trim())
    link.value = ''
    syncDraft(pack)
    packs.value = [...(packs.value ?? []).filter((p) => p.id !== pack.id), pack]
    open.value = new Set(open.value).add(pack.id)
    toasts.ok(t('admin.featured.added', { title: pack.title }))
  } catch (e) {
    linkError.value = errorMessage(e)
  } finally {
    busy.value = null
  }
}

// --- Ändern ----------------------------------------------------------------------------------

async function save(p: AdminFeaturedPack) {
  const d = drafts[p.id]
  if (!d || !canManage.value || busy.value) return
  if (windowError(d) || tooLong(d)) return
  busy.value = p.id
  const text = (s: string) => s.trim() || null
  try {
    replace(
      await backend.trs.adminUpdateFeaturedPack(p.id, {
        texts: { en: { title: text(d.enTitle), text: text(d.enText) }, de: { title: text(d.deTitle), text: text(d.deText) } },
        badge: d.badge || null,
        startsAt: localInputToIso(d.from),
        endsAt: localInputToIso(d.to),
      }),
    )
    toasts.ok(t('admin.featured.saved'))
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

async function togglePin(p: AdminFeaturedPack) {
  if (!canManage.value || busy.value) return
  busy.value = p.id
  try {
    await backend.trs.adminUpdateFeaturedPack(p.id, { pinned: !p.pinned })
    // Angeheftete stehen vorn – die Reihenfolge kommt vom Server (offene Entwürfe bleiben).
    setList(await backend.trs.adminFeaturedPacks())
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

async function refresh(p: AdminFeaturedPack) {
  if (!canManage.value || busy.value) return
  busy.value = p.id
  try {
    replace(await backend.trs.adminRefreshFeaturedPack(p.id))
    toasts.ok(t('admin.featured.refreshed'))
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

async function remove() {
  const p = removing.value
  if (!p || busy.value) return
  busy.value = p.id
  try {
    await backend.trs.adminDeleteFeaturedPack(p.id)
    packs.value = (packs.value ?? []).filter((x) => x.id !== p.id)
    removing.value = null
    toasts.ok(t('admin.featured.removed', { title: p.title }))
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

function toggleOpen(id: string) {
  const next = new Set(open.value)
  if (!next.delete(id)) next.add(id)
  open.value = next
}

function openProject(p: AdminFeaturedPack) {
  if (p.source === 'curseforge' && curseforge.available === false) {
    void backend.openExternalUrl(p.url).catch((e) => toasts.error(e))
    return
  }
  void router.push(projectRoute(p.source, p.projectId))
}

// --- Reihenfolge -----------------------------------------------------------------------------

async function move(from: number, to: number) {
  const list = packs.value
  if (!list || !canManage.value || busy.value || from === to || to < 0 || to >= list.length) return
  const before = list
  const next = featuredMove(list, from, to)
  packs.value = next
  busy.value = 'order'
  try {
    setList(await backend.trs.adminOrderFeaturedPacks(next.map((p) => p.id)))
    toasts.ok(t('admin.featured.orderSaved'))
  } catch (e) {
    packs.value = before
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

const dragFrom = ref<number | null>(null)
const dropAt = ref<number | null>(null)
function onDrop(to: number) {
  const from = dragFrom.value
  dragFrom.value = null
  dropAt.value = null
  if (from !== null && from !== to) void move(from, to)
}
function onHandleKey(e: KeyboardEvent, index: number) {
  if (!e.altKey || (e.key !== 'ArrowUp' && e.key !== 'ArrowDown')) return
  e.preventDefault()
  void move(index, index + (e.key === 'ArrowUp' ? -1 : 1))
}

const failedIcons = ref(new Set<string>())
</script>

<template>
  <section class="space-y-4" :aria-label="t('team.nav.featured')" data-testid="admin-featured">
    <p class="text-sm text-base-200">{{ t('admin.featured.intro') }}</p>

    <form v-if="canManage" class="card flex flex-wrap items-end gap-2 p-4" @submit.prevent="add">
      <label class="min-w-64 flex-1">
        <span class="label">{{ t('admin.featured.linkLabel') }}</span>
        <input
          v-model="link"
          class="field"
          type="url"
          maxlength="512"
          autocomplete="off"
          spellcheck="false"
          placeholder="https://modrinth.com/modpack/…"
          :aria-invalid="linkError ? 'true' : undefined"
          @input="linkError = null"
        />
      </label>
      <button class="btn btn-primary px-4 py-2" :disabled="busy === 'add' || !link.trim()">
        {{ busy === 'add' ? t('admin.featured.adding') : t('admin.featured.add') }}
      </button>
      <p v-if="linkError" role="alert" class="w-full text-xs text-redstone-300">{{ linkError }}</p>
    </form>

    <div v-if="!packs" class="skeleton h-40" />
    <RedstoneEmpty v-else-if="!packs.length" :title="t('admin.featured.empty')" compact :seed="0xfea7" />
    <template v-else>
      <p class="text-xs text-base-400">{{ t('admin.featured.orderHint') }}</p>
      <ol class="space-y-2">
        <li
          v-for="(p, i) in packs"
          :key="p.id"
          class="card relative"
          :class="{ 'opacity-50': dragFrom === i }"
          :data-featured="p.id"
          @dragover.prevent="dropAt = i"
          @drop.prevent="onDrop(i)"
        >
          <span v-if="dropAt === i && dragFrom !== null && dragFrom !== i" class="absolute inset-x-3 -top-1.5 h-0.5 rounded bg-redstone-400" aria-hidden="true" />
          <header class="flex flex-wrap items-center gap-3 px-3 py-2.5">
            <button
              v-if="canManage"
              type="button"
              class="handle"
              draggable="true"
              :title="t('admin.featured.dragHint')"
              :aria-label="t('admin.featured.dragLabel', { title: p.title, n: i + 1, total: packs.length })"
              @dragstart="dragFrom = i"
              @dragend="dragFrom = null; dropAt = null"
              @keydown="onHandleKey($event, i)"
            >
              <svg viewBox="0 0 24 24" class="size-4" fill="currentColor" aria-hidden="true"><path d="M9 5h2v2H9zM13 5h2v2h-2zM9 11h2v2H9zM13 11h2v2h-2zM9 17h2v2H9zM13 17h2v2h-2z" /></svg>
            </button>
            <span class="w-5 text-center text-xs tabular-nums text-base-400">{{ i + 1 }}</span>
            <span class="relative block size-10 shrink-0 overflow-hidden rounded-lg bg-base-800">
              <img
                v-if="p.iconUrl && !failedIcons.has(p.iconUrl)"
                :src="p.iconUrl"
                alt=""
                loading="lazy"
                class="size-full object-cover"
                @error="failedIcons = new Set(failedIcons).add(p.iconUrl!)"
              />
              <PixelIdenticon v-else :seed="p.title" color="var(--color-base-400)" />
            </span>
            <div class="min-w-0 flex-1">
              <p class="flex items-center gap-2 truncate text-sm font-semibold text-base-50">
                <svg viewBox="0 0 24 24" class="size-3.5 shrink-0" aria-hidden="true"><path :fill="platformMarks[p.source].color" :d="platformMarks[p.source].d" /></svg>
                <span class="truncate">{{ p.title }}</span>
              </p>
              <p class="truncate text-xs text-base-400">
                {{ t(`browse.source.${p.source}`) }}
                <template v-if="featuredVersion(p)"> · {{ featuredVersion(p) }}</template>
                <template v-if="p.author"> · {{ t('featured.by', { author: p.author }) }}</template>
                · {{ t('featured.downloads', { count: formatCount(p.downloads) }) }}
              </p>
            </div>
            <span class="badge" :class="statusClass[statusOf(p)]">{{ t(statusKey[statusOf(p)]) }}</span>
            <span v-if="p.pinned" class="badge bg-redstone-900/60 text-redstone-300">{{ t('admin.featured.pinned') }}</span>
            <span v-if="p.badge" class="badge bg-base-800 text-base-200">{{ t(featuredBadgeKeys[p.badge]) }}</span>
            <div class="flex items-center gap-1">
              <template v-if="canManage">
                <button type="button" class="btn-icon size-8" :disabled="i === 0 || !!busy" :title="t('admin.featured.moveUp')" :aria-label="t('admin.featured.moveUp')" @click="move(i, i - 1)">
                  <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="m6 15 6-6 6 6" /></svg>
                </button>
                <button type="button" class="btn-icon size-8" :disabled="i === packs.length - 1 || !!busy" :title="t('admin.featured.moveDown')" :aria-label="t('admin.featured.moveDown')" @click="move(i, i + 1)">
                  <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
                </button>
                <button
                  type="button"
                  class="btn-icon size-8"
                  :class="{ 'text-redstone-300': p.pinned }"
                  :disabled="!!busy"
                  :aria-pressed="p.pinned"
                  :title="p.pinned ? t('admin.featured.unpin') : t('admin.featured.pin')"
                  :aria-label="p.pinned ? t('admin.featured.unpin') : t('admin.featured.pin')"
                  @click="togglePin(p)"
                >
                  <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="icons.pin" /></svg>
                </button>
              </template>
              <button type="button" class="btn-icon size-8" :title="t('admin.featured.openProject')" :aria-label="t('admin.featured.openProject')" @click="openProject(p)">
                <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="icons.external" /></svg>
              </button>
              <button
                v-if="canManage"
                type="button"
                class="btn btn-ghost px-2.5 py-1 text-xs"
                :aria-expanded="open.has(p.id)"
                :aria-controls="`featured-edit-${p.id}`"
                @click="toggleOpen(p.id)"
              >
                {{ open.has(p.id) ? t('admin.featured.close') : t('admin.featured.edit') }}
                <span v-if="dirty(p)" class="size-1.5 rounded-full bg-lamp-400" :title="t('admin.featured.unsaved')" />
              </button>
            </div>
          </header>

          <form v-if="canManage && open.has(p.id) && drafts[p.id]" :id="`featured-edit-${p.id}`" class="space-y-4 border-t border-base-800 p-4" @submit.prevent="save(p)">
            <div class="grid gap-4 lg:grid-cols-2">
              <fieldset v-for="lang in (['en', 'de'] as const)" :key="lang" class="space-y-2">
                <legend class="label">{{ lang === 'en' ? t('admin.featured.textsEn') : t('admin.featured.textsDe') }}</legend>
                <label class="block">
                  <span class="mb-1 flex justify-between text-[11px] text-base-400">
                    {{ t('admin.featured.titleLabel') }}
                    <span class="tabular-nums" :class="{ 'text-redstone-300': drafts[p.id]![lang === 'en' ? 'enTitle' : 'deTitle'].trim().length > FEATURED_TITLE_MAX }">
                      {{ t('admin.featured.count', { n: drafts[p.id]![lang === 'en' ? 'enTitle' : 'deTitle'].trim().length, max: FEATURED_TITLE_MAX }) }}
                    </span>
                  </span>
                  <input
                    v-model="drafts[p.id]![lang === 'en' ? 'enTitle' : 'deTitle']"
                    class="field"
                    :maxlength="FEATURED_TITLE_MAX + 20"
                    :placeholder="lang === 'en' ? p.title : t('admin.featured.titleFallbackDe')"
                  />
                </label>
                <label class="block">
                  <span class="mb-1 flex justify-between text-[11px] text-base-400">
                    {{ t('admin.featured.textLabel') }}
                    <span class="tabular-nums" :class="{ 'text-redstone-300': drafts[p.id]![lang === 'en' ? 'enText' : 'deText'].trim().length > FEATURED_TEXT_MAX }">
                      {{ t('admin.featured.count', { n: drafts[p.id]![lang === 'en' ? 'enText' : 'deText'].trim().length, max: FEATURED_TEXT_MAX }) }}
                    </span>
                  </span>
                  <textarea
                    v-model="drafts[p.id]![lang === 'en' ? 'enText' : 'deText']"
                    class="field min-h-20 resize-y"
                    rows="3"
                    :maxlength="FEATURED_TEXT_MAX + 50"
                    :placeholder="lang === 'en' ? p.summary : t('admin.featured.textFallbackDe')"
                  />
                </label>
              </fieldset>
            </div>

            <div class="grid gap-3 sm:grid-cols-3">
              <label class="block">
                <span class="label">{{ t('admin.featured.badge') }}</span>
                <select v-model="drafts[p.id]!.badge" class="field">
                  <option value="">{{ t('admin.featured.badgeNone') }}</option>
                  <option v-for="b in featuredBadges" :key="b" :value="b">{{ t(featuredBadgeKeys[b]) }}</option>
                </select>
              </label>
              <label class="block">
                <span class="label">{{ t('admin.featured.from') }}</span>
                <input v-model="drafts[p.id]!.from" type="datetime-local" class="field" />
              </label>
              <label class="block">
                <span class="label">{{ t('admin.featured.to') }}</span>
                <input v-model="drafts[p.id]!.to" type="datetime-local" class="field" />
              </label>
            </div>
            <p class="text-xs" :class="windowError(drafts[p.id]!) ? 'text-redstone-300' : 'text-base-400'" :role="windowError(drafts[p.id]!) ? 'alert' : undefined">
              {{ windowError(drafts[p.id]!) ? t('admin.featured.invalidWindow') : t('admin.featured.windowHint') }}
            </p>

            <div class="flex flex-wrap items-center gap-2">
              <button class="btn btn-primary px-4 py-2" :disabled="busy === p.id || !dirty(p) || windowError(drafts[p.id]!) || tooLong(drafts[p.id]!)">
                {{ busy === p.id ? t('admin.featured.saving') : t('admin.featured.save') }}
              </button>
              <button type="button" class="btn btn-ghost px-3 py-2 text-xs" :disabled="!dirty(p)" @click="syncDraft(p)">{{ t('admin.featured.reset') }}</button>
              <span class="flex-1" />
              <span class="text-[11px] text-base-400">
                <template v-if="p.createdBy">{{ t('admin.featured.createdBy', { name: p.createdBy }) }}</template>
                <template v-if="p.refreshedAt"> · {{ t('admin.featured.refreshedAt', { date: formatShortDate(p.refreshedAt) }) }}</template>
              </span>
              <button type="button" class="btn btn-ghost px-3 py-2 text-xs" :disabled="!!busy" @click="refresh(p)">{{ t('admin.featured.refresh') }}</button>
              <button type="button" class="btn btn-ghost px-3 py-2 text-xs hover:text-redstone-300" :disabled="!!busy" @click="removing = p">{{ t('admin.featured.remove') }}</button>
            </div>
          </form>
        </li>
      </ol>
    </template>

    <BaseDialog v-if="removing" :title="t('admin.featured.removeTitle')" @close="removing = null">
      <i18n-t keypath="admin.featured.removeText" tag="p" scope="global" class="text-sm text-base-200">
        <template #title><strong class="text-base-50">{{ removing.title }}</strong></template>
      </i18n-t>
      <template #actions>
        <button class="btn btn-ghost" @click="removing = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" :disabled="!!busy" @click="remove">{{ t('admin.featured.remove') }}</button>
      </template>
    </BaseDialog>
  </section>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.handle {
  @apply grid size-8 shrink-0 cursor-grab place-items-center rounded-md text-base-400 hover:bg-base-800 hover:text-base-50 active:cursor-grabbing;
}
</style>
