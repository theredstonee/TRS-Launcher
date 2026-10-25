<script setup lang="ts">
import {
  PET_CODE_MAX_COUNT,
  PET_CODE_MAX_USES,
  PET_COLORS_MAX,
  PET_NOTE_MAX,
  PET_REQUEST_TEXT_MAX,
  PET_SPECIES_MAX,
  canRequestPet,
  checkPetCodes,
  checkPetRequest,
  expiryFromDate,
  petCodeLabel,
  petCodeStatus,
  petCodesFit,
  petImageKind,
  petImageProblem,
  petLimitPercent,
  petLimitResetsAt,
  petMonthDate,
  petRemaining,
  type CreatorPetState,
  type CreatorSelf,
  type PetCode,
  type PetCodesProblem,
  type PetRequestProblem,
} from '~/utils/creator'

// Haustier: Hat der Creator ein eigenes Haustier, sieht er die Karte, das
// Monatslimit und seine Codes (erstellen – Klartext nur einmal –, widerrufen).
// Sonst kann er sich eins vom Team wünschen (Tierart, Beschreibung, Farben,
// optional Referenzbild) und sieht den Stand des Wunsches; offene Wünsche lassen
// sich zurückziehen oder um ein Bild ergänzen. Helfer mit dem Recht „Codes“
// dürfen Codes verwalten, den Wunsch nicht.
const props = defineProps<{ self: CreatorSelf }>()
const store = useCreatorStore()
const toasts = useToasts()

const helper = computed(() => !!props.self.viewer?.helper)
/** Fremdes Haustier (Helfer oder Team-Modus) – Texte in der dritten Person. */
const foreign = computed(() => helper.value || !!props.self.viewer?.team)
const state = ref<CreatorPetState | null>(null)
const codes = ref<PetCode[] | null>(null)
const error = ref<string | null>(null)

const remaining = computed(() => petRemaining(state.value?.limit ?? null))
const percent = computed(() => petLimitPercent(state.value?.limit ?? null))
const monthText = computed(() => {
  const d = petMonthDate(state.value?.limit?.month ?? null)
  return d ? d.toLocaleDateString(intlLocale(), { month: 'long', year: 'numeric' }) : null
})
const resetText = computed(() => {
  const d = petLimitResetsAt(state.value?.limit?.month ?? null, state.value?.limit?.resetsAt ?? null)
  return d ? formatShortDate(d.toISOString()) : null
})

async function load() {
  error.value = null
  try {
    const s = await backend.creator.pet(store.actingAs)
    state.value = s
    codes.value = s.pet ? await backend.creator.petCodes(store.actingAs) : null
  } catch (e) {
    error.value = errorMessage(e)
  }
}
onMounted(load)
watch(() => store.actingAs, load)
// Team hat zugeordnet/entfernt/geantwortet (creator_updated pet_*).
watch(() => store.petRevision, load)

async function copy(text: string, all = false) {
  try {
    await navigator.clipboard.writeText(text)
    toasts.ok(all ? t('creator.pet.copiedAll') : t('creator.copied', { code: text }))
  } catch {
    toasts.error(t('admin.toasts.copyFailed'))
  }
}

// --- Codes erstellen ----------------------------------------------------------------------
const dialog = ref(false)
const form = reactive({ count: 1 as number | '', maxUses: 10 as number | '', until: '', note: '' })
const formError = ref<string | null>(null)
const saving = ref(false)
/** Gerade erstellte Codes – Klartext nur hier, bis „Ausblenden“ oder Neuladen der Seite. */
const created = ref<PetCode[]>([])

const cost = computed(() => (Number(form.count) || 0) * (Number(form.maxUses) || 0))
const fit = computed(() => petCodesFit(remaining.value, Number(form.maxUses)))

function openDialog() {
  const uses = Math.max(1, Math.min(10, remaining.value))
  form.count = 1
  form.maxUses = uses
  form.until = ''
  form.note = ''
  formError.value = null
  dialog.value = true
}

const problemText: Record<PetCodesProblem, () => string> = {
  count: () => t('creator.pet.create.countInvalid', { max: PET_CODE_MAX_COUNT }),
  uses: () => t('creator.pet.create.usesInvalid', { max: PET_CODE_MAX_USES }),
  until: () => t('creator.codes.untilInvalid'),
  note: () => t('creator.pet.create.noteInvalid', { max: PET_NOTE_MAX }),
  limit: () => t('creator.pet.create.overLimit', { cost: formatNumber(cost.value), left: formatNumber(remaining.value) }),
}

