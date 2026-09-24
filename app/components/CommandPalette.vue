<script setup lang="ts">
import type { ContentItem, Instance } from '~/types'
import type { IconName } from '~/utils/icons'
import type { MessageKey } from '~/utils/i18n'

// Globale Suche (Strg+K): Instanzen starten oder
// öffnen, installierte Mods finden, in einen Einstellungs-Bereich springen,
// Server, Seiten und Aktionen – alles unscharf durchsuchbar.
const emit = defineEmits<{ close: [] }>()

const router = useRouter()
const instances = useInstancesStore()
const servers = useServersStore()
const games = useGamesStore()
const settings = useSettingsStore()
const toasts = useToasts()
const ui = useUiStore()
const trs = useTrsStore()

type Group = 'instances' | 'mods' | 'servers' | 'settings' | 'pages' | 'actions'

function groupName(group: Group): string {
  return t(`palette.groups.${group}`)
}

interface Command {
  id: string
  group: Group
  title: string
  subtitle?: string
  /** Zusätzliche Wörter, unter denen der Eintrag gefunden werden soll. */
  keywords?: string
  icon?: IconName
  instance?: Instance
  iconUrl?: string | null
  run: () => void
  /** Zweite Aktion auf Strg+Enter bzw. über den Knopf rechts. */
  second?: { label: string; run: () => void; disabled?: boolean }
}

const query = ref('')
const activeIndex = ref(0)
const listEl = ref<HTMLElement | null>(null)
const mods = ref<{ instance: Instance; item: ContentItem }[]>([])

function close() {
  emit('close')
}

function go(path: string) {
  router.push(path)
  close()
}

function launch(instance: Instance) {
  if (games.state(instance.id).phase !== 'idle') {
    go(`/instances/${instance.id}`)
    return
  }
  games.launch(instance.id)
  close()
}

// --- Einträge -------------------------------------------------------------------
const pages = computed<Command[]>(() => {
  const list: { to: string; label: MessageKey; icon: IconName; keywords: MessageKey }[] = [
    { to: '/', label: 'nav.home', icon: 'home', keywords: 'palette.keywords.home' },
    { to: '/instances', label: 'nav.library', icon: 'library', keywords: 'palette.keywords.library' },
    { to: '/browse', label: 'nav.discover', icon: 'compass', keywords: 'palette.keywords.discover' },
    { to: '/servers', label: 'nav.servers', icon: 'server', keywords: 'palette.keywords.servers' },
    { to: '/accounts', label: 'palette.pages.accounts', icon: 'user', keywords: 'palette.keywords.accounts' },
    { to: '/screenshots', label: 'nav.screenshots', icon: 'screenshots', keywords: 'palette.keywords.screenshots' },
    { to: '/clips', label: 'nav.clips', icon: 'clips', keywords: 'palette.keywords.clips' },
    { to: '/skins', label: 'nav.skins', icon: 'skins', keywords: 'palette.keywords.skins' },
    { to: '/friends', label: 'nav.friends', icon: 'friends', keywords: 'palette.keywords.friends' },
  ]
  return list
    // Clips (Spielaufnahme) gibt es vorerst nur unter Windows.
    .filter((p) => router.resolve(p.to).matched.length > 0 && (p.to !== '/clips' || !isLinux))
    .map((p) => ({
      id: `page:${p.to}`,
      group: 'pages' as const,
      title: t(p.label),
      subtitle: t('palette.openPage'),
      keywords: t(p.keywords),
      icon: p.icon,
      run: () => go(p.to),
    }))
})

/** Für „Server beitreten“: die zuletzt gespielte Instanz. */
const joinTarget = computed(() => instances.items[0] ?? null)

