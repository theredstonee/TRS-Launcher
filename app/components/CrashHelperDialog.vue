<script setup lang="ts">
import type { CrashAction, CrashFinding, CrashKind } from '~/types'
import { actionConfirm, actionKey, actionLabel, findingMods, findingText, findingTitle, isConflictKind } from '~/utils/crash'

// Absturz-Helfer: erklärt in Spielersprache, was los ist, nennt die beteiligten
// Mods und bietet Behebungen an. Jede Änderung wird vorher bestätigt und landet
// im Verlauf der Instanz. Die Analyse lief lokal; gesendet wird nur beim „Log teilen“.
const helper = useCrashHelperStore()
const games = useGamesStore()
const instances = useInstancesStore()
const router = useRouter()
const openDocs = useDocs()
const bisect = useBisectStore()
const conflicts = useModConflictsStore()

const crash = computed(() => helper.current)
const primary = computed(() => crash.value?.findings[0] ?? null)
const others = computed(() => crash.value?.findings.slice(1) ?? [])
const instanceName = computed(() => {
  const id = crash.value?.instanceId
  return instances.items.find((i) => i.id === id)?.name ?? id ?? ''
})
const running = computed(() => (crash.value ? games.state(crash.value.instanceId).phase !== 'idle' : false))

/** Bestätigung, bevor sich etwas ändert. */
const confirming = ref<{ finding: CrashFinding; action: CrashAction } | null>(null)
watch(crash, () => (confirming.value = null))

const sharing = ref(false)
/** Crash-Report teilen, wenn es einen gibt – sonst den neuesten Log. */
const shareSource = computed(() => {
  const file = crash.value?.sources.find((s) => s.startsWith('crash-reports/') || s.startsWith('logs/') || s.startsWith('launcher/'))
  return file ?? 'live'
})
const shareLabel = computed(() => (shareSource.value === 'live' ? t('logViewer.latest') : shareSource.value.split('/').pop()!))

const when = computed(() => {
  const c = crash.value
  if (!c) return ''
  const at = formatRelative(c.at, true)
  if (c.playSeconds === null || c.playSeconds === undefined) return at
  return c.playSeconds < 45 ? t('crashHelper.whenStarting', { at }) : t('crashHelper.whenPlaying', { at, time: formatPlayTime(c.playSeconds) })
})

const KIND_ICONS: Record<CrashKind, string> = {
  known_issue: 'M12 3l9 16H3zM12 10v4m0 3v.01',
  duplicate_mod: 'M8 8h11v11H8zM5 16V5h11',
  wrong_game_version: 'M4 7h16M4 12h10M4 17h7m9-3-3 3 3 3',
  wrong_loader_version: 'M4 7h16M4 12h10M4 17h7m9-3-3 3 3 3',
  incompatible_mod: 'M9 3v6l-5 9a2 2 0 0 0 2 3h12a2 2 0 0 0 2-3l-5-9V3M8 3h8',
  missing_dependency: 'M12 5v14M5 12h14',
  wrong_java: 'M6 9h10v5a5 5 0 0 1-10 0zM16 10h1.5a2.5 2.5 0 0 1 0 5H16M9 3c0 1.5 1 1.5 1 3M12 3c0 1.5 1 1.5 1 3',
  out_of_memory: 'M5 7h14v10H5zM8 7V4m4 3V4m4 3V4M8 20v-3m4 3v-3m4 3v-3',
  corrupt_files: 'M6 3h9l4 4v14H6zM14 3v5h5M9 13l6 6m0-6-6 6',
  graphics_driver: 'M3 6h18v10H3zM8 20h8M12 16v4',
  mixin_conflict: 'M7 4v6a5 5 0 0 0 10 0V4M12 15v5',
  unknown: 'M9.5 9a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6V14m0 3v.01',
}

function ask(finding: CrashFinding, action: CrashAction) {
  confirming.value = { finding, action }
}

async function confirm() {
  const c = crash.value
  const pending = confirming.value
  if (!c || !pending) return
  confirming.value = null
  await helper.run(c, pending.action)
}

function relaunch() {
  const id = crash.value?.instanceId
  if (!id) return
  helper.close()
  games.launch(id)
}

function openJavaSettings() {
  const id = crash.value?.instanceId
  if (!id) return
  helper.close()
  router.push({ path: `/instances/${id}`, query: { settings: 'java' } })
}

/** „Schuldige Mod finden“: Mods abwechselnd halbieren, bis die Ursache übrig ist. */
function findCulprit() {
  const id = crash.value?.instanceId
  if (!id) return
  helper.close()
  bisect.askStart(id)
}

/** Unverträgliche Mod-Versionen: der Mod-Konflikt-Helfer zeigt alle auf einmal (mit Lösungen). */
function openConflictHelper() {
  const id = crash.value?.instanceId
  if (!id) return
  helper.close()
  void conflicts.open(id, 'crash')
}

