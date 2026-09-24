<script setup lang="ts">
import type { Cape, LibrarySkin, SkinProfile, SkinSyncStatus, SkinVariant } from '~/types'
import type { TrsCape } from '~/utils/trs'
import {
  baseDraft,
  draftChanges,
  rebaseDraft,
  sameDraft,
  syncBusy,
  syncLabel,
  type SkinDraft,
} from '~/utils/skinDraft'

// Skins & Umhänge des aktiven Accounts. Zuerst wird
// lokal bearbeitet (Skin, Modell, Umhang – sofort in der 3D-Vorschau, ohne
// Netzwerk) und erst „Anwenden“ schickt den Unterschied an den Kern. Dort
// sorgt eine Warteschlange dafür, dass Mojang nur den Endzustand sieht und
// bei 429 automatisch später erneut gesendet wird. Das Webview sieht nie ein
// Token, nur fertige Texturen als Data-URL.
const toasts = useToasts()
const accounts = useAccountsStore()

const profile = ref<SkinProfile | null>(null)
const library = ref<LibrarySkin[]>([])
const loading = ref(true)
const loadError = ref<string | null>(null)
const busy = ref<string | null>(null)
const animation = ref<'walk' | 'idle' | 'none'>('walk')

// --- Entwurf ----------------------------------------------------------------------

const draft = ref<SkinDraft>(baseDraft(null))
/** Zuletzt an den Kern geschickter Entwurf (solange er noch nicht bestätigt ist). */
const submitted = ref<SkinDraft | null>(null)
const sync = ref<SkinSyncStatus | null>(null)
const applyError = ref<string | null>(null)
const submitting = ref(false)

const draftLibrary = computed(() => {
  const skin = draft.value.skin
  return skin.source === 'library' ? (library.value.find((s) => s.id === skin.id) ?? null) : null
})
const previewSkin = computed(() => {
  const skin = draft.value.skin
  if (skin.source === 'library') return draftLibrary.value?.texture ?? null
  if (skin.source === 'default') return defaultSkinTexture()
  return profile.value?.skin ?? null
})

let defaultTexture: string | null = null
/**
 * Platzhalter für „Standard-Skin“: eine selbst gezeichnete, schlichte Figur in
 * Steve-Farben (ohne leere Textur wäre das Modell unsichtbar). Welchen der
 * Standard-Skins Mojang vergibt, entscheidet Mojang selbst.
 */
function defaultSkinTexture(): string | null {
  if (defaultTexture) return defaultTexture
  const canvas = document.createElement('canvas')
  canvas.width = 64
  canvas.height = 64
  const ctx = canvas.getContext('2d')
  if (!ctx) return null
  const fill = (color: string, x: number, y: number, w: number, h: number) => {
    ctx.fillStyle = color
    ctx.fillRect(x, y, w, h)
  }
  const skin = '#b98a6c'
  const hair = '#3b2a1c'
  const shirt = '#1fa6a6'
  const pants = '#3b3f9e'
  const shoes = '#5a5a5a'
  fill(skin, 0, 0, 32, 16) // Kopf
  fill(hair, 8, 0, 8, 8) // Oberseite
  fill(hair, 0, 8, 32, 2) // Haaransatz rundum
  fill('#ffffff', 9, 12, 2, 1) // Augen
  fill('#4b3aa8', 10, 12, 1, 1)
  fill('#ffffff', 13, 12, 2, 1)
  fill('#4b3aa8', 13, 12, 1, 1)
  fill('#6e4a36', 11, 14, 2, 1) // Mund
  fill(shirt, 16, 16, 24, 16) // Körper
  for (const [x, y] of [
    [40, 16],
    [32, 48],
  ] as const) {
    fill(skin, x, y, 16, 16) // Arme
    fill(shirt, x, y + 4, 16, 4) // Ärmel
    fill(shirt, x + 4, y, 4, 4)
  }
  for (const [x, y] of [
    [0, 16],
    [16, 48],
  ] as const) {
    fill(pants, x, y, 16, 16) // Beine
    fill(shoes, x, y + 13, 16, 3)
  }
  defaultTexture = canvas.toDataURL('image/png')
  return defaultTexture
}
const previewVariant = computed<SkinVariant>(() => (draft.value.skin.source === 'default' ? 'classic' : draft.value.variant))
/** Angeprobter TRS-Umhang (ersetzt in der Vorschau den Mojang-Umhang, ändert aber nichts am Konto). */
const trsPreview = ref<TrsCape | null>(null)
const previewCape = computed(
  () => trsPreview.value?.texture ?? profile.value?.capes.find((c) => c.id === draft.value.cape)?.texture ?? null,
)

