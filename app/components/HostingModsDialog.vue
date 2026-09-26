<script setup lang="ts">
import { formatBytes } from '~/utils/format'
import {
  canConfirmMods,
  canJoinWithout,
  defaultModSelection,
  downloadSize,
  missingRequired,
  modRows,
  modsToInstall,
  needsTrust,
  worldVersionLabel,
  type ModsMode,
  type SharedMod,
} from '~/utils/hosting'

// Welt mit Mods beitreten – bei JEDEM Beitritt: Liste der Mods (Pflicht/optional,
// Quelle, Größe, vorhanden/fehlt) und die Wahl „Neue Instanz“, „Vorhandene als Kopie
// ergänzen“ (das Original bleibt) oder „Ohne Mods“ (nur, wenn nichts Pflicht fehlt).
// Dateien direkt vom Host: deutliche Warnung + „Ich vertraue diesem Host“, sonst
// geht es nicht weiter. Store-Mods lädt der Kern aus der offiziellen Quelle.
const hosting = useHostingStore()
const instances = useInstancesStore()

const mode = ref<ModsMode>('new')
const baseId = ref<string | null>(null)
const selection = ref<Set<string>>(new Set())
const trust = ref(false)

const choice = computed(() => hosting.modsChoice)
const content = computed(() => choice.value?.content ?? null)
const candidates = computed(() =>
  (choice.value?.instanceIds ?? []).map((id) => instances.items.find((i) => i.id === id)).filter((i) => !!i),
)
const present = computed<Set<string>>(() => (mode.value === 'new' || !baseId.value ? new Set() : new Set(choice.value?.present[baseId.value] ?? [])))
const rows = computed(() => (content.value ? modRows(content.value, present.value) : []))
const manual = computed(() => rows.value.filter((r) => r.mod.source === 'manual' && !r.present))
const install = computed(() => (content.value && mode.value !== 'none' ? modsToInstall(content.value, selection.value, present.value) : []))
const trustNeeded = computed(() => !!content.value && needsTrust(content.value, mode.value, selection.value, present.value))
const withoutOk = computed(() => {
  if (!content.value || !baseId.value) return false
  return canJoinWithout(content.value, new Set(choice.value?.present[baseId.value] ?? []))
})
const confirmable = computed(
  () =>
    !!content.value &&
    !hosting.preparing &&
    canConfirmMods({ content: content.value, mode: mode.value, baseInstanceId: baseId.value, selection: selection.value, present: present.value, trust: trust.value }),
)
const summary = computed(() => {
  const c = content.value
  if (!c) return null
  return { count: c.mods.length, required: c.mods.filter((m) => m.required).length }
})

// Jede Öffnung fängt neu an: Häkchen „Ich vertraue …“ aus, alles Ladbare gewählt.
watch(
  () => [choice.value?.world.roomId, content.value] as const,
  () => {
    const c = content.value
    trust.value = false
    if (!c) return
    selection.value = defaultModSelection(c)
    const best = candidates.value[0]
    baseId.value = best?.id ?? null
    // Hat die beste Instanz schon alles Nötige, bietet sich „Ohne Mods“ nicht an – ergänzen bzw. neu ist sicherer.
    mode.value = best && missingRequired(c, new Set(choice.value?.present[best.id] ?? [])).length === 0 ? 'copy' : 'new'
  },
  { immediate: true },
)

function toggle(m: SharedMod) {
  if (m.required || m.source === 'manual') return
  const next = new Set(selection.value)
  if (next.has(m.sha1)) next.delete(m.sha1)
  else next.add(m.sha1)
  selection.value = next
}

function sourceLabel(m: SharedMod): string {
  return t(`social.hosting.mods.source.${m.source}`)
}

function missingFor(id: string): number {
  const c = content.value
  if (!c) return 0
  const have = new Set(choice.value?.present[id] ?? [])
  return c.mods.filter((m) => !have.has(m.sha1)).length
}

function bytes(n: number): string {
  return formatBytes(n)
}

const progressText = computed(() => {
  const p = hosting.preparing
  if (!p) return ''
  if (p.step === 'instance') return t('social.hosting.mods.stepInstance')
  if (p.step === 'store') return t('social.hosting.mods.stepStore', { name: p.name ?? '', done: p.done + 1, total: p.total })
  if (p.step === 'host') return t('social.hosting.mods.stepHost', { name: p.name ?? '', done: bytes(p.bytes), total: bytes(p.totalBytes) })
  return t('social.hosting.mods.stepDone')
})

