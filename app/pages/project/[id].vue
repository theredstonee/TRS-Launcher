<script setup lang="ts">
import type { ContentItem, ContentKind, ModrinthVersion, Platform, ProjectDetails, ProjectLink } from '~/types'

// Detailansicht eines Modrinth- oder CurseForge-Projekts (`?platform=curseforge`):
// Beschreibung, Galerie, Versionen (mit Changelog, Installieren/Wechseln) und
// Abhängigkeiten.
const route = useRoute()
const router = useRouter()
const instances = useInstancesStore()
const tasks = useTasksStore()
const toasts = useToasts()

const projectId = computed(() => String(route.params.id))
const platform = computed<Platform>(() => (route.query.platform === 'curseforge' ? 'curseforge' : 'modrinth'))
const isCf = computed(() => platform.value === 'curseforge')
const tab = ref<'description' | 'gallery' | 'versions' | 'dependencies'>('description')

const details = ref<ProjectDetails | null>(null)
const versions = ref<ModrinthVersion[]>([])
const loadingDetails = ref(true)
const loadingVersions = ref(true)
const error = ref<string | null>(null)

const kind = computed(() => (details.value ? projectKindOf(details.value.projectType) : null))
const contentKind = computed<ContentKind | null>(() => (kind.value && kind.value !== 'modpack' ? kind.value : null))
const isPack = computed(() => kind.value === 'modpack')

const instanceId = ref(typeof route.query.instance === 'string' ? route.query.instance : '')
const target = computed(() => instances.items.find((i) => i.id === instanceId.value) ?? null)
// Vanilla mit TRS-Optimierung läuft als Fabric und nimmt Fabric-Mods.
const modsBlocked = computed(
  () => contentKind.value === 'mod' && target.value?.loader.kind === 'vanilla' && target.value.overrides.boost === false,
)

const installed = ref<ContentItem | null>(null)
// Installationen laufen im Aufgaben-Store – zurück auf der Seite sieht man sie wieder.
const key = computed(() => (details.value ? projectKey(platform.value, details.value.projectId) : ''))
const packTask = computed(() => (details.value ? tasks.get(modpackTaskKey(key.value)) : null))
const contentTask = computed(() => (details.value && target.value ? tasks.get(contentTaskKey(target.value.id, key.value)) : null))
/** Der Autor erlaubt das Modpack nur auf CurseForge selbst – dann dorthin verlinken. */
const packPageUrl = computed(() => {
  const failure = packTask.value?.status === 'failed' ? packTask.value.errorRef : null
  const url = failure?.key === 'errors.curseforge.packBlocked' ? failure.params?.url : null
  return typeof url === 'string' && isSafeLink(url) ? url : null
})
const busy = computed(() => (contentTask.value?.status === 'running' ? (contentTask.value.tag ?? 'latest') : null))

const categories = computed(() => (details.value?.categories ?? []).filter((c) => !(c in loaderNames)))
const newestFitting = computed(() =>
  target.value && contentKind.value ? (versions.value.find((v) => versionFits(v, target.value!, contentKind.value!)) ?? null) : null,
)
const depsVersion = computed(() => newestFitting.value ?? versions.value[0] ?? null)
const updateAvailable = computed(
  () => !!installed.value?.source && !!newestFitting.value && newestFitting.value.id !== installed.value.source.versionId,
)

async function load() {
  loadingDetails.value = true
  loadingVersions.value = true
  error.value = null
  details.value = null
  versions.value = []
  try {
    details.value = await platformApi.project(platform.value, projectId.value)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loadingDetails.value = false
  }
  if (!details.value) return
  try {
    versions.value = await platformApi.projectVersions(platform.value, details.value.projectId)
  } catch (e) {
    toasts.error(e)
  } finally {
    loadingVersions.value = false
  }
}

async function loadInstalled() {
  installed.value = null
  if (!target.value || !contentKind.value || !details.value) return
  const id = details.value.projectId
  try {
    const items = await backend.listContent(target.value.id, contentKind.value)
    installed.value = items.find((i) => i.source?.projectId === id && sourcePlatform(i.source) === platform.value) ?? null
  } catch {
    installed.value = null
  }
}

onMounted(async () => {
  if (!instances.items.length) await instances.load()
  if (!target.value) {
    instanceId.value = (instances.items.find((i) => i.loader.kind !== 'vanilla') ?? instances.items[0])?.id ?? ''
  }
})
watch([projectId, platform], load, { immediate: true })
watch([target, details], loadInstalled)
// Fertig installiert (auch während man woanders war): Stand neu laden.
watch(
  () => contentTask.value?.status,
  (status) => {
    if (status === 'done') loadInstalled()
  },
)

