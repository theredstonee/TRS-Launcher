<script setup lang="ts">
import type { Instance, InstanceLocation, MovePlan } from '~/types'

// Speicherort einer Instanz: an einen eigenen Ordner verschieben oder zurück an
// den Standardort. Läuft als Aufgabe weiter, auch wenn der Dialog zugeht.
const props = defineProps<{ instance: Instance; location: InstanceLocation; toDefault?: boolean }>()
const emit = defineEmits<{ close: []; moved: [location: InstanceLocation] }>()

const tasks = useTasksStore()
const instances = useInstancesStore()
const games = useGamesStore()

const step = ref<'pick' | 'summary' | 'moving'>(props.toDefault ? 'summary' : 'pick')
const target = ref('')
const plan = ref<MovePlan | null>(null)
const busy = ref(false)
const error = ref<string | null>(null)

const key = taskKey('move', props.instance.id)
const task = computed(() => tasks.get(key))
const percent = computed(() => task.value?.percent ?? 0)
const running = computed(() => games.state(props.instance.id).phase !== 'idle')
const blocker = computed(() => (plan.value ? moveBlocker(plan.value, running.value) : null))

onMounted(() => {
  if (props.toDefault) void makePlan()
})

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
  let chosen: string | null = null
  if (!props.toDefault) {
    const parsed = targetFolderSchema.safeParse(target.value)
    if (!parsed.success) {
      error.value = t('relocate.pickFirst')
      return
    }
    chosen = parsed.data
  }
  busy.value = true
  error.value = null
  try {
    plan.value = await backend.instanceMovePlan(props.instance.id, chosen)
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
  step.value = 'moving'
  const destination = props.toDefault ? null : p.target
  const result = await tasks.run(
    {
      key,
      kind: 'move',
      title: props.instance.name,
      stage: t('relocate.stageCopying'),
      instanceId: props.instance.id,
      cancellable: true,
      doneText: t('relocate.instance.done', { name: props.instance.name }),
    },
    (ctx) => backend.moveInstance(props.instance.id, destination, (value) => ctx.progress(value, value >= 95 ? t('relocate.stageVerifying') : undefined), ctx.taskId),
  )
  if (result.ok) {
    await instances.load()
    emit('moved', result.value)
    emit('close')
  } else {
    step.value = 'summary'
    error.value = result.cancelled ? t('relocate.cancelled') : errorMessage(result.error)
  }
}
</script>

<template>
  <BaseDialog :title="toDefault ? t('relocate.instance.backTitle') : t('relocate.instance.title')" wide @close="emit('close')">
    <div v-if="step === 'pick'" class="space-y-3">
      <p class="text-sm leading-relaxed text-base-200">{{ t('relocate.instance.intro') }}</p>
      <div>
        <p class="label">{{ t('relocate.currentFolder') }}</p>
        <p class="truncate font-mono text-xs text-base-400" :title="location.path">{{ location.path }}</p>
      </div>
      <div>
        <label class="label" for="im-target">{{ t('relocate.newFolder') }}</label>
        <div class="flex gap-2">
          <input id="im-target" v-model="target" class="field font-mono text-xs" maxlength="1024" spellcheck="false" :placeholder="t('relocate.instance.placeholder')" />
          <button type="button" class="btn btn-ghost shrink-0" @click="browse">{{ t('relocate.browse') }}</button>
        </div>
        <p class="mt-1 text-xs text-base-400">{{ t('relocate.emptyHint') }}</p>
      </div>
    </div>

    <div v-else-if="step === 'summary'" class="space-y-3">
      <div v-if="!plan" class="skeleton h-24" />
      <template v-else>
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
            <span class="font-mono" :class="plan.enoughSpace ? '' : 'text-redstone-300'">
              {{ plan.sameVolume ? t('relocate.sameDrive') : plan.free === null ? t('relocate.freeUnknown') : formatBytes(plan.free) }}
            </span>
          </div>
        </div>
        <p v-if="plan.links && !plan.sameVolume" class="text-xs text-warn">{{ t('relocate.linksSkipped', { count: plan.links }, plan.links) }}</p>
        <p class="text-xs leading-relaxed text-base-400">{{ t('relocate.instance.hint') }}</p>
        <p v-if="blocker" class="text-sm text-redstone-300" role="alert">{{ t(blocker === 'relocate.blockedGames' ? 'relocate.instance.blockedRunning' : blocker) }}</p>
      </template>
    </div>

    <div v-else class="space-y-3" aria-live="polite">
      <p class="text-sm text-base-200">{{ task?.stage ?? t('relocate.stageCopying') }}</p>
      <div class="flex items-center gap-3">
        <RedstoneWire class="flex-1" :percent="percent" :segments="40" />
        <span class="display text-sm text-redstone-300 tabular-nums">{{ Math.floor(percent) }} %</span>
      </div>
    </div>

    <p v-if="error" class="mt-3 text-sm text-redstone-300" role="alert">{{ error }}</p>

    <template #actions>
      <template v-if="step === 'pick'">
        <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="busy || !target.trim()" @click="makePlan">{{ busy ? t('relocate.checking') : t('relocate.next') }}</button>
      </template>
      <template v-else-if="step === 'summary'">
        <button v-if="!toDefault" class="btn btn-ghost" @click="step = 'pick'">{{ t('relocate.back') }}</button>
        <button v-else class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="!plan || !!blocker" @click="start">{{ t('relocate.instance.start') }}</button>
      </template>
      <template v-else>
        <button class="btn btn-ghost" :disabled="task?.cancelling" @click="tasks.cancel(key)">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-ghost" @click="emit('close')">{{ t('relocate.hide') }}</button>
      </template>
    </template>
  </BaseDialog>
</template>