function confirm() {
  void hosting.confirmMods({ mode: mode.value, baseInstanceId: baseId.value, selection: [...selection.value], trust: trust.value })
}

const confirmLabel = computed(() =>
  mode.value === 'new'
    ? t('social.hosting.mods.confirmNew')
    : mode.value === 'copy'
      ? t('social.hosting.mods.confirmCopy')
      : t('social.hosting.join'),
)
</script>

<template>
  <BaseDialog v-if="choice" wide :title="t('social.hosting.mods.title', { world: choice.world.name })" @close="hosting.cancelMods()">
    <div data-testid="hosting-mods-dialog">
      <p class="mb-3 text-sm text-base-200">
        <template v-if="summary">
          {{ t('social.hosting.mods.intro', { host: choice.world.host?.name ?? '?', count: summary.count, required: summary.required, version: worldVersionLabel(choice.world) }) }}
        </template>
        <template v-else>{{ t('social.hosting.mods.loading') }}</template>
      </p>
      <p v-if="choice.error" class="mb-3 rounded-lg bg-redstone-900/40 px-3 py-2 text-xs text-redstone-200">{{ choice.error }}</p>

      <template v-if="content">
        <!-- Wie beitreten -->
        <div class="mb-3 grid gap-1.5" role="radiogroup">
          <label class="flex cursor-pointer items-start gap-3 rounded-lg px-2.5 py-2 hover:bg-base-800" :class="{ 'bg-base-800': mode === 'new' }">
            <input v-model="mode" type="radio" value="new" class="mt-1 accent-redstone-500" data-testid="mods-mode-new" />
            <span class="min-w-0 flex-1">
              <span class="block text-sm font-semibold text-base-50">{{ t('social.hosting.mods.modeNew') }}</span>
              <span class="block text-xs text-base-400">{{ t('social.hosting.mods.modeNewHint', { version: worldVersionLabel(choice.world) }) }}</span>
            </span>
          </label>
          <label
            class="flex items-start gap-3 rounded-lg px-2.5 py-2"
            :class="[candidates.length ? 'cursor-pointer hover:bg-base-800' : 'opacity-50', { 'bg-base-800': mode === 'copy' }]"
          >
            <input v-model="mode" type="radio" value="copy" class="mt-1 accent-redstone-500" :disabled="!candidates.length" data-testid="mods-mode-copy" />
            <span class="min-w-0 flex-1">
              <span class="block text-sm font-semibold text-base-50">{{ t('social.hosting.mods.modeCopy') }}</span>
              <span class="block text-xs text-base-400">{{ candidates.length ? t('social.hosting.mods.modeCopyHint') : t('social.hosting.mods.noInstance') }}</span>
            </span>
          </label>
          <label
            class="flex items-start gap-3 rounded-lg px-2.5 py-2"
            :class="[candidates.length ? 'cursor-pointer hover:bg-base-800' : 'opacity-50', { 'bg-base-800': mode === 'none' }]"
          >
            <input v-model="mode" type="radio" value="none" class="mt-1 accent-redstone-500" :disabled="!candidates.length" data-testid="mods-mode-none" />
            <span class="min-w-0 flex-1">
              <span class="block text-sm font-semibold text-base-50">{{ t('social.hosting.mods.modeNone') }}</span>
              <span v-if="mode === 'none' && !withoutOk" class="block text-xs text-redstone-300" data-testid="mods-none-blocked">
                {{ t('social.hosting.mods.noneBlocked') }}
              </span>
              <span v-else class="block text-xs text-base-400">{{ t('social.hosting.mods.modeNoneHint') }}</span>
            </span>
          </label>
          <select v-if="mode !== 'new' && candidates.length" v-model="baseId" class="field ml-8 text-sm" data-testid="mods-base">
            <option v-for="i in candidates" :key="i.id" :value="i.id">
              {{ i.name }} · {{ missingFor(i.id) ? t('social.hosting.mods.missingCount', { count: missingFor(i.id) }) : t('social.hosting.mods.allThere') }}
            </option>
          </select>
        </div>

        <!-- Mod-Liste -->
        <ul class="max-h-60 space-y-1 overflow-y-auto rounded-lg bg-base-900/60 p-1.5" data-testid="mods-list">
          <li v-for="r in rows" :key="r.mod.sha1" class="flex items-center gap-2.5 rounded-md px-2 py-1.5 text-sm" data-testid="mods-row">
            <input
              type="checkbox"
              class="accent-redstone-500"
              :checked="r.mod.required || (r.mod.source !== 'manual' && selection.has(r.mod.sha1))"
              :disabled="r.mod.required || r.mod.source === 'manual' || mode === 'none'"
              :aria-label="r.mod.name"
              @change="toggle(r.mod)"
            />
            <span class="min-w-0 flex-1">
              <span class="block truncate font-medium text-base-50">{{ r.mod.name }} <span class="text-xs text-base-400">{{ r.mod.version }}</span></span>
              <span class="block truncate text-[11px]" :class="r.mod.source === 'host' ? 'text-redstone-300' : r.mod.source === 'manual' ? 'text-base-400' : 'text-lamp-300'">
                {{ sourceLabel(r.mod) }} · {{ bytes(r.mod.size) }}
              </span>
            </span>
            <span class="badge px-1.5 py-0 text-[10px]" :class="r.mod.required ? 'bg-redstone-900/60 text-redstone-200' : 'bg-base-800 text-base-300'">
              {{ r.mod.required ? t('social.hosting.mods.required') : t('social.hosting.mods.optional') }}
            </span>
            <span class="w-20 shrink-0 text-right text-[11px]" :class="r.present ? 'text-ok' : 'text-base-400'">
              {{ r.present ? t('social.hosting.mods.present') : t('social.hosting.mods.missing') }}
            </span>
          </li>
        </ul>
        <p v-if="manual.length" class="mt-2 text-xs text-base-300" data-testid="mods-manual">
          {{ t('social.hosting.mods.manualHint', { names: manual.map((r) => r.mod.name).join(', ') }) }}
        </p>
        <p v-if="mode !== 'none' && install.length" class="mt-2 text-xs text-base-400">
          {{ t('social.hosting.mods.download', { count: install.length, size: bytes(downloadSize(content, selection, present)) }) }}
        </p>

        <!-- Warnung: Dateien direkt vom Host -->
        <div v-if="trustNeeded" class="mt-3 rounded-lg border border-redstone-500/70 bg-redstone-950/60 p-3" data-testid="mods-host-warning">
          <p class="mb-2 flex items-start gap-2 text-sm text-redstone-100">
            <SocialIcon name="shield" class="mt-0.5 size-4 shrink-0 text-redstone-300" />
            {{ t('social.hosting.mods.hostWarning') }}
          </p>
          <label class="flex cursor-pointer items-center gap-2 text-sm font-semibold text-base-50">
            <input v-model="trust" type="checkbox" class="accent-redstone-500" data-testid="mods-trust" />
            {{ t('social.hosting.mods.trust') }}
          </label>
        </div>

        <div v-if="hosting.preparing" class="mt-3" data-testid="mods-progress">
          <p class="mb-1 truncate text-xs text-base-300">{{ progressText }}</p>
          <div class="h-1.5 overflow-hidden rounded-full bg-base-800">
            <div
              class="h-full bg-redstone-500 transition-all"
              :style="{ width: `${hosting.preparing.totalBytes ? Math.round((hosting.preparing.bytes / hosting.preparing.totalBytes) * 100) : hosting.preparing.total ? Math.round((hosting.preparing.done / hosting.preparing.total) * 100) : 5}%` }"
            />
          </div>
        </div>
      </template>
      <div v-else-if="choice.loading" class="grid place-items-center py-6 text-sm text-base-400">{{ t('social.hosting.mods.loading') }}</div>
    </div>
    <template #actions>
      <button class="btn btn-ghost" :disabled="!!hosting.preparing" @click="hosting.cancelMods()">{{ t('common.actions.cancel') }}</button>
      <button class="btn btn-primary" :disabled="!confirmable" data-testid="mods-confirm" @click="confirm">{{ confirmLabel }}</button>
    </template>
  </BaseDialog>
</template>