async function create() {
  const problem = checkPetCodes(form, remaining.value)
  if (problem) {
    formError.value = problemText[problem]()
    return
  }
  formError.value = null
  saving.value = true
  try {
    const expiresAt = form.until ? expiryFromDate(form.until) : null
    const note = form.note.trim()
    const list = await backend.creator.createPetCodes(
      { count: Number(form.count), maxUses: Number(form.maxUses), ...(expiresAt ? { expiresAt } : {}), ...(note ? { note } : {}) },
      store.actingAs,
    )
    created.value = list.filter((c) => c.code)
    dialog.value = false
    toasts.ok(t('creator.pet.create.done', created.value.length))
    await load()
  } catch (e) {
    formError.value = errorMessage(e)
    // Limit erreicht / kein Haustier mehr: Stand neu laden, damit Balken und Knopf stimmen.
    if (e instanceof BackendError && (e.apiCode === 'limit_reached' || e.apiCode === 'creator_no_pet')) void load()
  } finally {
    saving.value = false
  }
}

// --- Widerrufen ---------------------------------------------------------------------------
const revoking = ref<PetCode | null>(null)
const revokeBusy = ref(false)
async function revoke() {
  const c = revoking.value
  if (!c) return
  revokeBusy.value = true
  try {
    await backend.creator.revokePetCode(c.id, store.actingAs)
    toasts.ok(t('creator.pet.codes.revoked'))
    revoking.value = null
    created.value = created.value.filter((x) => x.id !== c.id)
    await load()
  } catch (e) {
    toasts.error(e)
  } finally {
    revokeBusy.value = false
  }
}

const statusClass = { active: 'bg-ok/15 text-ok', revoked: 'bg-base-800 text-base-400', usedUp: 'bg-base-800 text-base-400', expired: 'bg-base-800 text-base-400' } as const

// --- Wunsch -------------------------------------------------------------------------------
const wish = reactive({ species: '', description: '', colors: '', image: null as string | null, imageName: '' })
const wishError = ref<string | null>(null)
const sending = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
/** Bild für einen schon gestellten, offenen Wunsch (statt fürs Formular). */
const attachTo = ref<string | null>(null)
const attaching = ref(false)

/** Datei prüfen (Typ, Größe, erste Bytes) und als Data-URL lesen; Fehler → `null` + Meldung. */
async function readImage(file: File): Promise<string | null> {
  const problem = petImageProblem(file)
  const head = new Uint8Array(await file.slice(0, 8).arrayBuffer())
  if (problem === 'size') {
    toasts.error(t('creator.pet.request.imageTooLarge'))
    return null
  }
  if (problem || !petImageKind(head)) {
    toasts.error(t('creator.pet.request.imageType'))
    return null
  }
  return await new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(file)
  })
}

function chooseImage(forRequest: string | null) {
  attachTo.value = forRequest
  fileInput.value?.click()
}

async function pickImage(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  const image = await readImage(file)
  if (!image) return
  const id = attachTo.value
  if (!id) {
    wish.image = image
    wish.imageName = file.name
    return
  }
  attaching.value = true
  try {
    const request = await backend.creator.petRequestImage(id, image, store.actingAs)
    if (state.value) state.value = { ...state.value, request }
    toasts.ok(t('creator.pet.request.imageAdded'))
  } catch (e) {
    toasts.error(e)
  } finally {
    attaching.value = false
  }
}

function clearImage() {
  wish.image = null
  wish.imageName = ''
}

const wishProblem: Record<PetRequestProblem, () => string> = {
  species: () => t('creator.pet.request.speciesInvalid', { max: PET_SPECIES_MAX }),
  description: () => t('creator.pet.request.textInvalid', { max: PET_REQUEST_TEXT_MAX }),
  colors: () => t('creator.pet.request.colorsInvalid', { max: PET_COLORS_MAX }),
}

async function sendWish() {
  const problem = checkPetRequest(wish)
  if (problem) {
    wishError.value = wishProblem[problem]()
    return
  }
  wishError.value = null
  sending.value = true
  try {
    const colors = wish.colors.trim()
    await backend.creator.requestPet({
      species: wish.species.trim(),
      description: wish.description.trim(),
      ...(colors ? { colors } : {}),
      ...(wish.image ? { image: wish.image } : {}),
    }, store.actingAs)
    wish.species = ''
    wish.description = ''
    wish.colors = ''
    clearImage()
    toasts.ok(t('creator.pet.request.sent'))
  } catch (e) {
    wishError.value = errorMessage(e)
  } finally {
    sending.value = false
    // Auch wenn nur das Bild scheiterte, steht der Wunsch schon – Stand neu laden.
    await load()
  }
}

