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
    toasts.ok(cape ? t('capes.toasts.worn', { name: cape.name }) : t('capes.toasts.removed'))
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
      toasts.ok(
        result.alreadyOwned
          ? t('capes.toasts.alreadyOwned', { name: result.name })
          : t('capes.toasts.unlocked', { name: result.name }),
      )
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
      toasts.ok(t('capes.toasts.uploaded'))
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
    toasts.ok(t('capes.toasts.deleted'))
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
      <h2 id="trs-capes-title" class="section-title">{{ t('capes.title') }}</h2>
      <span v-if="active" class="badge bg-ok/10 text-ok">{{ t('capes.wearing', { name: active.name }) }}</span>
      <div v-if="trs.enabled && accounts.active" class="ml-auto flex gap-2">
        <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="!!busy || offline" @click="startRedeem">{{ t('capes.redeem') }}</button>
        <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="!!busy || offline" @click="startUpload">
          {{ t('capes.upload') }}
        </button>
      </div>
    </div>
    <p class="mb-3 text-xs text-base-400">{{ t('capes.intro') }}</p>

    <TrsGate what="capes">
      <div v-if="offline" class="card flex items-center gap-3 px-4 py-3 text-sm text-base-400">
        <span class="size-2 rounded-full bg-base-600" />
        <span class="flex-1">{{ t('capes.offline') }}</span>
        <button class="btn btn-ghost px-3 py-1 text-xs" :disabled="loading" @click="load">{{ t('common.actions.retry') }}</button>
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
              :title="cape.owned ? t('capes.tryOn', { name: cape.name }) : t('capes.tryOnLocked', { name: cape.name })"
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
                  :aria-label="t('capes.locked')"
                >
                  <path d="M7 11V8a5 5 0 0 1 10 0v3M5 11h14v10H5z" />
                </svg>
              </span>
              <span class="w-full truncate text-center text-xs">{{ cape.name }}</span>
              <span class="flex flex-wrap justify-center gap-1">
                <span class="badge px-1.5 py-0 text-[10px]" :class="lockClass(cape)">{{ trsUnlockLabel(cape) }}</span>
                <span v-if="cape.frames > 1" class="badge bg-base-800 px-1.5 py-0 text-[10px] text-base-200">{{ t('capes.animated') }}</span>
              </span>
              <span v-if="cape.active" class="text-[10px] text-ok">{{ t('capes.worn') }}</span>
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
          {{ t('capes.empty') }}
        </p>

        <!-- Aktionen für den angeprobten Umhang -->
        <div v-if="selected" class="card mt-3 flex flex-wrap items-center gap-3 px-4 py-3" data-testid="trs-cape-actions">
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-semibold text-base-50">{{ selected.name }}</p>
            <p class="text-xs text-base-400">
              <template v-if="selected.kind === 'upload' && selected.status === 'pending'">
                {{ t('capes.selected.pending') }}
              </template>
              <template v-else-if="selected.kind === 'upload' && selected.status === 'rejected'">
                {{
                  selected.rejectReason
                    ? t('capes.selected.rejectedReason', { reason: selected.rejectReason })
                    : t('capes.selected.rejected')
                }}
              </template>
              <template v-else-if="!selected.owned && selected.unlock === 'code'">{{ t('capes.selected.lockedCode') }}</template>
              <template v-else-if="!selected.owned">{{ t('capes.selected.lockedTeam') }}</template>
              <template v-else-if="selected.active">{{ t('capes.selected.active') }}</template>
              <template v-else-if="selected.unlock === 'free'">{{ t('capes.selected.free') }}</template>
              <template v-else>{{ t('capes.selected.unlocked') }}</template>
            </p>
          </div>
          <button
            v-if="selected.owned && !selected.active"
            class="btn btn-primary px-3 py-1.5 text-xs"
            :disabled="!!busy"
            @click="wear(selected)"
          >
            {{ busy === 'wear' ? t('capes.actions.wearing') : t('capes.actions.wear') }}
          </button>
          <button v-if="selected.active" class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="!!busy" @click="wear(null)">
            {{ busy === 'wear' ? t('capes.actions.removing') : t('capes.actions.remove') }}
          </button>
          <button
            v-if="!selected.owned && selected.unlock === 'code'"
            class="btn btn-ghost px-3 py-1.5 text-xs"
            :disabled="!!busy"
            @click="startRedeem"
          >
            {{ t('capes.redeem') }}
          </button>
          <button
            v-if="selected.kind === 'upload'"
            class="btn btn-ghost px-3 py-1.5 text-xs hover:text-redstone-300"
            :disabled="!!busy"
            @click="toDelete = selected"
          >
            {{ t('common.actions.delete') }}
          </button>
          <button class="btn btn-ghost px-3 py-1.5 text-xs" @click="emit('preview', null)">{{ t('capes.actions.endPreview') }}</button>
        </div>
        <p v-if="pendingCount" class="mt-2 text-[11px] text-base-600">
          {{ t('capes.pendingCount', pendingCount) }}
        </p>
      </template>
    </TrsGate>

    <BaseDialog v-if="redeeming" :title="t('capes.redeem')" @close="redeeming = false">
      <label class="label" for="trs-code">{{ t('capes.redeemDialog.codeLabel') }}</label>
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
      <p class="mt-2 text-xs text-base-400">{{ t('capes.redeemDialog.hint') }}</p>
      <p v-if="codeError" role="alert" class="mt-2 text-xs text-redstone-300">{{ codeError }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="redeeming = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="busy === 'redeem'" @click="redeem">
          {{ busy === 'redeem' ? t('capes.redeemDialog.checking') : t('capes.redeemDialog.submit') }}
        </button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="uploading" :title="t('capes.upload')" @close="uploading = false">
      <label class="label" for="trs-cape-name">{{ t('capes.uploadDialog.nameLabel') }}</label>
      <input
        id="trs-cape-name"
        v-model="uploadName"
        class="field"
        maxlength="32"
        :placeholder="t('capes.uploadDialog.placeholder')"
        autofocus
        @keydown.enter="upload"
      />
      <ul class="mt-3 list-disc space-y-1 pl-4 text-xs text-base-400">
        <li>{{ t('capes.uploadDialog.rules.size') }}</li>
        <li>{{ t('capes.uploadDialog.rules.review') }}</li>
        <li>{{ t('capes.uploadDialog.rules.rights') }}</li>
      </ul>
      <p v-if="uploadError" role="alert" class="mt-2 text-xs text-redstone-300">{{ uploadError }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="uploading = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="busy === 'upload'" @click="upload">
          {{ busy === 'upload' ? t('capes.uploadDialog.uploading') : t('capes.uploadDialog.chooseFile') }}
        </button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="toDelete" :title="t('capes.deleteDialog.title')" @close="toDelete = null">
      <i18n-t keypath="capes.deleteDialog.text" tag="p" scope="global" class="text-sm text-base-200">
        <template #name><strong class="text-base-50">{{ toDelete.name }}</strong></template>
      </i18n-t>
      <template #actions>
        <button class="btn btn-ghost" @click="toDelete = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" @click="confirmDelete">{{ t('common.actions.delete') }}</button>
      </template>
    </BaseDialog>
  </section>
</template>
