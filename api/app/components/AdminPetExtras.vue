<script setup lang="ts">
// Begleiter-Gefolge (§35.13) im Reiter „Haustiere“ von /admin/creators: je Creator bis zu 4 zusätzliche Begleiter
// (Platz `pet`) hinzufügen, entfernen und sortieren. Nur der Creator selbst zeigt sie (Schalter „Gefolge zeigen“ im
// Dashboard). Eigene Datei + eigene Texte, damit die große Creator-Seite unberührt bleibt. Der Server prüft alles.
const { a, fill, lang } = useAdminText()
const { api } = useAdmin()

type Tier = 'starter' | 'partner' | 'top'
type State = 'active' | 'revoked' | 'banned'
interface Extra { cosmeticId: string, name: string, card: string | null, petScale: number, available: boolean }
interface Entry {
  creator: { uuid: string, name: string, tier: Tier, state: State }
  extras: Extra[]
  showExtras: boolean
  max: number
  updatedAt: string
  updatedBy: { uuid: string, name: string }
}
interface PetOption { id: string, name: string, unlock: string, hidden: boolean, card: string | null, available: boolean, owner: { uuid: string, name: string } | null }
interface CreatorHit { uuid: string, name: string, tier: Tier, mainCode: string | null }

const TEXTS = {
  en: {
    title: 'Extra companions',
    lead: 'Up to 4 more companions that walk with a creator in addition to their pet – only for the creator themself (no codes, no fans). The creator can hide them in the dashboard.',
    none: 'No creator has extra companions yet.',
    edit: 'Edit',
    hidden: 'Hidden by the creator',
    editor: 'Assign extra companions',
    search: 'Name, UUID or code …',
    noMatch: 'No active creator found.',
    pick: 'Add companion …',
    empty: 'No extra companions – add up to {max}.',
    full: 'Maximum of {max} reached.',
    up: 'Move up',
    down: 'Move down',
    remove: 'Remove',
    save: 'Save companions',
    saved: 'Extra companions saved.',
    unsaved: 'Unsaved changes',
    unavailable: 'not loaded',
    owner: 'pet of {name}',
    team: 'team only',
    code: 'by code',
    free: 'free',
    scale: 'size ×{n}',
  },
  de: {
    title: 'Zusätzliche Begleiter',
    lead: 'Bis zu 4 weitere Begleiter, die zusätzlich zum Haustier mit einem Creator laufen – nur beim Creator selbst (keine Codes, keine Fans). Der Creator kann sie im Dashboard ausblenden.',
    none: 'Noch kein Creator hat zusätzliche Begleiter.',
    edit: 'Bearbeiten',
    hidden: 'Vom Creator ausgeblendet',
    editor: 'Begleiter zuordnen',
    search: 'Name, UUID oder Code …',
    noMatch: 'Kein aktiver Creator gefunden.',
    pick: 'Begleiter hinzufügen …',
    empty: 'Keine zusätzlichen Begleiter – bis zu {max} möglich.',
    full: 'Höchstens {max} erreicht.',
    up: 'Nach oben',
    down: 'Nach unten',
    remove: 'Entfernen',
    save: 'Begleiter speichern',
    saved: 'Zusätzliche Begleiter gespeichert.',
    unsaved: 'Nicht gespeichert',
    unavailable: 'nicht geladen',
    owner: 'Haustier von {name}',
    team: 'nur Team',
    code: 'per Code',
    free: 'frei',
    scale: 'Größe ×{n}',
  },
  es: {
    title: 'Acompañantes extra',
    lead: 'Hasta 4 acompañantes más que caminan con un creador además de su mascota – solo para el propio creador (sin códigos, sin fans). El creador puede ocultarlos en el panel.',
    none: 'Ningún creador tiene acompañantes extra todavía.',
    edit: 'Editar',
    hidden: 'Ocultos por el creador',
    editor: 'Asignar acompañantes',
    search: 'Nombre, UUID o código …',
    noMatch: 'No se encontró ningún creador activo.',
    pick: 'Añadir acompañante …',
    empty: 'Sin acompañantes extra – hasta {max}.',
    full: 'Máximo de {max} alcanzado.',
    up: 'Subir',
    down: 'Bajar',
    remove: 'Quitar',
    save: 'Guardar acompañantes',
    saved: 'Acompañantes extra guardados.',
    unsaved: 'Cambios sin guardar',
    unavailable: 'no cargado',
    owner: 'mascota de {name}',
    team: 'solo equipo',
    code: 'con código',
    free: 'gratis',
    scale: 'tamaño ×{n}',
  },
} as const
const x = computed(() => TEXTS[lang.value as keyof typeof TEXTS] ?? TEXTS.en)
const tiers = computed(() => a.value.creators.roster.tiers as Record<Tier, string>)