function installVersion(version: ModrinthVersion | null) {
  if (!details.value || !target.value || !contentKind.value || busy.value) return
  installContentTask({
    instance: target.value,
    projectId: details.value.projectId,
    title: details.value.title,
    iconUrl: details.value.iconUrl,
    kind: contentKind.value,
    version,
    replace: version ? (installed.value?.fileName ?? null) : null,
    platform: platform.value,
  })
}

function installPack() {
  if (!details.value) return
  installModpackTask(details.value, platform.value)
}

function openPackPage() {
  if (packPageUrl.value) backend.openExternalUrl(packPageUrl.value).catch((e) => toasts.error(e))
}

function openLink(link: ProjectLink) {
  backend.openExternalUrl(link.url).catch((e) => toasts.error(e))
}

function back() {
  if (window.history.length > 1) router.back()
  else router.push('/browse')
}
</script>

<template>
  <div class="p-6">
    <button class="mb-4 inline-flex items-center gap-1 text-xs text-base-400 hover:text-base-50" @click="back">
      <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M15 5l-7 7 7 7" /></svg>
      {{ t('common.actions.back') }}
    </button>

    <p v-if="error" role="alert" class="card border-redstone-600/50 px-4 py-3 text-sm text-redstone-300">{{ error }}</p>

    <!-- Kopf -->
    <div v-else-if="loadingDetails" class="flex gap-5">
      <div class="skeleton size-24 rounded-xl" />
      <div class="flex-1 space-y-3 pt-1">
        <div class="skeleton h-9 w-72" />
        <div class="skeleton h-4 w-full max-w-lg" />
        <div class="skeleton h-4 w-60" />
      </div>
      <div class="skeleton h-40 w-72" />
    </div>

    <template v-else-if="details">
      <header class="project-hero relative mb-6 flex flex-wrap items-start gap-6 overflow-hidden rounded-2xl border border-base-800 p-6">
        <ModIcon :src="details.iconUrl" :name="details.title" :size="104" class="shadow-lg shadow-black/30" />
        <div class="min-w-0 flex-1 basis-80">
          <h1 class="display text-4xl leading-tight break-words text-base-50">{{ details.title }}</h1>
          <p v-if="isCf" class="mt-1 flex items-center gap-1.5 text-xs text-base-400">
            <span class="size-1.5 rounded-full bg-[#f16436]" aria-hidden="true" />
            {{ t('project.viaCurseForge') }}
          </p>
          <p class="mt-1.5 max-w-2xl text-sm text-base-200">{{ details.description }}</p>
          <dl class="mt-4 flex flex-wrap gap-x-6 gap-y-2 text-xs text-base-400">
            <div v-if="details.author">
              <dt class="sr-only">{{ t('project.stats.author') }}</dt>
              <i18n-t keypath="project.stats.by" tag="dd" scope="global">
                <template #author><span class="font-medium text-base-50">{{ details.author }}</span></template>
              </i18n-t>
            </div>
            <div class="flex items-baseline gap-1.5">
              <dt class="sr-only">{{ t('project.stats.downloadsLabel') }}</dt>
              <i18n-t keypath="project.stats.downloads" tag="dd" scope="global" :plural="details.downloads">
                <template #count><span class="display text-base text-base-50 tabular-nums">{{ formatCount(details.downloads) }}</span></template>
              </i18n-t>
            </div>
            <!-- CurseForge kennt keine Follower. -->
            <div v-if="!isCf" class="flex items-baseline gap-1.5">
              <dt class="sr-only">{{ t('project.stats.followersLabel') }}</dt>
              <i18n-t keypath="project.stats.followers" tag="dd" scope="global" :plural="details.followers">
                <template #count><span class="display text-base text-base-50 tabular-nums">{{ formatCount(details.followers) }}</span></template>
              </i18n-t>
            </div>
            <div v-if="details.updated">
              <dt class="sr-only">{{ t('project.stats.updatedLabel') }}</dt>
              <dd :title="formatDate(details.updated)">{{ t('project.stats.updated', { time: formatRelative(details.updated, true) }) }}</dd>
            </div>
          </dl>
          <div v-if="categories.length || details.loaders.length" class="mt-4 flex flex-wrap gap-1.5">
            <span v-for="l in details.loaders" :key="l" class="chip bg-base-800/80 font-medium">{{ loaderNames[l] ?? l }}</span>
            <span v-for="c in categories" :key="c" class="chip bg-base-850/80 text-base-400">{{ categoryLabel(c) }}</span>
          </div>
        </div>

        <!-- Installieren -->
        <aside class="w-full shrink-0 rounded-xl border border-base-700/70 bg-base-900/80 p-4 backdrop-blur sm:w-72">
          <template v-if="isPack">
            <p class="text-sm font-medium">{{ t('project.pack.title') }}</p>
            <p class="mt-0.5 mb-3 text-xs text-base-400">{{ t('project.pack.hint') }}</p>
            <div v-if="packTask?.status === 'running'" class="space-y-2">
              <button
                class="btn btn-ghost w-full tabular-nums"
                :aria-label="t('project.pack.installingLabel', { percent: packTask.percent ?? 0 })"
                @click="tasks.openPanel(packTask.key)"
              >
                {{ t(packTask.paused ? 'project.pack.pausedPercent' : 'project.pack.installingPercent', { percent: packTask.percent ?? 0 }) }}
              </button>
              <RedstoneWire :percent="packTask.percent ?? 0" :segments="24" />
              <p class="text-center text-xs text-base-400">{{ packTask.stage }}</p>
            </div>
            <template v-else-if="packTask?.status === 'done' && packTask.instanceId">
              <button class="btn btn-primary w-full" @click="router.push(`/instances/${packTask.instanceId}`)">{{ t('project.pack.openInstance') }}</button>
              <button class="mt-2 w-full text-center text-xs text-base-400 hover:text-base-200" @click="installPack">{{ t('project.pack.reinstall') }}</button>
            </template>
            <template v-else-if="packPageUrl">
              <p class="mb-2 text-xs leading-relaxed text-warn">{{ t('curseforge.packBlocked') }}</p>
              <button class="btn btn-primary w-full" @click="openPackPage">{{ t('curseforge.openOnCurseForge') }}</button>
            </template>
            <button v-else class="btn btn-primary w-full" @click="installPack">{{ t('project.pack.install') }}</button>
          </template>
          <template v-else>
            <label class="label" for="p-target">{{ t('project.install.target') }}</label>
            <select id="p-target" v-model="instanceId" class="field mb-3 py-1.5" :disabled="!instances.items.length">
              <option v-if="!instances.items.length" value="">{{ t('project.install.noInstance') }}</option>
              <option v-for="i in instances.items" :key="i.id" :value="i.id">{{ i.name }} ({{ i.gameVersion }}, {{ loaderLabels[i.loader.kind] }})</option>
            </select>

            <div v-if="busy" class="py-2"><RedstoneWire :percent="60" :segments="24" /></div>
            <template v-else-if="installed">
              <div class="flex items-center gap-2 rounded-md bg-base-850 px-3 py-2 text-sm">
                <svg viewBox="0 0 24 24" class="size-4 shrink-0 text-ok" fill="none" stroke="currentColor" stroke-width="2.5"><path d="m5 12 5 5 9-10" /></svg>
                <i18n-t keypath="project.install.installed" tag="span" scope="global" class="min-w-0 flex-1 truncate">
                  <template #version><span class="font-mono text-xs text-base-400">{{ installed.version ?? installed.source?.versionNumber }}</span></template>
                </i18n-t>
              </div>
              <button v-if="updateAvailable" class="btn mt-2 w-full bg-lamp-900 text-lamp-300 ring-1 ring-lamp-400/40 hover:bg-base-800" @click="installVersion(newestFitting)">
                {{ t('project.install.updateTo', { version: newestFitting?.versionNumber ?? '' }) }}
              </button>
            </template>
            <button
              v-else
              class="btn btn-primary w-full"
              :disabled="!target || modsBlocked || (!loadingVersions && !newestFitting)"
              @click="installVersion(null)"
            >
              {{ t('common.actions.install') }}
            </button>
            <p v-if="modsBlocked" class="mt-2 text-xs text-warn">{{ t('project.install.modsBlocked') }}</p>
            <p v-else-if="target && !loadingVersions && !newestFitting && !installed" class="mt-2 text-xs text-warn">
              {{ t('project.install.noVersion', { version: target.gameVersion, loader: loaderLabels[target.loader.kind] }) }}
            </p>
            <button class="mt-2 w-full text-center text-xs text-base-400 hover:text-base-50" @click="tab = 'versions'">
              {{ installed ? t('project.install.switchVersion') : t('project.install.otherVersion') }}
            </button>
          </template>

          <div v-if="details.links.length" class="mt-4 flex flex-wrap gap-1.5 border-t border-base-800 pt-3">
            <button v-for="l in details.links" :key="l.kind" class="chip hover:bg-base-700 hover:text-base-50" @click="openLink(l)">
              {{ t(`project.links.${l.kind}`) }}
            </button>
          </div>
        </aside>
      </header>

      <nav class="mb-4 flex gap-1" :aria-label="t('project.tabs.label')">
        <button class="tab" :class="{ 'tab-on': tab === 'description' }" @click="tab = 'description'">{{ t('common.labels.description') }}</button>
        <button class="tab" :class="{ 'tab-on': tab === 'gallery' }" @click="tab = 'gallery'">
          {{ t('project.tabs.gallery') }} <span v-if="details.gallery.length" class="ml-1 text-xs text-base-600">{{ details.gallery.length }}</span>
        </button>
        <button class="tab" :class="{ 'tab-on': tab === 'versions' }" @click="tab = 'versions'">
          {{ t('project.tabs.versions') }} <span v-if="versions.length" class="ml-1 text-xs text-base-600">{{ versions.length }}</span>
        </button>
        <button class="tab" :class="{ 'tab-on': tab === 'dependencies' }" @click="tab = 'dependencies'">{{ t('project.tabs.dependencies') }}</button>
      </nav>

      <div v-if="tab === 'description'" class="grid gap-6 lg:grid-cols-[minmax(0,1fr)_16rem]">
        <article class="card min-w-0 px-6 py-5">
          <MarkdownView v-if="details.body" :source="details.body" :html="isCf" />
          <p v-else class="text-sm text-base-400">{{ t('project.noDescription') }}</p>
        </article>
        <aside class="space-y-3 text-xs">
          <div class="card space-y-2.5 p-4">
            <h2 class="section-title">{{ t('project.info.title') }}</h2>
            <p v-if="details.license" class="flex justify-between gap-2"><span class="text-base-400">{{ t('project.info.license') }}</span><span class="truncate text-right">{{ details.license }}</span></p>
            <template v-if="!isCf">
              <p class="flex justify-between gap-2"><span class="text-base-400">{{ t('modrinth.environment.client') }}</span><span>{{ sideLabel(details.clientSide) }}</span></p>
              <p class="flex justify-between gap-2"><span class="text-base-400">{{ t('modrinth.environment.server') }}</span><span>{{ sideLabel(details.serverSide) }}</span></p>
            </template>
            <p v-if="details.author" class="flex justify-between gap-2"><span class="text-base-400">{{ t('project.stats.author') }}</span><span class="truncate text-right">{{ details.author }}</span></p>
            <p v-if="details.published" class="flex justify-between gap-2"><span class="text-base-400">{{ t('project.info.published') }}</span><span>{{ formatShortDate(details.published) }}</span></p>
          </div>
          <div v-if="details.gameVersions.length" class="card p-4">
            <h2 class="section-title mb-2">Minecraft</h2>
            <div class="flex flex-wrap gap-1">
              <span v-for="g in details.gameVersions.slice(0, 24)" :key="g" class="rounded bg-base-800 px-1.5 py-0.5 font-mono text-[11px] text-base-200">{{ g }}</span>
              <span v-if="details.gameVersions.length > 24" class="px-1 py-0.5 text-[11px] text-base-400">+{{ details.gameVersions.length - 24 }}</span>
            </div>
          </div>
        </aside>
      </div>
      <ProjectGallery v-else-if="tab === 'gallery'" :images="details.gallery" />
      <ProjectVersions
        v-else-if="tab === 'versions'"
        :versions="versions"
        :loading="loadingVersions"
        :instance="isPack ? null : target"
        :kind="contentKind"
        :installed-version-id="installed?.source?.versionId ?? null"
        :busy-version-id="busy"
        :project-id="details.projectId"
        :platform="platform"
        @install="installVersion"
      />
      <ProjectDependencies v-else :version="depsVersion" :instance-id="target?.id ?? null" :platform="platform" />
    </template>
  </div>
</template>

<style scoped>
.project-hero {
  background:
    radial-gradient(90% 120% at 0% 0%, rgb(224 40 30 / 0.12), transparent 60%),
    repeating-linear-gradient(0deg, rgb(255 255 255 / 0.02) 0 1px, transparent 1px 32px),
    repeating-linear-gradient(90deg, rgb(255 255 255 / 0.02) 0 1px, transparent 1px 32px),
    var(--color-base-900);
}
</style>
