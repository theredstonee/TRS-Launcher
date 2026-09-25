<script setup lang="ts">
import { isTauri } from '@tauri-apps/api/core'
import { listen, type UnlistenFn } from '@tauri-apps/api/event'
import type {
  DropEvent,
  LauncherSkinScan,
  LibrarySkin,
  SkinImportBatch,
  SkinImportCandidate,
  SkinImportFailure,
  SkinImportRequest,
  SkinVariant,
} from '~/types'
import { trsPlayerNameSchema } from '~/utils/trs'
import { bulkPlan, defaultSelection, groupBySource, isLauncherSource, selectedRequests, unusedTokens } from '~/utils/skinImport'

// „+ Skin hinzufügen“ nach Essential-Vorbild: Menü mit Dateien (mehrere auf
// einmal), Link, Spielername und anderen Launchern, dazu Drag & Drop ins
// Fenster. Jede Quelle wird im Kern gelesen und geprüft und nur VORGEMERKT –
// hier kommen Marke, Vorschlag und Vorschaubild an. Übernommen wird per Marke;
// Bytes oder Pfade schickt diese Komponente nie an den Kern.
defineProps<{ disabled?: boolean }>()
const emit = defineEmits<{ imported: [skins: LibrarySkin[]] }>()

const toasts = useToasts()

type Busy = 'files' | 'drop' | 'url' | 'player' | 'scan' | 'import'
const busy = ref<Busy | null>(null)

// --- Menü -------------------------------------------------------------------------
const menuOpen = ref(false)
const root = useTemplateRef<HTMLElement>('root')
const trigger = useTemplateRef<HTMLButtonElement>('trigger')

function onDocPointer(e: PointerEvent) {
  if (menuOpen.value && root.value && !root.value.contains(e.target as Node)) menuOpen.value = false
}
function onKey(e: KeyboardEvent) {
  if (menuOpen.value && e.key === 'Escape') {
    menuOpen.value = false
    trigger.value?.focus()
  }
}

function choose(action: () => void) {
  menuOpen.value = false
  action()
}

// --- Gemeinsame Abläufe --------------------------------------------------------------

function reportFailures(failed: SkinImportFailure[]) {
  for (const f of failed) toasts.error(`${f.name}: ${f.errorInfo ? userErrorText(f.errorInfo) : f.error}`)
}

function discard(tokens: string[]) {
  if (tokens.length) backend.discardStagedSkins(tokens).catch(() => {})
}