const changes = computed(() => draftChanges(draft.value, profile.value))
const working = computed(() => syncBusy(sync.value))
/** Entwurf weicht vom Konto ab und ist auch noch nicht unterwegs. */
const unapplied = computed(() => !!changes.value && !(working.value && sameDraft(draft.value, submitted.value)))

const skinLabel = computed(() => {
  const skin = draft.value.skin
  if (skin.source === 'default') return t('skins.defaultSkin')
  const name = skin.source === 'library' ? (draftLibrary.value?.name ?? t('skins.skin')) : t('skins.currentSkin')
  return `${name} · ${t(`skins.variants.${draft.value.variant}`)}`
})
const capeLabel = computed(() => profile.value?.capes.find((c) => c.id === draft.value.cape)?.name ?? t('skins.noCape'))

function selectLibrary(skin: LibrarySkin) {
  draft.value = { ...draft.value, skin: { source: 'library', id: skin.id }, variant: skin.variant }
}
function selectCurrent() {
  draft.value = { ...draft.value, skin: { source: 'current' }, variant: profile.value?.variant ?? 'classic' }
}
function selectDefault() {
  draft.value = { ...draft.value, skin: { source: 'default' } }
}
function selectVariant(variant: SkinVariant) {
  draft.value = { ...draft.value, variant }
}
function selectCape(cape: Cape | null) {
  trsPreview.value = null
  draft.value = { ...draft.value, cape: cape?.id ?? null }
}

/** Nicht angewendete Bearbeitungen verwerfen – zurück zum Konto bzw. zum gesendeten Stand. */
function discard() {
  applyError.value = null
  draft.value = working.value && submitted.value ? { ...submitted.value } : baseDraft(profile.value)
}

// --- Anwenden & Warteschlange ---------------------------------------------------

const now = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | null = null
/** Version des zuletzt verarbeiteten Abschlusses (nichts doppelt übernehmen). */
let settledVersion = -1

function sameAccount(status: SkinSyncStatus): boolean {
  const own = profile.value?.uuid.replace(/-/g, '').toLowerCase()
  return !status.account || !own || status.account.replace(/-/g, '').toLowerCase() === own
}

function handleStatus(status: SkinSyncStatus) {
  if (!sameAccount(status)) return
  sync.value = status
  const finished = status.state === 'done' || status.state === 'failed'
  if (finished && status.version !== settledVersion) {
    settledVersion = status.version
    void settle(status)
  }
  updateTicker()
}

/** Abschluss aus dem Kern übernehmen: neues Profil, Entwurf darauf umstellen. */
async function settle(status: SkinSyncStatus) {
  const reference = submitted.value ?? baseDraft(profile.value)
  submitted.value = null
  if (status.profile) profile.value = status.profile
  else await loadProfile()
  if (status.state === 'done') {
    // Übernommenes wird zu „getragen“, spätere Bearbeitungen bleiben.
    draft.value = rebaseDraft(draft.value, reference, profile.value)
  } else {
    applyError.value = status.errorInfo ? userErrorText(status.errorInfo) : status.message
  }
  accounts.load().catch(() => {})
}

