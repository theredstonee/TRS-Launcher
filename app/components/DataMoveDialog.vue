<script setup lang="ts">
import { relaunch } from '@tauri-apps/plugin-process'
import type { MovePlan } from '~/types'

// Datenordner verschieben: Ziel wählen → Zusammenfassung → kopieren + prüfen
// (Aufgabe, abbrechbar) → alten Ordner löschen oder behalten → Neustart.
const props = defineProps<{ current: string }>()
const emit = defineEmits<{ close: [] }>()

const tasks = useTasksStore()
const games = useGamesStore()
const toasts = useToasts()

type Step = 'pick' | 'summary' | 'copying' | 'done'
const step = ref<Step>('pick')
const target = ref('')
const plan = ref<MovePlan | null>(null)
const busy = ref(false)
const error = ref<string | null>(null)
const newRoot = ref('')

const key = taskKey('move', 'data-dir')
const task = computed(() => tasks.get(key))
const percent = computed(() => task.value?.percent ?? 0)
const gamesRunning = computed(() => games.running.length > 0)
const blocker = computed(() => (plan.value ? moveBlocker(plan.value, gamesRunning.value) : null))

async function browse() {
  error.value = null
  try {
    const picked = await backend.pickTargetFolder()
    if (picked) target.value = picked
  } catch (e) {
    error.value = errorMessage(e)
  }
}

