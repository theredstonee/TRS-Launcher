<script setup lang="ts">
import type { ModrinthVersion, ProjectDetails, ProjectLink } from '~/types'
import { type AddonSearchFacets, type CraftingModsResult, installedVersion, newestServerVersion, serverVersions } from '~/utils/craftinghosting'

// Projektseite eines Plugins/Mods für den Server: Beschreibung, Galerie und die passenden
// Versionen (Loader + Minecraft-Version des Servers), jede einzeln installierbar.
const props = defineProps<{ projectId: string; facets: AddonSearchFacets; installedFiles: string[] }>()
const emit = defineEmits<{ back: [] }>()
const store = useCraftingHostingStore()
const toasts = useToasts()

const details = ref<ProjectDetails | null>(null)
const versions = ref<ModrinthVersion[]>([])
const loading = ref(true)
const loadingVersions = ref(true)
const error = ref<string | null>(null)
const tab = ref<'description' | 'gallery' | 'versions'>('description')
const showAll = ref(false)
const busyVersion = ref<string | null>(null)
const outcome = ref<{ version: string; result: CraftingModsResult } | null>(null)

let seq = 0
async function load() {
  const mine = ++seq
  loading.value = true
  loadingVersions.value = true
  error.value = null
  try {
    const next = await backend.modrinthProject(props.projectId)
    if (mine === seq) details.value = next
  } catch (e) {
    if (mine === seq) error.value = errorMessage(e)
  } finally {
    if (mine === seq) loading.value = false
  }
  try {
    const list = await backend.modrinthProjectVersions(props.projectId)
    if (mine === seq) versions.value = list
  } catch (e) {
    if (mine === seq) toasts.error(e)
  } finally {
    if (mine === seq) loadingVersions.value = false
  }
}
watch(() => props.projectId, load, { immediate: true })

const clientOnly = computed(() => details.value?.serverSide === 'unsupported')
const shown = computed(() => serverVersions(versions.value, props.facets, showAll.value))
const fittingCount = computed(() => serverVersions(versions.value, props.facets, false).length)
const newest = computed(() => newestServerVersion(versions.value, props.facets))
const installed = computed(() => installedVersion(versions.value, props.installedFiles))
const loaderChips = computed(() => (details.value?.loaders ?? []).slice(0, 8))
const categories = computed(() => (details.value?.categories ?? []).filter((c) => !details.value?.loaders.includes(c)))

async function install(version: ModrinthVersion | null) {
  const target = version ?? newest.value
  if (!details.value || !target || clientOnly.value) return
  busyVersion.value = target.id
  try {
    const result = await store.installVersion(details.value.projectId, target.id)
    if (!result) return
    outcome.value = { version: target.versionNumber, result }
    if (!result.skipped.length) toasts.ok(t('craftinghosting.addons.installedToast', { name: details.value.title }))
  } finally {
    busyVersion.value = null
  }
}

function openLink(link: ProjectLink) {
  backend.openExternalUrl(link.url).catch((e) => toasts.error(e))
}

const TYPE_TONE: Record<string, string> = {
  release: 'bg-emerald-900/40 text-emerald-300 ring-emerald-700/40',
  beta: 'bg-lamp-900/40 text-lamp-300 ring-lamp-700/40',
  alpha: 'bg-redstone-900/40 text-redstone-300 ring-redstone-700/40',
}
const requiredDeps = (v: ModrinthVersion) => v.dependencies.filter((d) => d.dependencyType === 'required').length
</script>