async function poll() {
  now.value = Date.now()
  try {
    handleStatus(await backend.skinSyncStatus())
  } catch {
    // Beim nächsten Takt erneut.
  }
}

/** Läuft im Kern etwas, fragt die Seite jede Sekunde nach (Countdown + Abschluss). */
function updateTicker() {
  if (working.value && !ticker) {
    ticker = setInterval(poll, 1000)
  } else if (!working.value && ticker) {
    clearInterval(ticker)
    ticker = null
  }
}

async function applyDraft() {
  const diff = changes.value
  if (!diff || !profile.value || submitting.value) return
  applyError.value = null
  submitting.value = true
  const snapshot = { ...draft.value }
  try {
    const status = await backend.applySkinChanges(profile.value.uuid, diff)
    submitted.value = snapshot
    handleStatus(status)
  } catch (e) {
    applyError.value = errorMessage(e)
  } finally {
    submitting.value = false
  }
}

/** Wartende Änderungen zurückziehen (eine laufende Anfrage läuft noch zu Ende). */
async function cancelSync() {
  try {
    const status = await backend.cancelSkinSync()
    submitted.value = null
    draft.value = baseDraft(profile.value)
    handleStatus(status)
  } catch (e) {
    applyError.value = errorMessage(e)
  }
}

const statusLine = computed(() => (working.value ? syncLabel(sync.value, now.value) : null))

// --- Laden --------------------------------------------------------------------------

async function loadLibrary() {
  library.value = await backend.skinLibrary()
}

async function loadProfile() {
  try {
    profile.value = await backend.skinProfile()
    loadError.value = null
  } catch (e) {
    loadError.value = errorMessage(e)
  }
}

async function load() {
  loading.value = true
  loadError.value = null
  try {
    await loadLibrary()
  } catch (e) {
    toasts.error(e)
  }
  await loadProfile()
  draft.value = baseDraft(profile.value)
  loading.value = false
  // Läuft noch etwas vom letzten Besuch der Seite? Dann den Status weiter zeigen.
  try {
    const status = await backend.skinSyncStatus()
    if (syncBusy(status)) handleStatus(status)
    else settledVersion = status.version
  } catch {
    // Ohne Status geht es auch.
  }
}

onMounted(() => {
  if (!accounts.loaded) accounts.load().catch(() => {})
  load()
})

onBeforeUnmount(() => {
  if (ticker) clearInterval(ticker)
  ticker = null
})

