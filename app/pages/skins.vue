<script setup lang="ts">
import type { BuiltinSkins, Cape, LibrarySkin, SelectableSkin, SkinPack, SkinProfile, SkinSyncStatus, SkinVariant } from '~/types'
import type { TrsCape, TrsHeadCosmetic } from '~/utils/trs'
import type { ViewerCosmetic } from '~/utils/cosmetic-v2/format'
import type { ThumbRequest } from '~/utils/skinThumbs'
import {
  baseDraft,
  draftChanges,
  rebaseDraft,
  sameDraft,
  syncBusy,
  syncLabel,
  type SkinDraft,
} from '~/utils/skinDraft'

// Skins, Umhänge & Kosmetik des aktiven Accounts. Links steht die große
// 3D-Figur (bleibt beim Scrollen stehen), rechts die Reiter mit Karten.
// Alles Angeklickte erscheint sofort links (lokaler Entwurf, ohne Netzwerk);
// erst „Anwenden“ schickt den Unterschied an den Kern. Dort sorgt eine
// Warteschlange dafür, dass Mojang nur den Endzustand sieht und bei 429
// automatisch später erneut gesendet wird. Das Webview sieht nie ein Token,
// nur fertige Texturen als Data-URL.
const toasts = useToasts()
const accounts = useAccountsStore()

const profile = ref<SkinProfile | null>(null)
const library = ref<LibrarySkin[]>([])
const loading = ref(true)
const loadError = ref<string | null>(null)
const busy = ref<string | null>(null)
const animation = ref<'walk' | 'idle' | 'none'>('walk')

/** Standard-Skins aus einem installierten Client (`null` = lädt). */
const builtins = ref<BuiltinSkins | null>(null)
/** Offizielle Skin-Pakete (`null` = lädt, leer = offline/keine – Abschnitt ausgeblendet). */
const packs = ref<SkinPack[] | null>(null)

// --- Reiter -------------------------------------------------------------------------

const TABS = ['skins', 'capes', 'cosmetics'] as const
type Tab = (typeof TABS)[number]
const TAB_KEY = 'trs.skins.tab'
const TAB_ICONS: Record<Tab, string> = {
  skins: 'M8 3h8l1 4h3v4h-3v10H7V11H4V7h3l1-4Z',
  capes: 'M6 3h12l1 18-7-3-7 3 1-18Z',
  cosmetics: 'M4 17h16M6 17l1-8 5 3 5-3 1 8M12 12V6',
}

function storedTab(): Tab {
  try {
    const value = localStorage.getItem(TAB_KEY)
    return (TABS as readonly string[]).includes(value ?? '') ? (value as Tab) : 'skins'
  } catch {
    return 'skins'
  }
}
const tab = ref<Tab>(storedTab())

function selectTab(next: Tab, focus = false) {
  tab.value = next
  try {
    localStorage.setItem(TAB_KEY, next)
  } catch {
    // Ohne Speicher gilt die Wahl nur jetzt.
  }
  if (focus) nextTick(() => document.getElementById(`skins-tab-${next}`)?.focus())
}

function onTabKey(e: KeyboardEvent) {
  const i = TABS.indexOf(tab.value)
  let next = i
  if (e.key === 'ArrowRight') next = (i + 1) % TABS.length
  else if (e.key === 'ArrowLeft') next = (i - 1 + TABS.length) % TABS.length
  else if (e.key === 'Home') next = 0
  else if (e.key === 'End') next = TABS.length - 1
  else return
  e.preventDefault()
  selectTab(TABS[next]!, true)
}

// --- Große Vorschau: Höhe je nach Fensterbreite -------------------------------------

const wide = ref(true)
let wideQuery: MediaQueryList | null = null
const onWide = (e: MediaQueryListEvent | MediaQueryList) => (wide.value = e.matches)
/** Höhe des Scroll-Bereichs: Die stehende Spalte links muss samt „Anwenden“ hineinpassen. */
const scroller = useTemplateRef<HTMLElement>('scroller')
const areaHeight = ref(640)
let areaObserver: ResizeObserver | null = null
/** Platz unter/über der Figur in der linken Spalte (Name, Leisten, Entwurf-Karte). */
const LEFT_CHROME = 240
const viewerHeight = computed(() =>
  wide.value ? Math.round(Math.min(520, Math.max(240, areaHeight.value - LEFT_CHROME))) : 300,
)

// --- Entwurf ----------------------------------------------------------------------

const draft = ref<SkinDraft>(baseDraft(null))
/** Zuletzt an den Kern geschickter Entwurf (solange er noch nicht bestätigt ist). */
const submitted = ref<SkinDraft | null>(null)
const sync = ref<SkinSyncStatus | null>(null)
const applyError = ref<string | null>(null)
const submitting = ref(false)