const commands = computed<Command[]>(() => [
  ...instances.items.map<Command>((i) => ({
    id: `instance:${i.id}`,
    group: 'instances',
    title: i.name,
    subtitle: [i.gameVersion, loaderLabels[i.loader.kind], ...(games.state(i.id).phase !== 'idle' ? [t('palette.running')] : [])].join(' · '),
    keywords: `${i.gameVersion} ${loaderLabels[i.loader.kind]} ${i.group ?? ''}`,
    instance: i,
    run: () => go(`/instances/${i.id}`),
    second: {
      label: games.state(i.id).phase === 'idle' ? t('common.actions.play') : t('common.status.running'),
      run: () => launch(i),
      disabled: games.state(i.id).phase !== 'idle',
    },
  })),
  ...mods.value.map<Command>(({ instance, item }) => ({
    id: `mod:${instance.id}:${item.kind}:${item.fileName}`,
    group: 'mods',
    title: item.title ?? item.fileName,
    subtitle: [instance.name, item.enabled ? t('palette.modEnabled') : t('palette.modDisabled'), ...(item.version ? [item.version] : [])].join(' · '),
    keywords: `${item.fileName} ${item.author ?? ''} ${instance.name}`,
    iconUrl: item.iconUrl,
    run: () => go(`/instances/${instance.id}?tab=content`),
    second: item.source
      ? { label: t('palette.projectPage'), run: () => go(`/project/${item.source!.projectId}`) }
      : undefined,
  })),
  ...servers.items.map<Command>((s) => ({
    id: `server:${s.id}`,
    group: 'servers',
    title: s.name,
    subtitle: s.address,
    keywords: s.address,
    icon: 'server',
    run: () => go('/servers'),
    second: joinTarget.value
      ? {
          label: t('palette.joinWith', { name: joinTarget.value.name }),
          disabled: games.state(joinTarget.value.id).phase !== 'idle',
          run: () => {
            const target = joinTarget.value
            if (!target || games.state(target.id).phase !== 'idle') return
            games.launch(target.id, s.id)
            close()
          },
        }
      : undefined,
  })),
  ...appSettingsSections.map<Command>((s) => ({
    id: `settings:${s.key}`,
    group: 'settings',
    title: s.label,
    subtitle: s.group ? `${groupName('settings')} · ${s.group}` : groupName('settings'),
    keywords: t('palette.keywords.settings'),
    icon: s.icon,
    run: () => {
      settings.open(s.key)
      close()
    },
  })),
  ...pages.value,
  {
    id: 'action:new-instance',
    group: 'actions',
    title: t('palette.actions.newInstance.title'),
    subtitle: t('palette.actions.newInstance.subtitle'),
    keywords: t('palette.keywords.newInstance'),
    icon: 'plus',
    run: () => {
      ui.creating = true
      close()
    },
  },
  {
    id: 'action:import',
    group: 'actions',
    title: t('palette.actions.import.title'),
    subtitle: t('palette.actions.import.subtitle'),
    keywords: t('palette.keywords.import'),
    icon: 'install',
    run: () => {
      ui.importing = true
      close()
    },
  },
  {
    id: 'action:modpacks',
    group: 'actions',
    title: t('palette.actions.modpacks.title'),
    subtitle: t('palette.actions.modpacks.subtitle'),
    keywords: t('palette.keywords.modpacks'),
    icon: 'compass',
    run: () => go('/browse?kind=modpack'),
  },
  // Nur für TRS-Admins – die Website-Anmeldung ist deren Verwaltungszugang.
  ...(trs.isAdmin
    ? [
        {
          id: 'action:web-login',
          group: 'actions',
          title: t('webLogin.title'),
          subtitle: t('palette.actions.webLogin.subtitle', { host: TRS_HOST }),
          keywords: t('palette.keywords.webLogin'),
          icon: 'shield',
          run: () => {
            trs.openWebLogin()
            close()
          },
        } satisfies Command,
      ]
    : []),
  {
    id: 'action:data-dir',
    group: 'actions',
    title: t('palette.actions.dataDir.title'),
    subtitle: t('palette.actions.dataDir.subtitle'),
    keywords: t('palette.keywords.dataDir'),
    icon: 'storage',
    run: () => {
      backend.openDataDir().catch((e) => toasts.error(e))
      close()
    },
  },
])