<template>
  <section class="card overflow-hidden" data-testid="hosting-addon-project">
    <div class="flex items-center gap-2 border-b border-base-800 px-3 py-2">
      <button class="btn btn-ghost px-2.5 py-1 text-xs" data-testid="hosting-addon-back" @click="emit('back')">
        <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" aria-hidden="true"><path d="M15 5l-7 7 7 7" /></svg>
        {{ t('craftinghosting.addons.backToSearch') }}
      </button>
    </div>

    <p v-if="error" role="alert" class="p-4 text-sm text-redstone-300">{{ error }}</p>
    <div v-else-if="loading" class="flex gap-4 p-5">
      <div class="skeleton size-20 rounded-xl" />
      <div class="flex-1 space-y-2.5"><div class="skeleton h-7 w-60" /><div class="skeleton h-4 w-full max-w-md" /><div class="skeleton h-4 w-40" /></div>
    </div>

    <template v-else-if="details">
      <header class="ch-project-hero flex flex-wrap items-start gap-5 p-5 mobile:gap-3 mobile:p-4">
        <ModIcon :src="details.iconUrl" :name="details.title" :size="80" class="shadow-lg shadow-black/30" />
        <div class="min-w-0 flex-1 basis-72">
          <h3 class="display text-2xl leading-tight break-words text-base-50">{{ details.title }}</h3>
          <p v-if="details.author" class="mt-0.5 text-xs text-base-400">{{ t('project.stats.by', { author: details.author }) }}</p>
          <p class="mt-1.5 max-w-2xl text-sm text-base-200">{{ details.description }}</p>
          <p class="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-xs text-base-400">
            <span>{{ t('project.stats.downloads', { count: formatCount(details.downloads) }, details.downloads) }}</span>
            <span>{{ t('project.stats.followers', { count: formatCount(details.followers) }, details.followers) }}</span>
            <span v-if="details.updated" :title="formatDate(details.updated)">{{ t('project.stats.updated', { time: formatRelative(details.updated, true) }) }}</span>
          </p>
          <div class="mt-3 flex flex-wrap gap-1.5">
            <span v-for="l in loaderChips" :key="l" class="chip bg-base-800/80 font-medium">{{ loaderNames[l] ?? categoryLabel(l) }}</span>
            <span v-for="c in categories" :key="c" class="chip bg-base-850/80 text-base-400">{{ categoryLabel(c) }}</span>
          </div>
        </div>

        <aside class="w-full shrink-0 rounded-xl border border-base-700/70 bg-base-900/80 p-4 sm:w-64">
          <p v-if="clientOnly" class="text-xs text-lamp-300" data-testid="hosting-addon-client-only">{{ t('craftinghosting.addons.clientOnly') }}</p>
          <template v-else>
            <div v-if="installed" class="mb-2 flex items-center gap-2 rounded-md bg-base-850 px-3 py-2 text-xs" data-testid="hosting-addon-installed">
              <svg viewBox="0 0 24 24" class="size-4 shrink-0 text-ok" fill="none" stroke="currentColor" stroke-width="2.5" aria-hidden="true"><path d="m5 12 5 5 9-10" /></svg>
              <span class="min-w-0 flex-1 truncate">{{ t('craftinghosting.addons.installedVersion', { version: installed.version.versionNumber }) }}</span>
            </div>
            <button
              v-if="!installed || (newest && newest.id !== installed.version.id)"
              class="btn btn-primary w-full"
              :disabled="!newest || !!busyVersion || !!store.busy"
              data-testid="hosting-addon-install-newest"
              @click="install(null)"
            >
              {{ busyVersion && busyVersion === newest?.id ? t('craftinghosting.addons.installing') : installed ? t('craftinghosting.addons.updateTo', { version: newest?.versionNumber ?? '' }) : t('craftinghosting.addons.install') }}
            </button>
            <p v-if="!loadingVersions && !newest" class="mt-2 text-xs text-lamp-300">{{ t('craftinghosting.addons.noFitting') }}</p>
            <button class="mt-2 w-full text-center text-xs text-base-400 hover:text-base-50" @click="tab = 'versions'">
              {{ t('craftinghosting.addons.otherVersion') }}
            </button>
            <p v-if="outcome" class="mt-2 text-xs" :class="outcome.result.skipped.length ? 'text-lamp-300' : 'text-ok'" aria-live="polite">
              {{
                outcome.result.skipped.length
                  ? t('craftinghosting.addons.skipped', { reason: t(`craftinghosting.mods.reason.${outcome.result.skipped[0]!.reason}`) })
                  : t('craftinghosting.addons.installedNext', { version: outcome.version })
              }}
            </p>
          </template>
          <div v-if="details.links.length" class="mt-3 flex flex-wrap gap-1.5 border-t border-base-800 pt-3">
            <button v-for="l in details.links" :key="l.kind" class="chip hover:bg-base-700 hover:text-base-50" @click="openLink(l)">{{ t(`project.links.${l.kind}`) }}</button>
          </div>
        </aside>
      </header>

      <nav class="flex gap-1 border-y border-base-800 px-3" :aria-label="t('project.tabs.label')">
        <button class="tab" :class="{ 'tab-on': tab === 'description' }" @click="tab = 'description'">{{ t('common.labels.description') }}</button>
        <button v-if="details.gallery.length" class="tab" :class="{ 'tab-on': tab === 'gallery' }" @click="tab = 'gallery'">
          {{ t('project.tabs.gallery') }} <span class="ml-1 text-xs text-base-600">{{ details.gallery.length }}</span>
        </button>
        <button class="tab" :class="{ 'tab-on': tab === 'versions' }" data-testid="hosting-addon-versions-tab" @click="tab = 'versions'">
          {{ t('project.tabs.versions') }} <span v-if="!loadingVersions" class="ml-1 text-xs text-base-600">{{ fittingCount }}</span>
        </button>
      </nav>

      <div class="p-5 mobile:p-4">
        <article v-if="tab === 'description'" class="min-w-0">
          <MarkdownView v-if="details.body" :source="details.body" />
          <p v-else class="text-sm text-base-400">{{ t('project.noDescription') }}</p>
        </article>
        <ProjectGallery v-else-if="tab === 'gallery'" :images="details.gallery" />
        <div v-else>
          <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
            <p class="text-xs text-base-400">{{ showAll ? t('craftinghosting.addons.allVersionsHint') : t('craftinghosting.addons.fittingHint', { version: facets.gameVersions[0] ?? '–' }) }}</p>
            <label class="flex items-center gap-2 text-xs text-base-200">
              <ToggleSwitch v-model="showAll" :label="t('craftinghosting.addons.showAll')" />
              {{ t('craftinghosting.addons.showAll') }}
            </label>
          </div>
          <div v-if="loadingVersions" class="space-y-2"><div v-for="i in 4" :key="i" class="skeleton h-14" /></div>
          <p v-else-if="!shown.length" class="text-sm text-base-500">{{ t('craftinghosting.addons.noFitting') }}</p>
          <ul v-else class="divide-y divide-base-800/60 rounded-lg ring-1 ring-base-800" data-testid="hosting-addon-versions">
            <li v-for="v in shown" :key="v.id" class="flex flex-wrap items-center gap-3 px-3 py-2.5">
              <div class="min-w-0 flex-1">
                <p class="flex flex-wrap items-center gap-2 text-sm">
                  <span class="truncate font-mono text-base-50">{{ v.versionNumber }}</span>
                  <span class="rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase ring-1" :class="TYPE_TONE[v.versionType] ?? 'bg-base-800 text-base-300 ring-base-700'">{{ versionTypeLabel(v.versionType) }}</span>
                  <span v-if="installed?.version.id === v.id" class="badge bg-emerald-900/50 text-emerald-300 ring-1 ring-emerald-700/40">{{ t('craftinghosting.addons.installed') }}</span>
                </p>
                <p class="mt-0.5 flex flex-wrap gap-x-3 text-[11px] text-base-500">
                  <span v-if="v.datePublished">{{ formatDate(v.datePublished) }}</span>
                  <span v-if="v.downloads">{{ t('craftinghosting.addons.downloads', { n: formatCount(v.downloads) }) }}</span>
                  <span>{{ gameVersionRange(v.gameVersions) }}</span>
                  <span>{{ v.loaders.map((l) => loaderNames[l] ?? categoryLabel(l)).join(', ') }}</span>
                  <span v-if="requiredDeps(v)">{{ t('craftinghosting.addons.dependencies', { n: requiredDeps(v) }) }}</span>
                </p>
              </div>
              <button
                class="btn btn-ghost px-2.5 py-1 text-xs"
                :disabled="clientOnly || !!busyVersion || !!store.busy || installed?.version.id === v.id"
                :data-testid="`hosting-addon-install-${v.id}`"
                @click="install(v)"
              >
                {{ busyVersion === v.id ? t('craftinghosting.addons.installing') : t('craftinghosting.addons.installThis') }}
              </button>
            </li>
          </ul>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.ch-project-hero {
  background:
    radial-gradient(80% 120% at 0% 0%, color-mix(in srgb, var(--color-redstone-500) 10%, transparent), transparent 60%),
    var(--color-base-900);
}
</style>
