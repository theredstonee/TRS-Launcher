<script setup lang="ts">
import type { ControlLayout, StoredLayout } from '~/utils/controls'
import { CODE_PREFIX, DEFAULT_ID, builtinHints, isBuiltinId, layoutName } from '~/utils/controls'

// Touch-Steuerung der mobilen App: Layouts ansehen, bearbeiten (Editor auf
// einem Handy-Bildschirm), teilen (Code/Datei) und je Instanz wählen.
const instances = useInstancesStore()
const toasts = useToasts()

const list = ref<StoredLayout[]>([])
const loaded = ref(false)
const error = ref<string | null>(null)
const busy = ref<string | null>(null)
const editing = ref<StoredLayout | null>(null)
const confirm = ref<string | null>(null)
const shareCode = ref<{ name: string; code: string } | null>(null)
const importOpen = ref(false)
const importText = ref('')
const importError = ref<string | null>(null)

async function load() {
  try {
    list.value = await backend.controls.list()
    loaded.value = true
    error.value = null
  } catch (e) {
    error.value = errorMessage(e)
  }
}

onMounted(() => {
  void load()
  if (!instances.loaded) void instances.load()
})

function replace(stored: StoredLayout) {
  const i = list.value.findIndex((s) => s.layout.id === stored.layout.id)
  if (i >= 0) list.value = list.value.map((s, j) => (j === i ? stored : s))
  else list.value = [...list.value, stored]
}