const results = computed(() => {
  const q = query.value.trim()
  if (!q) {
    // Ohne Eingabe: zuletzt benutzte Befehle, sonst ein sinnvoller Einstieg.
    const byId = new Map(commands.value.map((c) => [c.id, c]))
    const recent = ui.recentCommands.map((id) => byId.get(id)).filter((c): c is Command => !!c)
    const rest = commands.value.filter((c) => !recent.includes(c) && (c.group === 'instances' || c.group === 'actions'))
    return [...recent, ...rest].slice(0, 12).map((item) => ({ item, positions: [] as number[] }))
  }
  return rank(commands.value, q, (c) => [c.title, c.subtitle ?? '', c.keywords ?? '', groupName(c.group)])
    .slice(0, 40)
    .map(({ item, positions }) => ({ item, positions }))
})

/** Überschriften nur beim ersten Eintrag einer Gruppe zeigen. */
function groupLabel(index: number): string | null {
  const current = results.value[index]?.item.group
  if (!current) return null
  return index === 0 || results.value[index - 1]?.item.group !== current ? groupName(current) : null
}

watch(results, () => (activeIndex.value = 0))

function scrollActiveIntoView() {
  nextTick(() => {
    listEl.value?.querySelector('[data-active="true"]')?.scrollIntoView({ block: 'nearest' })
  })
}

function move(delta: number) {
  const count = results.value.length
  if (!count) return
  activeIndex.value = (activeIndex.value + delta + count) % count
  scrollActiveIntoView()
}

function runCommand(command: Command, secondary = false) {
  ui.rememberCommand(command.id)
  if (secondary && command.second && !command.second.disabled) command.second.run()
  else command.run()
}

function onKey(e: KeyboardEvent) {
  switch (e.key) {
    case 'ArrowDown':
      e.preventDefault()
      move(1)
      break
    case 'ArrowUp':
      e.preventDefault()
      move(-1)
      break
    case 'Home':
      if (!query.value) return
      e.preventDefault()
      activeIndex.value = 0
      scrollActiveIntoView()
      break
    case 'End':
      if (!query.value) return
      e.preventDefault()
      activeIndex.value = Math.max(0, results.value.length - 1)
      scrollActiveIntoView()
      break
    case 'Enter': {
      const current = results.value[activeIndex.value]?.item
      if (!current) return
      e.preventDefault()
      runCommand(current, e.ctrlKey || e.metaKey)
      break
    }
    case 'Escape':
      e.preventDefault()
      close()
      break
  }
}

// Mods aller Instanzen einmal beim Öffnen nachladen – die Liste kommt aus dem
// Kern und wird nur hier im Speicher gehalten.
const MAX_SCANNED = 20
onMounted(async () => {
  if (!instances.loaded) await instances.load().catch(() => {})
  if (!servers.loaded) servers.load().catch(() => {})
  const targets = instances.items.slice(0, MAX_SCANNED)
  const lists = await Promise.allSettled(targets.map((i) => backend.listContent(i.id, 'mod')))
  mods.value = lists.flatMap((result, index) =>
    result.status === 'fulfilled'
      ? result.value.map((item) => ({ instance: targets[index]!, item }))
      : [],
  )
})
</script>

