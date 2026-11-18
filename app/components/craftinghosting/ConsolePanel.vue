<script setup lang="ts">
import { plainLogLine } from '~/utils/craftinghosting'

// Konsole des Partner-Servers: letzte Log-Zeilen (alle 3 s, solange er läuft) und Befehlszeile.
const store = useCraftingHostingStore()
const text = ref('')
const box = useTemplateRef<HTMLElement>('box')
const follow = ref(true)
const running = computed(() => store.server?.state === 'running')

onMounted(() => store.setConsoleOpen(true))
onUnmounted(() => store.setConsoleOpen(false))

function onScroll() {
  const el = box.value
  if (el) follow.value = el.scrollHeight - el.scrollTop - el.clientHeight < 40
}

watch(
  () => store.logs,
  async () => {
    if (!follow.value) return
    await nextTick()
    box.value?.scrollTo({ top: box.value.scrollHeight })
  },
)

async function send() {
  if (await store.command(text.value)) text.value = ''
}
</script>

<template>
  <form class="card p-4" data-testid="hosting-console" @submit.prevent="send">
    <div
      ref="box"
      class="mb-3 h-72 overflow-y-auto rounded-lg bg-base-950 p-3 font-mono text-xs leading-relaxed text-base-200 ring-1 ring-base-800"
      role="log"
      aria-live="polite"
      @scroll="onScroll"
    >
      <p v-if="!store.logs.length" class="text-base-500">{{ t('craftinghosting.console.empty') }}</p>
      <div v-for="(line, i) in store.logs" :key="i" class="break-all whitespace-pre-wrap">{{ plainLogLine(line) }}</div>
    </div>
    <div v-if="store.features.console" class="flex gap-2">
      <input
        v-model="text"
        class="field flex-1 font-mono"
        maxlength="257"
        :placeholder="t('craftinghosting.console.placeholder')"
        :disabled="!running"
        autocomplete="off"
        spellcheck="false"
      />
      <button type="submit" class="btn btn-ghost" :disabled="!running || !text.trim() || !!store.busy">
        {{ t('craftinghosting.console.send') }}
      </button>
    </div>
    <p v-if="!running && store.features.console" class="mt-2 text-xs text-base-500">{{ t('craftinghosting.console.offline') }}</p>
  </form>
</template>
