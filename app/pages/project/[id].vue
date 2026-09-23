<script setup lang="ts">
import type { ContentItem, ContentKind, ModrinthVersion, ProjectDetails, ProjectLink } from '~/types'

// Detailansicht eines Modrinth-Projekts: Beschreibung, Galerie, Versionen
// (mit Changelog, Installieren/Wechseln) und Abhängigkeiten.
const route = useRoute()
const router = useRouter()
const instances = useInstancesStore()
const tasks = useTasksStore()
const toasts = useToasts()

const projectId = computed(() => String(route.params.id))
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
const packTask = computed(() => (details.value ? tasks.get(modpackTaskKey(details.value.projectId)) : null))
const contentTask = computed(() =>
  details.value && target.value ? tasks.get(contentTaskKey(target.value.id, details.value.projectId)) : null,
)
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
    details.value = await backend.modrinthProject(projectId.value)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loadingDetails.value = false
  }
  if (!details.value) return
  try {
    versions.value = await backend.modrinthProjectVersions(details.value.projectId)
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
    installed.value = items.find((i) => i.source?.projectId === id) ?? null
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
watch(projectId, load, { immediate: true })
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
  })
}

function installPack() {
  if (!details.value) return
  installModpackTask(details.value)
}

const linkLabels: Record<ProjectLink['kind'], string> = {
  modrinth: 'Auf Modrinth',
  source: 'Quelltext',
  issues: 'Fehler melden',
  wiki: 'Wiki',
  discord: 'Discord',
}
function openLink(link: ProjectLink) {
  backend.openExternalUrl(link.url).catch((e) => toasts.error(e))
}

const sideLabels: Record<string, string> = { required: 'Nötig', optional: 'Optional', unsupported: 'Nicht nötig', unknown: 'Unbekannt' }

function back() {
  if (window.history.length > 1) router.back()
  else router.push('/browse')
}
</script>

