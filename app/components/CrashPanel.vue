<script setup lang="ts">
import type { Diagnosis } from '~/types'

const props = defineProps<{ instanceId: string; exitCode: number | null; diagnosis: Diagnosis | null }>()

const tasks = useTasksStore()
const repairTask = computed(() => tasks.get(repairTaskKey(props.instanceId)))
const repairing = computed(() => (repairTask.value?.status === 'running' ? (repairTask.value.percent ?? 0) : null))
// Teilen läuft über denselben Dialog wie im Log-Tab (Bestätigung, Link, QR-Code).
const sharing = ref(false)

/** Diagnose nach Art übersetzen; unbekannte Arten zeigen den Text des Kerns. */
const diagnosisText = computed(() => {
  const d = props.diagnosis
  if (!d) return t('crash.hint')
  const c = d.conflict
  if (d.kind === 'incompatible_mod' && c) {
    const other = c.otherVersion ? `${c.otherName} ${c.otherVersion}` : c.otherName
    return t('crash.diagnosis.incompatible_mod', { name: `${c.modName} ${c.modVersion}`, other })
  }
  const m = d.missing
  if (d.kind === 'missing_dependency' && m?.dependencies.length) {
    const deps = m.dependencies.join(', ')
    const name = m.modName ?? m.modId
    return name ? t('crash.diagnosis.missing_dependency_named', { name, deps }) : t('crash.diagnosis.missing_dependency_only', { deps })
  }
  const key = `crash.diagnosis.${d.kind}`
  return hasKey(key) ? tKey(key) : d.message
})

// Unverträgliche Versionen: die genannte Mod gegen eine passende tauschen.
const fixTask = computed(() => tasks.get(modFixTaskKey(props.instanceId)))
const fixing = computed(() => fixTask.value?.status === 'running')

function fixConflict() {
  const instance = useInstancesStore().items.find((i) => i.id === props.instanceId)
  fixModConflictsTask({ id: props.instanceId, name: instance?.name ?? props.instanceId }, props.diagnosis?.conflict?.modId ?? null)
}

// Fehlende Mods (laut Loader): installieren.
const missingTask = computed(() => tasks.get(missingModsTaskKey(props.instanceId)))
const installingMissing = computed(() => missingTask.value?.status === 'running')

function installMissing() {
  const m = props.diagnosis?.missing
  if (!m?.dependencies.length) return
  const instance = useInstancesStore().items.find((i) => i.id === props.instanceId)
  installMissingModsTask({ id: props.instanceId, name: instance?.name ?? props.instanceId }, m.modId, m.dependencies)
}

function repair() {
  const instance = useInstancesStore().items.find((i) => i.id === props.instanceId)
  repairInstanceTask({ id: props.instanceId, name: instance?.name ?? props.instanceId }, 'repair')
}

</script>

<template>
  <section class="card border-warn/40 px-4 py-3" role="alert">
    <p class="text-sm font-medium text-warn">
      {{ diagnosis ? t('crash.crashed') : t('crash.exitedUnexpectedly', { code: exitCode ?? '?' }) }}
    </p>
    <p class="mt-0.5 text-sm text-base-200">
      {{ diagnosisText }}
    </p>

    <div class="mt-3 flex flex-wrap items-center gap-2">
      <button v-if="diagnosis?.conflict" class="btn btn-primary px-3 py-1.5 text-xs" :disabled="fixing" @click="fixConflict">
        {{ fixing ? t('crash.fixing') : t('crash.fixConflict', { name: diagnosis.conflict.modName }) }}
      </button>
      <button
        v-if="diagnosis?.missing?.dependencies.length"
        class="btn btn-primary px-3 py-1.5 text-xs"
        :disabled="installingMissing"
        @click="installMissing"
      >
        {{ installingMissing ? t('crash.installingMissing') : t('crash.installMissing', { name: diagnosis.missing.dependencies.join(', ') }) }}
      </button>
      <button
        v-if="repairing === null"
        class="btn btn-primary px-3 py-1.5 text-xs"
        :class="{ 'btn-ghost': !diagnosis?.canRepair || diagnosis?.conflict || diagnosis?.missing }"
        @click="repair"
      >
        {{ t('crash.checkFiles') }}
      </button>
      <div v-else class="flex w-56 items-center gap-2">
        <RedstoneWire :percent="repairing" :segments="16" class="flex-1" />
        <span class="display text-xs tabular-nums text-redstone-300">{{ repairing }} %</span>
      </div>
      <button class="btn btn-ghost px-3 py-1.5 text-xs" @click="sharing = true">
        {{ t('crash.shareLog') }}
      </button>
    </div>

    <LogShareDialog v-if="sharing" :instance-id="instanceId" source="live" :label="t('logViewer.latest')" @close="sharing = false" />
  </section>
</template>