<template>
  <div class="fixed inset-0 z-[70] flex justify-center bg-black/60 p-6 pt-[12vh] backdrop-blur-[2px]" @mousedown.self="close">
    <div
      class="flex max-h-[70vh] w-full max-w-2xl animate-pop flex-col overflow-hidden rounded-2xl border border-base-700 bg-base-850 shadow-2xl shadow-black/60"
      role="dialog"
      aria-modal="true"
      :aria-label="t('palette.dialogLabel')"
    >
      <div class="flex items-center gap-3 border-b border-base-800 px-4">
        <svg viewBox="0 0 24 24" class="size-4 shrink-0 text-base-600" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path :d="icons.search" /></svg>
        <input
          v-model="query"
          class="min-w-0 flex-1 bg-transparent py-3.5 text-sm text-base-50 outline-none placeholder:text-base-600"
          :placeholder="t('palette.placeholder')"
          :aria-label="t('common.actions.search')"
          role="combobox"
          aria-expanded="true"
          aria-controls="palette-list"
          :aria-activedescendant="results.length ? `palette-item-${activeIndex}` : undefined"
          spellcheck="false"
          autofocus
          @keydown="onKey"
        />
        <kbd class="shrink-0 rounded border border-base-700 px-1.5 py-0.5 font-mono text-[10px] text-base-400">Esc</kbd>
      </div>

      <div v-if="results.length" id="palette-list" ref="listEl" class="min-h-0 flex-1 overflow-y-auto p-2" role="listbox" :aria-label="t('palette.resultsLabel')">
        <template v-for="(entry, index) in results" :key="entry.item.id">
          <p v-if="groupLabel(index)" class="mt-2 mb-1 px-2 text-[11px] font-semibold tracking-wider text-base-600 uppercase first:mt-0">
            {{ groupLabel(index) }}
          </p>
          <div
            :id="`palette-item-${index}`"
            class="group flex cursor-pointer items-center gap-3 rounded-lg px-2 py-2 transition-colors"
            :class="index === activeIndex ? 'bg-base-800' : 'hover:bg-base-800/60'"
            :data-active="index === activeIndex"
            role="option"
            :aria-selected="index === activeIndex"
            @mousemove="activeIndex = index"
            @click="runCommand(entry.item)"
          >
            <InstanceIcon v-if="entry.item.instance" :instance="entry.item.instance" :size="28" />
            <img v-else-if="entry.item.iconUrl" :src="entry.item.iconUrl" alt="" class="size-7 shrink-0 rounded-md bg-base-800 object-cover" />
            <span v-else class="grid size-7 shrink-0 place-items-center rounded-md bg-base-800 text-base-400">
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <path :d="icons[entry.item.icon ?? 'general']" />
              </svg>
            </span>

            <span class="min-w-0 flex-1">
              <span class="block truncate text-sm text-base-50">
                <span v-for="(seg, s) in highlight(entry.item.title, entry.positions)" :key="s" :class="{ 'text-redstone-300': seg.hit }">{{ seg.text }}</span>
              </span>
              <span v-if="entry.item.subtitle" class="block truncate text-xs text-base-400">{{ entry.item.subtitle }}</span>
            </span>

            <button
              v-if="entry.item.second"
              class="btn btn-ghost shrink-0 px-2 py-1 text-xs"
              :class="index === activeIndex ? 'inline-flex' : 'hidden group-hover:inline-flex'"
              :disabled="entry.item.second.disabled"
              @click.stop="runCommand(entry.item, true)"
            >
              {{ entry.item.second.label }}
            </button>
          </div>
        </template>
      </div>

      <p v-else class="flex-1 px-4 py-10 text-center text-sm text-base-400">{{ t('palette.noResults') }}</p>

      <div class="flex items-center gap-4 border-t border-base-800 px-4 py-2 text-[11px] text-base-600">
        <span><kbd class="kbd">↑</kbd><kbd class="kbd">↓</kbd> {{ t('palette.hints.select') }}</span>
        <span><kbd class="kbd">↵</kbd> {{ t('palette.hints.open') }}</span>
        <span><kbd class="kbd">{{ t('titleBar.ctrl') }}</kbd>+<kbd class="kbd">↵</kbd> {{ t('palette.hints.second') }}</span>
        <span class="ml-auto">{{ t('palette.resultCount', results.length) }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.kbd {
  @apply mx-0.5 rounded border border-base-700 px-1 py-0.5 font-mono text-[10px] text-base-400;
}
</style>