const entries = ref<Entry[]>([])
const options = ref<PetOption[]>([])
const max = ref(4)
const error = ref('')
const notice = ref('')
const busy = ref(false)
const fail = (e: unknown) => (error.value = fill(a.value.common.failed, { error: apiMessage(e) }))

function localUrl(url: string): string {
  try {
    const u = new URL(url, location.origin)
    return u.pathname + u.search
  } catch {
    return url
  }
}

async function load() {
  const o = await api<{ creators: Entry[], cosmetics: PetOption[], max: number }>('/v1/admin/creators/pet-extras')
  entries.value = o.creators
  options.value = o.cosmetics
  max.value = o.max
}
onMounted(() => load().catch(fail))

// --- Editor: Creator suchen, Liste bearbeiten, speichern ---------------------------------------------
const query = ref('')
const hits = ref<CreatorHit[]>([])
const target = ref<CreatorHit | null>(null)
const draft = ref<string[]>([])
const saved = ref<string[]>([])
const pick = ref('')
let timer: ReturnType<typeof setTimeout> | null = null
watch(query, (v) => {
  if (timer) clearTimeout(timer)
  if (target.value && v === target.value.name) return
  target.value = null
  if (!v.trim()) {
    hits.value = []
    return
  }
  timer = setTimeout(async () => {
    try {
      hits.value = (await api<{ creators: CreatorHit[] }>(`/v1/admin/creators?q=${encodeURIComponent(v.trim().slice(0, 32))}&state=active`)).creators.slice(0, 8)
    } catch (e) {
      fail(e)
    }
  }, 250)
})
function choose(c: CreatorHit) {
  target.value = c
  query.value = c.name
  hits.value = []
  const ids = entries.value.find((e) => e.creator.uuid === c.uuid)?.extras.map((e) => e.cosmeticId) ?? []
  draft.value = [...ids]
  saved.value = [...ids]
  notice.value = ''
}
function editEntry(e: Entry) {
  choose({ uuid: e.creator.uuid, name: e.creator.name, tier: e.creator.tier, mainCode: null })
}
const option = (id: string) => options.value.find((o) => o.id === id)
const known = (id: string): Extra => {
  const o = option(id)
  const fromList = entries.value.flatMap((e) => e.extras).find((e) => e.cosmeticId === id)
  return { cosmeticId: id, name: o?.name ?? fromList?.name ?? id, card: o?.card ?? fromList?.card ?? null, petScale: fromList?.petScale ?? 1, available: o?.available ?? fromList?.available ?? false }
}
const addable = computed(() => options.value.filter((o) => !draft.value.includes(o.id)))
const dirty = computed(() => draft.value.join(',') !== saved.value.join(','))
watch(pick, (id) => {
  if (!id) return
  if (draft.value.length < max.value && !draft.value.includes(id)) draft.value = [...draft.value, id]
  pick.value = ''
})
function move(i: number, d: -1 | 1) {
  const j = i + d
  if (j < 0 || j >= draft.value.length) return
  const next = [...draft.value]
  ;[next[i], next[j]] = [next[j]!, next[i]!]
  draft.value = next
}
function drop(i: number) {
  draft.value = draft.value.filter((_, k) => k !== i)
}
async function save() {
  const c = target.value
  if (!c) return
  busy.value = true
  error.value = ''
  try {
    const r = await api<{ extras: Extra[] }>(`/v1/admin/creators/${c.uuid}/pet-extras`, { method: 'PUT', body: { cosmeticIds: draft.value } })
    saved.value = r.extras.map((e) => e.cosmeticId)
    draft.value = [...saved.value]
    notice.value = x.value.saved
    await load()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = false
  }
}
const scaleText = (n: number) => fill(x.value.scale, { n: String(n).replace('.', lang.value === 'en' ? '.' : ',') })
</script>

