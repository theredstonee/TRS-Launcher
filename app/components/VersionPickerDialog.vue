<script setup lang="ts">
import type { ContentKind, Instance, ModrinthVersion, Platform } from '~/types'

// Wählt eine zur Instanz passende Version – zum Installieren oder, mit
// `currentVersionId`, zum Wechseln (auch auf ältere Versionen).
const props = defineProps<{
  instance: Instance
  projectId: string
  title: string
  kind: ContentKind
  currentVersionId?: string | null
  platform?: Platform
}>()
const emit = defineEmits<{ close: []; pick: [version: ModrinthVersion] }>()

const versions = ref<ModrinthVersion[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const showPrerelease = ref(false)
const open = ref<string | null>(null)
const isCf = computed(() => props.platform === 'curseforge')
// CurseForge liefert Changelogs nur einzeln – beim Aufklappen nachladen.
const changelogs = useLazyChangelogs(() => props.projectId)

function toggle(v: ModrinthVersion) {
  open.value = open.value === v.id ? null : v.id
  if (open.value && isCf.value) changelogs.load(v.id)
}

const currentIndex = computed(() => versions.value.findIndex((v) => v.id === props.currentVersionId))
const visible = computed(() =>
  showPrerelease.value
    ? versions.value
    : versions.value.filter((v) => v.versionType === 'release' || v.id === props.currentVersionId),
)

onMounted(async () => {
  try {
    versions.value = await platformApi.versions(props.platform ?? 'modrinth', props.instance.id, props.projectId, props.kind)
    // Gibt es nur Betas, sollen die nicht hinter dem Schalter verschwinden.
    if (!versions.value.some((v) => v.versionType === 'release')) showPrerelease.value = true
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
})

function relation(v: ModrinthVersion): 'newer' | 'older' | null {
  if (!props.currentVersionId || currentIndex.value < 0 || v.id === props.currentVersionId) return null
  return versions.value.indexOf(v) < currentIndex.value ? 'newer' : 'older'
}

const versionTypes = ['release', 'beta', 'alpha'] as const
/** „Stabil“, „Beta“, „Alpha“ – Unbekanntes bleibt, wie Modrinth es liefert. */
function typeLabel(type: string): string {
  const known = versionTypes.find((k) => k === type)
  return known ? t(`versionPicker.type.${known}`) : type
}

function actionLabel(v: ModrinthVersion): string {
  if (!props.currentVersionId) return t('common.actions.install')
  const rel = relation(v)
  return rel === 'older' ? t('versionPicker.downgrade') : rel === 'newer' ? t('common.actions.update') : t('versionPicker.switch')
}
</script>

<template>
  <BaseDialog :title="currentVersionId ? t('versionPicker.titleChange', { title }) : t('versionPicker.titlePick', { title })" wide @close="emit('close')">
    <div class="mb-3 flex items-center justify-between text-xs text-base-400">
      <span>{{ t('versionPicker.compatibleWith', { version: instance.gameVersion, loader: loaderLabels[instance.loader.kind] }) }}</span>
      <label class="flex items-center gap-1.5">
        <input v-model="showPrerelease" type="checkbox" class="accent-redstone-500" />
        {{ t('versionPicker.showPrerelease') }}
      </label>
    </div>

    <div v-if="loading" class="space-y-2">
      <div v-for="i in 4" :key="i" class="skeleton h-14" />
    </div>
    <p v-else-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
    <p v-else-if="!visible.length" class="py-6 text-center text-sm text-base-400">{{ t('versionPicker.none') }}</p>

    <ul v-else class="-mr-2 max-h-[26rem] space-y-1.5 overflow-y-auto pr-2">
      <li v-for="(v, i) in visible" :key="v.id" class="overflow-hidden rounded-lg border bg-base-900" :class="v.id === currentVersionId ? 'border-redstone-600/60' : 'border-base-700'">
        <div class="flex items-center gap-2 px-2 py-2">
          <button
            class="flex size-7 shrink-0 items-center justify-center rounded-md text-base-400 hover:bg-base-800 hover:text-base-50 disabled:opacity-30"
            :disabled="!v.changelog && !isCf"
            :aria-expanded="open === v.id"
            :aria-label="open === v.id ? t('versionPicker.hideChangelog') : t('versionPicker.showChangelog')"
            @click="toggle(v)"
          >
            <svg viewBox="0 0 24 24" class="size-4 transition-transform" :class="{ 'rotate-90': open === v.id }" fill="none" stroke="currentColor" stroke-width="2.2"><path d="m9 6 6 6-6 6" /></svg>
          </button>
          <div class="min-w-0 flex-1">
            <p class="flex items-center gap-1.5 truncate text-sm font-medium">
              <span class="truncate">{{ v.versionNumber }}</span>
              <span v-if="v.id === currentVersionId" class="badge bg-redstone-900 text-redstone-300">{{ t('versionPicker.installed') }}</span>
              <span v-else-if="i === 0" class="badge bg-base-800 text-base-200">{{ t('versionPicker.latest') }}</span>
              <span v-if="relation(v)" class="badge bg-base-850 text-base-400">{{ relation(v) === 'newer' ? t('versionPicker.newer') : t('versionPicker.older') }}</span>
            </p>
            <p class="truncate text-xs text-base-400">
              <span :class="v.versionType === 'release' ? 'text-ok' : 'text-lamp-400'">{{ typeLabel(v.versionType) }}</span>
              · {{ formatDate(v.datePublished) }} · {{ formatFileSize(v.size) }}
            </p>
          </div>
          <button
            v-if="v.id !== currentVersionId"
            class="btn shrink-0 px-3 py-1.5 text-xs"
            :class="currentVersionId ? 'btn-ghost' : 'btn-primary'"
            @click="emit('pick', v)"
          >
            {{ actionLabel(v) }}
          </button>
        </div>
        <div v-if="open === v.id && isCf" class="border-t border-base-800 bg-base-950/40 px-4 py-3">
          <p v-if="changelogs.state(v.id) === 'loading'" class="text-xs text-base-400">{{ t('common.status.loading') }}</p>
          <MarkdownView v-else-if="changelogs.text(v.id)" :source="changelogs.text(v.id)" html />
          <p v-else class="text-xs text-base-400">{{ t('changelog.noChangelog') }}</p>
        </div>
        <div v-else-if="open === v.id && v.changelog" class="border-t border-base-800 bg-base-950/40 px-4 py-3">
          <MarkdownView :source="v.changelog" />
        </div>
      </li>
    </ul>

    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.close') }}</button>
    </template>
  </BaseDialog>
</template>
