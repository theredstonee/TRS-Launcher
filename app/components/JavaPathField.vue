<script setup lang="ts">
import type { JavaInstall } from '~/types'

// Java-Pfad mit Prüf-Haken, „Erkennen“ (gefundene Installationen) und
// „Durchsuchen“ (nativer Dialog). Leer = automatisch/global.
const model = defineModel<string>({ required: true })
const props = defineProps<{ placeholder?: string; expectedMajor?: number; disabled?: boolean; inputId?: string }>()

// Text als Funktion, damit er beim Sprachwechsel neu übersetzt wird.
const check = ref<{ ok: boolean; text: () => string } | null>(null)
const detecting = ref(false)
const found = ref<JavaInstall[] | null>(null)
const toasts = useToasts()

let timer: ReturnType<typeof setTimeout> | undefined
watch(
  model,
  (path) => {
    clearTimeout(timer)
    check.value = null
    if (!path.trim()) return
    timer = setTimeout(async () => {
      const parsed = javaPathSchema.safeParse(path.trim())
      if (!parsed.success) {
        const issue = firstIssue(parsed.error)
        check.value = { ok: false, text: () => issue }
        return
      }
      try {
        const r = await backend.checkJava(parsed.data)
        const expected = props.expectedMajor
        if (r.major === null) check.value = { ok: true, text: () => t('javaPath.foundUnknown') }
        else if (expected && javaSlot(r.major) !== expected) {
          const found = r.major
          check.value = { ok: false, text: () => t('javaPath.wrongMajor', { found, expected }) }
        } else {
          const version = `Java ${r.version ?? r.major}`
          check.value = { ok: true, text: () => version }
        }
      } catch (e) {
        check.value = { ok: false, text: () => errorMessage(e) }
      }
    }, 350)
  },
  { immediate: true },
)
onBeforeUnmount(() => clearTimeout(timer))

// Gleiche Zuordnung wie `JavaPaths::slot_for` im Kern.
function javaSlot(major: number) {
  return major <= 8 ? 8 : major <= 17 ? 17 : major <= 21 ? 21 : 25
}

async function detect() {
  detecting.value = true
  try {
    const all = await backend.detectJava()
    found.value = props.expectedMajor ? all.filter((j) => javaSlot(j.major) === props.expectedMajor) : all
    if (!found.value.length)
      toasts.info(props.expectedMajor ? t('javaPath.notFoundMajor', { major: props.expectedMajor }) : t('javaPath.notFound'))
  } catch (e) {
    toasts.error(e)
  } finally {
    detecting.value = false
  }
}

async function browse() {
  try {
    const path = await backend.pickJavaPath()
    if (path) model.value = path
  } catch (e) {
    toasts.error(e)
  }
}

function pick(j: JavaInstall) {
  model.value = j.path
  found.value = null
}
</script>

<template>
  <div>
    <div class="flex items-center gap-2">
      <div class="relative min-w-0 flex-1">
        <input
          :id="inputId"
          v-model="model"
          class="field pr-8 font-mono text-xs"
          maxlength="1024"
          :placeholder="placeholder ?? t('common.labels.automatic')"
          spellcheck="false"
          :disabled="disabled"
        />
        <span v-if="check?.ok" class="absolute top-1/2 right-2.5 grid size-4 -translate-y-1/2 place-items-center rounded-full bg-ok text-base-950" :title="check.text()">
          <svg viewBox="0 0 24 24" class="size-3" fill="none" stroke="currentColor" stroke-width="3.5" stroke-linecap="round"><path :d="icons.check" /></svg>
        </span>
      </div>
      <button type="button" class="btn btn-ghost py-1.5 text-xs" :disabled="disabled || detecting" @click="detect">{{ detecting ? t('javaPath.searching') : t('javaPath.detect') }}</button>
      <button type="button" class="btn btn-ghost py-1.5 text-xs" :disabled="disabled" @click="browse">{{ t('common.actions.browse') }}</button>
    </div>
    <p v-if="check" class="mt-1 text-xs" :class="check.ok ? 'text-ok' : 'text-redstone-300'">{{ check.text() }}</p>
    <ul v-if="found?.length" class="mt-2 space-y-1 rounded-lg border border-base-800 bg-base-850 p-1">
      <li v-for="j in found" :key="j.path">
        <button type="button" class="flex w-full items-center gap-3 rounded-md px-2.5 py-1.5 text-left text-xs hover:bg-base-700" @click="pick(j)">
          <span class="badge bg-base-800 text-base-50">Java {{ j.major }}</span>
          <span class="min-w-0 flex-1 truncate font-mono text-base-400" :title="j.path">{{ j.path }}</span>
          <span v-if="j.managed" class="text-base-600">{{ t('javaPath.managed') }}</span>
        </button>
      </li>
    </ul>
  </div>
</template>
