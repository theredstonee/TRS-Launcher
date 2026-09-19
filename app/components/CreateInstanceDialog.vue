<script setup lang="ts">
import type { Instance, LoaderKind } from '~/types'

const emit = defineEmits<{ close: []; created: [instance: Instance] }>()

const instances = useInstancesStore()
const meta = useMetaStore()
const settings = useSettingsStore()

const name = ref('')
const gameVersion = ref('')
const loaderKind = ref<LoaderKind>('vanilla')
const showSnapshots = ref(settings.current?.showSnapshots ?? false)

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
    emit('created', await instances.create(parsed.data))
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <BaseDialog title="Neue Instanz" @close="emit('close')">
    <form id="create-instance" class="space-y-4" @submit.prevent="submit">
      <div>
        <label class="label" for="ci-name">Name</label>
        <input id="ci-name" v-model="name" class="field" maxlength="64" placeholder="Meine Welt" autofocus />
      </div>

      <div>
        <div class="flex items-center justify-between">
          <label class="label" for="ci-version">Minecraft-Version</label>
          <label class="mb-1.5 flex items-center gap-1.5 text-xs text-base-400">
            <input v-model="showSnapshots" type="checkbox" class="accent-redstone-500" />
            Snapshots &amp; alte Versionen
          </label>
        </div>
        <select id="ci-version" v-model="gameVersion" class="field font-mono" :disabled="loadingVersions || !versions.length">
          <option v-if="loadingVersions" value="">Lade Versionen …</option>
          <option v-for="v in versions" :key="v.id" :value="v.id">{{ v.id }}</option>
        </select>
      </div>

      <div>
        <span class="label">Modloader</span>
        <div class="grid grid-cols-5 gap-1.5">
          <button
            v-for="kind in loaderKinds"
            :key="kind"
            type="button"
            class="rounded-md border px-1 py-2 text-xs font-medium transition-colors"
            :class="loaderKind === kind
              ? 'border-redstone-500 bg-redstone-900 text-base-50'
              : 'border-base-700 bg-base-900 text-base-400 hover:border-base-600 hover:text-base-50'"
            @click="loaderKind = kind"
          >
            {{ loaderLabels[kind] }}
          </button>
        </div>
      </div>

      <p v-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
    </form>

    <template #actions>
      <button type="button" class="btn btn-ghost" @click="emit('close')">Abbrechen</button>
      <button type="submit" form="create-instance" class="btn btn-primary" :disabled="submitting || loadingVersions || !gameVersion">
        {{ submitting ? 'Erstelle …' : 'Erstellen' }}
      </button>
    </template>
  </BaseDialog>
</template>