<template>
  <section class="mt-8" aria-labelledby="pet-extras-title">
    <h2 id="pet-extras-title" class="section-title">{{ x.title }}</h2>
    <p class="mt-1 max-w-3xl text-xs text-base-400">{{ x.lead }}</p>
    <p v-if="error" class="mt-3 text-sm text-redstone-300" role="alert">{{ error }}</p>
    <p v-if="notice" class="mt-3 text-sm text-ok" role="status">{{ notice }}</p>

    <div class="mt-3 grid gap-4 2xl:grid-cols-[1fr_22rem]">
      <div class="min-w-0">
        <p v-if="!entries.length" class="adm-empty"><SiteIcon name="star" class="size-6" />{{ x.none }}</p>
        <ul v-else class="space-y-2">
          <li v-for="e in entries" :key="e.creator.uuid" class="card flex flex-wrap items-center gap-3 p-4">
            <div class="flex min-w-0 flex-1 items-center gap-2">
              <PlayerHead :uuid="e.creator.uuid" :name="e.creator.name" :size="20" />
              <span class="truncate font-semibold text-base-50">{{ e.creator.name }}</span>
              <span class="tone tone-info">{{ tiers[e.creator.tier] }}</span>
              <span v-if="!e.showExtras" class="tone tone-muted">{{ x.hidden }}</span>
              <span v-if="e.creator.state !== 'active'" class="tone tone-danger">{{ a.creators.roster.states[e.creator.state] }}</span>
            </div>
            <ol class="flex items-center gap-1.5">
              <li v-for="(p, i) in e.extras" :key="p.cosmeticId" class="extra-mini" :title="`${i + 1}. ${p.name}${p.petScale !== 1 ? ` · ${scaleText(p.petScale)}` : ''}`">
                <img v-if="p.card" :src="localUrl(p.card)" :alt="p.name" loading="lazy" :class="{ 'opacity-40': !p.available }" />
                <SiteIcon v-else name="star" class="size-4 text-base-500" />
              </li>
            </ol>
            <button type="button" class="btn btn-ghost" @click="editEntry(e)"><SiteIcon name="pencil" class="size-4" />{{ x.edit }}</button>
          </li>
        </ul>
      </div>

      <form class="card h-fit p-5" @submit.prevent="save">
        <h3 class="font-semibold text-base-50">{{ x.editor }}</h3>
        <label class="label mt-3" for="px-creator">{{ a.creators.tabs.roster }}</label>
        <div class="relative">
          <input id="px-creator" v-model="query" class="field" maxlength="32" autocomplete="off" spellcheck="false" :placeholder="x.search" />
          <ul v-if="hits.length" class="extra-hits" role="listbox">
            <li v-for="c in hits" :key="c.uuid">
              <button type="button" role="option" :aria-selected="false" @click="choose(c)">
                <PlayerHead :uuid="c.uuid" :name="c.name" :size="20" />
                <span class="flex-1 truncate text-left">{{ c.name }}</span>
                <span class="text-xs text-base-400">{{ tiers[c.tier] }}</span>
              </button>
            </li>
          </ul>
          <p v-else-if="query.trim() && !target" class="mt-1 text-xs text-base-500">{{ x.noMatch }}</p>
        </div>

        <template v-if="target">
          <p class="mt-2 flex items-center gap-2 text-sm text-ok"><SiteIcon name="check" class="size-4" />{{ target.name }} · {{ tiers[target.tier] }}</p>
          <p v-if="!draft.length" class="mt-3 text-xs text-base-500">{{ fill(x.empty, { max: String(max) }) }}</p>
          <ol v-else class="mt-3 space-y-1.5">
            <li v-for="(id, i) in draft" :key="id" class="extra-row">
              <span class="w-4 text-center text-xs text-base-500">{{ i + 1 }}</span>
              <span class="extra-thumb grid place-items-center">
                <img v-if="known(id).card" :src="localUrl(known(id).card!)" alt="" />
                <SiteIcon v-else name="star" class="size-4 text-base-500" />
              </span>
              <span class="min-w-0 flex-1">
                <span class="block truncate text-sm text-base-100">{{ known(id).name }}</span>
                <span class="block text-[11px] text-base-500">
                  <template v-if="known(id).petScale !== 1">{{ scaleText(known(id).petScale) }} · </template>
                  <template v-if="option(id)?.owner">{{ fill(x.owner, { name: option(id)!.owner!.name }) }}</template>
                  <template v-else>{{ option(id)?.unlock === 'code' ? x.code : option(id)?.unlock === 'free' ? x.free : x.team }}</template>
                  <template v-if="!known(id).available"> · <span class="text-redstone-300">{{ x.unavailable }}</span></template>
                </span>
              </span>
              <button type="button" class="extra-btn" :disabled="i === 0" :aria-label="x.up" :title="x.up" @click="move(i, -1)"><SiteIcon name="up" class="size-4" /></button>
              <button type="button" class="extra-btn" :disabled="i === draft.length - 1" :aria-label="x.down" :title="x.down" @click="move(i, 1)"><SiteIcon name="up" class="size-4 rotate-180" /></button>
              <button type="button" class="extra-btn text-redstone-300" :aria-label="x.remove" :title="x.remove" @click="drop(i)"><SiteIcon name="trash" class="size-4" /></button>
            </li>
          </ol>
          <select v-model="pick" class="field mt-3" :disabled="draft.length >= max" :aria-label="x.pick">
            <option value="">{{ draft.length >= max ? fill(x.full, { max: String(max) }) : x.pick }}</option>
            <option v-for="o in addable" :key="o.id" :value="o.id">
              {{ o.name }}{{ o.owner ? ` – ${fill(x.owner, { name: o.owner.name })}` : '' }}{{ o.available ? '' : ` (${x.unavailable})` }}
            </option>
          </select>
          <p v-if="dirty" class="mt-2 text-xs text-lamp-300">{{ x.unsaved }}</p>
          <button type="submit" class="btn btn-primary mt-3 w-full" :disabled="busy || !dirty">{{ x.save }}</button>
        </template>
      </form>
    </div>
  </section>
