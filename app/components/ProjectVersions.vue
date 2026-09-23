<script setup lang="ts">
import type { ContentKind, Instance, ModrinthVersion } from '~/types'

// Versionsliste eines Projekts: filterbar nach Spielversion und Loader, mit
// aufklappbarem Changelog und Installieren/Wechseln je Version.
const props = defineProps<{
  versions: ModrinthVersion[]
  loading: boolean
  /** Zielinstanz – ohne sie gibt es nur die Liste. */
  instance: Instance | null
  kind: ContentKind | null
  installedVersionId: string | null
  busyVersionId: string | null
}>()
const emit = defineEmits<{ install: [version: ModrinthVersion] }>()

const gameVersion = ref<string>(props.instance?.gameVersion ?? '')
const loader = ref<string>(props.instance && props.kind === 'mod' ? (loaderTags[props.instance.loader.kind][0] ?? '') : '')
const showPrerelease = ref(false)
const open = ref<string | null>(null)
const limit = ref(30)

watch(
  () => props.instance?.id,
  () => {
    gameVersion.value = props.instance?.gameVersion ?? ''
    loader.value = props.instance && props.kind === 'mod' ? (loaderTags[props.instance.loader.kind][0] ?? '') : ''
  },
)

const allGameVersions = computed(() => {
  const set = new Set<string>()
  for (const v of props.versions) for (const g of v.gameVersions) set.add(g)
  // Neueste zuerst; Releases („1.21.1“) vor Snapshots.
  return [...set].sort((a, b) => b.localeCompare(a, 'en', { numeric: true }))
})
const allLoaders = computed(() => {
  const set = new Set<string>()
  for (const v of props.versions) for (const l of v.loaders) set.add(l)
  return [...set].sort()
})

const hasRelease = computed(() => props.versions.some((v) => v.versionType === 'release'))
const filtered = computed(() =>
  props.versions.filter(
    (v) =>
      (!gameVersion.value || v.gameVersions.includes(gameVersion.value)) &&
      (!loader.value || v.loaders.includes(loader.value)) &&
      (showPrerelease.value || !hasRelease.value || v.versionType === 'release'),
  ),
)
const newestFitting = computed(() =>
  props.instance && props.kind ? props.versions.find((v) => versionFits(v, props.instance!, props.kind!))?.id : null,
)
const installedIndex = computed(() => props.versions.findIndex((v) => v.id === props.installedVersionId))

function fits(v: ModrinthVersion) {
  return !!props.instance && !!props.kind && versionFits(v, props.instance, props.kind)
}

// Liste ist neueste zuerst: weiter oben als die installierte = neuer.
function actionLabel(v: ModrinthVersion): string {
  if (!props.installedVersionId) return t('common.actions.install')
  if (installedIndex.value < 0) return t('project.versions.action.switch')
  return props.versions.indexOf(v) < installedIndex.value ? t('project.versions.action.update') : t('project.versions.action.downgrade')
}

function toggle(id: string) {
  open.value = open.value === id ? null : id
}
</script>