async function run(id: string, work: () => Promise<unknown>) {
  busy.value = id
  try {
    await work()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

async function save(layout: ControlLayout) {
  await run(layout.id, async () => {
    const saved = await backend.controls.save(layout)
    replace(saved)
    editing.value = null
    toasts.ok(t('controls.page.saved', { name: layoutName(saved) }))
  })
}

function duplicate(s: StoredLayout) {
  run(s.layout.id, async () => {
    const copy = await backend.controls.duplicate(s.layout.id, t('controls.page.copyName', { name: layoutName(s) }))
    replace(copy)
    editing.value = copy
  })
}

function removeOrReset(s: StoredLayout) {
  if (confirm.value !== s.layout.id) {
    confirm.value = s.layout.id
    return
  }
  confirm.value = null
  run(s.layout.id, async () => {
    if (s.builtin) {
      replace(await backend.controls.reset(s.layout.id))
      toasts.ok(t('controls.page.resetDone', { name: layoutName(s) }))
    } else {
      await backend.controls.remove(s.layout.id)
      list.value = list.value.filter((x) => x.layout.id !== s.layout.id)
      toasts.ok(t('controls.page.deleted', { name: layoutName(s) }))
    }
  })
}

function share(s: StoredLayout) {
  run(s.layout.id, async () => {
    shareCode.value = { name: layoutName(s), code: await backend.controls.exportCode(s.layout.id) }
  })
}

async function copyCode() {
  if (!shareCode.value) return
  try {
    await navigator.clipboard.writeText(shareCode.value.code)
    toasts.ok(t('controls.page.codeCopied'))
  } catch (e) {
    toasts.error(e)
  }
}

function exportFile(s: StoredLayout) {
  run(s.layout.id, async () => {
    if (await backend.controls.exportFile(s.layout.id)) toasts.ok(t('controls.page.exported', { name: layoutName(s) }))
  })
}

async function importFile() {
  try {
    const stored = await backend.controls.importFile()
    if (stored) {
      replace(stored)
      toasts.ok(t('controls.page.imported', { name: stored.layout.name }))
    }
  } catch (e) {
    toasts.error(e)
  }
}

async function importCode() {
  importError.value = null
  const code = importText.value.trim()
  if (!code.startsWith(CODE_PREFIX)) {
    importError.value = t('errors.controls.invalidCode')
    return
  }
  busy.value = 'import'
  try {
    const stored = await backend.controls.importCode(code)
    replace(stored)
    importOpen.value = false
    importText.value = ''
    toasts.ok(t('controls.page.imported', { name: stored.layout.name }))
  } catch (e) {
    importError.value = errorMessage(e)
  } finally {
    busy.value = null
  }
}

// --- Je Instanz -----------------------------------------------------------------
const defaultName = computed(() => {
  const s = list.value.find((x) => x.layout.id === DEFAULT_ID)
  return s ? layoutName(s) : DEFAULT_ID
})

function profileOf(id: string): string {
  const p = instances.items.find((i) => i.id === id)?.overrides.touchProfile ?? ''
  // Gelöschtes Layout: wie im Spiel der Standard.
  return p && list.value.some((s) => s.layout.id === p) ? p : ''
}

async function assign(instanceId: string, profile: string) {
  await run(`inst:${instanceId}`, async () => {
    const updated = await backend.controls.setInstanceProfile(instanceId, profile || null)
    instances.items = instances.items.map((i) => (i.id === updated.id ? { ...i, overrides: updated.overrides } : i))
  })
}
</script>

<template>
  <div class="mx-auto max-w-4xl p-4 sm:p-6">
    <PageHeader :title="t('controls.page.title')" :subtitle="t('controls.page.subtitle')">
      <button class="btn btn-ghost" @click="importOpen = true">{{ t('controls.page.importCode') }}</button>
      <button class="btn btn-ghost" @click="importFile">{{ t('controls.page.importFile') }}</button>
    </PageHeader>

    <p v-if="error" role="alert" class="card mb-3 border-redstone-600/50 px-4 py-2.5 text-sm text-redstone-300">{{ error }}</p>

    <div v-if="!loaded && !error" class="grid gap-3 sm:grid-cols-2">
      <div v-for="i in 3" :key="i" class="skeleton h-56" />
    </div>

    <ul v-else class="grid gap-3 sm:grid-cols-2" data-testid="controls-list">
      <li v-for="s in list" :key="s.layout.id" class="card overflow-hidden">
        <button type="button" class="block w-full p-2 pb-0" :aria-label="t('controls.page.editNamed', { name: layoutName(s) })" @click="editing = s">
          <ControlsScreen :layout="s.layout" :label="layoutName(s)" />
        </button>
        <div class="px-4 pt-3 pb-1">
          <p class="flex flex-wrap items-center gap-1.5">
            <span class="truncate font-medium text-base-50">{{ layoutName(s) }}</span>
            <span v-if="s.builtin" class="badge bg-base-800 text-base-400">TRS</span>
            <span v-if="s.modified" class="badge bg-lamp-400/15 text-lamp-400">{{ t('controls.page.modified') }}</span>
            <span class="text-xs text-base-600">{{ t('controls.page.buttons', s.layout.buttons.length) }}</span>
          </p>
          <p v-if="s.builtin && isBuiltinId(s.layout.id)" class="mt-0.5 text-xs text-base-400">{{ t(builtinHints[s.layout.id]) }}</p>
        </div>
        <div class="flex flex-wrap gap-1.5 px-4 pt-2 pb-3">
          <button class="btn btn-primary" :disabled="busy === s.layout.id" @click="editing = s">{{ t('controls.page.edit') }}</button>
          <button class="btn btn-ghost" :disabled="busy === s.layout.id" @click="duplicate(s)">{{ t('controls.page.duplicate') }}</button>
          <button class="btn btn-ghost" :disabled="busy === s.layout.id" @click="share(s)">{{ t('controls.page.shareCode') }}</button>
          <button class="btn btn-ghost" :disabled="busy === s.layout.id" @click="exportFile(s)">{{ t('controls.page.exportFile') }}</button>
          <button
            v-if="!s.builtin || s.modified"
            class="btn btn-danger"
            :disabled="busy === s.layout.id"
            @click="removeOrReset(s)"
          >
            {{ confirm === s.layout.id ? (s.builtin ? t('controls.page.resetConfirm') : t('controls.page.deleteConfirm')) : s.builtin ? t('controls.page.reset') : t('common.actions.delete') }}
          </button>
        </div>
      </li>
    </ul>

    <!-- Je Instanz -->
    <section v-if="loaded" class="mt-8" :aria-label="t('controls.instances.title')">
      <h2 class="text-base font-semibold">{{ t('controls.instances.title') }}</h2>
      <p class="mb-3 text-sm text-base-400">{{ t('controls.instances.hint') }}</p>
      <p v-if="instances.loaded && !instances.items.length" class="text-sm text-base-600">{{ t('controls.instances.none') }}</p>
      <ul class="card divide-y divide-base-800">
        <li v-for="i in instances.items" :key="i.id" class="flex flex-wrap items-center gap-3 px-4 py-2.5">
          <InstanceIcon :instance="i" :size="32" />
          <span class="min-w-0 flex-1">
            <span class="block truncate text-sm text-base-200">{{ i.name }}</span>
            <span class="block font-mono text-[11px] text-base-600">{{ i.gameVersion }}</span>
          </span>
          <select
            class="field w-full sm:w-56"
            :value="profileOf(i.id)"
            :disabled="busy === `inst:${i.id}`"
            :aria-label="t('controls.instances.selectFor', { name: i.name })"
            @change="assign(i.id, ($event.target as HTMLSelectElement).value)"
          >
            <option value="">{{ t('controls.instances.default', { name: defaultName }) }}</option>
            <option v-for="s in list" :key="s.layout.id" :value="s.layout.id">{{ layoutName(s) }}</option>
          </select>
        </li>
      </ul>
    </section>

    <ControlsEditor v-if="editing" :stored="editing" :busy="busy === editing.layout.id" @save="save" @close="editing = null" />

    <BaseDialog v-if="shareCode" :title="t('controls.code.title')" @close="shareCode = null">
      <p class="mb-2 text-sm text-base-400">{{ t('controls.code.hint', { name: shareCode.name }) }}</p>
      <textarea class="field h-28 w-full font-mono text-xs break-all" readonly :value="shareCode.code" @focus="($event.target as HTMLTextAreaElement).select()" />
      <template #actions>
        <button class="btn btn-ghost" @click="shareCode = null">{{ t('common.actions.close') }}</button>
        <button class="btn btn-primary" @click="copyCode">{{ t('common.actions.copy') }}</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="importOpen" :title="t('controls.code.importTitle')" @close="importOpen = false">
      <p class="mb-2 text-sm text-base-400">{{ t('controls.code.importHint') }}</p>
      <textarea v-model="importText" class="field h-28 w-full font-mono text-xs" :placeholder="`${CODE_PREFIX}…`" spellcheck="false" />
      <p v-if="importError" role="alert" class="mt-2 text-sm text-redstone-300">{{ importError }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="importOpen = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="!importText.trim() || busy === 'import'" @click="importCode">{{ t('common.actions.import') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