</template>

<style scoped>
.extra-mini {
  display: grid;
  width: 2.25rem;
  height: 2.25rem;
  place-items: center;
  overflow: hidden;
  border-radius: 0.4rem;
  background: var(--color-base-950);
}
/* Die Karten zeigen Spieler + Begleiter; für die kleinen Bilder auf den Begleiter (unten Mitte) zoomen. */
.extra-mini img,
.extra-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transform: scale(1.9);
  transform-origin: 56% 82%;
}
.extra-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  border-radius: 0.5rem;
  background: var(--color-base-950);
  padding: 0.35rem 0.45rem;
}
.extra-thumb {
  width: 2.75rem;
  height: 2.75rem;
  flex-shrink: 0;
  overflow: hidden;
  border-radius: 0.4rem;
  background: var(--color-base-900);
}
.extra-btn {
  display: grid;
  width: 1.75rem;
  height: 1.75rem;
  place-items: center;
  border-radius: 0.4rem;
  color: var(--color-base-300);
}
.extra-btn:hover:not(:disabled),
.extra-btn:focus-visible {
  background: var(--color-base-800);
}
.extra-btn:disabled {
  opacity: 0.3;
}
.extra-hits {
  position: absolute;
  inset-inline: 0;
  top: calc(100% + 0.25rem);
  z-index: 20;
  overflow: hidden;
  border-radius: 0.6rem;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-900);
  box-shadow: 0 12px 30px rgb(0 0 0 / 0.35);
}
.extra-hits button {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 0.5rem;
  padding: 0.45rem 0.7rem;
  font-size: 0.875rem;
  color: var(--color-base-100);
}
.extra-hits button:hover,
.extra-hits button:focus-visible {
  background: var(--color-base-800);
}
</style>