/** Alle auswählbaren Skins (Standard + Pakete) nach ID. */
const selectables = computed(() => {
  const map = new Map<string, SelectableSkin>()
  for (const skin of builtins.value?.skins ?? []) map.set(skin.id, skin)
  for (const pack of packs.value ?? []) for (const skin of pack.skins) map.set(skin.id, skin)
  return map
})

const draftLibrary = computed(() => {
  const skin = draft.value.skin
  return skin.source === 'library' ? (library.value.find((s) => s.id === skin.id) ?? null) : null
})
const draftBuiltin = computed(() => {
  const skin = draft.value.skin
  return skin.source === 'builtin' ? (selectables.value.get(skin.id) ?? null) : null
})
const previewSkin = computed(() => {
  const skin = draft.value.skin
  if (skin.source === 'library') return draftLibrary.value?.texture ?? null
  if (skin.source === 'builtin') return draftBuiltin.value?.texture ?? null
  // Der Kern liest die echte Textur aus einem installierten Client (Steve, Alex, Ari, …);
  // ohne installierte Version bleibt der gezeichnete Platzhalter (ohne Textur wäre das Modell unsichtbar).
  if (skin.source === 'default') return accountDefaultTexture.value
  return profile.value?.skin ?? accountDefaultTexture.value
})

let defaultTexture: string | null = null
/**
 * Rückfall für „Standard-Skin“, solange keine Minecraft-Version installiert ist: eine selbst gezeichnete, schlichte Figur in
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

/** Standard-Textur dieses Kontos (aus dem Client) oder der gezeichnete Platzhalter. */
const accountDefaultTexture = computed(() => profile.value?.defaultSkin ?? (profile.value ? defaultSkinTexture() : null))
const accountDefaultVariant = computed<SkinVariant>(() => (profile.value?.defaultSkin ? profile.value.defaultVariant : 'classic'))

/** Hinweis zum Konto-Standard: welcher Skin es ist und aus welcher Version – sonst der allgemeine Text. */
const defaultSkinHint = computed(() => {
  const p = profile.value
  if (!p?.defaultSkin || !p.defaultSkinVersion) return t('skins.defaultSkinHint')
  return t('skins.defaultSkinFrom', { name: capitalize(p.defaultSkinName), version: p.defaultSkinVersion })
})
const previewVariant = computed<SkinVariant>(() => {
  const source = draft.value.skin.source
  if (source === 'default' || (source === 'current' && !profile.value?.skin)) return accountDefaultVariant.value
  return draft.value.variant
})
/** Angeprobter TRS-Umhang (ersetzt in der Vorschau den Mojang-Umhang, ändert aber nichts am Konto). */
const trsPreview = ref<TrsCape | null>(null)
/** Getragener TRS-Umhang – im Spiel sehen TRS-Spieler ihn statt des Mojang-Umhangs. */
const trsActive = ref<TrsCape | null>(null)
/** Bewusst einen Mojang-Umhang angeklickt: dann zeigt die Vorschau den, nicht den TRS-Umhang. */
const mojangFocus = ref(false)
/** TRS-Umhang in der Vorschau: angeprobt, sonst der getragene (außer ein Mojang-Umhang wird angesehen). */
const shownTrs = computed(() => trsPreview.value ?? (mojangFocus.value ? null : trsActive.value))
const previewCape = computed(
  () => shownTrs.value?.texture ?? profile.value?.capes.find((c) => c.id === draft.value.cape)?.texture ?? null,
)

// --- Kopf-Kosmetik (v2): in der großen Vorschau anprobieren -----------------------------

/** Angeprobtes Teil (ändert nichts am Konto). */
const headPreview = ref<TrsHeadCosmetic | null>(null)
/** Aufgesetztes Teil – so sehen andere den Spieler im Spiel. */
const headEquipped = ref<TrsHeadCosmetic | null>(null)
const shownHead = computed(() => headPreview.value ?? headEquipped.value)
/** Modell + Texturen für die Vorschau (null = nichts am Kopf bzw. noch am Laden). */
const viewerCosmetic = shallowRef<ViewerCosmetic | null>(null)
/** Tag/Nacht in der Vorschau (nachts leuchten Lampen und Kristalle). */
const night = ref(false)
/** Kamera: ganzer Spieler oder Kopf + Schultern. */
const focus = ref<'body' | 'head'>('body')
const cosmeticModels = new Map<string, ViewerCosmetic>()
let cosmeticToken = 0