function openContent() {
  const id = crash.value?.instanceId
  if (!id) return
  helper.close()
  router.push({ path: `/instances/${id}`, query: { tab: 'content' } })
}
</script>

<template>
  <BaseDialog v-if="crash && primary" :key="crash.id" :title="t('crashHelper.title')" wide @close="helper.close()">
    <p class="-mt-1 mb-3 text-xs text-base-400">
      <span class="font-medium text-base-200">{{ instanceName }}</span> · {{ when }}
      <template v-if="crash.exitCode !== null"> · {{ t('history.details.exitCode', { code: crash.exitCode }) }}</template>
    </p>

    <div class="max-h-[62vh] space-y-4 overflow-y-auto pr-1">
      <!-- Hauptursache -->
      <section class="rounded-xl border border-redstone-600/40 bg-redstone-900/30 p-4" data-crash-primary>
        <div class="flex items-start gap-3">
          <span class="grid size-10 shrink-0 place-items-center rounded-lg bg-redstone-900 text-redstone-300 ring-1 ring-redstone-600/40">
            <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path :d="KIND_ICONS[primary.kind]" /></svg>
          </span>
          <div class="min-w-0 flex-1">
            <h3 class="font-semibold text-base-50">{{ findingTitle(primary) }}</h3>
            <p class="mt-1 text-sm leading-relaxed text-base-200">{{ findingText(primary) }}</p>
            <p v-if="primary.kind === 'graphics_driver' && primary.params?.shaders" class="mt-1.5 text-xs text-base-400">
              {{ t('crashHelper.shadersHint', { name: primary.params.shaders }) }}
            </p>
          </div>
        </div>

        <ul v-if="findingMods(primary, crash).length" class="mt-3 flex flex-wrap gap-2" :aria-label="t('crashHelper.involved')">
          <li
            v-for="mod in findingMods(primary, crash)"
            :key="mod.id"
            class="flex items-center gap-2 rounded-lg bg-base-900/80 py-1 pl-1 pr-2.5 ring-1 ring-base-700"
          >
            <ModIcon :src="mod.iconUrl" :name="mod.name" :size="24" />
            <span class="text-sm text-base-50">{{ mod.name }}</span>
            <span v-if="mod.version" class="font-mono text-[11px] text-base-400">{{ mod.version }}</span>
            <span v-if="mod.bundledIn" class="text-[11px] text-base-400">{{ t('crashHelper.bundledIn', { name: mod.bundledIn }) }}</span>
            <span v-else-if="mod.file && !mod.enabled" class="badge text-[10px]">{{ t('crashHelper.modDisabled') }}</span>
          </li>
        </ul>

        <div v-if="primary.actions.length || primary.kind === 'wrong_java' || isConflictKind(primary.kind)" class="mt-4 flex flex-wrap gap-2">
          <button
            v-for="(action, i) in primary.actions"
            :key="actionKey(action)"
            type="button"
            class="btn px-3 py-1.5 text-xs"
            :class="i === 0 ? 'btn-primary' : 'btn-ghost'"
            :disabled="helper.isRunning(crash, action) || helper.isDone(crash, action)"
            @click="ask(primary, action)"
          >
            <svg v-if="helper.isDone(crash, action)" viewBox="0 0 24 24" class="size-3.5 text-ok" fill="none" stroke="currentColor" stroke-width="3"><path d="m5 12 5 5 9-10" /></svg>
            {{ helper.isRunning(crash, action) ? t('crashHelper.working') : actionLabel(action, crash) }}
          </button>
          <button v-if="primary.kind === 'wrong_java'" type="button" class="btn btn-ghost px-3 py-1.5 text-xs" @click="openJavaSettings">
            {{ t('crashHelper.openJavaSettings') }}
          </button>
          <button v-if="isConflictKind(primary.kind)" type="button" class="btn btn-ghost px-3 py-1.5 text-xs" data-testid="crash-conflict-helper" @click="openConflictHelper">
            {{ t('modConflicts.openHelper') }}
          </button>
        </div>
      </section>

      <!-- Bestätigung -->
      <section v-if="confirming" class="rounded-xl border border-lamp-400/40 bg-lamp-900/60 p-4" role="alertdialog" :aria-label="t('crashHelper.confirmTitle')">
        <h3 class="text-sm font-semibold text-lamp-300">{{ t('crashHelper.confirmTitle') }}</h3>
        <p class="mt-1 text-sm text-base-50">{{ actionConfirm(confirming.action, crash) }}</p>
        <p class="mt-1 text-xs text-base-400">{{ t('crashHelper.confirmHistory') }}</p>
        <div class="mt-3 flex justify-end gap-2">
          <button type="button" class="btn btn-ghost px-3 py-1.5 text-xs" @click="confirming = null">{{ t('common.actions.cancel') }}</button>
          <button type="button" class="btn btn-primary px-3 py-1.5 text-xs" @click="confirm">{{ t('crashHelper.confirmDo') }}</button>
        </div>
      </section>

      <!-- Weitere Hinweise -->
      <section v-if="others.length">
        <h3 class="mb-2 text-xs font-semibold uppercase tracking-wide text-base-400">{{ t('crashHelper.moreHints') }}</h3>
        <ul class="space-y-2">
          <li v-for="(f, i) in others" :key="i" class="rounded-lg border border-base-800 bg-base-900 px-3 py-2.5">
            <div class="flex items-start gap-2.5">
              <svg viewBox="0 0 24 24" class="mt-0.5 size-4 shrink-0 text-base-400" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path :d="KIND_ICONS[f.kind]" /></svg>
              <div class="min-w-0 flex-1">
                <p class="text-sm font-medium text-base-50">{{ findingTitle(f) }}</p>
                <p class="mt-0.5 text-xs leading-relaxed text-base-400">{{ findingText(f) }}</p>
                <div v-if="f.actions.length" class="mt-2 flex flex-wrap gap-1.5">
                  <button
                    v-for="action in f.actions"
                    :key="actionKey(action)"
                    type="button"
                    class="btn btn-ghost px-2.5 py-1 text-xs"
                    :disabled="helper.isRunning(crash, action) || helper.isDone(crash, action)"
                    @click="ask(f, action)"
                  >
                    {{ helper.isRunning(crash, action) ? t('crashHelper.working') : actionLabel(action, crash) }}
                  </button>
                </div>
              </div>
            </div>
          </li>
        </ul>
      </section>

      <!-- Details: die wichtigsten Zeilen (maskiert) -->
      <details class="group rounded-lg border border-base-800 bg-base-900 px-3 py-2 text-xs">
        <summary class="cursor-pointer select-none text-base-400 hover:text-base-50">{{ t('crashHelper.details') }}</summary>
        <dl class="mt-2 space-y-2">
          <div v-if="crash.cause">
            <dt class="text-base-400">{{ t('crashHelper.cause') }}</dt>
            <dd class="break-words font-mono text-[11px] text-base-50">{{ crash.cause }}</dd>
          </div>
          <div v-if="crash.firstFrame">
            <dt class="text-base-400">{{ t('crashHelper.firstFrame') }}</dt>
            <dd class="break-words font-mono text-[11px] text-base-50">{{ crash.firstFrame }}</dd>
          </div>
          <div v-if="crash.excerpt.length">
            <dt class="text-base-400">{{ t('crashHelper.excerpt') }}</dt>
            <dd>
              <pre class="mt-1 max-h-48 overflow-auto whitespace-pre-wrap break-words rounded bg-base-950 p-2 font-mono text-[11px] leading-relaxed text-base-200">{{ crash.excerpt.join('\n') }}</pre>
            </dd>
          </div>
        </dl>
        <p class="mt-2 text-[11px] text-base-400">{{ t('crashHelper.masked') }}</p>
        <button type="button" class="mt-2 text-[11px] text-redstone-300 hover:underline" @click="openContent">{{ t('crashHelper.openContent') }}</button>
      </details>

      <p class="flex items-center gap-1.5 text-[11px] text-base-400">
        <svg viewBox="0 0 24 24" class="size-3.5 shrink-0" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3 5 6v5c0 4.5 3 8.5 7 10 4-1.5 7-5.5 7-10V6z" /></svg>
        {{ t('crashHelper.privacy') }}
      </p>
      <!-- Mehr Hilfe: passende Seite der Dokumentation (Java, häufige Probleme oder der Absturz-Helfer selbst). -->
      <button type="button" class="flex items-center gap-1.5 text-[11px] text-redstone-300 hover:underline" data-testid="crash-docs" @click="openDocs(crashDocsPage(primary.kind))">
        <svg viewBox="0 0 24 24" class="size-3.5 shrink-0" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.help" /></svg>
        {{ t('docs.moreHelp') }}
      </button>
    </div>

    <template #actions>
      <button type="button" class="btn btn-ghost" @click="sharing = true">{{ t('crash.shareLog') }}</button>
      <button
        v-if="!bisect.isActive(crash.instanceId)"
        type="button"
        class="btn btn-ghost"
        :disabled="running"
        :title="t('bisect.buttonTitle')"
        data-testid="crash-bisect"
        @click="findCulprit"
      >
        {{ t('bisect.button') }}
      </button>
      <button type="button" class="btn btn-ghost" :disabled="running" @click="relaunch">{{ t('crashHelper.relaunch') }}</button>
      <button type="button" class="btn btn-primary" @click="helper.close()">{{ t('common.actions.close') }}</button>
    </template>
  </BaseDialog>
  <LogShareDialog v-if="sharing && crash" :instance-id="crash.instanceId" :source="shareSource" :label="shareLabel" @close="sharing = false" />
</template>
