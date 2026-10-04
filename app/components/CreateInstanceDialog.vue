<script setup lang="ts">
import type { Instance, LoaderKind } from '~/types'
import type { IconResult } from '~/utils/iconEditor'

const emit = defineEmits<{ close: []; created: [instance: Instance] }>()

const instances = useInstancesStore()
const meta = useMetaStore()
const settings = useSettingsStore()

const name = ref('')
const gameVersion = ref('')
const loaderKind = ref<LoaderKind>('vanilla')
const showSnapshots = ref(settings.current?.showSnapshots ?? false)

/** Presets, die nach dem Anlegen installiert werden (Hintergrund-Aufgabe). */
const presetIds = ref<string[]>([])

/** Symbol aus dem Symbol-Editor (wird nach dem Anlegen gespeichert). */
const toasts = useToasts()
const icon = ref<IconResult | null>(null)
const iconOpen = ref(false)
const iconSrc = computed(() => (icon.value ? `data:image/png;base64,${icon.value.png}` : null))
function onIcon(result: IconResult) {
  icon.value = result
  iconOpen.value = false
}

const loadingVersions = ref(true)
const submitting = ref(false)
const error = ref<string | null>(null)

const versions = computed(() =>
  (meta.manifest?.versions ?? []).filter((v) => v.type === 'release' || showSnapshots.value),
)

onMounted(async () => {
  try {
    const manifest = await meta.loadManifest()
    gameVersion.value = manifest.latest.release
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loadingVersions.value = false
  }
})

// Beim Ausblenden der Snapshots darf keine unsichtbare Version gewählt bleiben.
watch(versions, (list) => {
  if (list.length && !list.some((v) => v.id === gameVersion.value)) {
    gameVersion.value = meta.manifest?.latest.release ?? list[0]!.id
  }
})

async function submit() {
  error.value = null
  const parsed = newInstanceSchema.safeParse({
    name: name.value,
    gameVersion: gameVersion.value,
    // Konkrete Loader-Version wird bei der Installation aufgelöst (null = neueste stabile).
    loader: { kind: loaderKind.value, version: null },
  })
  if (!parsed.success) {
    error.value = firstIssue(parsed.error)
    return
  }

  submitting.value = true
  try {
    let instance = await instances.create(parsed.data)
    // Im Symbol-Editor gebautes Symbol gleich mitspeichern (Fehler nur als Hinweis – die Instanz steht).
    if (icon.value) {
      try {
        instance = await backend.saveInstanceIcon(instance.id, icon.value.png, JSON.stringify(icon.value.source))
        void instances.load()
      } catch (e) {
        toasts.error(e)
      }
    }
    // Nur was es für Version + Loader gibt – der Rest steht im Bericht.
    if (presetIds.value.length) void applyPresetsTask(instance, [...presetIds.value])
    emit('created', instance)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <BaseDialog :title="t('createInstance.title')" @close="emit('close')">
    <form id="create-instance" class="space-y-4" @submit.prevent="submit">
      <div class="flex items-end gap-3">
        <button
          type="button"
          class="group relative size-[3.75rem] shrink-0 overflow-hidden rounded-lg bg-base-800 ring-1 ring-white/5 transition-shadow hover:ring-redstone-500"
          :aria-label="t('createInstance.icon')"
          :title="t('createInstance.icon')"
          data-icon-editor
          @click="iconOpen = true"
        >
          <img v-if="iconSrc" :src="iconSrc" alt="" class="size-full" draggable="false" />
          <PixelIdenticon v-else :seed="name || 'trs'" :color="loaderColors[loaderKind]" :letter="(name.trim().charAt(0) || '?').toUpperCase()" />
          <span class="absolute inset-0 grid place-items-center bg-black/55 text-[11px] font-medium text-white opacity-0 transition-opacity group-hover:opacity-100">{{ t('common.actions.change') }}</span>
        </button>
        <div class="min-w-0 flex-1">
          <label class="label" for="ci-name">{{ t('common.labels.name') }}</label>
          <input id="ci-name" v-model="name" class="field" maxlength="64" :placeholder="t('createInstance.namePlaceholder')" autofocus />
        </div>
      </div>

      <div>
        <div class="flex items-center justify-between">
          <label class="label" for="ci-version">{{ t('createInstance.gameVersion') }}</label>
          <label class="mb-1.5 flex items-center gap-1.5 text-xs text-base-400">
            <input v-model="showSnapshots" type="checkbox" class="accent-redstone-500" />
            {{ t('createInstance.showSnapshots') }}
          </label>
        </div>
        <select id="ci-version" v-model="gameVersion" class="field font-mono" :disabled="loadingVersions || !versions.length">
          <option v-if="loadingVersions" value="">{{ t('createInstance.loadingVersions') }}</option>
          <option v-for="v in versions" :key="v.id" :value="v.id">{{ v.id }}</option>
        </select>
      </div>

      <div>
        <span class="label">{{ t('common.labels.loader') }}</span>
        <div class="grid grid-cols-5 gap-1.5 mobile:grid-cols-3">
          <button
            v-for="kind in loaderKinds"
            :key="kind"
            type="button"
            class="rounded-md border px-1 py-2 text-xs font-medium transition-colors mobile:min-h-11"
            :class="loaderKind === kind
              ? 'border-redstone-500 bg-redstone-900 text-base-50'
              : 'border-base-700 bg-base-900 text-base-400 hover:border-base-600 hover:text-base-50'"
            @click="loaderKind = kind"
          >
            {{ loaderLabels[kind] }}
          </button>
        </div>
      </div>

      <PresetPicker v-model="presetIds" :loader="loaderKind" preselect @use-fabric="loaderKind = 'fabric'" @manage="emit('close')" />

      <p v-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
    </form>

    <template #actions>
      <button type="button" class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
      <button type="submit" form="create-instance" class="btn btn-primary" :disabled="submitting || loadingVersions || !gameVersion">
        {{ submitting ? t('createInstance.creating') : t('common.actions.create') }}
      </button>
    </template>
  </BaseDialog>
  <IconEditorDialog v-if="iconOpen" :initial="icon?.source ?? null" :export-name="name || 'icon'" @close="iconOpen = false" @save="onIcon" />
</template>