watch(
  () => (shownHead.value?.preview ? `${shownHead.value.id}:${shownHead.value.hash ?? ''}` : null),
  async (key) => {
    const token = ++cosmeticToken
    const item = shownHead.value
    if (!key || !item) {
      viewerCosmetic.value = null
      return
    }
    const cached = cosmeticModels.get(key)
    if (cached) {
      viewerCosmetic.value = cached
      return
    }
    try {
      const data = await backend.trs.headCosmeticModel(item.id)
      const loaded: ViewerCosmetic = { model: data.model, texture: data.texture, glow: data.glow }
      cosmeticModels.set(key, loaded)
      if (token === cosmeticToken) viewerCosmetic.value = loaded
    } catch (e) {
      if (token !== cosmeticToken) return
      viewerCosmetic.value = null
      toasts.error(e)
    }
  },
)

function previewHead(item: TrsHeadCosmetic | null) {
  headPreview.value = item
  // Anprobieren: Kamera auf den Kopf, damit man das Teil auch sieht.
  if (item) focus.value = 'head'
}

const capesSection = useTemplateRef<{ startRedeem: () => void }>('capesSection')

const changes = computed(() => draftChanges(draft.value, profile.value))
const working = computed(() => syncBusy(sync.value))
/** Entwurf weicht vom Konto ab und ist auch noch nicht unterwegs. */
const unapplied = computed(() => !!changes.value && !(working.value && sameDraft(draft.value, submitted.value)))

function capitalize(name: string): string {
  return name.charAt(0).toUpperCase() + name.slice(1)
}

/** Name eines auswählbaren Skins: Standard-Skins heißen `steve` → „Steve“. */
function selectableName(skin: SelectableSkin): string {
  return skin.id.startsWith('pack/') ? skin.name : capitalize(skin.name)
}