/** Übernimmt vorgemerkte Skins und meldet das Ergebnis. */
async function importRequests(requests: SkinImportRequest[]) {
  if (!requests.length) return
  busy.value = 'import'
  try {
    const report = await backend.importStagedSkins(requests)
    reportFailures(report.failed)
    if (report.added.length === 1) toasts.ok(t('skins.addedToast', { name: report.added[0]!.name }))
    else if (report.added.length > 1) toasts.ok(t('skins.import.toasts.imported', report.added.length))
    if (report.added.length) emit('imported', report.added)
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

/**
 * Dateien (Dialog oder Drag & Drop): eine einzelne → Bestätigen mit Name und
 * Modell; mehrere → direkt mit Dateiname und erkanntem Modell übernehmen.
 */
async function handleBatch(batch: SkinImportBatch) {
  reportFailures(batch.failed)
  const candidates = batch.candidates
  if (candidates.length === 1) {
    openConfirm(candidates[0]!)
    return
  }
  if (!candidates.length) return
  const plan = bulkPlan(candidates)
  discard(plan.skipped)
  if (!plan.requests.length) {
    toasts.info(t('skins.import.toasts.allDuplicates'))
    return
  }
  if (plan.skipped.length) toasts.info(t('skins.import.toasts.skipped', plan.skipped.length))
  await importRequests(plan.requests)
}

async function run(kind: Busy, action: () => Promise<void>) {
  if (busy.value) return
  busy.value = kind
  try {
    await action()
  } catch (e) {
    toasts.error(e)
  } finally {
    if (busy.value === kind) busy.value = null
  }
}

// --- Dateien ---------------------------------------------------------------------------
function pickFiles() {
  return run('files', async () => {
    const batch = await backend.pickSkinFiles()
    busy.value = null
    if (batch) await handleBatch(batch)
  })
}

// --- Drag & Drop (Pfade bleiben im Kern, hier nur die Marke) ---------------------------
const dragging = ref(false)
let unlisten: UnlistenFn | null = null

onMounted(async () => {
  document.addEventListener('pointerdown', onDocPointer)
  document.addEventListener('keydown', onKey)
  if (!isTauri()) return
  unlisten = await listen<DropEvent>('file-drop', ({ payload }) => {
    if (payload.type === 'enter') dragging.value = !busy.value
    else if (payload.type === 'leave') dragging.value = false
    else {
      dragging.value = false
      void run('drop', async () => {
        const batch = await backend.stageDroppedSkins(payload.token)
        busy.value = null
        await handleBatch(batch)
      })
    }
  })
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocPointer)
  document.removeEventListener('keydown', onKey)
  unlisten?.()
  // Offene Vormerkungen nicht liegen lassen.
  if (confirmSkin.value) discard([confirmSkin.value.token])
  if (scan.value) discard(scan.value.candidates.map((c) => c.token))
})

// --- Einzelner Skin: Name + Modell bestätigen --------------------------------------------
const confirmSkin = ref<SkinImportCandidate | null>(null)
const confirmName = ref('')
const confirmVariant = ref<SkinVariant>('classic')
const confirmError = ref<string | null>(null)

function openConfirm(candidate: SkinImportCandidate) {
  if (confirmSkin.value) discard([confirmSkin.value.token])
  confirmSkin.value = candidate
  confirmName.value = candidate.name
  confirmVariant.value = candidate.variant
  confirmError.value = null
}

function closeConfirm() {
  if (confirmSkin.value) discard([confirmSkin.value.token])
  confirmSkin.value = null
}

async function submitConfirm() {
  const skin = confirmSkin.value
  if (!skin || busy.value) return
  const parsed = skinNameSchema.safeParse(confirmName.value)
  if (!parsed.success) {
    confirmError.value = firstIssue(parsed.error)
    return
  }
  confirmSkin.value = null
  await importRequests([{ token: skin.token, name: parsed.data, variant: confirmVariant.value }])
}

// --- Per Link ------------------------------------------------------------------------------
const urlOpen = ref(false)
const urlValue = ref('')
const urlError = ref<string | null>(null)

function openUrl() {
  urlValue.value = ''
  urlError.value = null
  urlOpen.value = true
}

async function submitUrl() {
  const parsed = skinUrlSchema.safeParse(urlValue.value)
  if (!parsed.success) {
    urlError.value = firstIssue(parsed.error)
    return
  }
  if (busy.value) return
  urlError.value = null
  busy.value = 'url'
  try {
    const candidate = await backend.stageSkinUrl(parsed.data)
    urlOpen.value = false
    openConfirm(candidate)
  } catch (e) {
    urlError.value = errorMessage(e)
  } finally {
    busy.value = null
  }
}

// --- Per Spielername ------------------------------------------------------------------------
const playerOpen = ref(false)
const playerValue = ref('')
const playerError = ref<string | null>(null)

function openPlayer() {
  playerValue.value = ''
  playerError.value = null
  playerOpen.value = true
}

async function submitPlayer() {
  const parsed = trsPlayerNameSchema.safeParse(playerValue.value)
  if (!parsed.success) {
    playerError.value = firstIssue(parsed.error)
    return
  }
  if (busy.value) return
  playerError.value = null
  busy.value = 'player'
  try {
    const candidate = await backend.stagePlayerSkin(parsed.data)
    playerOpen.value = false
    openConfirm(candidate)
  } catch (e) {
    playerError.value = errorMessage(e)
  } finally {
    busy.value = null
  }
}

// --- Aus anderen Launchern ---------------------------------------------------------------------
const launcherOpen = ref(false)
const scan = ref<LauncherSkinScan | null>(null)
const scanError = ref<string | null>(null)
const selected = ref<Set<string>>(new Set())

const groups = computed(() => groupBySource(scan.value?.candidates ?? []))
/** Gefundene Launcher ohne gespeicherte Skins. */
const emptySources = computed(() => {
  const withSkins = new Set(groups.value.map((g) => g.source))
  const found = (scan.value?.found ?? [])
    .filter(isLauncherSource)
    .filter((s) => !withSkins.has(s))
    .map((s) => t(`skins.import.sources.${s}`))
  // Lunar, Badlion, Feather & Co. speichern gar keine Skin-Liste auf der Platte.
  const without = (scan.value?.withoutSkins ?? []).map((s) => importSourceLabel(s))
  return [...found, ...without]
})

async function openLaunchers() {
  if (busy.value) return
  launcherOpen.value = true
  scan.value = null
  scanError.value = null
  busy.value = 'scan'
  try {
    const result = await backend.scanLauncherSkins()
    scan.value = result
    selected.value = defaultSelection(result.candidates)
  } catch (e) {
    scanError.value = errorMessage(e)
  } finally {
    busy.value = null
  }
}

function toggle(token: string) {
  const next = new Set(selected.value)
  if (next.has(token)) next.delete(token)
  else next.add(token)
  selected.value = next
}

function selectAll(on: boolean) {
  selected.value = on ? new Set(scan.value?.candidates.map((c) => c.token) ?? []) : new Set()
}

function closeLaunchers() {
  if (scan.value) discard(scan.value.candidates.map((c) => c.token))
  scan.value = null
  launcherOpen.value = false
}

async function importFromLaunchers() {
  const result = scan.value
  if (!result || busy.value) return
  const requests = selectedRequests(result.candidates, selected.value)
  discard(unusedTokens(result.candidates, requests.map((r) => r.token)))
  scan.value = null
  launcherOpen.value = false
  await importRequests(requests)
}

// --- Vorschau ------------------------------------------------------------------------------------
/** Gesicht (8×8) samt Hut-Ebene aus der Skin-Textur. */
function faceLayers(texture: string, size = 40) {
  const base = {
    backgroundImage: `url("${texture}")`,
    backgroundSize: `${size * 8}px ${size * 8}px`,
    width: `${size}px`,
    height: `${size}px`,
  }
  return [
    { ...base, backgroundPosition: `-${size}px -${size}px` },
    { ...base, backgroundPosition: `-${size * 5}px -${size}px` },
  ]
}

defineExpose({ pickFiles })
</script>

<template>
  <div ref="root" class="relative">
    <button
      ref="trigger"
      class="btn btn-primary"
      :disabled="disabled || !!busy"
      :aria-expanded="menuOpen"
      aria-haspopup="menu"
      data-testid="skin-add"
      @click="menuOpen = !menuOpen"
    >
      <svg v-if="busy" viewBox="0 0 24 24" class="size-4 animate-spin" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M12 3a9 9 0 1 0 9 9" /></svg>
      <svg v-else viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 5v14M5 12h14" /></svg>
      {{ busy ? t('skins.import.loading') : t('skins.add') }}
    </button>

    <div v-if="menuOpen" class="menu top-full right-0 z-30 mt-1 w-72 animate-pop" role="menu" :aria-label="t('skins.add')">
      <button class="menu-item gap-3 py-2" role="menuitem" data-testid="skin-add-files" @click="choose(pickFiles)">
        <svg viewBox="0 0 24 24" class="size-4 shrink-0" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8Z" /><path d="M14 3v5h5M12 11v6M9 14h6" /></svg>
        <span class="min-w-0 flex-1">
          <span class="block font-medium text-base-50">{{ t('skins.import.fromFiles') }}</span>
          <span class="block text-[11px] text-base-400">{{ t('skins.import.fromFilesHint') }}</span>
        </span>
      </button>
      <button class="menu-item gap-3 py-2" role="menuitem" data-testid="skin-add-url" @click="choose(openUrl)">
        <svg viewBox="0 0 24 24" class="size-4 shrink-0" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1" /><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1" /></svg>
        <span class="min-w-0 flex-1">
          <span class="block font-medium text-base-50">{{ t('skins.import.fromUrl') }}</span>
          <span class="block text-[11px] text-base-400">{{ t('skins.import.fromUrlHint') }}</span>
        </span>
      </button>
      <button class="menu-item gap-3 py-2" role="menuitem" data-testid="skin-add-player" @click="choose(openPlayer)">
        <svg viewBox="0 0 24 24" class="size-4 shrink-0" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="8" r="4" /><path d="M4 20c1.5-4 4.5-6 8-6s6.5 2 8 6" /></svg>
        <span class="min-w-0 flex-1">
          <span class="block font-medium text-base-50">{{ t('skins.import.fromPlayer') }}</span>
          <span class="block text-[11px] text-base-400">{{ t('skins.import.fromPlayerHint') }}</span>
        </span>
      </button>
      <button class="menu-item gap-3 py-2" role="menuitem" data-testid="skin-add-launchers" @click="choose(openLaunchers)">
        <svg viewBox="0 0 24 24" class="size-4 shrink-0" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M4 7h16M4 12h16M4 17h10" /><path d="m17 15 3 3-3 3" /></svg>
        <span class="min-w-0 flex-1">
          <span class="block font-medium text-base-50">{{ t('skins.import.fromLaunchers') }}</span>
          <span class="block text-[11px] text-base-400">{{ t('skins.import.fromLaunchersHint') }}</span>
        </span>
      </button>
      <div class="my-1 h-px bg-base-700" />
      <p class="px-2.5 pt-0.5 pb-1.5 text-[11px] text-base-400">{{ t('skins.import.dropTip') }}</p>
    </div>

    <!-- Drag & Drop -->
    <Teleport to="body">
      <Transition name="toast">
        <div v-if="dragging" class="pointer-events-none fixed inset-0 z-40 grid place-items-center bg-black/55 backdrop-blur-sm">
          <div class="rounded-2xl border-2 border-dashed border-redstone-400 bg-base-900/90 px-12 py-10 text-center">
            <svg viewBox="0 0 24 24" class="mx-auto size-10 text-redstone-400" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 3v12m0 0-4-4m4 4 4-4M4 17v3h16v-3" /></svg>
            <p class="display mt-3 text-2xl">{{ t('skins.import.drop.title') }}</p>
            <p class="mt-1 text-sm text-base-400">{{ t('skins.import.drop.hint') }}</p>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- Einzelner Skin: Name + Modell ------------------------------------------------ -->
    <BaseDialog v-if="confirmSkin" :title="t('skins.import.confirm.title')" wide @close="closeConfirm">
      <div class="grid gap-4 sm:grid-cols-[180px_1fr]">
        <div class="rounded-lg bg-gradient-to-b from-base-850 to-base-950">
          <ClientOnly>
            <SkinViewer :skin="confirmSkin.texture" :variant="confirmVariant" animation="idle" :height="220" />
          </ClientOnly>
        </div>
        <div class="min-w-0">
          <label class="label" for="skin-import-name">{{ t('common.labels.name') }}</label>
          <input
            id="skin-import-name"
            v-model="confirmName"
            class="field"
            maxlength="48"
            :placeholder="t('skins.addDialog.namePlaceholder')"
            autofocus
            @keydown.enter="submitConfirm"
          />
          <p class="label mt-4">{{ t('skins.model') }}</p>
          <div class="grid grid-cols-2 gap-2" role="radiogroup" :aria-label="t('skins.model')">
            <button
              v-for="v in skinVariants"
              :key="v"
              class="rounded-lg border px-3 py-2.5 text-left transition-colors"
              :class="confirmVariant === v ? 'border-redstone-500 bg-redstone-900/40' : 'border-base-700 hover:border-base-600'"
              role="radio"
              :aria-checked="confirmVariant === v"
              @click="confirmVariant = v"
            >
              <span class="block text-sm font-semibold">{{ t(`skins.variants.${v}`) }}</span>
              <span class="block text-xs text-base-400">{{ t(`skins.variantHints.${v}`) }}</span>
            </button>
          </div>
          <p class="mt-3 text-xs text-base-400">{{ t('skins.import.confirm.detected') }}</p>
          <p v-if="confirmSkin.duplicate" class="mt-2 text-xs text-warn">{{ t('skins.import.confirm.duplicate') }}</p>
          <p v-if="confirmError" role="alert" class="mt-2 text-xs text-redstone-300">{{ confirmError }}</p>
        </div>
      </div>
      <template #actions>
        <button class="btn btn-ghost" @click="closeConfirm">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="!!busy" data-testid="skin-import-confirm" @click="submitConfirm">
          {{ t('skins.import.confirm.add') }}
        </button>
      </template>
    </BaseDialog>

    <!-- Per Link ------------------------------------------------------------------------ -->
    <BaseDialog v-if="urlOpen" :title="t('skins.import.url.title')" @close="urlOpen = false">
      <label class="label" for="skin-import-url">{{ t('skins.import.url.label') }}</label>
      <input
        id="skin-import-url"
        v-model="urlValue"
        class="field"
        type="url"
        maxlength="2048"
        inputmode="url"
        spellcheck="false"
        autocomplete="off"
        :placeholder="t('skins.import.url.placeholder')"
        autofocus
        @keydown.enter="submitUrl"
      />
      <p class="mt-2 text-xs text-base-400">{{ t('skins.import.url.hint') }}</p>
      <p v-if="urlError" role="alert" class="mt-2 text-xs text-redstone-300">{{ urlError }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="urlOpen = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="busy === 'url'" @click="submitUrl">
          {{ busy === 'url' ? t('skins.import.loading') : t('skins.import.url.load') }}
        </button>
      </template>
    </BaseDialog>

    <!-- Per Spielername -------------------------------------------------------------------- -->
    <BaseDialog v-if="playerOpen" :title="t('skins.import.player.title')" @close="playerOpen = false">
      <label class="label" for="skin-import-player">{{ t('skins.import.player.label') }}</label>
      <input
        id="skin-import-player"
        v-model="playerValue"
        class="field"
        maxlength="16"
        spellcheck="false"
        autocomplete="off"
        :placeholder="t('skins.import.player.placeholder')"
        autofocus
        @keydown.enter="submitPlayer"
      />
      <p class="mt-2 text-xs text-base-400">{{ t('skins.import.player.hint') }}</p>
      <p v-if="playerError" role="alert" class="mt-2 text-xs text-redstone-300">{{ playerError }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="playerOpen = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="busy === 'player'" @click="submitPlayer">
          {{ busy === 'player' ? t('skins.import.loading') : t('skins.import.player.load') }}
        </button>
      </template>
    </BaseDialog>

    <!-- Aus anderen Launchern --------------------------------------------------------------- -->
    <BaseDialog v-if="launcherOpen" :title="t('skins.import.launchers.title')" wide @close="closeLaunchers">
      <div v-if="busy === 'scan'" class="flex items-center gap-2 py-6 text-sm text-base-400" role="status">
        <svg viewBox="0 0 24 24" class="size-4 animate-spin" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M12 3a9 9 0 1 0 9 9" /></svg>
        {{ t('skins.import.launchers.scanning') }}
      </div>
      <p v-else-if="scanError" role="alert" class="text-sm text-redstone-300">{{ scanError }}</p>
      <template v-else-if="scan">
        <p v-if="!groups.length" class="py-4 text-center text-sm text-base-400">{{ t('skins.import.launchers.empty') }}</p>
        <template v-else>
          <div class="mb-2 flex justify-end gap-2 text-xs">
            <button class="text-base-400 hover:text-base-50 hover:underline" @click="selectAll(true)">{{ t('skins.import.launchers.selectAll') }}</button>
            <button class="text-base-400 hover:text-base-50 hover:underline" @click="selectAll(false)">{{ t('skins.import.launchers.selectNone') }}</button>
          </div>
          <div class="max-h-[50vh] space-y-4 overflow-y-auto pr-1">
            <section v-for="group in groups" :key="group.source">
              <h3 class="mb-1.5 flex items-baseline justify-between text-sm font-semibold">
                {{ t(`skins.import.sources.${group.source}`) }}
                <span class="text-[11px] font-normal text-base-400">{{ t('skins.import.launchers.count', group.items.length) }}</span>
              </h3>
              <ul class="grid grid-cols-1 gap-1.5 sm:grid-cols-2">
                <li v-for="skin in group.items" :key="skin.token">
                  <label
                    class="flex cursor-pointer items-center gap-3 rounded-lg border px-2.5 py-2 transition-colors"
                    :class="selected.has(skin.token) ? 'border-redstone-600/60 bg-redstone-900/20' : 'border-base-700 hover:border-base-600'"
                  >
                    <input type="checkbox" class="accent-redstone-500" :checked="selected.has(skin.token)" @change="toggle(skin.token)" />
                    <span class="relative size-10 shrink-0 overflow-hidden rounded bg-base-800 [image-rendering:pixelated]">
                      <span v-for="(layer, i) in faceLayers(skin.texture)" :key="i" class="absolute inset-0" :style="layer" />
                    </span>
                    <span class="min-w-0 flex-1">
                      <span class="block truncate text-sm font-medium">{{ skin.name }}</span>
                      <span class="block text-[11px] text-base-400">
                        {{ t(`skins.variants.${skin.variant}`) }}
                        <template v-if="skin.duplicate"> · <span class="text-warn">{{ t('skins.import.launchers.duplicate') }}</span></template>
                      </span>
                    </span>
                  </label>
                </li>
              </ul>
            </section>
          </div>
        </template>
        <p v-if="emptySources.length" class="mt-3 text-xs text-base-400">
          {{ t('skins.import.launchers.noSkins', { names: emptySources.join(', ') }) }}
        </p>
        <p class="mt-2 text-xs text-base-600">{{ t('skins.import.launchers.onlineOnly') }}</p>
      </template>
      <template #actions>
        <button class="btn btn-ghost" @click="closeLaunchers">{{ t('common.actions.cancel') }}</button>
        <button
          class="btn btn-primary"
          :disabled="!!busy || !selected.size || !scan"
          data-testid="skin-import-launchers"
          @click="importFromLaunchers"
        >
          {{ t('skins.import.launchers.import', selected.size) }}
        </button>
      </template>
    </BaseDialog>
  </div>
</template>
