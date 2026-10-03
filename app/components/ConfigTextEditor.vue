<script setup lang="ts">
import type { ConfigFormat } from '~/utils/config/types'
import { highlightLine } from '~/utils/config/highlight'

// Erweiterter Modus: Textfeld mit darunterliegender Einfärbung und
// Zeilennummern. Nur die sichtbaren Zeilen werden eingefärbt (Dateien bis 2 MB).
const props = defineProps<{ format: ConfigFormat; errorLine: number | null; label: string }>()
const model = defineModel<string>({ required: true })

const LINE = 20 // px, passt zu leading-5
const PAD = 8 // px, passt zu p-2
const area = ref<HTMLTextAreaElement | null>(null)
const scrollTop = ref(0)
const scrollLeft = ref(0)
const height = ref(400)

const lines = computed(() => model.value.split('\n'))
const first = computed(() => Math.max(0, Math.floor((scrollTop.value - PAD) / LINE) - 5))
const last = computed(() => Math.min(lines.value.length, Math.ceil((scrollTop.value + height.value) / LINE) + 5))
const visible = computed(() =>
  lines.value.slice(first.value, last.value).map((line, i) => ({
    n: first.value + i + 1,
    tokens: highlightLine(props.format, line.replace(/\r$/, '')),
  })),
)
const gutterWidth = computed(() => `${String(lines.value.length).length + 2}ch`)

function onScroll() {
  const el = area.value
  if (!el) return
  scrollTop.value = el.scrollTop
  scrollLeft.value = el.scrollLeft
}

let observer: ResizeObserver | null = null
onMounted(() => {
  if (area.value) {
    height.value = area.value.clientHeight
    observer = new ResizeObserver(() => (height.value = area.value?.clientHeight ?? height.value))
    observer.observe(area.value)
  }
})
onBeforeUnmount(() => observer?.disconnect())

/** Einrückung: Tabs, wenn die Datei Tabs nutzt (nicht in YAML), sonst Leerzeichen. */
const indentUnit = computed(() => {
  if (props.format !== 'yaml' && /^\t/m.test(model.value)) return '\t'
  return props.format === 'forgecfg' ? '    ' : '  '
})

function insert(text: string) {
  const el = area.value
  if (!el) return
  el.focus()
  // execCommand hält den Rückgängig-Verlauf des Textfelds intakt.
  if (!document.execCommand('insertText', false, text)) {
    el.setRangeText(text, el.selectionStart, el.selectionEnd, 'end')
    model.value = el.value
  }
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Tab' && !e.shiftKey && !e.ctrlKey && !e.altKey) {
    e.preventDefault()
    insert(indentUnit.value)
  } else if (e.key === 'Enter' && !e.ctrlKey && !e.altKey) {
    // Einrückung der aktuellen Zeile übernehmen.
    const el = e.target as HTMLTextAreaElement
    const start = el.value.lastIndexOf('\n', el.selectionStart - 1) + 1
    const indent = /^[ \t]*/.exec(el.value.slice(start, el.selectionStart))![0]
    e.preventDefault()
    insert(`\n${indent}`)
  }
}

/** Cursor an den Anfang von Zeile `n` (1-basiert) und dorthin scrollen. */
function goToLine(n: number) {
  const el = area.value
  if (!el) return
  let offset = 0
  for (let i = 1; i < n && offset >= 0; i++) offset = el.value.indexOf('\n', offset) + 1 || -1
  if (offset < 0) offset = el.value.length
  el.focus()
  el.setSelectionRange(offset, offset)
  el.scrollTop = Math.max(0, (n - 1) * LINE - el.clientHeight / 2)
  onScroll()
}
defineExpose({ goToLine })

const TOKEN_CLASS: Record<string, string> = {
  key: 'text-sky-400',
  string: 'text-emerald-400',
  number: 'text-lamp-300',
  bool: 'text-violet-400',
  comment: 'text-base-400 italic',
  section: 'text-redstone-300 font-semibold',
  punct: 'text-base-400',
  text: 'text-base-200',
}
</script>

<template>
  <div class="relative flex min-h-0 flex-1 overflow-hidden rounded-lg border border-base-800 bg-base-950 font-mono text-[12.5px] leading-5">
    <!-- Zeilennummern -->
    <div class="relative shrink-0 overflow-hidden border-r border-base-800 bg-base-900 text-right text-base-600 select-none" :style="{ width: gutterWidth }" aria-hidden="true">
      <div class="absolute inset-x-0 top-0" :style="{ transform: `translateY(${PAD + (first * LINE) - scrollTop}px)` }">
        <div v-for="row in visible" :key="row.n" class="h-5 pr-2" :class="{ 'bg-redstone-900/60 text-redstone-300': row.n === errorLine }">{{ row.n }}</div>
      </div>
    </div>
    <div class="relative min-w-0 flex-1 overflow-hidden">
      <!-- Einfärbung unter dem (durchsichtigen) Text -->
      <div class="pointer-events-none absolute inset-0 overflow-hidden" aria-hidden="true">
        <div class="absolute top-0 left-0 min-w-full" :style="{ transform: `translate(${PAD - scrollLeft}px, ${PAD + first * LINE - scrollTop}px)` }">
          <div v-for="row in visible" :key="row.n" class="h-5 whitespace-pre" :class="{ 'cfg-error-line': row.n === errorLine }"><span v-for="(tok, i) in row.tokens" :key="i" :class="TOKEN_CLASS[tok.cls]">{{ tok.text }}</span></div>
        </div>
      </div>
      <textarea
        ref="area"
        v-model="model"
        class="cfg-area absolute inset-0 resize-none overflow-auto bg-transparent p-2 whitespace-pre text-transparent outline-none"
        wrap="off"
        spellcheck="false"
        autocomplete="off"
        autocapitalize="off"
        :aria-label="label"
        @scroll="onScroll"
        @keydown="onKey"
      />
    </div>
  </div>
</template>

<style scoped>
.cfg-area {
  caret-color: var(--color-base-50);
  font: inherit;
  line-height: inherit;
  tab-size: 4;
}
.cfg-area::selection {
  background: color-mix(in srgb, var(--color-redstone-500) 35%, transparent);
}
.whitespace-pre {
  tab-size: 4;
}
.cfg-error-line {
  background: color-mix(in srgb, var(--color-redstone-900) 70%, transparent);
  box-shadow: inset 2px 0 0 var(--color-redstone-500);
}
</style>
