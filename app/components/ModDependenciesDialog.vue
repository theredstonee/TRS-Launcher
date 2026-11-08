<script setup lang="ts">
import { dependentMods, planModEnable, type DependencyNode } from '~/utils/modDependencies'

const props = defineProps<{
  instanceId: string
  graph: DependencyNode[]
  files: string[]
  action: 'enable' | 'disable' | null
  busy: boolean
  projectUrl?: { path: string; query: Record<string, string> } | null
}>()
const emit = defineEmits<{ close: []; apply: []; refresh: [] }>()
const activeFile = ref(props.files[0] ?? '')
const active = computed(() => props.graph.find(node => node.fileName === activeFile.value))
const plan = computed(() => planModEnable(props.graph, props.action ? props.files : [activeFile.value]))
const affected = computed(() => dependentMods(props.graph, props.files, true))
const dependents = computed(() => dependentMods(props.graph, [activeFile.value]))
const enabling = computed(() => props.graph.filter(node => plan.value.files.includes(node.fileName) && !node.enabled))
const installing = ref(false)
const error = ref<string | null>(null)
const toasts = useToasts()
const title = computed(() => props.action ? t(`modDependencies.${props.action}Title`) : active.value?.title ?? t('modDependencies.title'))
const label = (file: string) => props.graph.find(node => node.fileName === file)?.title ?? file
const enabled = (file: string) => props.graph.find(node => node.fileName === file)?.enabled ?? false

async function install(id: string) {
  if (installing.value || props.busy) return
  installing.value = true
  error.value = null
  try {
    const result = await backend.installMissingDependencies(props.instanceId, null, [id])
    toasts.ok(t('modDependencies.installed', { names: result.added.join(', ') }))
    if (result.unresolved.length) error.value = t('modDependencies.unresolved', { names: result.unresolved.join(', ') })
    emit('refresh')
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    installing.value = false
  }
}
</script>

<template>
  <BaseDialog :title="title" @close="!installing && !busy && emit('close')">
    <div class="max-h-[60vh] space-y-4 overflow-y-auto">
      <template v-if="action === 'enable'">
        <p class="text-sm text-base-200">{{ t('modDependencies.enableText') }}</p>
        <ul class="divide-y divide-base-800">
          <li v-for="node in enabling" :key="node.fileName" class="wrap-break-word py-2 text-sm">{{ node.title }}</li>
        </ul>
      </template>
      <template v-else-if="action === 'disable'">
        <p class="text-sm text-base-200">{{ t('modDependencies.disableText') }}</p>
        <ul class="divide-y divide-base-800">
          <li v-for="node in affected" :key="node.fileName" class="wrap-break-word py-2 text-sm">{{ node.title }}</li>
        </ul>
      </template>
      <template v-else>
        <button v-if="activeFile !== files[0]" class="btn btn-ghost" @click="activeFile = files[0] ?? ''">{{ t('common.actions.back') }}</button>
        <p v-if="active && !active.readable" class="text-sm text-lamp-300">{{ t('modDependencies.unknown') }}</p>
        <section>
          <h3 class="mb-2 text-sm font-semibold">{{ t('modDependencies.requires') }}</h3>
          <p v-if="!active?.requires.length" class="text-sm text-base-400">{{ t('modDependencies.none') }}</p>
          <ul v-else class="divide-y divide-base-800">
            <li v-for="dependency in active.requires" :key="dependency.id" class="py-2 text-sm">
              <span class="block wrap-break-word text-base-400">{{ dependency.id }}</span>
              <button v-for="file in dependency.fileNames" :key="file" class="flex w-full items-start justify-between gap-3 py-1 text-left hover:text-redstone-300" @click="activeFile = file">
                <span class="min-w-0 wrap-break-word">{{ label(file) }}</span>
                <span class="shrink-0 text-xs text-base-400">{{ t(enabled(file) ? 'modDependencies.enabled' : 'modDependencies.disabled') }}</span>
              </button>
              <span v-if="!dependency.fileNames.length" class="text-lamp-300">{{ t('modDependencies.missing') }}</span>
            </li>
          </ul>
        </section>
        <section>
          <h3 class="mb-2 text-sm font-semibold">{{ t('modDependencies.usedBy') }}</h3>
          <p v-if="!dependents.length" class="text-sm text-base-400">{{ t('modDependencies.none') }}</p>
          <ul v-else class="divide-y divide-base-800">
            <li v-for="node in dependents" :key="node.fileName" class="py-2">
              <button class="flex w-full items-start justify-between gap-3 text-left text-sm hover:text-redstone-300" @click="activeFile = node.fileName">
                <span class="min-w-0 wrap-break-word">{{ node.title }}</span>
                <span class="shrink-0 text-xs text-base-400">{{ t(node.enabled ? 'modDependencies.enabled' : 'modDependencies.disabled') }}</span>
              </button>
            </li>
          </ul>
        </section>
        <NuxtLink v-if="projectUrl && activeFile === files[0]" :to="projectUrl" class="btn btn-ghost" @click="emit('close')">{{ t('modDependencies.project') }}</NuxtLink>
      </template>
      <section v-if="action !== 'disable' && plan.missing.length">
        <h3 class="text-sm font-semibold text-lamp-300">{{ t('modDependencies.missing') }}</h3>
        <ul class="divide-y divide-base-800">
          <li v-for="id in plan.missing" :key="id" class="flex flex-wrap items-center justify-between gap-2 py-2">
            <span class="min-w-0 wrap-break-word text-sm">{{ id }}</span>
            <button class="btn btn-secondary" :disabled="installing || busy" @click="install(id)">{{ t('modDependencies.install', { id }) }}</button>
          </li>
        </ul>
      </section>
      <p v-if="action !== 'disable' && plan.ambiguous.length" class="wrap-break-word text-sm text-lamp-300">{{ t('modDependencies.ambiguous', { names: plan.ambiguous.join(', ') }) }}</p>
      <p v-if="action === 'enable' && plan.unknown.length" class="wrap-break-word text-sm text-lamp-300">{{ t('modDependencies.unknownFiles', { names: plan.unknown.map(label).join(', ') }) }}</p>
      <p v-if="error" role="alert" class="wrap-break-word text-sm text-redstone-300">{{ error }}</p>
    </div>
    <template #actions>
      <button class="btn btn-ghost" :disabled="installing || busy" @click="emit('close')">{{ t(action ? 'common.actions.cancel' : 'common.actions.close') }}</button>
      <button v-if="action" class="btn" :class="action === 'disable' ? 'btn-danger' : 'btn-primary'" :disabled="installing || busy || (action === 'enable' && !!(plan.missing.length || plan.ambiguous.length))" @click="emit('apply')">{{ t(`modDependencies.${action}`) }}</button>
    </template>
  </BaseDialog>
</template>