const skinLabel = computed(() => {
  const skin = draft.value.skin
  if (skin.source === 'default') return t('skins.defaultSkin')
  let name: string
  if (skin.source === 'library') name = draftLibrary.value?.name ?? t('skins.skin')
  else if (skin.source === 'builtin') name = draftBuiltin.value ? selectableName(draftBuiltin.value) : t('skins.skin')
  else name = t('skins.currentSkin')
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
function selectBuiltin(skin: SelectableSkin) {
  draft.value = { ...draft.value, skin: { source: 'builtin', id: skin.id }, variant: skin.variant }
}
function selectVariant(variant: SkinVariant) {
  draft.value = { ...draft.value, variant }
}
function selectCape(cape: Cape | null) {
  trsPreview.value = null
  mojangFocus.value = true
  draft.value = { ...draft.value, cape: cape?.id ?? null }
}

function isSelected(source: SkinDraft['skin']['source'], id?: string): boolean {
  const skin = draft.value.skin
  if (skin.source !== source) return false
  return !('id' in skin) || skin.id === id
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

/** Standard-Skins (lokal aus dem Client-Jar) und offizielle Pakete (Netz/Cache) – beides still. */
async function loadSelectables() {
  backend
    .builtinSkins()
    .then((result) => (builtins.value = result))
    .catch(() => (builtins.value = { version: null, skins: [] }))
  try {
    packs.value = await backend.skinPacks()
  } catch {
    packs.value = []
  }
}

async function load() {
  loading.value = true
  loadError.value = null
  void loadSelectables()
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

// --- Synchronisation mit dem TRS-Konto ------------------------------------------------
// Läuft still im Kern; hier nur ein dezenter Hinweis und Neuladen, wenn ein
// Abgleich die Sammlung geändert hat.
const trs = useTrsStore()
/** Tickt alle 30 s, damit „vor 2 Minuten“ weiterzählt. */
const syncTick = ref(Date.now())
let syncTimer: ReturnType<typeof setInterval> | null = null
const syncLine = computed(() => {
  void syncTick.value
  return trsSyncLabel(trs.sync)
})

watch(
  () => trs.skinsRevision,
  () => {
    loadLibrary()
      .then(() => {
        // Ausgewählter Skin wurde woanders gelöscht? Dann zurück zum getragenen.
        const selected = draft.value.skin
        if (selected.source === 'library' && !library.value.some((s) => s.id === selected.id)) selectCurrent()
      })
      .catch(() => {})
  },
)

onMounted(() => {
  if (!accounts.loaded) accounts.load().catch(() => {})
  wideQuery = window.matchMedia('(min-width: 1024px)')
  onWide(wideQuery)
  wideQuery.addEventListener('change', onWide)
  if (scroller.value) {
    areaObserver = new ResizeObserver(([entry]) => {
      if (entry) areaHeight.value = entry.contentRect.height
    })
    areaObserver.observe(scroller.value)
  }
  load()
  void trs.refreshSync()
  syncTimer = setInterval(() => (syncTick.value = Date.now()), 30_000)
})

onBeforeUnmount(() => {
  wideQuery?.removeEventListener('change', onWide)
  areaObserver?.disconnect()
  if (ticker) clearInterval(ticker)
  ticker = null
  if (syncTimer) clearInterval(syncTimer)
  syncTimer = null
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

/** „+ Skin hinzufügen“ (Dateien, Drag & Drop, Link, Spielername, andere Launcher). */
const importer = useTemplateRef<{ pickFiles: () => Promise<void> }>('importer')

/** Neu aufgenommene Skins: Sammlung neu laden, einen einzelnen gleich anprobieren. */
async function onImported(added: LibrarySkin[]) {
  try {
    await loadLibrary()
  } catch (e) {
    toasts.error(e)
  }
  const only = added.length === 1 ? library.value.find((s) => s.id === added[0]!.id) : undefined
  if (only) selectLibrary(only)
}

async function saveActive() {
  const name = `${profile.value?.name ?? 'Skin'} ${formatShortDate(new Date().toISOString())}`
  await run('save', async () => {
    const saved = await backend.saveActiveSkin(name.slice(0, 48))
    await loadLibrary()
    toasts.ok(t('skins.savedToast', { name: saved.name }))
  })
}

const toRename = ref<LibrarySkin | null>(null)
const renameName = ref('')
const renameError = ref<string | null>(null)

function startRename(skin: LibrarySkin) {
  toRename.value = skin
  renameName.value = skin.name
  renameError.value = null
}

async function confirmRename() {
  const skin = toRename.value
  if (!skin) return
  const parsed = skinNameSchema.safeParse(renameName.value)
  if (!parsed.success) {
    renameError.value = firstIssue(parsed.error)
    return
  }
  toRename.value = null
  if (parsed.data === skin.name) return
  await run('rename', async () => {
    await backend.renameSkin(skin.id, parsed.data)
    await loadLibrary()
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

// --- Karten --------------------------------------------------------------------------

const playerName = computed(() => profile.value?.name ?? accounts.active?.name ?? '')

/** Getragener Skin (ohne eigenen Skin: der Standard des Kontos). */
const currentThumb = computed<ThumbRequest | null>(() => {
  const p = profile.value
  if (!p) return null
  const skin = p.skin ?? accountDefaultTexture.value
  return skin ? { skin, variant: p.skin ? p.variant : accountDefaultVariant.value } : null
})
const accountDefaultThumb = computed<ThumbRequest | null>(() =>
  accountDefaultTexture.value ? { skin: accountDefaultTexture.value, variant: accountDefaultVariant.value } : null,
)

/** Mojang-Umhang als flaches Bild, Ausschnitt aus der Cape-Textur (10×16 im 64×32-Blatt). */
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

/** Dieser Standard-Skin ist der, den Mojang dem Konto zuteilt. */
function isAssigned(skin: SelectableSkin): boolean {
  const p = profile.value
  return !!p?.defaultSkin && !skin.id.startsWith('pack/') && skin.name === p.defaultSkinName && skin.variant === p.defaultVariant
}

function selectableTitle(skin: SelectableSkin): string {
  return `${selectableName(skin)} · ${t(`skins.variants.${skin.variant}`)}`
}

const packSkinCount = computed(() => packs.value?.reduce((n, p) => n + p.skins.length, 0) ?? null)
</script>

<template>
  <div class="flex h-full min-h-0 flex-col p-6">
    <PageHeader :title="t('skins.title')" :subtitle="t('skins.subtitle')">
      <button class="btn btn-ghost" :disabled="!!busy || !profile" @click="saveActive">
        {{ busy === 'save' ? t('skins.savingCurrent') : t('skins.saveCurrent') }}
      </button>
      <SkinImport ref="importer" :disabled="!!busy" @imported="onImported" />
    </PageHeader>

    <div v-if="loadError" role="alert" class="card mb-4 border-warn/40 px-4 py-3 text-sm text-warn">
      {{ loadError }}
      <p class="mt-1 text-xs text-base-400">{{ t('skins.loadErrorHint') }}</p>
    </div>

    <div ref="scroller" class="min-h-0 flex-1 overflow-y-auto pr-1" data-testid="skins-scroller">
      <div class="grid grid-cols-1 gap-6 lg:grid-cols-[minmax(300px,360px)_1fr] xl:grid-cols-[400px_1fr]">
        <!-- Große 3D-Figur + Entwurf (bleibt beim Scrollen stehen) ------------------ -->
        <section
          class="flex flex-col gap-3 lg:sticky lg:top-0 lg:self-start lg:overflow-y-auto"
          :style="wide ? { maxHeight: `${areaHeight}px` } : undefined"
          :aria-label="t('skins.previewLabel')"
        >
          <div class="card shrink-0 overflow-hidden">
            <div
              class="relative bg-gradient-to-b transition-colors duration-500"
              :class="night ? 'from-[#0b0d18] to-[#030409]' : 'from-base-850 via-base-900 to-base-950'"
              data-testid="skin-preview"
            >
              <div class="pointer-events-none absolute inset-x-0 top-0 z-10 flex items-center gap-2 px-4 pt-3">
                <h2 class="display min-w-0 flex-1 truncate text-2xl leading-tight text-base-50 drop-shadow" data-testid="skin-player-name">
                  {{ playerName }}
                </h2>
                <span v-if="unapplied" class="badge bg-warn/15 text-warn" data-testid="skin-unapplied">
                  <span class="size-1.5 rounded-full bg-warn" />
                  {{ t('skins.unapplied') }}
                </span>
              </div>
              <div v-if="loading" class="skeleton mx-4 my-3 rounded-lg" :style="{ height: `${viewerHeight - 24}px` }" />
              <ClientOnly v-else>
                <SkinViewer
                  :skin="previewSkin"
                  :cape="previewCape"
                  :variant="previewVariant"
                  :animation="animation"
                  :height="viewerHeight"
                  :cape-frames="shownTrs?.frames ?? 1"
                  :cape-frame-time="shownTrs?.frameTimeMs ?? null"
                  :cosmetic="viewerCosmetic"
                  :night="night"
                  :focus="focus"
                  @cosmetic-error="toasts.error(t('headCosmetics.previewFailed'))"
                />
              </ClientOnly>
              <div class="absolute top-12 right-3 flex max-w-[60%] flex-col items-end gap-1">
                <button
                  v-if="trsPreview"
                  class="badge max-w-full truncate bg-redstone-900/70 text-redstone-300 hover:text-base-50"
                  :title="t('skins.endTrsPreview')"
                  @click="trsPreview = null"
                >
                  TRS: {{ trsPreview.name }} ✕
                </button>
                <button
                  v-if="headPreview"
                  class="badge max-w-full truncate bg-lamp-900/70 text-lamp-200 hover:text-base-50"
                  :title="t('skins.endHeadPreview')"
                  data-testid="head-preview-badge"
                  @click="previewHead(null)"
                >
                  {{ headPreview.name }} ✕
                </button>
              </div>
              <div class="flex items-center gap-1 border-t border-base-800/80 bg-base-950/60 p-1 text-[11px] backdrop-blur-sm">
                <button
                  v-for="key in (['walk', 'idle', 'none'] as const)"
                  :key="key"
                  class="seg flex-1 rounded px-2 py-1"
                  :class="{ 'seg-on': animation === key }"
                  @click="animation = key"
                >
                  {{ t(`skins.animation.${key}`) }}
                </button>
                <span class="mx-0.5 h-4 w-px bg-base-700" aria-hidden="true" />
                <button
                  class="seg grid size-6 shrink-0 place-items-center rounded px-0 py-0"
                  :class="{ 'seg-on': focus === 'head' }"
                  :aria-pressed="focus === 'head'"
                  :title="focus === 'head' ? t('skins.viewer.showBody') : t('skins.viewer.showHead')"
                  :aria-label="t('skins.viewer.showHead')"
                  data-testid="skin-focus"
                  @click="focus = focus === 'head' ? 'body' : 'head'"
                >
                  <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <path d="M9 4H5a1 1 0 0 0-1 1v4M15 4h4a1 1 0 0 1 1 1v4M9 20H5a1 1 0 0 1-1-1v-4M15 20h4a1 1 0 0 0 1-1v-4M9 9h6v6H9z" />
                  </svg>
                </button>
                <button
                  class="seg grid size-6 shrink-0 place-items-center rounded px-0 py-0"
                  :class="{ 'seg-on': night }"
                  :aria-pressed="night"
                  :title="night ? t('skins.viewer.day') : t('skins.viewer.night')"
                  :aria-label="t('skins.viewer.night')"
                  data-testid="skin-night"
                  @click="night = !night"
                >
                  <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <path v-if="night" d="M12 3v2M12 19v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M3 12h2M19 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z" />
                    <path v-else d="M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5Z" />
                  </svg>
                </button>
              </div>
            </div>
            <p class="flex items-center justify-center gap-1.5 px-3 py-2 text-[11px] text-base-400">
              <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M3 12a9 4 0 0 0 18 0M21 12a9 4 0 0 0-18 0M18 9l3 3-3 3" />
              </svg>
              {{ t('skins.previewHint') }}
            </p>
          </div>

          <div v-if="profile" class="card shrink-0 p-4">
            <!-- Modell (Armbreite) -->
            <div v-if="draft.skin.source !== 'default'">
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
            <!-- Zusammenfassung -->
            <dl class="mt-3 space-y-1 border-t border-base-800 pt-3 text-xs">
              <div class="flex justify-between gap-3">
                <dt class="text-base-400">{{ t('skins.skin') }}</dt>
                <dd class="truncate text-right font-medium text-base-50" data-testid="skin-draft-label">{{ skinLabel }}</dd>
              </div>
              <div class="flex justify-between gap-3">
                <dt class="text-base-400">{{ t('skins.cape') }}</dt>
                <dd class="truncate text-right font-medium text-base-50">{{ capeLabel }}</dd>
              </div>
              <div v-if="shownTrs" class="flex justify-between gap-3">
                <dt class="text-base-400">{{ t('skins.trsCape') }}</dt>
                <dd class="truncate text-right font-medium text-base-50">{{ shownTrs.name }}</dd>
              </div>
              <div v-if="shownHead" class="flex justify-between gap-3">
                <dt class="text-base-400">{{ t('skins.headCosmetic') }}</dt>
                <dd class="truncate text-right font-medium text-base-50">{{ shownHead.name }}</dd>
              </div>
            </dl>
          </div>
        </section>

        <!-- Reiter ------------------------------------------------------------------ -->
        <div class="min-w-0">
          <div class="tabbar mb-3" role="tablist" :aria-label="t('skins.tabsLabel')" @keydown="onTabKey">
            <button
              v-for="key in TABS"
              :id="`skins-tab-${key}`"
              :key="key"
              role="tab"
              class="itab"
              :class="{ 'itab-on': tab === key }"
              :aria-selected="tab === key"
              :aria-controls="`skins-panel-${key}`"
              :tabindex="tab === key ? 0 : -1"
              :data-testid="`skins-tab-${key}`"
              @click="selectTab(key)"
            >
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="TAB_ICONS[key]" /></svg>
              <span>{{ t(`skins.tabs.${key}`) }}</span>
            </button>
          </div>

          <!-- Skins ------------------------------------------------------------------ -->
          <div v-show="tab === 'skins'" id="skins-panel-skins" role="tabpanel" aria-labelledby="skins-tab-skins">
            <SkinSection id="saved" :title="t('skins.sections.saved')" :count="library.length">
              <template #aside>
                <p
                  v-if="syncLine"
                  class="flex items-center gap-1.5 text-[11px] text-base-400"
                  role="status"
                  aria-live="polite"
                  data-testid="trs-sync-status"
                >
                  <svg
                    viewBox="0 0 24 24"
                    class="size-3.5 shrink-0"
                    :class="{ 'animate-spin': trs.sync?.syncing }"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    aria-hidden="true"
                  >
                    <path v-if="trs.sync?.syncing" d="M4 12a8 8 0 0 1 14-5.3M20 4v4h-4M20 12a8 8 0 0 1-14 5.3M4 20v-4h4" />
                    <path v-else d="M7 18h10a4 4 0 0 0 .6-7.95A6 6 0 0 0 6.1 9.1 4.5 4.5 0 0 0 7 18Z" />
                  </svg>
                  {{ syncLine }}
                </p>
              </template>
              <div v-if="loading" class="skin-grid">
                <div v-for="i in 5" :key="i" class="skeleton aspect-[3/4] rounded-xl" />
              </div>
              <ul v-else class="skin-grid" data-testid="skin-library">
                <!-- Hinzufügen: Klick = Dateien wählen, Ziehen ins Fenster geht immer -->
                <li>
                  <button
                    class="add-card group flex aspect-[3/4] w-full flex-col items-center justify-center gap-2 rounded-xl px-2 text-center outline-none focus-visible:ring-2 focus-visible:ring-redstone-400"
                    :disabled="!!busy"
                    :title="t('skins.import.dropTip')"
                    data-testid="skin-add-card"
                    @click="importer?.pickFiles()"
                  >
                    <span class="grid size-9 place-items-center rounded-full bg-base-800 text-base-200 transition-colors group-hover:bg-redstone-500 group-hover:text-white">
                      <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
                    </span>
                    <span class="text-xs font-medium text-base-50">{{ t('skins.add') }}</span>
                    <span class="text-[11px] leading-tight text-base-400">{{ t('skins.dropHere') }}</span>
                  </button>
                </li>
                <li v-if="profile">
                  <SkinCard
                    :thumb="currentThumb"
                    :title="`${t('skins.currentSkin')} · ${t('skins.onAccount', { variant: t(`skins.variants.${profile.variant}`) })}`"
                    :selected="isSelected('current')"
                    data-testid="skin-card-current"
                    @select="selectCurrent"
                  >
                    <template #badges>
                      <span class="badge bg-ok/20 px-1.5 py-0 text-[10px] text-ok backdrop-blur-sm">{{ t('skins.badges.worn') }}</span>
                    </template>
                  </SkinCard>
                </li>
                <li v-for="skin in library" :key="skin.id" :data-skin="skin.id">
                  <SkinCard
                    :thumb="{ skin: skin.texture, variant: skin.variant }"
                    :title="`${skin.name} · ${t(`skins.variants.${skin.variant}`)} · ${formatShortDate(skin.addedAt)}`"
                    :selected="isSelected('library', skin.id)"
                    @select="selectLibrary(skin)"
                  >
                    <template #actions>
                      <button
                        class="card-action"
                        :disabled="!!busy"
                        :title="t('skins.rename')"
                        :aria-label="t('skins.rename')"
                        @click="startRename(skin)"
                      >
                        <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 20h4L19 9l-4-4L4 16v4ZM13.5 6.5l4 4" /></svg>
                      </button>
                      <button
                        class="card-action hover:bg-redstone-600!"
                        :disabled="!!busy"
                        :title="t('common.actions.delete')"
                        :aria-label="t('common.actions.delete')"
                        @click="toDelete = skin"
                      >
                        <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" /></svg>
                      </button>
                    </template>
                  </SkinCard>
                </li>
              </ul>
              <p v-if="!loading && !library.length" class="mt-3 text-xs text-base-400">
                <span class="font-semibold text-base-200">{{ t('skins.empty.title') }}</span> – {{ t('skins.empty.text') }}
              </p>
            </SkinSection>

            <SkinSection id="defaults" :title="t('skins.sections.defaults')" :count="builtins ? builtins.skins.length : null">
              <template #aside>
                <span v-if="builtins?.version" class="text-[11px] text-base-400">{{ t('skins.defaultsFrom', { version: builtins.version }) }}</span>
              </template>
              <div v-if="!builtins || loading" class="skin-grid">
                <div v-for="i in 6" :key="i" class="skeleton aspect-[3/4] rounded-xl" />
              </div>
              <template v-else>
                <ul class="skin-grid" data-testid="skin-defaults">
                  <!-- Zurück zum Standard des Kontos (Mojang wählt selbst) -->
                  <li v-if="profile">
                    <SkinCard
                      :thumb="accountDefaultThumb"
                      :title="`${t('skins.accountDefault')} · ${defaultSkinHint}`"
                      :selected="isSelected('default')"
                      data-testid="skin-card-reset"
                      @select="selectDefault"
                    >
                      <template #badges>
                        <span class="badge bg-base-900/75 px-1.5 py-0 text-[10px] text-base-200 backdrop-blur-sm">{{ t('skins.badges.reset') }}</span>
                      </template>
                    </SkinCard>
                  </li>
                  <li v-for="skin in builtins.skins" :key="skin.id" :data-skin="skin.id">
                    <SkinCard
                      :thumb="{ skin: skin.texture, variant: skin.variant }"
                      :title="selectableTitle(skin)"
                      :selected="isSelected('builtin', skin.id)"
                      @select="selectBuiltin(skin)"
                    >
                      <template v-if="isAssigned(skin)" #badges>
                        <span class="badge bg-redstone-900/70 px-1.5 py-0 text-[10px] text-redstone-200 backdrop-blur-sm">{{ t('skins.badges.assigned') }}</span>
                      </template>
                    </SkinCard>
                  </li>
                </ul>
                <p v-if="!builtins.skins.length" class="mt-3 text-xs text-base-400">{{ t('skins.defaultsMissing') }}</p>
              </template>
            </SkinSection>

            <!-- Offizielle Pakete: ohne Internet und Cache ausgeblendet -->
            <SkinSection v-if="packs === null || packs.length" id="packs" :title="t('skins.sections.packs')" :count="packSkinCount" data-testid="skin-packs">
              <template #aside>
                <span class="text-[11px] text-base-400">{{ t('skins.packsSource') }}</span>
              </template>
              <div v-if="packs === null" class="skin-grid">
                <div v-for="i in 6" :key="i" class="skeleton aspect-[3/4] rounded-xl" />
              </div>
              <!-- Kleine Pakete stehen nebeneinander, große brechen um -->
              <div v-else class="flex flex-wrap gap-x-6 gap-y-4">
                <div v-for="pack in packs" :key="pack.id" class="min-w-0 max-w-full" :data-pack="pack.id">
                  <p class="mb-2 flex items-baseline gap-2 text-xs">
                    <span class="truncate font-semibold text-base-50">{{ pack.name }}</span>
                    <span class="shrink-0 text-base-400">{{ formatShortDate(`${pack.released}T12:00:00Z`) }}</span>
                  </p>
                  <ul class="pack-row">
                    <li v-for="skin in pack.skins" :key="skin.id" :data-skin="skin.id">
                      <SkinCard
                        :thumb="{ skin: skin.texture, variant: skin.variant }"
                        :title="selectableTitle(skin)"
                        :selected="isSelected('builtin', skin.id)"
                        @select="selectBuiltin(skin)"
                      />
                    </li>
                  </ul>
                </div>
              </div>
            </SkinSection>
          </div>

          <!-- Umhänge: altes Raster (Umhang-Bild, Name, Badge), nur im Reiter. -->
          <div v-show="tab === 'capes'" id="skins-panel-capes" role="tabpanel" aria-labelledby="skins-tab-capes">
            <section v-if="profile">
              <h2 class="section-title mb-2">{{ t('skins.mojangCapes') }}</h2>
              <ul v-if="profile.capes.length" class="grid grid-cols-[repeat(auto-fill,minmax(8rem,1fr))] gap-3" data-testid="mojang-capes">
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
                <li v-for="cape in profile.capes" :key="cape.id" :data-cape="cape.id">
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
            <div v-else-if="loading" class="grid grid-cols-[repeat(auto-fill,minmax(8rem,1fr))] gap-3">
              <div v-for="i in 6" :key="i" class="skeleton h-32" />
            </div>

            <TrsCapes
              ref="capesSection"
              class="mt-6"
              :preview-id="trsPreview?.id ?? null"
              @preview="trsPreview = $event"
              @active="(cape) => ((trsActive = cape), (mojangFocus = false))"
            />
          </div>

          <!-- Kosmetik: altes Kartenbild, nur im Reiter. -->
          <div v-show="tab === 'cosmetics'" id="skins-panel-cosmetics" role="tabpanel" aria-labelledby="skins-tab-cosmetics">
            <TrsHeadCosmetics
              :preview-id="headPreview?.id ?? null"
              :night="night"
              @preview="previewHead"
              @equipped="headEquipped = $event"
              @redeem="capesSection?.startRedeem()"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- Dialoge -------------------------------------------------------------------- -->
    <BaseDialog v-if="toRename" :title="t('skins.renameDialog.title')" @close="toRename = null">
      <label class="label" for="skin-rename">{{ t('common.labels.name') }}</label>
      <input
        id="skin-rename"
        v-model="renameName"
        class="field"
        maxlength="48"
        autofocus
        @keydown.enter="confirmRename"
      />
      <p v-if="renameError" role="alert" class="mt-2 text-xs text-redstone-300">{{ renameError }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="toRename = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" @click="confirmRename">{{ t('common.actions.save') }}</button>
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

<style scoped>
@reference "~/assets/css/main.css";

/* Reiter wie auf der Instanz-Seite: Redstone-Leitung, der aktive Reiter „leuchtet“. */
.tabbar {
  @apply relative flex flex-wrap gap-0.5 border-b border-base-800;
}
.itab {
  @apply relative -mb-px inline-flex items-center gap-2 rounded-t-lg px-3.5 py-2 text-sm font-medium text-base-400 transition-colors outline-none hover:bg-base-900 hover:text-base-50 focus-visible:bg-base-900 focus-visible:text-base-50;
}
.itab:focus-visible {
  outline: 2px solid var(--color-redstone-400);
  outline-offset: -2px;
}
.itab-on {
  @apply text-base-50;
}
.itab-on::after {
  content: "";
  position: absolute;
  inset-inline: 0.5rem;
  bottom: 0;
  height: 2px;
  background: linear-gradient(90deg, var(--color-redstone-600), var(--color-redstone-400), var(--color-redstone-600));
  box-shadow: 0 0 8px color-mix(in srgb, var(--color-redstone-500) 80%, transparent);
}
.itab-on svg {
  @apply text-redstone-400;
  filter: drop-shadow(0 0 4px color-mix(in srgb, var(--color-redstone-500) 60%, transparent));
}
/* „Skin hinzufügen“: gestrichelte Fläche, beim Drüberfahren Redstone-Rand. */
.add-card {
  border: 1.5px dashed var(--color-base-700);
  background: color-mix(in srgb, var(--color-base-900) 70%, transparent);
  transition: border-color 0.15s, background-color 0.15s;
}
.add-card:hover:not(:disabled) {
  border-color: var(--color-redstone-500);
  background: color-mix(in srgb, var(--color-redstone-900) 25%, var(--color-base-900));
}
/* Paket-Reihe: Karten in fester Breite (wie im Raster), damit Pakete nebeneinander passen. */
.pack-row {
  @apply flex flex-wrap gap-2.5;
}
.pack-row > li {
  width: 6.75rem;
}
.card-action {
  @apply grid size-7 place-items-center rounded-md bg-base-900/85 text-base-50 shadow-md backdrop-blur-sm transition-colors hover:bg-base-700 disabled:opacity-50;
}
</style>
