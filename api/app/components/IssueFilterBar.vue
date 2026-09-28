<script setup lang="ts">
// Filter für Issue-Liste und Roadmap (§28): ein Feld mit Suchsyntax (`status:geplant area:client author:Alex …`)
// und Aufklapp-Filter, die das Feld mitschreiben. Gefiltert wird serverseitig; hier wird nur geparst, um die
// Häkchen zu setzen (derselbe Parser wie auf dem Server, shared/issue-query.ts).
import {
  AREA_VALUES,
  PRIORITY_VALUES,
  STATUS_VALUES,
  TYPE_VALUES,
  formatIssueQuery,
  parseIssueQuery,
  type IssueQuery,
} from '#shared/issue-query'

const props = withDefaults(defineProps<{ modelValue: string, errors?: string[], people?: string[] }>(), { errors: () => [], people: () => [] })
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const { it, lang, fill } = useIssueText()

const text = ref(props.modelValue)
watch(() => props.modelValue, (v) => {
  if (v !== text.value) text.value = v
})
let timer: ReturnType<typeof setTimeout> | null = null
function onInput() {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => emit('update:modelValue', text.value.trim()), 350)
}
function applyNow() {
  if (timer) clearTimeout(timer)
  emit('update:modelValue', text.value.trim())
}
const parsed = computed<IssueQuery>(() => parseIssueQuery(text.value))
function write(q: IssueQuery) {
  text.value = formatIssueQuery(q, lang.value)
  applyNow()
}

type ListKey = 'status' | 'priority' | 'type' | 'area' | 'author' | 'assignee'
const open = ref<ListKey | 'help' | null>(null)
const bar = shallowRef<HTMLElement | null>(null)
function toggleMenu(k: ListKey | 'help') {
  open.value = open.value === k ? null : k
}
function onDoc(e: PointerEvent) {
  if (open.value && bar.value && !bar.value.contains(e.target as Node)) open.value = null
}
function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && open.value) {
    open.value = null
    e.stopPropagation()
  }
}
onMounted(() => {
  document.addEventListener('pointerdown', onDoc)
  document.addEventListener('keydown', onKey, true)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDoc)
  document.removeEventListener('keydown', onKey, true)
})

function has(k: ListKey, v: string): boolean {
  return (parsed.value[k] as string[]).includes(v)
}
function toggleValue(k: ListKey, v: string) {
  const q = parseIssueQuery(text.value)
  const list = q[k] as string[]
  const i = list.indexOf(v)
  if (i >= 0) list.splice(i, 1)
  else list.push(v)
  write(q)
}
const name = ref('')
function addName(k: 'author' | 'assignee') {
  const n = name.value.trim().replace(/^@/, '')
  if (!/^[A-Za-z0-9_]{1,16}$/.test(n)) return
  if (!has(k, n)) toggleValue(k, n)
  name.value = ''
}
function reset() {
  text.value = ''
  applyNow()
}

const menus = computed(() => [
  { k: 'status' as const, label: it.value.filter.status, values: STATUS_VALUES.map((v) => ({ v, label: it.value.statuses[v]! })) },
  { k: 'priority' as const, label: it.value.filter.priority, values: PRIORITY_VALUES.map((v) => ({ v, label: it.value.priorities[v]! })) },
  { k: 'type' as const, label: it.value.filter.type, values: TYPE_VALUES.map((v) => ({ v, label: it.value.types[v]! })) },
  { k: 'area' as const, label: it.value.filter.area, values: AREA_VALUES.map((v) => ({ v, label: it.value.areas[v]! })) },
])
const count = (k: ListKey) => (parsed.value[k] as string[]).length
const peopleList = computed(() => [...new Set(props.people)].slice(0, 8))
</script>