const withdrawing = ref(false)
const confirmWithdraw = ref(false)
async function withdraw() {
  const id = state.value?.request?.id
  if (!id) return
  withdrawing.value = true
  try {
    await backend.creator.withdrawPetRequest(id, store.actingAs)
    confirmWithdraw.value = false
    toasts.ok(t('creator.pet.request.withdrawn'))
    await load()
  } catch (e) {
    toasts.error(e)
  } finally {
    withdrawing.value = false
  }
}

const requestClass = {
  open: 'bg-lamp-900 text-lamp-300',
  in_progress: 'bg-sky-400/10 text-sky-300',
  done: 'bg-ok/15 text-ok',
  rejected: 'bg-redstone-900/50 text-redstone-200',
} as const
</script>

<template>
  <div class="space-y-3" data-testid="creator-pet">
    <div v-if="!state && !error" class="skeleton h-40" />
    <div v-else-if="error" class="card px-4 py-4 text-sm" role="alert">
      <p class="text-base-400">{{ error }}</p>
      <button class="btn btn-ghost mt-2 px-3 py-1.5 text-xs" @click="load">{{ t('common.actions.retry') }}</button>
    </div>

    <!-- Eigenes Haustier -->
    <template v-else-if="state?.pet">
      <section class="card flex flex-wrap items-stretch gap-4 p-4 mobile:flex-col" data-testid="creator-pet-card">
        <span class="relative block size-36 shrink-0 overflow-hidden rounded-lg bg-gradient-to-b from-base-800 to-base-950 ring-1 ring-base-700">
          <img v-if="state.pet.card" :src="state.pet.card" alt="" class="size-full object-cover" draggable="false" />
          <span v-else class="grid size-full place-items-center text-base-600"><SocialIcon name="star" class="size-10" /></span>
        </span>
        <div class="flex min-w-0 flex-1 flex-col gap-2">
          <div>
            <p class="text-[11px] tracking-wide text-base-400 uppercase">{{ foreign ? t('creator.pet.theirPet') : t('creator.pet.yourPet') }}</p>
            <h3 class="display text-2xl leading-tight text-base-50">{{ state.pet.name }}</h3>
            <p class="mt-1 text-xs text-base-400">{{ t('creator.pet.intro') }}</p>
            <p v-if="!state.pet.available" class="mt-2 rounded-lg border-l-2 border-lamp-400 bg-base-900 px-3 py-1.5 text-xs text-lamp-300" data-testid="creator-pet-unavailable">{{ t('creator.pet.unavailable') }}</p>
          </div>
          <div v-if="state.limit" class="mt-auto" data-testid="creator-pet-limit">
            <div class="flex flex-wrap items-baseline gap-x-2 text-xs">
              <span class="font-medium text-base-200">{{ monthText ? t('creator.pet.limit.title', { month: monthText }) : t('creator.pet.limit.titleNoMonth') }}</span>
              <span class="flex-1" />
              <span class="tabular-nums text-base-200">{{ t('creator.pet.limit.used', { used: formatNumber(state.limit.used), max: formatNumber(state.limit.max) }) }}</span>
            </div>
            <div
              class="mt-1.5 h-2 overflow-hidden rounded-full bg-base-800"
              role="progressbar"
              :aria-valuenow="state.limit.used"
              :aria-valuemin="0"
              :aria-valuemax="state.limit.max"
              :aria-label="t('creator.pet.limit.titleNoMonth')"
            >
              <div class="h-full rounded-full transition-[width] duration-500" :class="percent >= 100 ? 'bg-redstone-500' : percent >= 80 ? 'bg-lamp-400' : 'bg-ok'" :style="{ width: `${percent}%` }" />
            </div>
            <p class="mt-1 text-[11px] text-base-400">
              {{ remaining > 0 ? t('creator.pet.limit.left', { n: formatNumber(remaining) }) : t('creator.pet.limit.full') }}
              <template v-if="resetText"> · {{ t('creator.pet.limit.resets', { date: resetText }) }}</template>
            </p>
          </div>
        </div>
      </section>

      <!-- Gerade erstellt: Klartext nur jetzt -->
      <section v-if="created.length" class="card border-lamp-400/40 p-4" data-testid="creator-pet-created">
        <div class="mb-1 flex flex-wrap items-center gap-2">
          <p class="flex-1 text-sm font-semibold text-lamp-300">{{ t('creator.pet.created.title', created.length) }}</p>
          <button class="btn btn-ghost px-3 py-1 text-xs" @click="copy(created.map((c) => c.code).join('\n'), true)">{{ t('admin.codes.copyAll') }}</button>
          <button class="btn btn-ghost px-3 py-1 text-xs" @click="created = []">{{ t('admin.codes.hide') }}</button>
        </div>
        <p class="mb-2 text-xs text-base-400">{{ t('creator.pet.created.once') }}</p>
        <ul class="grid gap-1.5 sm:grid-cols-2">
          <li v-for="c in created" :key="c.id" class="flex items-center gap-2 rounded-md bg-base-850 px-3 py-1.5">
            <code class="flex-1 font-mono text-sm tracking-wider text-base-50 select-all">{{ c.code }}</code>
            <span class="text-[11px] text-base-400 tabular-nums">{{ t('creator.pet.created.uses', { n: c.maxUses }) }}</span>
            <button class="btn-icon size-7" :aria-label="t('creator.copy')" :title="t('creator.copy')" @click="copy(c.code ?? '')"><SocialIcon name="copy" class="size-4" /></button>
          </li>
        </ul>
      </section>

      <section class="card p-4">
        <div class="flex flex-wrap items-center gap-2">
          <h3 class="section-title mr-auto">{{ t('creator.pet.codes.title') }}</h3>
          <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="remaining <= 0 || !state.pet.available" :title="remaining <= 0 ? t('creator.pet.limit.full') : undefined" data-testid="creator-pet-new" @click="openDialog">
            <SocialIcon name="plus" class="size-4" />{{ t('creator.pet.codes.new') }}
          </button>
        </div>
        <div v-if="!codes" class="skeleton mt-3 h-20" />
        <p v-else-if="!codes.length" class="mt-3 text-sm text-base-400">{{ t('creator.pet.codes.empty') }}</p>
        <div v-else class="mt-3 overflow-x-auto">
          <table class="w-full min-w-[36rem] text-sm" data-testid="creator-pet-codes">
            <thead>
              <tr class="text-left text-[11px] tracking-wide text-base-400 uppercase">
                <th class="py-1.5 font-medium">{{ t('creator.codes.code') }}</th>
                <th class="py-1.5 font-medium">{{ t('creator.codes.uses') }}</th>
                <th class="py-1.5 font-medium">{{ t('creator.codes.until') }}</th>
                <th class="py-1.5 font-medium">{{ t('creator.pet.codes.note') }}</th>
                <th class="py-1.5 font-medium">{{ t('creator.codes.status') }}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in codes" :key="c.id" class="border-t border-base-800" :class="{ 'opacity-55': c.revoked }">
                <td class="py-2 font-mono text-xs font-semibold text-redstone-300">{{ petCodeLabel(c) }}</td>
                <td class="py-2 text-xs tabular-nums">{{ formatNumber(c.uses) }} / {{ formatNumber(c.maxUses) }}</td>
                <td class="py-2 text-xs text-base-400">{{ c.expiresAt ? formatShortDate(c.expiresAt) : '–' }}</td>
                <td class="max-w-48 truncate py-2 text-xs text-base-400" :title="c.note ?? ''">{{ c.note ?? '–' }}</td>
                <td class="py-2">
                  <span class="badge" :class="statusClass[petCodeStatus(c)]">{{ t(`creator.pet.codes.state.${petCodeStatus(c)}`) }}</span>
                </td>
                <td class="py-2 text-right whitespace-nowrap">
                  <button v-if="!c.revoked" class="btn btn-ghost px-3 py-1 text-xs hover:text-redstone-300" :data-testid="`creator-pet-revoke-${c.id}`" @click="revoking = c">
                    {{ t('creator.pet.codes.revoke') }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="mt-3 text-xs text-base-400">{{ t('creator.pet.codes.footer', { count: PET_CODE_MAX_COUNT, uses: PET_CODE_MAX_USES }) }}</p>
      </section>
    </template>

    <!-- Noch kein Haustier -->
    <template v-else-if="state">
      <section class="card p-4" data-testid="creator-pet-none">
        <h3 class="section-title">{{ foreign ? t('creator.pet.none.helperTitle') : t('creator.pet.none.title') }}</h3>
        <p class="mt-1 text-sm text-base-400">{{ foreign ? t('creator.pet.none.helperText') : t('creator.pet.none.text') }}</p>
      </section>

      <section v-if="state.request" class="card p-4" data-testid="creator-pet-request">
        <div class="flex flex-wrap items-center gap-2">
          <h3 class="section-title mr-auto">{{ t('creator.pet.request.yours') }}</h3>
          <span class="badge" :class="requestClass[state.request.status]" data-testid="creator-pet-request-status">{{ t(`creator.pet.request.status.${state.request.status}`) }}</span>
        </div>
        <p class="mt-2 text-xs text-base-400">{{ t(`creator.pet.request.statusText.${state.request.status}`) }}</p>
        <div class="mt-3 space-y-1 rounded-lg bg-base-850 px-3 py-2 text-sm text-base-200">
          <p v-if="state.request.species" class="font-semibold text-base-50">{{ state.request.species }}</p>
          <p class="whitespace-pre-line">{{ state.request.text }}</p>
          <p v-if="state.request.colors" class="text-xs text-base-400">{{ t('creator.pet.request.colorsShown', { colors: state.request.colors }) }}</p>
          <p v-if="state.request.hasImage" class="flex items-center gap-1 text-xs text-base-400"><SocialIcon name="image" class="size-3.5" />{{ t('creator.pet.request.imageAttached') }}</p>
        </div>
        <p v-if="state.request.answer" class="mt-2 rounded-lg border-l-2 border-redstone-500 bg-base-900 px-3 py-2 text-xs text-base-200">
          <b class="text-base-50">{{ t('creator.pet.request.answer') }}</b> {{ state.request.answer }}
        </p>
        <div class="mt-2 flex flex-wrap items-center gap-2">
          <p class="mr-auto text-[11px] text-base-600">
            <template v-if="state.request.createdAt">{{ t('creator.pet.request.sentAt', { date: formatShortDate(state.request.createdAt) }) }}</template>
          </p>
          <template v-if="!helper && state.request.status === 'open'">
            <button class="btn btn-ghost px-3 py-1 text-xs" :disabled="attaching" data-testid="creator-pet-attach" @click="chooseImage(state.request.id)">
              <SocialIcon name="image" class="size-4" />{{ state.request.hasImage ? t('creator.pet.request.replaceImage') : t('creator.pet.request.addImage') }}
            </button>
            <button class="btn btn-ghost px-3 py-1 text-xs hover:text-redstone-300" data-testid="creator-pet-withdraw" @click="confirmWithdraw = true">{{ t('creator.pet.request.withdraw') }}</button>
          </template>
        </div>
      </section>

      <section v-if="!helper && canRequestPet(state)" class="card p-4" data-testid="creator-pet-form">
        <h3 class="section-title">{{ state.request ? t('creator.pet.request.again') : t('creator.pet.request.title') }}</h3>
        <p class="mt-1 text-xs text-base-400">{{ t('creator.pet.request.hint') }}</p>
        <form class="mt-3 space-y-3" @submit.prevent="sendWish">
          <div class="grid gap-3 sm:grid-cols-2">
            <label class="block">
              <span class="label">{{ t('creator.pet.request.speciesLabel') }}</span>
              <input v-model="wish.species" class="field" :maxlength="PET_SPECIES_MAX" :placeholder="t('creator.pet.request.speciesPlaceholder')" data-testid="creator-pet-species" />
            </label>
            <label class="block">
              <span class="label">{{ t('creator.pet.request.colorsLabel') }}</span>
              <input v-model="wish.colors" class="field" :maxlength="PET_COLORS_MAX" :placeholder="t('creator.pet.request.colorsPlaceholder')" data-testid="creator-pet-colors" />
            </label>
          </div>
          <label class="block">
            <span class="label">{{ t('creator.pet.request.textLabel') }}</span>
            <textarea
              v-model="wish.description"
              class="field min-h-28 resize-y"
              :maxlength="PET_REQUEST_TEXT_MAX"
              :placeholder="t('creator.pet.request.placeholder')"
              data-testid="creator-pet-text"
            />
            <span class="mt-1 block text-right text-[11px] text-base-600 tabular-nums">{{ wish.description.length }} / {{ PET_REQUEST_TEXT_MAX }}</span>
          </label>
          <div>
            <span class="label">{{ t('creator.pet.request.imageLabel') }}</span>
            <div v-if="wish.image" class="flex items-center gap-3 rounded-lg bg-base-850 p-2">
              <img :src="wish.image" alt="" class="size-14 rounded-md object-cover" />
              <span class="min-w-0 flex-1 truncate text-xs text-base-200">{{ wish.imageName }}</span>
              <button type="button" class="btn btn-ghost px-3 py-1 text-xs" @click="clearImage">{{ t('creator.pet.request.removeImage') }}</button>
            </div>
            <button v-else type="button" class="btn btn-ghost px-3 py-1.5 text-xs" @click="chooseImage(null)">
              <SocialIcon name="image" class="size-4" />{{ t('creator.pet.request.pickImage') }}
            </button>
          </div>
          <p v-if="wishError" role="alert" class="text-xs text-redstone-300">{{ wishError }}</p>
          <div class="flex justify-end">
            <button class="btn btn-primary" :disabled="sending" data-testid="creator-pet-send">{{ sending ? t('creator.pet.request.sending') : t('creator.pet.request.send') }}</button>
          </div>
        </form>
      </section>
      <input ref="fileInput" type="file" accept="image/png,image/jpeg" class="hidden" data-testid="creator-pet-file" @change="pickImage" />
    </template>

    <BaseDialog v-if="dialog" :title="t('creator.pet.create.title')" @close="dialog = false">
      <form class="space-y-3" @submit.prevent="create">
        <div class="grid grid-cols-2 gap-3">
          <label class="block">
            <span class="label">{{ t('creator.pet.create.count', { max: PET_CODE_MAX_COUNT }) }}</span>
            <input v-model.number="form.count" type="number" min="1" :max="PET_CODE_MAX_COUNT" class="field" data-testid="creator-pet-count" />
          </label>
          <label class="block">
            <span class="label">{{ t('creator.pet.create.uses', { max: PET_CODE_MAX_USES }) }}</span>
            <input v-model.number="form.maxUses" type="number" min="1" :max="PET_CODE_MAX_USES" class="field" data-testid="creator-pet-uses" />
          </label>
        </div>
        <label class="block">
          <span class="label">{{ t('creator.codes.untilLabel') }}</span>
          <input v-model="form.until" type="date" class="field" />
        </label>
        <label class="block">
          <span class="label">{{ t('creator.pet.create.note') }}</span>
          <input v-model="form.note" class="field" :maxlength="PET_NOTE_MAX" :placeholder="t('creator.pet.create.notePlaceholder')" />
        </label>
        <p class="rounded-lg bg-base-850 px-3 py-2 text-xs" :class="cost > remaining ? 'text-redstone-300' : 'text-base-200'" data-testid="creator-pet-cost">
          {{ t('creator.pet.create.cost', { cost: formatNumber(cost), left: formatNumber(remaining) }) }}
          <span v-if="cost > remaining && fit > 0" class="mt-0.5 block text-base-400">{{ t('creator.pet.create.fit', { n: fit }) }}</span>
        </p>
        <p class="text-[11px] text-base-400">{{ t('creator.pet.create.once') }}</p>
        <p v-if="formError" role="alert" class="text-xs text-redstone-300">{{ formError }}</p>
      </form>
      <template #actions>
        <button class="btn btn-ghost" @click="dialog = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="saving" data-testid="creator-pet-create" @click="create">{{ saving ? t('creator.codes.creating') : t('creator.codes.create') }}</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="revoking" :title="t('creator.pet.revoke.title')" @close="revoking = null">
      <p class="text-sm text-base-200">{{ t('creator.pet.revoke.text', { code: petCodeLabel(revoking) }) }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="revoking = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" :disabled="revokeBusy" data-testid="creator-pet-revoke-confirm" @click="revoke">{{ t('creator.pet.codes.revoke') }}</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="confirmWithdraw" :title="t('creator.pet.request.withdrawTitle')" @close="confirmWithdraw = false">
      <p class="text-sm text-base-200">{{ t('creator.pet.request.withdrawText') }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="confirmWithdraw = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" :disabled="withdrawing" data-testid="creator-pet-withdraw-confirm" @click="withdraw">{{ t('creator.pet.request.withdraw') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
