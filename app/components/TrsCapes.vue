<script setup lang="ts">
import type { TrsCape } from '~/utils/trs'

// TRS-Umhänge auf der Skins-Seite: Katalog mit Sperr-Status, Vorschau in der
// großen 3D-Ansicht der Seite (per `preview`), Anlegen/Ablegen, Code einlösen
// und eigene Umhänge hochladen (Prüfung durch das Team).
const props = defineProps<{ previewId: string | null }>()
const emit = defineEmits<{ preview: [cape: TrsCape | null] }>()

const trs = useTrsStore()
const accounts = useAccountsStore()
const toasts = useToasts()

const capes = ref<TrsCape[] | null>(null)
const loading = ref(false)
const offline = ref(false)
const busy = ref<string | null>(null)

const active = computed(() => capes.value?.find((c) => c.active) ?? null)
const selected = computed(() => capes.value?.find((c) => c.id === props.previewId) ?? null)
const pendingCount = computed(() => capes.value?.filter((c) => c.kind === 'upload' && c.status === 'pending').length ?? 0)

async function load() {
  if (!trs.enabled || !accounts.active) {
    capes.value = null
    return
  }
  loading.value = true
  try {
    capes.value = await backend.trs.capes()
    offline.value = false
    // Die Vorschau zeigt die aktuellen Daten (z. B. nach einer Freigabe).
    if (props.previewId) emit('preview', capes.value.find((c) => c.id === props.previewId) ?? null)
  } catch (e) {
    if (e instanceof BackendError && e.kind === 'trs_offline') offline.value = true
    else if (!(e instanceof BackendError && trsIsQuiet(e.kind))) toasts.error(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => [trs.enabled, accounts.active?.id], load)

function preview(cape: TrsCape) {
  emit('preview', props.previewId === cape.id ? null : cape)
}

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

async function wear(cape: TrsCape | null) {
  await run('wear', async () => {
    await backend.trs.setCape(cape?.id ?? null)
    toasts.ok(cape ? `„${cape.name}“ angelegt` : 'TRS-Umhang abgelegt')
    await load()
  })
}

// --- Code einlösen ------------------------------------------------------------------

const redeeming = ref(false)
const code = ref('')
const codeError = ref<string | null>(null)

function startRedeem() {
  code.value = ''
  codeError.value = null
  redeeming.value = true
}

async function redeem() {
  const parsed = trsRedeemCodeSchema.safeParse(code.value)
  if (!parsed.success) {
    codeError.value = firstIssue(parsed.error)
    return
  }
  codeError.value = null
  await run('redeem', async () => {
    try {
      const result = await backend.trs.redeem(parsed.data)
      redeeming.value = false
      toasts.ok(result.alreadyOwned ? `„${result.name}“ hattest du schon.` : `„${result.name}“ freigeschaltet!`)
      await load()
      const cape = capes.value?.find((c) => c.id === result.capeId)
      if (cape) emit('preview', cape)
    } catch (e) {
      // Im Dialog anzeigen statt als Toast – der Code steht ja noch da.
      codeError.value = errorMessage(e)
    }
  })
}

// --- Hochladen ----------------------------------------------------------------------

const uploading = ref(false)
const uploadName = ref('')
const uploadError = ref<string | null>(null)

function startUpload() {
  uploadName.value = ''
  uploadError.value = null
  uploading.value = true
}

async function upload() {
  const parsed = trsCapeNameSchema.safeParse(uploadName.value)
  if (!parsed.success) {
    uploadError.value = firstIssue(parsed.error)
    return
  }
  uploadError.value = null
  await run('upload', async () => {
    try {
      const cape = await backend.trs.uploadCape(parsed.data || null)
      if (!cape) return
      uploading.value = false
      toasts.ok('Hochgeladen – dein Umhang wartet auf Freigabe.')
      await load()
      emit('preview', capes.value?.find((c) => c.id === cape.id) ?? cape)
    } catch (e) {
      uploadError.value = errorMessage(e)
    }
  })
}

const toDelete = ref<TrsCape | null>(null)
async function confirmDelete() {
  const cape = toDelete.value
  toDelete.value = null
  if (!cape) return
  await run('delete', async () => {
    await backend.trs.deleteCape(cape.id)
    if (props.previewId === cape.id) emit('preview', null)
    toasts.ok('Umhang gelöscht')
    await load()
  })
}

function lockClass(cape: TrsCape) {
  if (cape.kind === 'upload') return 'bg-base-800 text-base-200'
  if (cape.unlock === 'free') return 'bg-ok/10 text-ok'
  if (cape.unlock === 'code') return 'bg-lamp-900/60 text-lamp-300'
  return 'bg-redstone-900/50 text-redstone-300'
}
</script>

<template>
  <section aria-labelledby="trs-capes-title">
    <div class="mb-2 flex flex-wrap items-center gap-2">
      <h2 id="trs-capes-title" class="section-title">TRS-Umhänge</h2>
      <span v-if="active" class="badge bg-ok/10 text-ok">Getragen: {{ active.name }}</span>
      <div v-if="trs.enabled && accounts.active" class="ml-auto flex gap-2">
        <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="!!busy || offline" @click="startRedeem">Code einlösen</button>
        <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="!!busy || offline" @click="startUpload">
          Eigenen Umhang hochladen
        </button>
      </div>
    </div>
    <p class="mb-3 text-xs text-base-400">
      TRS-Umhänge sind unabhängig von deinen Mojang-Umhängen: Sie sehen alle, die mit dem TRS Client spielen. Deinen
      Mojang-Umhang änderst du oben – beides bleibt getrennt.
    </p>

    <TrsGate what="TRS-Umhänge">
      <div v-if="offline" class="card flex items-center gap-3 px-4 py-3 text-sm text-base-400">
        <span class="size-2 rounded-full bg-base-600" />
        <span class="flex-1">Der TRS-Server ist gerade nicht erreichbar.</span>
        <button class="btn btn-ghost px-3 py-1 text-xs" :disabled="loading" @click="load">Erneut versuchen</button>
      </div>
      <div v-else-if="loading && !capes" class="grid grid-cols-[repeat(auto-fill,minmax(8rem,1fr))] gap-3">
        <div v-for="i in 6" :key="i" class="skeleton h-32" />
      </div>
      <template v-else-if="capes">
        <ul class="grid grid-cols-[repeat(auto-fill,minmax(8rem,1fr))] gap-3" data-testid="trs-capes">
          <li v-for="cape in capes" :key="cape.id">
            <button
              class="card card-hover flex h-full w-full flex-col items-center gap-2 p-3"
              :class="{
                'border-redstone-600/60 bg-redstone-900/20': previewId === cape.id,
                'opacity-60': !cape.owned && cape.kind !== 'upload',
              }"
              :aria-pressed="previewId === cape.id"
              :title="cape.owned ? `${cape.name} anprobieren` : `${cape.name} – noch gesperrt (anprobieren geht trotzdem)`"
              @click="preview(cape)"
            >
              <span class="relative">
                <CapeThumb
                  :texture="cape.texture"
                  :scale="cape.scale"
                  :frames="cape.frames"
                  :frame-time-ms="cape.frameTimeMs"
                  :width="30"
                />
                <svg
                  v-if="!cape.owned && cape.kind !== 'upload'"
                  viewBox="0 0 24 24"
                  class="absolute -right-2 -bottom-1 size-4 rounded bg-base-900 p-0.5 text-base-200"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  aria-label="gesperrt"
                >
                  <path d="M7 11V8a5 5 0 0 1 10 0v3M5 11h14v10H5z" />
                </svg>
              </span>
              <span class="w-full truncate text-center text-xs">{{ cape.name }}</span>
              <span class="flex flex-wrap justify-center gap-1">
                <span class="badge px-1.5 py-0 text-[10px]" :class="lockClass(cape)">{{ trsUnlockLabel(cape) }}</span>
                <span v-if="cape.frames > 1" class="badge bg-base-800 px-1.5 py-0 text-[10px] text-base-200">animiert</span>
              </span>
              <span v-if="cape.active" class="text-[10px] text-ok">getragen</span>
              <span
                v-else-if="trsStatusLabel(cape.status)"
                class="text-[10px]"
                :class="cape.status === 'rejected' ? 'text-redstone-300' : 'text-lamp-300'"
              >
                {{ trsStatusLabel(cape.status) }}
              </span>
            </button>
          </li>
        </ul>
        <p v-if="!capes.length" class="card px-4 py-6 text-center text-sm text-base-400">
          Gerade gibt es keine TRS-Umhänge.
        </p>

        <!-- Aktionen für den angeprobten Umhang -->
        <div v-if="selected" class="card mt-3 flex flex-wrap items-center gap-3 px-4 py-3" data-testid="trs-cape-actions">
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-semibold text-base-50">{{ selected.name }}</p>
            <p class="text-xs text-base-400">
              <template v-if="selected.kind === 'upload' && selected.status === 'pending'">
                Wartet auf Freigabe durch das Team – bis dahin siehst nur du ihn.
              </template>
              <template v-else-if="selected.kind === 'upload' && selected.status === 'rejected'">
                Abgelehnt{{ selected.rejectReason ? `: ${selected.rejectReason}` : '' }}.
              </template>
              <template v-else-if="!selected.owned && selected.unlock === 'code'">Gesperrt – mit einem Code freischaltbar.</template>
              <template v-else-if="!selected.owned">Gesperrt – nur für das TRS-Team bzw. per Code.</template>
              <template v-else-if="selected.active">Du trägst diesen Umhang.</template>
              <template v-else-if="selected.unlock === 'free'">Frei verfügbar – anlegen, damit ihn andere sehen.</template>
              <template v-else>Für dich freigeschaltet – anlegen, damit ihn andere sehen.</template>
            </p>
          </div>
          <button
            v-if="selected.owned && !selected.active"
            class="btn btn-primary px-3 py-1.5 text-xs"
            :disabled="!!busy"
            @click="wear(selected)"
          >
            {{ busy === 'wear' ? 'Lege an …' : 'Anlegen' }}
          </button>
          <button v-if="selected.active" class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="!!busy" @click="wear(null)">
            {{ busy === 'wear' ? 'Lege ab …' : 'Ablegen' }}
          </button>
          <button
            v-if="!selected.owned && selected.unlock === 'code'"
            class="btn btn-ghost px-3 py-1.5 text-xs"
            :disabled="!!busy"
            @click="startRedeem"
          >
            Code einlösen
          </button>
          <button
            v-if="selected.kind === 'upload'"
            class="btn btn-ghost px-3 py-1.5 text-xs hover:text-redstone-300"
            :disabled="!!busy"
            @click="toDelete = selected"
          >
            Löschen
          </button>
          <button class="btn btn-ghost px-3 py-1.5 text-xs" @click="emit('preview', null)">Vorschau beenden</button>
        </div>
        <p v-if="pendingCount" class="mt-2 text-[11px] text-base-600">
          {{ pendingCount }} {{ pendingCount === 1 ? 'Umhang wartet' : 'Umhänge warten' }} auf Freigabe.
        </p>
      </template>
    </TrsGate>

    <BaseDialog v-if="redeeming" title="Code einlösen" @close="redeeming = false">
      <label class="label" for="trs-code">Code</label>
      <input
        id="trs-code"
        v-model="code"
        class="field font-mono tracking-wider uppercase"
        maxlength="64"
        placeholder="XXXXX-XXXXX-XXXXX-XXXXX"
        autocomplete="off"
        spellcheck="false"
        autofocus
        @keydown.enter="redeem"
      />
      <p class="mt-2 text-xs text-base-400">Groß-/Kleinschreibung und Bindestriche sind egal.</p>
      <p v-if="codeError" role="alert" class="mt-2 text-xs text-redstone-300">{{ codeError }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="redeeming = false">Abbrechen</button>
        <button class="btn btn-primary" :disabled="busy === 'redeem'" @click="redeem">
          {{ busy === 'redeem' ? 'Prüfe …' : 'Einlösen' }}
        </button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="uploading" title="Eigenen Umhang hochladen" @close="uploading = false">
      <label class="label" for="trs-cape-name">Name (optional)</label>
      <input
        id="trs-cape-name"
        v-model="uploadName"
        class="field"
        maxlength="32"
        placeholder="z. B. Mein Umhang"
        autofocus
        @keydown.enter="upload"
      />
      <ul class="mt-3 list-disc space-y-1 pl-4 text-xs text-base-400">
        <li>PNG mit 64×32 Pixeln (oder 128×64, 192×96, 256×128) bzw. im Umhang-Format 22×17 – höchstens 256 KB.</li>
        <li>Keine Animation. Das Team prüft jeden Umhang, bis dahin siehst nur du ihn.</li>
        <li>Nur eigene Bilder oder solche, die du verwenden darfst – nichts Anstößiges.</li>
      </ul>
      <p v-if="uploadError" role="alert" class="mt-2 text-xs text-redstone-300">{{ uploadError }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="uploading = false">Abbrechen</button>
        <button class="btn btn-primary" :disabled="busy === 'upload'" @click="upload">
          {{ busy === 'upload' ? 'Lade hoch …' : 'Datei wählen' }}
        </button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="toDelete" title="Umhang löschen?" @close="toDelete = null">
      <p class="text-sm text-base-200">
        <strong class="text-base-50">{{ toDelete.name }}</strong> wird endgültig gelöscht.
      </p>
      <template #actions>
        <button class="btn btn-ghost" @click="toDelete = null">Abbrechen</button>
        <button class="btn btn-danger" @click="confirmDelete">Löschen</button>
      </template>
    </BaseDialog>
  </section>
</template>