<template>
  <div ref="bar" class="filterbar">
    <div class="relative">
      <SiteIcon name="search" class="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-base-400" />
      <input
        v-model="text"
        type="search"
        class="field filter-input"
        :placeholder="it.filter.placeholder"
        :aria-label="it.filter.label"
        maxlength="300"
        spellcheck="false"
        autocomplete="off"
        data-testid="issue-filter"
        @input="onInput"
        @keydown.enter.prevent="applyNow"
      />
      <button type="button" class="help-btn" :aria-expanded="open === 'help'" :aria-label="it.filter.help" :title="it.filter.help" @click="toggleMenu('help')">?</button>
      <div v-if="open === 'help'" class="pop help-pop" role="dialog" :aria-label="it.filter.help">
        <p class="text-xs font-semibold text-base-50">{{ it.filter.help }}</p>
        <p class="mt-1 text-xs text-base-400">{{ it.filter.helpText }}</p>
        <ul class="mt-2 space-y-1">
          <li v-for="(l, i) in it.filter.helpLines" :key="i"><code>{{ l }}</code></li>
        </ul>
      </div>
    </div>

    <div class="mt-2 flex flex-wrap items-center gap-1.5">
      <div v-for="menu in menus" :key="menu.k" class="relative">
        <button type="button" class="drop" :class="{ on: count(menu.k) }" :aria-expanded="open === menu.k" @click="toggleMenu(menu.k)">
          {{ menu.label }}<span v-if="count(menu.k)" class="n">{{ count(menu.k) }}</span><SiteIcon name="chevron" class="size-3.5" />
        </button>
        <div v-if="open === menu.k" class="pop" role="group" :aria-label="menu.label">
          <label v-for="o in menu.values" :key="o.v" class="opt">
            <input type="checkbox" :checked="has(menu.k, o.v)" @change="toggleValue(menu.k, o.v)" />
            <IssueStatus v-if="menu.k === 'status'" :status="o.v as never" />
            <IssuePriority v-else-if="menu.k === 'priority'" :priority="o.v === 'none' ? null : (o.v as never)" />
            <IssueArea v-else-if="menu.k === 'area'" :area="o.v as never" />
            <span v-else class="inline-flex items-center gap-1.5"><SiteIcon :name="o.v === 'bug' ? 'bug' : 'bolt'" class="size-3.5" :class="o.v === 'bug' ? 'text-redstone-300' : 'text-lamp-300'" />{{ o.label }}</span>
          </label>
        </div>
      </div>
      <div v-for="k in (['author', 'assignee'] as const)" :key="k" class="relative">
        <button type="button" class="drop" :class="{ on: count(k) }" :aria-expanded="open === k" @click="toggleMenu(k); name = ''">
          {{ k === 'author' ? it.filter.author : it.filter.assignee }}<span v-if="count(k)" class="n">{{ count(k) }}</span><SiteIcon name="chevron" class="size-3.5" />
        </button>
        <div v-if="open === k" class="pop w-60">
          <form class="flex gap-1.5" @submit.prevent="addName(k)">
            <input v-model="name" class="field py-1.5 text-xs" maxlength="16" :placeholder="it.filter.namePlaceholder" :aria-label="it.filter.namePlaceholder" />
            <button type="submit" class="btn btn-ghost px-2 py-1 text-xs">{{ it.filter.add }}</button>
          </form>
          <template v-if="k === 'assignee'">
            <label class="opt"><input type="checkbox" :checked="has(k, 'me')" @change="toggleValue(k, 'me')" />{{ it.filter.me }}</label>
            <label class="opt"><input type="checkbox" :checked="has(k, 'none')" @change="toggleValue(k, 'none')" />{{ it.filter.none }}</label>
          </template>
          <label v-for="p in [...new Set([...(parsed[k] as string[]).filter((x) => x !== 'me' && x !== 'none'), ...peopleList])]" :key="p" class="opt">
            <input type="checkbox" :checked="has(k, p)" @change="toggleValue(k, p)" />{{ p }}
          </label>
        </div>
      </div>
      <slot />
      <button v-if="text" type="button" class="ml-1 text-xs text-base-400 underline-offset-4 hover:text-base-100 hover:underline" @click="reset">{{ it.filter.reset }}</button>
    </div>
    <p v-if="errors.length" class="mt-2 text-xs text-lamp-300" role="status">{{ fill(it.filter.errors, { list: errors.join(', ') }) }}</p>
  </div>
</template>

<style scoped>
.filter-input {
  padding-left: 2.25rem;
  padding-right: 2.5rem;
  font-family: var(--font-mono);
  font-size: 0.8rem;
}
.help-btn {
  position: absolute;
  top: 50%;
  right: 0.5rem;
  width: 1.5rem;
  height: 1.5rem;
  transform: translateY(-50%);
  border-radius: 0.35rem;
  border: 1px solid var(--color-base-700);
  font-size: 0.75rem;
  font-weight: 700;
  color: var(--color-base-400);
}
.help-btn:hover,
.help-btn[aria-expanded='true'] {
  color: var(--color-base-50);
  border-color: var(--color-base-600);
}
.drop {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.35rem 0.6rem;
  border-radius: 0.45rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-900);
  font-size: 0.8rem;
  color: var(--color-base-200);
  transition: border-color 0.12s, background-color 0.12s;
}
.drop:hover,
.drop[aria-expanded='true'] {
  border-color: var(--color-base-600);
  color: var(--color-base-50);
}
.drop.on {
  border-color: color-mix(in srgb, var(--color-redstone-500) 60%, transparent);
  background: color-mix(in srgb, var(--color-redstone-500) 10%, var(--color-base-900));
}
.n {
  min-width: 1.1rem;
  padding: 0 0.3rem;
  border-radius: 999px;
  background: var(--color-redstone-500);
  font-size: 10px;
  line-height: 1rem;
  text-align: center;
  color: white;
}
.pop {
  position: absolute;
  top: calc(100% + 0.35rem);
  left: 0;
  z-index: 30;
  min-width: 12rem;
  padding: 0.4rem;
  border-radius: 0.6rem;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-850);
  box-shadow: 0 16px 40px -12px rgb(0 0 0 / 0.7);
}
.help-pop {
  left: auto;
  right: 0;
  width: min(24rem, 90vw);
  padding: 0.75rem;
}
.help-pop code {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--color-lamp-300);
}
.opt {
  display: flex;
  align-items: center;
  gap: 0.55rem;
  padding: 0.35rem 0.45rem;
  border-radius: 0.35rem;
  font-size: 0.8rem;
  color: var(--color-base-200);
  cursor: pointer;
}
.opt:hover {
  background: var(--color-base-800);
}
.opt input {
  accent-color: var(--color-redstone-500);
}
</style>
