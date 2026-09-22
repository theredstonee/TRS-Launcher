<script setup lang="ts">
import type { Cape, LibrarySkin, SkinProfile, SkinVariant } from '~/types'

// Skins & Umhänge des aktiven Accounts. Alle Mojang-Aufrufe laufen im Kern –
// das Webview sieht nie ein Token, nur fertige Texturen als Data-URL.
const toasts = useToasts()
const accounts = useAccountsStore()

const profile = ref<SkinProfile | null>(null)
const library = ref<LibrarySkin[]>([])
const loading = ref(true)
const loadError = ref<string | null>(null)
const busy = ref<string | null>(null)

/** Was gerade in der 3D-Vorschau steht: der getragene Skin oder einer aus der Sammlung. */
const previewId = ref<string | null>(null)
const animation = ref<'walk' | 'idle' | 'none'>('walk')

const preview = computed(() => library.value.find((s) => s.id === previewId.value) ?? null)
const previewSkin = computed(() => preview.value?.texture ?? profile.value?.skin ?? null)
const previewVariant = computed<SkinVariant>(() => preview.value?.variant ?? profile.value?.variant ?? 'classic')
const activeCape = computed(() => profile.value?.capes.find((c) => c.active) ?? null)

async function loadLibrary() {
  library.value = await backend.skinLibrary()
}

async function load() {
  loading.value = true
  loadError.value = null
  try {
    await loadLibrary()
  } catch (e) {
    toasts.error(e)
  }
  try {
    profile.value = await backend.skinProfile()
  } catch (e) {
    loadError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (!accounts.loaded) accounts.load().catch(() => {})
  load()
})

/** Führt eine Aktion aus und hält so lange alle Knöpfe an. */
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
    previewId.value = added.id
    toasts.ok(`„${added.name}“ zur Sammlung hinzugefügt`)
  })
}

async function saveActive() {
  const name = `${profile.value?.name ?? 'Skin'} ${new Date().toLocaleDateString('de-DE')}`
  await run('save', async () => {
    const saved = await backend.saveActiveSkin(name.slice(0, 48))
    await loadLibrary()
    toasts.ok(`Aktueller Skin als „${saved.name}“ gesichert`)
  })
}

const toDelete = ref<LibrarySkin | null>(null)
async function confirmDelete() {
  const skin = toDelete.value
  toDelete.value = null
  if (!skin) return
  await run('delete', async () => {
    await backend.deleteSkin(skin.id)
    if (previewId.value === skin.id) previewId.value = null
    await loadLibrary()
  })
}

// --- Mojang-Konto ------------------------------------------------------------------

async function apply(skin: LibrarySkin, variant: SkinVariant) {
  await run(`apply-${skin.id}`, async () => {
    profile.value = await backend.applySkin(skin.id, variant)
    previewId.value = null
    await accounts.load().catch(() => {})
    toasts.ok(`„${skin.name}“ wird jetzt getragen`)
  })
}

const resetting = ref(false)
async function resetSkin() {
  resetting.value = false
  await run('reset', async () => {
    profile.value = await backend.resetSkin()
    previewId.value = null
    await accounts.load().catch(() => {})
    toasts.ok('Standard-Skin wiederhergestellt')
  })
}