<template>
  <div>
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <select v-model="gameVersion" class="field h-8 w-40 py-0 text-xs" :aria-label="t('project.versions.gameVersionLabel')">
        <option value="">{{ t('project.versions.allVersions') }}</option>
        <option v-for="g in allGameVersions" :key="g" :value="g">{{ g }}</option>
      </select>
      <select v-if="allLoaders.length > 1" v-model="loader" class="field h-8 w-36 py-0 text-xs" :aria-label="t('common.labels.loader')">
        <option value="">{{ t('project.versions.allLoaders') }}</option>
        <option v-for="l in allLoaders" :key="l" :value="l">{{ loaderNames[l] ?? l }}</option>
      </select>
      <label v-if="hasRelease" class="flex items-center gap-1.5 text-xs text-base-400">
        <input v-model="showPrerelease" type="checkbox" class="accent-redstone-500" />
        {{ t('project.versions.showPrerelease') }}
      </label>
      <span class="ml-auto text-xs text-base-600">{{ t('project.versions.count', { shown: filtered.length, total: versions.length }) }}</span>
    </div>

    <div v-if="loading" class="space-y-2">
      <div v-for="i in 5" :key="i" class="skeleton h-14" />
    </div>
    <p v-else-if="!filtered.length" class="card px-4 py-10 text-center text-sm text-base-400">
      {{ t('project.versions.noMatch') }}
    </p>

    <ul v-else class="space-y-1.5">
      <li v-for="v in filtered.slice(0, limit)" :key="v.id" class="card overflow-hidden" :class="{ 'border-redstone-600/50': v.id === installedVersionId }">
        <div class="flex items-center gap-3 px-3 py-2.5">
          <button
            class="flex size-7 shrink-0 items-center justify-center rounded-md text-base-400 transition-colors hover:bg-base-800 hover:text-base-50 disabled:opacity-30"
            :aria-expanded="open === v.id"
            :aria-label="open === v.id ? t('project.versions.collapseChangelog') : t('project.versions.expandChangelog')"
            :disabled="!v.changelog"
            @click="toggle(v.id)"
          >
            <svg viewBox="0 0 24 24" class="size-4 transition-transform" :class="{ 'rotate-90': open === v.id }" fill="none" stroke="currentColor" stroke-width="2.2"><path d="m9 6 6 6-6 6" /></svg>
          </button>
          <div class="min-w-0 flex-1">
            <p class="flex items-center gap-2 truncate text-sm font-medium">
              <span class="truncate">{{ v.versionNumber }}</span>
              <span class="badge" :class="v.versionType === 'release' ? 'bg-ok/10 text-ok' : 'bg-lamp-900 text-lamp-300'">{{ versionTypeLabel(v.versionType) }}</span>
              <span v-if="v.id === installedVersionId" class="badge bg-redstone-900 text-redstone-300">{{ t('project.versions.installed') }}</span>
              <span v-else-if="v.id === newestFitting" class="badge bg-base-800 text-base-200">{{ t('project.versions.newestFitting') }}</span>
            </p>
            <p class="mt-0.5 truncate text-xs text-base-400">
              {{ v.loaders.map((l) => loaderNames[l] ?? l).join(', ') }}
              <span v-if="v.gameVersions.length" class="text-base-600"> · </span>{{ gameVersionRange(v.gameVersions) }}
              <span class="text-base-600"> · </span>{{ formatDate(v.datePublished) }}
            </p>
          </div>
          <span class="hidden w-16 shrink-0 text-right font-mono text-xs text-base-600 sm:block">{{ formatFileSize(v.size) }}</span>
          <template v-if="instance && kind">
            <span v-if="busyVersionId === v.id" class="w-28 shrink-0"><RedstoneWire :percent="60" :segments="10" /></span>
            <button
              v-else-if="v.id !== installedVersionId"
              class="btn w-28 shrink-0 px-2 py-1.5 text-xs"
              :class="fits(v) ? (installedVersionId ? 'btn-ghost' : 'btn-primary') : 'btn-ghost'"
              :disabled="!fits(v) || !!busyVersionId"
              :title="fits(v) ? '' : t('project.versions.incompatibleTitle', { version: instance.gameVersion, loader: loaderLabels[instance.loader.kind] })"
              @click="emit('install', v)"
            >
              {{ fits(v) ? actionLabel(v) : t('project.versions.incompatible') }}
            </button>
            <span v-else class="w-28 shrink-0" />
          </template>
        </div>
        <div v-if="open === v.id && v.changelog" class="border-t border-base-800 bg-base-950/40 px-5 py-4">
          <MarkdownView :source="v.changelog" />
        </div>
      </li>
    </ul>
    <div v-if="filtered.length > limit" class="flex justify-center pt-3">
      <button class="btn btn-ghost text-xs" @click="limit += 30">{{ t('project.versions.showMore') }}</button>
    </div>
  </div>
</template>