/** Führt eine Aktion aus und hält so lange die Sammlungs-Knöpfe an. */
async function run(key: string, action: () => Promise<void>) {
  if (busy.value) return
  busy.value = key
  try {
    await action()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

// --- Sammlung ---------------------------------------------------------------------

const adding = ref(false)
const newName = ref('')
const newVariant = ref<SkinVariant>('classic')
const formError = ref<string | null>(null)

function startAdd() {
  newName.value = ''
  newVariant.value = 'classic'
  formError.value = null
  adding.value = true
}

async function addSkin() {
  const parsed = skinNameSchema.safeParse(newName.value)
  if (!parsed.success) {
    formError.value = firstIssue(parsed.error)
    return
  }
  adding.value = false
  await run('add', async () => {
    const added = await backend.addSkinFile(parsed.data, newVariant.value)
    if (!added) return
    await loadLibrary()
    selectLibrary(added)
    toasts.ok(t('skins.addedToast', { name: added.name }))
  })
}

async function saveActive() {
  const name = `${profile.value?.name ?? 'Skin'} ${formatShortDate(new Date().toISOString())}`
  await run('save', async () => {
    const saved = await backend.saveActiveSkin(name.slice(0, 48))
    await loadLibrary()
    toasts.ok(t('skins.savedToast', { name: saved.name }))
  })
}

const toDelete = ref<LibrarySkin | null>(null)
async function confirmDelete() {
  const skin = toDelete.value
  toDelete.value = null
  if (!skin) return
  await run('delete', async () => {
    await backend.deleteSkin(skin.id)
    const selected = draft.value.skin
    if (selected.source === 'library' && selected.id === skin.id) selectCurrent()
    await loadLibrary()
  })
}

// --- Vorschaubilder ------------------------------------------------------------------

/** Gesicht (8×8 bei 8/8) aus der Skin-Textur. */
function faceStyle(texture: string, size = 48) {
  return {
    backgroundImage: `url("${texture}")`,
    backgroundSize: `${size * 8}px ${size * 8}px`,
    backgroundPosition: `-${size}px -${size}px`,
    width: `${size}px`,
    height: `${size}px`,
  }
}

/** Vorderseite eines Umhangs (10×16 bei 1/1 einer 64×32-Textur). */
function capeStyle(texture: string, width = 30) {
  const scale = width / 10
  return {
    backgroundImage: `url("${texture}")`,
    backgroundSize: `${64 * scale}px ${32 * scale}px`,
    backgroundPosition: `-${scale}px -${scale}px`,
    width: `${width}px`,
    height: `${16 * scale}px`,
  }
}
</script>

<template>
  <div class="flex h-full min-h-0 flex-col p-6">
    <PageHeader :title="t('skins.title')" :subtitle="t('skins.subtitle')">
      <button class="btn btn-ghost" :disabled="!!busy || !profile" @click="saveActive">
        {{ busy === 'save' ? t('skins.savingCurrent') : t('skins.saveCurrent') }}
      </button>
      <button class="btn btn-primary" :disabled="!!busy" @click="startAdd">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 5v14M5 12h14" /></svg>
        {{ t('skins.add') }}
      </button>
    </PageHeader>

    <div v-if="loadError" role="alert" class="card mb-4 border-warn/40 px-4 py-3 text-sm text-warn">
      {{ loadError }}
      <p class="mt-1 text-xs text-base-400">{{ t('skins.loadErrorHint') }}</p>
    </div>

    <div class="grid min-h-0 flex-1 grid-cols-1 gap-5 overflow-y-auto pr-1 lg:grid-cols-[320px_1fr]">
      <!-- 3D-Vorschau + Entwurf ----------------------------------------------- -->
      <section class="card flex h-fit flex-col p-4 lg:sticky lg:top-0" :aria-label="t('skins.previewLabel')">
        <div class="relative rounded-lg bg-gradient-to-b from-base-850 to-base-950" :title="t('skins.previewHint')">
          <div v-if="loading" class="skeleton h-[280px] w-full rounded-lg" />
          <ClientOnly v-else>
            <SkinViewer
              :skin="previewSkin"
              :cape="previewCape"
              :variant="previewVariant"
              :animation="animation"
              :height="280"
              :cape-frames="trsPreview?.frames ?? 1"
              :cape-frame-time="trsPreview?.frameTimeMs ?? null"
            />
          </ClientOnly>
          <span v-if="unapplied" class="badge absolute top-2 left-2 bg-warn/15 text-warn" data-testid="skin-unapplied">
            <span class="size-1.5 rounded-full bg-warn" />
            {{ t('skins.unapplied') }}
          </span>
          <button
            v-if="trsPreview"
            class="badge absolute top-2 right-2 bg-redstone-900/70 text-redstone-300 hover:text-base-50"
            :title="t('skins.endTrsPreview')"
            @click="trsPreview = null"
          >
            TRS: {{ trsPreview.name }} ✕
          </button>
          <div class="absolute inset-x-2 bottom-2 flex items-center gap-1 rounded-md bg-base-950/70 p-0.5 text-[11px] backdrop-blur-sm">
            <button
              v-for="key in (['walk', 'idle', 'none'] as const)"
              :key="key"
              class="seg flex-1 rounded px-2 py-1"
              :class="{ 'seg-on': animation === key }"
              @click="animation = key"
            >
              {{ t(`skins.animation.${key}`) }}
            </button>
          </div>
        </div>

        <template v-if="profile">
          <!-- Modell (Armbreite) -->
          <div v-if="draft.skin.source !== 'default'" class="mt-3">
            <p class="label">{{ t('skins.model') }}</p>
            <div class="flex gap-1 rounded-lg bg-base-850 p-1 text-xs" role="radiogroup" :aria-label="t('skins.model')">
              <button
                v-for="v in skinVariants"
                :key="v"
                class="seg flex-1 rounded-md"
                :class="{ 'seg-on': draft.variant === v }"
                role="radio"
                :aria-checked="draft.variant === v"
                @click="selectVariant(v)"
              >
                {{ t(`skins.variants.${v}`) }}
              </button>
            </div>
          </div>

          <!-- Zusammenfassung + Anwenden -->
          <dl class="mt-3 space-y-1 text-xs">
            <div class="flex justify-between gap-3">
              <dt class="text-base-400">{{ t('skins.skin') }}</dt>
              <dd class="truncate text-right font-medium text-base-50">{{ skinLabel }}</dd>
            </div>
            <div class="flex justify-between gap-3">
              <dt class="text-base-400">{{ t('skins.cape') }}</dt>
              <dd class="truncate text-right font-medium text-base-50">{{ capeLabel }}</dd>
            </div>
          </dl>

          <div class="mt-3 flex gap-2">
            <button
              class="btn btn-primary flex-1 py-1.5 text-xs"
              :disabled="!unapplied || submitting"
              data-testid="skin-apply"
              @click="applyDraft"
            >
              {{ submitting ? t('skins.submitting') : t('common.actions.apply') }}
            </button>
            <button class="btn btn-ghost py-1.5 text-xs" :disabled="!unapplied" data-testid="skin-discard" @click="discard">
              {{ t('skins.discard') }}
            </button>
          </div>

          <!-- Eine Statuszeile statt Toasts -->
          <div
            v-if="statusLine"
            class="mt-3 flex items-start gap-2 rounded-lg border px-3 py-2 text-xs"
            :class="sync?.reason === 'rateLimited' || sync?.reason === 'network' ? 'border-warn/40 bg-warn/10 text-warn' : 'border-base-700 bg-base-850 text-base-200'"
            role="status"
            aria-live="polite"
            data-testid="skin-sync-status"
          >
            <svg viewBox="0 0 24 24" class="mt-px size-3.5 shrink-0" :class="{ 'animate-spin': sync?.state === 'applying' }" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <path v-if="sync?.state === 'applying'" d="M4 12a8 8 0 0 1 14-5.3M20 4v4h-4M20 12a8 8 0 0 1-14 5.3M4 20v-4h4" />
              <path v-else d="M12 7v5l3 2M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z" />
            </svg>
            <p class="min-w-0 flex-1 tabular-nums">{{ statusLine }}</p>
            <button v-if="sync?.state === 'waiting'" class="shrink-0 text-base-400 hover:text-base-50 hover:underline" @click="cancelSync">
              {{ t('common.actions.cancel') }}
            </button>
          </div>
          <p v-else-if="applyError" role="alert" class="mt-3 rounded-lg border border-redstone-600/50 bg-redstone-900/30 px-3 py-2 text-xs text-redstone-300">
            {{ applyError }}
          </p>
          <p v-else-if="!unapplied" class="mt-3 text-center text-[11px] text-base-600">
            {{ t('skins.applyHint') }}
          </p>
        </template>
      </section>

      <div class="min-w-0 space-y-5">
        <!-- Skins ---------------------------------------------------------------- -->
        <section>
          <h2 class="section-title mb-2">{{ t('skins.mySkins') }}</h2>
          <div v-if="loading" class="grid grid-cols-[repeat(auto-fill,minmax(13rem,1fr))] gap-3">
            <div v-for="i in 4" :key="i" class="skeleton h-28" />
          </div>
          <ul v-else class="grid grid-cols-[repeat(auto-fill,minmax(13rem,1fr))] gap-3">
            <!-- Getragener Skin: zurück zum aktuellen Stand -->
            <li v-if="profile">
              <button
                class="card card-hover flex h-full w-full items-center gap-3 p-3 text-left"
                :class="{ 'border-redstone-600/60 bg-redstone-900/20': draft.skin.source === 'current' }"
                :aria-pressed="draft.skin.source === 'current'"
                @click="selectCurrent"
              >
                <span v-if="profile.skin" class="shrink-0 rounded [image-rendering:pixelated]" :style="faceStyle(profile.skin)" />
                <span v-else class="size-12 shrink-0 rounded bg-base-800" />
                <span class="min-w-0 flex-1">
                  <span class="block truncate text-sm font-medium">{{ t('skins.currentSkin') }}</span>
                  <span class="block text-[11px] text-base-400">{{ t('skins.onAccount', { variant: t(`skins.variants.${profile.variant}`) }) }}</span>
                </span>
              </button>
            </li>
            <li v-if="profile">
              <button
                class="card card-hover flex h-full w-full items-center gap-3 p-3 text-left"
                :class="{ 'border-redstone-600/60 bg-redstone-900/20': draft.skin.source === 'default' }"
                :aria-pressed="draft.skin.source === 'default'"
                @click="selectDefault"
              >
                <span class="grid size-12 shrink-0 place-items-center rounded bg-base-800 text-base-400">
                  <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M3 12a9 9 0 1 0 3-6.7M3 4v5h5" /></svg>
                </span>
                <span class="min-w-0 flex-1">
                  <span class="block truncate text-sm font-medium">{{ t('skins.defaultSkin') }}</span>
                  <span class="block text-[11px] text-base-400">{{ t('skins.defaultSkinHint') }}</span>
                </span>
              </button>
            </li>
            <li
              v-for="skin in library"
              :key="skin.id"
              class="card card-hover flex items-center gap-3 p-3"
              :class="{ 'border-redstone-600/60 bg-redstone-900/20': draft.skin.source === 'library' && draft.skin.id === skin.id }"
            >
              <button class="shrink-0 rounded [image-rendering:pixelated]" :style="faceStyle(skin.texture)" :aria-label="t('skins.tryOnLabel', { name: skin.name })" @click="selectLibrary(skin)" />
              <div class="min-w-0 flex-1">
                <p class="truncate text-sm font-medium">{{ skin.name }}</p>
                <p class="text-[11px] text-base-400">{{ t(`skins.variants.${skin.variant}`) }} · {{ formatDate(skin.addedAt) }}</p>
                <div class="mt-1.5 flex gap-1.5">
                  <button
                    class="btn btn-ghost px-2 py-1 text-[11px]"
                    :aria-pressed="draft.skin.source === 'library' && draft.skin.id === skin.id"
                    @click="selectLibrary(skin)"
                  >
                    {{ draft.skin.source === 'library' && draft.skin.id === skin.id ? t('skins.selected') : t('skins.tryOn') }}
                  </button>
                  <button class="btn btn-ghost px-2 py-1 text-[11px] hover:text-redstone-300" :disabled="!!busy" @click="toDelete = skin">
                    {{ t('common.actions.delete') }}
                  </button>
                </div>
              </div>
            </li>
          </ul>
          <div v-if="!loading && !library.length" class="card mt-3 px-6 py-8 text-center">
            <h3 class="font-semibold">{{ t('skins.empty.title') }}</h3>
            <p class="mx-auto mt-1 max-w-md text-sm text-base-400">{{ t('skins.empty.text') }}</p>
            <button class="btn btn-primary mt-4" @click="startAdd">{{ t('skins.add') }}</button>
          </div>
        </section>

        <!-- Umhänge -------------------------------------------------------------- -->
        <section v-if="profile">
          <h2 class="section-title mb-2">{{ t('skins.mojangCapes') }}</h2>
          <ul v-if="profile.capes.length" class="grid grid-cols-[repeat(auto-fill,minmax(8rem,1fr))] gap-3">
            <li>
              <button
                class="card card-hover flex h-full w-full flex-col items-center gap-2 p-3"
                :class="{ 'border-redstone-600/60 bg-redstone-900/20': draft.cape === null }"
                :aria-pressed="draft.cape === null"
                @click="selectCape(null)"
              >
                <span class="grid h-[48px] w-[30px] place-items-center rounded bg-base-800 text-base-600">–</span>
                <span class="text-xs">{{ t('skins.noCape') }}</span>
              </button>
            </li>
            <li v-for="cape in profile.capes" :key="cape.id">
              <button
                class="card card-hover flex h-full w-full flex-col items-center gap-2 p-3"
                :class="{ 'border-redstone-600/60 bg-redstone-900/20': draft.cape === cape.id }"
                :aria-pressed="draft.cape === cape.id"
                @click="selectCape(cape)"
              >
                <span v-if="cape.texture" class="rounded [image-rendering:pixelated]" :style="capeStyle(cape.texture)" />
                <span v-else class="h-[48px] w-[30px] rounded bg-base-800" />
                <span class="w-full truncate text-center text-xs">{{ cape.name }}</span>
                <span v-if="cape.active" class="text-[10px] text-base-400">{{ t('skins.worn') }}</span>
              </button>
            </li>
          </ul>
          <p v-else class="card px-4 py-6 text-center text-sm text-base-400">
            {{ t('skins.noCapes') }}
          </p>
        </section>

        <!-- TRS-Umhänge (eigener Dienst, getrennt von Mojang) ------------------------ -->
        <TrsCapes :preview-id="trsPreview?.id ?? null" @preview="trsPreview = $event" />
      </div>
    </div>

    <!-- Dialoge -------------------------------------------------------------------- -->
    <BaseDialog v-if="adding" :title="t('skins.add')" @close="adding = false">
      <label class="label" for="skin-name">{{ t('common.labels.name') }}</label>
      <input
        id="skin-name"
        v-model="newName"
        class="field"
        maxlength="48"
        :placeholder="t('skins.addDialog.namePlaceholder')"
        autofocus
        @keydown.enter="addSkin"
      />
      <p class="label mt-4">{{ t('skins.model') }}</p>
      <div class="grid grid-cols-2 gap-2">
        <button
          v-for="v in skinVariants"
          :key="v"
          class="rounded-lg border px-3 py-2.5 text-left transition-colors"
          :class="newVariant === v ? 'border-redstone-500 bg-redstone-900/40' : 'border-base-700 hover:border-base-600'"
          :aria-pressed="newVariant === v"
          @click="newVariant = v"
        >
          <span class="block text-sm font-semibold">{{ t(`skins.variants.${v}`) }}</span>
          <span class="block text-xs text-base-400">{{ t(`skins.variantHints.${v}`) }}</span>
        </button>
      </div>
      <p class="mt-4 text-xs text-base-400">{{ t('skins.addDialog.fileHint') }}</p>
      <p v-if="formError" role="alert" class="mt-2 text-xs text-redstone-300">{{ formError }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="adding = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" @click="addSkin">{{ t('skins.addDialog.chooseFile') }}</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="toDelete" :title="t('skins.deleteDialog.title')" @close="toDelete = null">
      <i18n-t keypath="skins.deleteDialog.text" tag="p" scope="global" class="text-sm text-base-200">
        <template #name><strong class="text-base-50">{{ toDelete.name }}</strong></template>
      </i18n-t>
      <template #actions>
        <button class="btn btn-ghost" @click="toDelete = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" @click="confirmDelete">{{ t('common.actions.delete') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