async function chooseCape(cape: Cape | null) {
  await run(`cape-${cape?.id ?? 'none'}`, async () => {
    profile.value = await backend.chooseCape(cape?.id ?? null)
    toasts.ok(cape ? `Umhang „${cape.name}“ angelegt` : 'Umhang abgenommen')
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
    <PageHeader title="Skins & Umhänge" subtitle="Eigene Skins sammeln, anprobieren und auf dein Konto setzen.">
      <button class="btn btn-ghost" :disabled="!!busy || !profile" @click="saveActive">
        {{ busy === 'save' ? 'Sichere …' : 'Getragenen Skin sichern' }}
      </button>
      <button class="btn btn-primary" :disabled="!!busy" @click="startAdd">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 5v14M5 12h14" /></svg>
        Skin hinzufügen
      </button>
    </PageHeader>

    <div v-if="loadError" role="alert" class="card mb-4 border-warn/40 px-4 py-3 text-sm text-warn">
      {{ loadError }}
      <p class="mt-1 text-xs text-base-400">
        Deine gespeicherten Skins lassen sich trotzdem verwalten – zum Wechseln auf dem Konto braucht es eine
        Anmeldung und Internet.
      </p>
    </div>

    <div class="grid min-h-0 flex-1 grid-cols-1 gap-5 overflow-y-auto pr-1 lg:grid-cols-[320px_1fr]">
      <!-- 3D-Vorschau ---------------------------------------------------------- -->
      <section class="card flex h-fit flex-col p-4">
        <div class="rounded-lg bg-gradient-to-b from-base-850 to-base-950">
          <div v-if="loading" class="skeleton h-[340px] w-full rounded-lg" />
          <ClientOnly v-else>
            <SkinViewer
              :skin="previewSkin"
              :cape="activeCape?.texture ?? null"
              :variant="previewVariant"
              :animation="animation"
            />
          </ClientOnly>
        </div>

        <div class="mt-3 flex items-center justify-center gap-1 rounded-lg bg-base-850 p-1 text-xs">
          <button
            v-for="[key, label] in ([['walk', 'Laufen'], ['idle', 'Ruhig'], ['none', 'Stehen']] as const)"
            :key="key"
            class="seg flex-1 rounded-md"
            :class="{ 'seg-on': animation === key }"
            @click="animation = key"
          >
            {{ label }}
          </button>
        </div>
        <p class="mt-2 text-center text-[11px] text-base-600">Ziehen zum Drehen · Mausrad zum Zoomen</p>

        <div v-if="preview" class="mt-3 rounded-lg border border-redstone-600/50 bg-redstone-900/30 px-3 py-2.5">
          <p class="text-xs text-base-200">
            Vorschau von <strong class="text-base-50">{{ preview.name }}</strong> – noch nicht auf deinem Konto.
          </p>
          <div class="mt-2 flex gap-2">
            <button class="btn btn-primary flex-1 py-1.5 text-xs" :disabled="!!busy || !profile" @click="apply(preview, preview.variant)">
              {{ busy === `apply-${preview.id}` ? 'Wird gesetzt …' : 'Skin anwenden' }}
            </button>
            <button class="btn btn-ghost py-1.5 text-xs" @click="previewId = null">Abbrechen</button>
          </div>
        </div>
        <div v-else-if="profile" class="mt-3 text-center text-xs text-base-400">
          Getragen: <span class="font-medium text-base-50">{{ profile.variant === 'slim' ? 'Slim (Alex)' : 'Klassisch (Steve)' }}</span>
          <button class="ml-2 text-redstone-300 hover:underline" :disabled="!!busy" @click="resetting = true">Zurücksetzen</button>
        </div>
      </section>

      <div class="min-w-0 space-y-5">
        <!-- Eigene Sammlung ---------------------------------------------------- -->
        <section>
          <h2 class="section-title mb-2">Meine Skins</h2>
          <div v-if="loading" class="grid grid-cols-[repeat(auto-fill,minmax(11rem,1fr))] gap-3">
            <div v-for="i in 4" :key="i" class="skeleton h-28" />
          </div>
          <ul v-else-if="library.length" class="grid grid-cols-[repeat(auto-fill,minmax(11rem,1fr))] gap-3">
            <li
              v-for="skin in library"
              :key="skin.id"
              class="card card-hover flex items-center gap-3 p-3"
              :class="{ 'border-redstone-600/60': previewId === skin.id }"
            >
              <button class="shrink-0 rounded [image-rendering:pixelated]" :style="faceStyle(skin.texture)" :aria-label="`${skin.name} in der Vorschau zeigen`" @click="previewId = skin.id" />
              <div class="min-w-0 flex-1">
                <p class="truncate text-sm font-medium">{{ skin.name }}</p>
                <p class="text-[11px] text-base-400">{{ skin.variant === 'slim' ? 'Slim' : 'Klassisch' }} · {{ formatDate(skin.addedAt) }}</p>
                <div class="mt-1.5 flex gap-1.5">
                  <button class="btn btn-ghost px-2 py-1 text-[11px]" :disabled="!!busy || !profile" @click="apply(skin, skin.variant)">
                    {{ busy === `apply-${skin.id}` ? '…' : 'Anwenden' }}
                  </button>
                  <button class="btn btn-ghost px-2 py-1 text-[11px] hover:text-redstone-300" :disabled="!!busy" @click="toDelete = skin">Löschen</button>
                </div>
              </div>
            </li>
          </ul>
          <div v-else class="card px-6 py-10 text-center">
            <h3 class="font-semibold">Noch keine eigenen Skins</h3>
            <p class="mx-auto mt-1 max-w-md text-sm text-base-400">
              Lege 64×64-PNG-Dateien in deine Sammlung – dann kannst du sie mit einem Klick anprobieren und setzen.
            </p>
            <button class="btn btn-primary mt-4" @click="startAdd">Skin hinzufügen</button>
          </div>
        </section>

        <!-- Umhänge -------------------------------------------------------------- -->
        <section v-if="profile">
          <h2 class="section-title mb-2">Umhänge</h2>
          <ul v-if="profile.capes.length" class="grid grid-cols-[repeat(auto-fill,minmax(8rem,1fr))] gap-3">
            <li>
              <button
                class="card card-hover flex h-full w-full flex-col items-center gap-2 p-3"
                :class="{ 'border-redstone-600/60': !activeCape }"
                :disabled="!!busy"
                @click="chooseCape(null)"
              >
                <span class="grid h-[48px] w-[30px] place-items-center rounded bg-base-800 text-base-600">–</span>
                <span class="text-xs">Keiner</span>
              </button>
            </li>
            <li v-for="cape in profile.capes" :key="cape.id">
              <button
                class="card card-hover flex h-full w-full flex-col items-center gap-2 p-3"
                :class="{ 'border-redstone-600/60': cape.active }"
                :disabled="!!busy"
                @click="chooseCape(cape)"
              >
                <span v-if="cape.texture" class="rounded [image-rendering:pixelated]" :style="capeStyle(cape.texture)" />
                <span v-else class="h-[48px] w-[30px] rounded bg-base-800" />
                <span class="w-full truncate text-center text-xs">{{ cape.name }}</span>
              </button>
            </li>
          </ul>
          <p v-else class="card px-4 py-6 text-center text-sm text-base-400">
            Für dieses Konto gibt es keine Umhänge. Die gibt es nur von Mojang – etwa den Migrator-Umhang.
          </p>
        </section>
      </div>
    </div>

    <!-- Dialoge -------------------------------------------------------------------- -->
    <BaseDialog v-if="adding" title="Skin hinzufügen" @close="adding = false">
      <label class="label" for="skin-name">Name</label>
      <input id="skin-name" v-model="newName" class="field" maxlength="48" placeholder="z. B. Winter-Skin" autofocus @keydown.enter="addSkin" />
      <p class="label mt-4">Modell</p>
      <div class="grid grid-cols-2 gap-2">
        <button
          v-for="v in ([['classic', 'Klassisch', '4 Pixel breite Arme (Steve)'], ['slim', 'Slim', '3 Pixel breite Arme (Alex)']] as const)"
          :key="v[0]"
          class="rounded-lg border px-3 py-2.5 text-left transition-colors"
          :class="newVariant === v[0] ? 'border-redstone-500 bg-redstone-900/40' : 'border-base-700 hover:border-base-600'"
          :aria-pressed="newVariant === v[0]"
          @click="newVariant = v[0]"
        >
          <span class="block text-sm font-semibold">{{ v[1] }}</span>
          <span class="block text-xs text-base-400">{{ v[2] }}</span>
        </button>
      </div>
      <p class="mt-4 text-xs text-base-400">
        Danach öffnet sich der Dateidialog. Erlaubt sind PNG-Dateien mit 64×64 (oder 64×32 für alte Skins).
      </p>
      <p v-if="formError" role="alert" class="mt-2 text-xs text-redstone-300">{{ formError }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="adding = false">Abbrechen</button>
        <button class="btn btn-primary" @click="addSkin">Datei wählen</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="toDelete" title="Skin löschen?" @close="toDelete = null">
      <p class="text-sm text-base-200">
        <strong class="text-base-50">{{ toDelete.name }}</strong> wird aus deiner Sammlung entfernt. Dein getragener
        Skin ändert sich dadurch nicht.
      </p>
      <template #actions>
        <button class="btn btn-ghost" @click="toDelete = null">Abbrechen</button>
        <button class="btn btn-danger" @click="confirmDelete">Löschen</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="resetting" title="Skin zurücksetzen?" @close="resetting = false">
      <p class="text-sm text-base-200">
        Dein Konto trägt danach wieder den Standard-Skin (Steve oder Alex). Deine Sammlung bleibt erhalten.
      </p>
      <template #actions>
        <button class="btn btn-ghost" @click="resetting = false">Abbrechen</button>
        <button class="btn btn-danger" @click="resetSkin">Zurücksetzen</button>
      </template>
    </BaseDialog>
  </div>
</template>