async function makePlan() {
  const parsed = targetFolderSchema.safeParse(target.value)
  if (!parsed.success) {
    error.value = t('relocate.pickFirst')
    return
  }
  busy.value = true
  error.value = null
  try {
    plan.value = await backend.dataMovePlan(parsed.data)
    step.value = 'summary'
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

async function start() {
  const p = plan.value
  if (!p || blocker.value) return
  error.value = null
  step.value = 'copying'
  const result = await tasks.run(
    {
      key,
      kind: 'move',
      title: t('relocate.data.taskTitle'),
      stage: t('relocate.stageCopying'),
      cancellable: true,
      notify: false,
    },
    async (ctx) => {
      return await backend.moveDataDir(p.target, (value) => ctx.progress(value, value >= 95 ? t('relocate.stageVerifying') : undefined), ctx.taskId)
    },
  )
  if (result.ok) {
    newRoot.value = result.value
    step.value = 'done'
  } else {
    step.value = 'summary'
    error.value = result.cancelled ? t('relocate.cancelled') : errorMessage(result.error)
  }
}

function cancel() {
  void tasks.cancel(key)
}

async function finish(deleteOld: boolean) {
  busy.value = true
  try {
    await backend.confirmDataMove(deleteOld)
    await relaunch()
  } catch (e) {
    busy.value = false
    toasts.error(e)
  }
}

/** Während des Kopierens und vor der Entscheidung bleibt der Dialog offen. */
function close() {
  if (step.value === 'copying' || step.value === 'done') return
  emit('close')
}
</script>

<template>
  <BaseDialog :title="t('relocate.data.title')" wide @close="close">
    <!-- Ziel wählen -->
    <div v-if="step === 'pick'" class="space-y-3">
      <p class="text-sm leading-relaxed text-base-200">{{ t('relocate.data.intro') }}</p>
      <div>
        <p class="label">{{ t('relocate.currentFolder') }}</p>
        <p class="truncate font-mono text-xs text-base-400" :title="props.current">{{ props.current }}</p>
      </div>
      <div>
        <label class="label" for="dm-target">{{ t('relocate.newFolder') }}</label>
        <div class="flex gap-2">
          <input id="dm-target" v-model="target" class="field font-mono text-xs" maxlength="1024" spellcheck="false" :placeholder="t('relocate.newFolderPlaceholder')" />
          <button type="button" class="btn btn-ghost shrink-0" @click="browse">{{ t('relocate.browse') }}</button>
        </div>
        <p class="mt-1 text-xs text-base-400">{{ t('relocate.emptyHint') }}</p>
      </div>
    </div>

    <!-- Zusammenfassung -->
    <div v-else-if="step === 'summary' && plan" class="space-y-3">
      <div class="card divide-y divide-base-800 bg-base-900/40 text-sm">
        <div class="flex items-start justify-between gap-4 px-4 py-2.5">
          <span class="text-base-400">{{ t('relocate.newFolder') }}</span>
          <span class="min-w-0 truncate text-right font-mono text-xs" :title="plan.target">{{ plan.target }}</span>
        </div>
        <div class="flex justify-between px-4 py-2.5">
          <span class="text-base-400">{{ t('relocate.size') }}</span>
          <span class="font-mono">{{ formatBytes(plan.bytes) }} · {{ t('relocate.files', { count: formatCount(plan.files) }, plan.files) }}</span>
        </div>
        <div class="flex justify-between px-4 py-2.5">
          <span class="text-base-400">{{ t('relocate.free') }}</span>
          <span class="font-mono" :class="plan.enoughSpace ? '' : 'text-redstone-300'">{{ plan.free === null ? t('relocate.freeUnknown') : formatBytes(plan.free) }}</span>
        </div>
      </div>
      <p v-if="plan.links" class="text-xs text-warn">{{ t('relocate.linksSkipped', { count: plan.links }, plan.links) }}</p>
      <p class="text-xs leading-relaxed text-base-400">{{ t('relocate.data.restartHint') }}</p>
      <p v-if="blocker" class="text-sm text-redstone-300" role="alert">{{ t(blocker) }}</p>
    </div>

    <!-- Kopieren -->
    <div v-else-if="step === 'copying'" class="space-y-3" aria-live="polite">
      <p class="text-sm text-base-200">{{ task?.stage ?? t('relocate.stageCopying') }}</p>
      <div class="flex items-center gap-3">
        <RedstoneWire class="flex-1" :percent="percent" :segments="40" />
        <span class="display text-sm text-redstone-300 tabular-nums">{{ Math.floor(percent) }} %</span>
      </div>
      <p class="text-xs text-base-400">{{ t('relocate.keepOpen') }}</p>
    </div>

    <!-- Fertig: alter Ordner? -->
    <div v-else-if="step === 'done'" class="space-y-3">
      <p class="text-sm text-ok">{{ t('relocate.data.verified') }}</p>
      <p class="truncate font-mono text-xs text-base-300" :title="newRoot">{{ newRoot }}</p>
      <p class="text-sm leading-relaxed text-base-200">{{ t('relocate.data.oldQuestion') }}</p>
      <p class="truncate font-mono text-xs text-base-400" :title="props.current">{{ props.current }}</p>
    </div>

    <p v-if="error" class="mt-3 text-sm text-redstone-300" role="alert">{{ error }}</p>

    <template #actions>
      <template v-if="step === 'pick'">
        <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="busy || !target.trim()" @click="makePlan">{{ busy ? t('relocate.checking') : t('relocate.next') }}</button>
      </template>
      <template v-else-if="step === 'summary'">
        <button class="btn btn-ghost" @click="step = 'pick'">{{ t('relocate.back') }}</button>
        <button class="btn btn-primary" :disabled="!!blocker" @click="start">{{ t('relocate.startCopy') }}</button>
      </template>
      <template v-else-if="step === 'copying'">
        <button class="btn btn-ghost" :disabled="task?.cancelling" @click="cancel">{{ t('common.actions.cancel') }}</button>
      </template>
      <template v-else>
        <button class="btn btn-ghost" :disabled="busy" @click="finish(false)">{{ t('relocate.data.keepOld') }}</button>
        <button class="btn btn-danger" :disabled="busy" @click="finish(true)">{{ t('relocate.data.deleteOld') }}</button>
      </template>
    </template>
  </BaseDialog>
</template>