<template>
  <div class="p-6">
    <button class="mb-4 inline-flex items-center gap-1 text-xs text-base-400 hover:text-base-50" @click="back">
      <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M15 5l-7 7 7 7" /></svg>
      Zurück
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
          <p class="mt-1.5 max-w-2xl text-sm text-base-200">{{ details.description }}</p>
          <dl class="mt-4 flex flex-wrap gap-x-6 gap-y-2 text-xs text-base-400">
            <div v-if="details.author">
              <dt class="sr-only">Autor</dt>
              <dd>von <span class="font-medium text-base-50">{{ details.author }}</span></dd>
            </div>
            <div class="flex items-baseline gap-1.5">
              <dt class="sr-only">Downloads</dt>
              <dd><span class="display text-base text-base-50 tabular-nums">{{ formatCount(details.downloads) }}</span> Downloads</dd>
            </div>
            <div class="flex items-baseline gap-1.5">
              <dt class="sr-only">Follower</dt>
              <dd><span class="display text-base text-base-50 tabular-nums">{{ formatCount(details.followers) }}</span> Follower</dd>
            </div>
            <div v-if="details.updated">
              <dt class="sr-only">Aktualisiert</dt>
              <dd>Aktualisiert {{ formatRelative(details.updated).toLowerCase() }}</dd>
            </div>
          </dl>
          <div v-if="categories.length || details.loaders.length" class="mt-4 flex flex-wrap gap-1.5">
            <span v-for="l in details.loaders" :key="l" class="chip bg-base-800/80 font-medium">{{ loaderNames[l] ?? l }}</span>
            <span v-for="c in categories" :key="c" class="chip bg-base-850/80 text-base-400">{{ categoryLabels[c] ?? c }}</span>
          </div>
        </div>

        <!-- Installieren -->
        <aside class="w-full shrink-0 rounded-xl border border-base-700/70 bg-base-900/80 p-4 backdrop-blur sm:w-72">
          <template v-if="isPack">
            <p class="text-sm font-medium">Modpack</p>
            <p class="mt-0.5 mb-3 text-xs text-base-400">Wird als neue Instanz mit allen Mods angelegt.</p>
            <div v-if="packTask?.status === 'running'" class="space-y-2">
              <button
                class="btn btn-ghost w-full tabular-nums"
                :aria-label="`Wird installiert, ${packTask.percent ?? 0} Prozent – im Aufgaben-Panel anzeigen`"
                @click="tasks.openPanel(packTask.key)"
              >
                {{ packTask.paused ? 'Pausiert' : 'Wird installiert …' }} {{ packTask.percent ?? 0 }} %
              </button>
              <RedstoneWire :percent="packTask.percent ?? 0" :segments="24" />
              <p class="text-center text-xs text-base-400">{{ packTask.stage }}</p>
            </div>
            <template v-else-if="packTask?.status === 'done' && packTask.instanceId">
              <button class="btn btn-primary w-full" @click="router.push(`/instances/${packTask.instanceId}`)">Instanz öffnen</button>
              <button class="mt-2 w-full text-center text-xs text-base-400 hover:text-base-200" @click="installPack">Noch einmal installieren</button>
            </template>
            <button v-else class="btn btn-primary w-full" @click="installPack">Als Instanz installieren</button>
          </template>
          <template v-else>
            <label class="label" for="p-target">Installieren in</label>
            <select id="p-target" v-model="instanceId" class="field mb-3 py-1.5" :disabled="!instances.items.length">
              <option v-if="!instances.items.length" value="">Keine Instanz vorhanden</option>
              <option v-for="i in instances.items" :key="i.id" :value="i.id">{{ i.name }} ({{ i.gameVersion }}, {{ loaderLabels[i.loader.kind] }})</option>
            </select>

            <div v-if="busy" class="py-2"><RedstoneWire :percent="60" :segments="24" /></div>
            <template v-else-if="installed">
              <div class="flex items-center gap-2 rounded-md bg-base-850 px-3 py-2 text-sm">
                <svg viewBox="0 0 24 24" class="size-4 shrink-0 text-ok" fill="none" stroke="currentColor" stroke-width="2.5"><path d="m5 12 5 5 9-10" /></svg>
                <span class="min-w-0 flex-1 truncate">Installiert <span class="font-mono text-xs text-base-400">{{ installed.version ?? installed.source?.versionNumber }}</span></span>
              </div>
              <button v-if="updateAvailable" class="btn mt-2 w-full bg-lamp-900 text-lamp-300 ring-1 ring-lamp-400/40 hover:bg-base-800" @click="installVersion(newestFitting)">
                Auf {{ newestFitting?.versionNumber }} aktualisieren
              </button>
            </template>
            <button
              v-else
              class="btn btn-primary w-full"
              :disabled="!target || modsBlocked || (!loadingVersions && !newestFitting)"
              @click="installVersion(null)"
            >
              Installieren
            </button>
            <p v-if="modsBlocked" class="mt-2 text-xs text-warn">Diese Vanilla-Instanz hat die TRS-Optimierung aus und lädt keine Mods – aktiviere sie in den Instanz-Einstellungen oder wähle eine Instanz mit Modloader.</p>
            <p v-else-if="target && !loadingVersions && !newestFitting && !installed" class="mt-2 text-xs text-warn">
              Keine Version für {{ target.gameVersion }} ({{ loaderLabels[target.loader.kind] }}).
            </p>
            <button class="mt-2 w-full text-center text-xs text-base-400 hover:text-base-50" @click="tab = 'versions'">
              {{ installed ? 'Version wechseln' : 'Andere Version wählen' }}
            </button>
          </template>

          <div v-if="details.links.length" class="mt-4 flex flex-wrap gap-1.5 border-t border-base-800 pt-3">
            <button v-for="l in details.links" :key="l.kind" class="chip hover:bg-base-700 hover:text-base-50" @click="openLink(l)">
              {{ linkLabels[l.kind] }}
            </button>
          </div>
        </aside>
      </header>

      <nav class="mb-4 flex gap-1" aria-label="Bereiche">
        <button class="tab" :class="{ 'tab-on': tab === 'description' }" @click="tab = 'description'">Beschreibung</button>
        <button class="tab" :class="{ 'tab-on': tab === 'gallery' }" @click="tab = 'gallery'">
          Galerie <span v-if="details.gallery.length" class="ml-1 text-xs text-base-600">{{ details.gallery.length }}</span>
        </button>
        <button class="tab" :class="{ 'tab-on': tab === 'versions' }" @click="tab = 'versions'">
          Versionen <span v-if="versions.length" class="ml-1 text-xs text-base-600">{{ versions.length }}</span>
        </button>
        <button class="tab" :class="{ 'tab-on': tab === 'dependencies' }" @click="tab = 'dependencies'">Abhängigkeiten</button>
      </nav>

      <div v-if="tab === 'description'" class="grid gap-6 lg:grid-cols-[minmax(0,1fr)_16rem]">
        <article class="card min-w-0 px-6 py-5">
          <MarkdownView v-if="details.body" :source="details.body" />
          <p v-else class="text-sm text-base-400">Keine Beschreibung vorhanden.</p>
        </article>
        <aside class="space-y-3 text-xs">
          <div class="card space-y-2.5 p-4">
            <h2 class="section-title">Infos</h2>
            <p v-if="details.license" class="flex justify-between gap-2"><span class="text-base-400">Lizenz</span><span class="truncate text-right">{{ details.license }}</span></p>
            <p class="flex justify-between gap-2"><span class="text-base-400">Client</span><span>{{ sideLabels[details.clientSide] ?? details.clientSide }}</span></p>
            <p class="flex justify-between gap-2"><span class="text-base-400">Server</span><span>{{ sideLabels[details.serverSide] ?? details.serverSide }}</span></p>
            <p v-if="details.published" class="flex justify-between gap-2"><span class="text-base-400">Veröffentlicht</span><span>{{ formatDate(details.published).split(',')[0] }}</span></p>
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
        @install="installVersion"
      />
      <ProjectDependencies v-else :version="depsVersion" :instance-id="target?.id ?? null" />
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
