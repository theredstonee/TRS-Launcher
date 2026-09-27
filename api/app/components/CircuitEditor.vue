<script setup lang="ts">
// Schaltungs-Editor (§25.4): Schicht-Raster (Block wählen, Richtung/Zustand setzen), isometrische Vorschau,
// Angaben (Kategorie, Schwierigkeit, Versionen) und Texte EN/DE/ES. Prüft live mit denselben Regeln wie der Server
// (shared/circuits.ts). Speichern/Veröffentlichen machen die Seiten – die Komponente liefert nur die Schaltung.
import {
  CIRCUIT_BLOCKS,
  CIRCUIT_CATEGORIES,
  CIRCUIT_LANGS,
  MAX_CIRCUIT_SIZE,
  MAX_DESC,
  MAX_NAME,
  MAX_NOTE,
  checkCircuit,
  circuitBlock,
  circuitOfGrid,
  compareVersions,
  gridOf,
  resizeGrid,
  trimGrid,
  type BlockSpec,
  type CircuitData,
  type CircuitGrid,
  type CircuitLang,
} from '#shared/circuits'

const props = withDefaults(defineProps<{ initial: CircuitData, idEditable?: boolean, readonly?: boolean }>(), { idEditable: false, readonly: false })
const emit = defineEmits<{ dirty: [boolean] }>()
const { c, fill, lang } = useCircuitText()

type TextSet = { name: string, desc: string, note: string }
const meta = reactive({ id: '', category: 'basics' as string, difficulty: 1, server: 'ok' as 'ok' | 'note', since: '', until: '' })
const texts = reactive<Record<CircuitLang, TextSet>>({ en: { name: '', desc: '', note: '' }, de: { name: '', desc: '', note: '' }, es: { name: '', desc: '', note: '' } })
/** Weitere Sprachen aus der Datei (bleiben unverändert erhalten). */
let otherTexts: Record<string, { name?: string, desc?: string, note?: string }> = {}
let tests: unknown[] | undefined
let prefer: Record<string, string> = {}
const grid = shallowRef<CircuitGrid>(gridOf({ palette: {}, layers: [['.']] }))
const layer = ref(0)
const rotation = ref(0)
const editLang = ref<CircuitLang>('en')
const baseline = ref('')

function load(d: CircuitData) {
  meta.id = d.id
  meta.category = d.category
  meta.difficulty = d.difficulty
  meta.server = d.server ?? 'ok'
  meta.since = d.since ?? ''
  meta.until = d.until ?? ''
  otherTexts = {}
  for (const l of CIRCUIT_LANGS) texts[l] = { name: d.texts?.[l]?.name ?? '', desc: d.texts?.[l]?.desc ?? '', note: d.texts?.[l]?.note ?? '' }
  for (const [l, t] of Object.entries(d.texts ?? {})) if (!(CIRCUIT_LANGS as readonly string[]).includes(l)) otherTexts[l] = t
  tests = d.tests
  prefer = { ...d.palette }
  grid.value = gridOf(d)
  layer.value = 0
  selected.value = null
  baseline.value = JSON.stringify(assemble())
  emit('dirty', false)
}

/** Aus dem Editor-Zustand die Schaltung im Client-Format bauen (Fehler beim Palette-Bau → Text). */
function assemble(): CircuitData | string {
  let body: Pick<CircuitData, 'palette' | 'layers'>
  try {
    body = circuitOfGrid(grid.value, prefer)
  } catch (e) {
    return (e as Error).message
  }
  const out: CircuitData['texts'] = { ...otherTexts }
  for (const l of CIRCUIT_LANGS) {
    const t = texts[l]
    const x: { name?: string, desc?: string, note?: string } = {}
    if (t.name.trim()) x.name = t.name.trim()
    if (t.desc.trim()) x.desc = t.desc.trim()
    if (t.note.trim()) x.note = t.note.trim()
    if (Object.keys(x).length) out[l] = x
  }
  return {
    format: 1,
    id: meta.id.trim(),
    category: meta.category as CircuitData['category'],
    difficulty: meta.difficulty as 1 | 2 | 3,
    server: meta.server,
    ...(meta.since.trim() ? { since: meta.since.trim() } : {}),
    ...(meta.until.trim() ? { until: meta.until.trim() } : {}),
    texts: out,
    ...body,
    ...(tests ? { tests } : {}),
  }
}

const current = computed(() => {
  void grid.value
  void JSON.stringify(meta)
  void JSON.stringify(texts)
  return assemble()
})
const check = computed(() => (typeof current.value === 'string' ? { ok: false as const, errors: [current.value] } : checkCircuit(current.value)))
const dirty = computed(() => JSON.stringify(current.value) !== baseline.value)
watch(dirty, (d) => emit('dirty', d))


// --- Raster bearbeiten --------------------------------------------------------------------------------
type Tool = 'paint' | 'erase' | 'pick'
const tool = ref<Tool>('paint')
const brush = reactive<BlockSpec>({ key: 'redstone_wire', props: {}, movable: false, optional: false, marker: null })
const selected = ref<{ x: number, y: number, z: number } | null>(null)

function setCell(x: number, y: number, z: number, s: BlockSpec | null) {
  const g = grid.value
  const cells = g.cells.map((l) => l.map((r) => r.slice()))
  cells[y]![z]![x] = s ? { ...s, props: { ...s.props } } : null
  grid.value = { ...g, cells }
}
const cellAt = (x: number, z: number) => grid.value.cells[layer.value]?.[z]?.[x] ?? null

let painting = false
function onCell(x: number, z: number, e: PointerEvent) {
  if (props.readonly) return
  if (e.type === 'pointerdown') painting = true
  else if (!painting) return
  const y = layer.value
  if (tool.value === 'pick' || e.altKey) {
    const s = cellAt(x, z)
    selected.value = { x, y, z }
    if (s && e.altKey) Object.assign(brush, { ...s, props: { ...s.props }, marker: null })
    return
  }
  if (tool.value === 'erase' || e.button === 2) {
    setCell(x, y, z, null)
    return
  }
  setCell(x, y, z, { ...brush, props: { ...brush.props }, marker: null })
}
function stopPaint() {
  painting = false
}
onMounted(() => window.addEventListener('pointerup', stopPaint))
onBeforeUnmount(() => window.removeEventListener('pointerup', stopPaint))

const sel = computed(() => (selected.value ? grid.value.cells[selected.value.y]?.[selected.value.z]?.[selected.value.x] ?? null : null))
function updateSel(patch: Partial<BlockSpec>) {
  const p = selected.value
  const s = sel.value
  if (!p || !s) return
  setCell(p.x, p.y, p.z, { ...s, ...patch, props: { ...(patch.props ?? s.props) } })
}
function setSelBlock(key: string) {
  const p = selected.value
  if (!p) return
  const s = sel.value
  setCell(p.x, p.y, p.z, { key, props: defaultProps(key), movable: s?.movable ?? false, optional: s?.optional ?? false, marker: s?.marker ?? null })
}

function defaultProps(key: string): Record<string, string> {
  const def = circuitBlock(key)
  const out: Record<string, string> = {}
  if (!def) return out
  for (const [k, values] of Object.entries(def.props)) {
    // Richtung zuerst nach Norden, Hebel/Knopf auf dem Boden.
    out[k] = k === 'face' ? 'floor' : values[0]!
  }
  if (key === 'lever' || key === 'stone_button') delete out.facing
  return out
}
function pickBrush(key: string) {
  Object.assign(brush, { key, props: defaultProps(key) })
  if (tool.value !== 'paint') tool.value = 'paint'
}
/** Eigenschaft setzen/entfernen (leer = Standard des Spiels). */
function withProp(propsIn: Record<string, string>, k: string, v: string): Record<string, string> {
  const out = { ...propsIn }
  if (v) out[k] = v
  else delete out[k]
  return out
}

// Größe
const sizeX = computed({ get: () => grid.value.x, set: (v: number) => resize(v, grid.value.y, grid.value.z) })
const sizeY = computed({ get: () => grid.value.y, set: (v: number) => resize(grid.value.x, v, grid.value.z) })
const sizeZ = computed({ get: () => grid.value.z, set: (v: number) => resize(grid.value.x, grid.value.y, v) })
function clampSize(v: number) {
  return Math.max(1, Math.min(MAX_CIRCUIT_SIZE, Math.round(Number(v) || 1)))
}
function resize(x: number, y: number, z: number) {
  grid.value = resizeGrid(grid.value, clampSize(x), clampSize(y), clampSize(z))
  if (layer.value >= grid.value.y) layer.value = grid.value.y - 1
  selected.value = null
}
function addLayer() {
  if (grid.value.y >= MAX_CIRCUIT_SIZE) return
  resize(grid.value.x, grid.value.y + 1, grid.value.z)
  layer.value = grid.value.y - 1
}
function trim() {
  grid.value = trimGrid(grid.value)
  layer.value = Math.min(layer.value, grid.value.y - 1)
  selected.value = null
}

// Anzeige der Felder
const ARROWS: Record<string, number> = { north: 0, east: 90, south: 180, west: 270 }
function cellStyle(s: BlockSpec | null) {
  if (!s) return {}
  const def = circuitBlock(s.key)
  return { background: def?.color ?? '#888', color: textOn(def?.color ?? '#888') }
}
function textOn(hex: string): string {
  const n = Number.parseInt(hex.slice(1), 16)
  const l = 0.299 * ((n >> 16) & 255) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)
  return l > 150 ? '#111116' : '#f3f3f8'
}
const blockName = (key: string) => c.value.blocks[key] ?? key
const cellTitle = (s: BlockSpec | null, x: number, z: number) =>
  s ? `${x}, ${layer.value}, ${z} · ${blockName(s.key)}${Object.keys(s.props).length ? ` [${Object.entries(s.props).map(([k, v]) => `${k}=${v}`).join(', ')}]` : ''}${s.marker ? ` @${s.marker}` : ''}` : `${x}, ${layer.value}, ${z} · ${c.value.adm.air}`
const cellPx = computed(() => Math.max(18, Math.min(34, Math.floor(520 / Math.max(grid.value.x, grid.value.z)))))

const effectiveSince = computed(() => (check.value.ok ? check.value.info.since : ''))
const showEffective = computed(() => !!effectiveSince.value && (!meta.since || compareVersions(effectiveSince.value, meta.since) > 0))

/** Für Import: Schaltung ersetzen, ohne die Angaben (ID, Texte) zu verlieren, wenn die Datei keine hat. */
function replaceBlocks(d: CircuitData) {
  prefer = { ...d.palette }
  grid.value = gridOf(d)
  layer.value = 0
  selected.value = null
  if (d.tests) tests = d.tests
  for (const l of CIRCUIT_LANGS) {
    const t = d.texts?.[l]
    if (t?.name && !texts[l].name) texts[l].name = t.name
    if (t?.desc && !texts[l].desc) texts[l].desc = t.desc
  }
}
function markClean() {
  baseline.value = JSON.stringify(current.value)
}

// Erst hier: load() braucht alle Zustände oben.
watch(() => props.initial, (d) => load(d), { immediate: true })

defineExpose({ current, check, dirty, load, replaceBlocks, markClean })
const langName = (l: string) => (l === 'en' ? 'English' : l === 'de' ? 'Deutsch' : 'Español')
onMounted(() => (editLang.value = (CIRCUIT_LANGS as readonly string[]).includes(lang.value) ? lang.value as CircuitLang : 'en'))
</script>

<template>
  <div class="grid gap-5 xl:grid-cols-[minmax(0,1fr)_24rem]">
    <!-- Raster + Werkzeuge -->
    <section class="min-w-0 space-y-4">
      <div class="card p-4">
        <div class="adm-toolbar">
          <div class="adm-seg" role="group" :aria-label="c.adm.blocks">
            <button v-for="t in (['paint', 'erase', 'pick'] as const)" :key="t" type="button" :aria-pressed="tool === t" :disabled="readonly" @click="tool = t">{{ c.adm.tools[t] }}</button>
          </div>
          <div class="flex items-center gap-1.5 text-xs text-base-400">
            <span>{{ c.adm.layer }}</span>
            <div class="adm-seg">
              <button v-for="n in grid.y" :key="n" type="button" class="tabular-nums" :aria-pressed="layer === n - 1" @click="layer = n - 1">{{ n }}</button>
            </div>
            <button type="button" class="btn btn-ghost px-2 py-1 text-xs" :disabled="readonly || grid.y >= MAX_CIRCUIT_SIZE" @click="addLayer"><SiteIcon name="plus" class="size-3.5" />{{ c.adm.addLayer }}</button>
          </div>
        </div>
        <div class="mt-3 flex flex-wrap items-end gap-3 text-xs">
          <label class="size-field">{{ c.adm.width }}<input v-model.lazy.number="sizeX" type="number" min="1" :max="MAX_CIRCUIT_SIZE" class="field" :disabled="readonly" /></label>
          <label class="size-field">{{ c.adm.height }}<input v-model.lazy.number="sizeY" type="number" min="1" :max="MAX_CIRCUIT_SIZE" class="field" :disabled="readonly" /></label>
          <label class="size-field">{{ c.adm.depth }}<input v-model.lazy.number="sizeZ" type="number" min="1" :max="MAX_CIRCUIT_SIZE" class="field" :disabled="readonly" /></label>
          <button type="button" class="btn btn-ghost px-2.5 py-1.5 text-xs" :disabled="readonly" @click="trim"><SiteIcon name="fit" class="size-3.5" />{{ c.adm.trim }}</button>
        </div>

        <div class="grid-wrap mt-4" @contextmenu.prevent>
          <p class="compass">N ↑</p>
          <div class="cell-grid" :style="{ gridTemplateColumns: `repeat(${grid.x}, ${cellPx}px)` }" role="grid" :aria-label="`${c.adm.layer} ${layer + 1}`">
            <template v-for="(row, z) in grid.cells[layer]" :key="z">
              <button
                v-for="(s, x) in row"
                :key="`${x}-${z}`"
                type="button"
                class="cell"
                :class="{ sel: selected && selected.x === x && selected.z === z && selected.y === layer, below: !s && layer > 0 && grid.cells[layer - 1]?.[z]?.[x] }"
                :style="{ ...cellStyle(s), width: `${cellPx}px`, height: `${cellPx}px` }"
                :title="cellTitle(s, x, z)"
                @pointerdown.prevent="onCell(x, z, $event)"
                @pointerenter="onCell(x, z, $event)"
              >
                <template v-if="s">
                  <span v-if="s.props.facing && ARROWS[s.props.facing] !== undefined" class="arrow" :style="{ transform: `rotate(${ARROWS[s.props.facing]}deg)` }">↑</span>
                  <span v-else-if="s.props.facing === 'up'" class="glyph">⊙</span>
                  <span v-else-if="s.props.facing === 'down'" class="glyph">⊗</span>
                  <span v-else class="glyph">{{ circuitBlock(s.key)?.glyph }}</span>
                  <span v-if="s.marker" class="mark">{{ s.marker }}</span>
                  <span v-if="s.props.delay" class="delay">{{ s.props.delay }}</span>
                </template>
              </button>
            </template>
          </div>
        </div>
        <p class="mt-2 text-xs text-base-400">{{ c.adm.ghostHint }}</p>
      </div>

      <!-- Pinsel -->
      <div v-if="!readonly" class="card p-4">
        <h3 class="section-title">{{ c.adm.brush }}</h3>
        <div class="mt-3 flex flex-wrap gap-1.5">
          <button
            v-for="b in CIRCUIT_BLOCKS"
            :key="b.key"
            type="button"
            class="block-btn"
            :aria-pressed="brush.key === b.key"
            :title="blockName(b.key)"
            @click="pickBrush(b.key)"
          >
            <span class="sw" :style="{ background: b.color }" />{{ blockName(b.key) }}
          </button>
        </div>
        <div v-if="Object.keys(circuitBlock(brush.key)?.props ?? {}).length" class="mt-3 flex flex-wrap gap-3">
          <label v-for="(values, k) in circuitBlock(brush.key)!.props" :key="k" class="text-xs text-base-400">
            {{ c.props[k] ?? k }}
            <select class="field mt-1 py-1.5" :value="brush.props[k] ?? ''" @change="brush.props = withProp(brush.props, k, ($event.target as HTMLSelectElement).value)">
              <option value="">–</option>
              <option v-for="v in values" :key="v" :value="v">{{ c.props[v] ?? v }}</option>
            </select>
          </label>
        </div>
        <div class="mt-3 flex flex-wrap gap-4 text-xs text-base-300">
          <label class="flex items-center gap-1.5"><input v-model="brush.movable" type="checkbox" />{{ c.adm.movable }}</label>
          <label class="flex items-center gap-1.5"><input v-model="brush.optional" type="checkbox" />{{ c.adm.optional }}</label>
        </div>
      </div>

      <!-- Ausgewähltes Feld -->
      <div class="card p-4">
        <p v-if="!selected" class="text-sm text-base-400">{{ c.adm.noSelection }}</p>
        <template v-else>
          <h3 class="section-title">{{ fill(c.adm.selected, selected) }}</h3>
          <div class="mt-3 flex flex-wrap items-end gap-3">
            <label class="text-xs text-base-400">
              {{ c.adm.blocks }}
              <select class="field mt-1 py-1.5" :value="sel?.key ?? ''" :disabled="readonly" @change="(($event.target as HTMLSelectElement).value ? setSelBlock(($event.target as HTMLSelectElement).value) : setCell(selected.x, selected.y, selected.z, null))">
                <option value="">{{ c.adm.air }}</option>
                <option v-for="b in CIRCUIT_BLOCKS" :key="b.key" :value="b.key">{{ blockName(b.key) }}</option>
              </select>
            </label>
            <template v-if="sel">
              <label v-for="(values, k) in circuitBlock(sel.key)?.props ?? {}" :key="k" class="text-xs text-base-400">
                {{ c.props[k] ?? k }}
                <select class="field mt-1 py-1.5" :value="sel.props[k] ?? ''" :disabled="readonly" @change="updateSel({ props: withProp(sel.props, k, ($event.target as HTMLSelectElement).value) })">
                  <option value="">–</option>
                  <option v-for="v in values" :key="v" :value="v">{{ c.props[v] ?? v }}</option>
                </select>
              </label>
              <label class="text-xs text-base-400">
                {{ c.adm.marker }}
                <input class="field mt-1 w-24 py-1.5" maxlength="8" :value="sel.marker ?? ''" :disabled="readonly" @change="updateSel({ marker: ($event.target as HTMLInputElement).value.replace(/[^A-Za-z0-9]/g, '').slice(0, 8) || null })" />
              </label>
            </template>
          </div>
          <div v-if="sel" class="mt-3 flex flex-wrap gap-4 text-xs text-base-300">
            <label class="flex items-center gap-1.5"><input type="checkbox" :checked="sel.movable" :disabled="readonly" @change="updateSel({ movable: ($event.target as HTMLInputElement).checked })" />{{ c.adm.movable }}</label>
            <label class="flex items-center gap-1.5"><input type="checkbox" :checked="sel.optional" :disabled="readonly" @change="updateSel({ optional: ($event.target as HTMLInputElement).checked })" />{{ c.adm.optional }}</label>
          </div>
        </template>
      </div>
    </section>

    <!-- Vorschau, Angaben, Texte -->
    <aside class="space-y-4">
      <div class="card stage overflow-hidden p-2">
        <CircuitIso v-model:layer="layer" v-model:rotation="rotation" :grid="grid" :height="300" controls markers :highlight="selected" :label="c.adm.preview" />
      </div>

      <div class="rounded-lg border px-3 py-2 text-sm" :class="check.ok ? 'border-ok/40 text-ok' : 'border-redstone-600/60 text-redstone-300'" role="status">
        <template v-if="check.ok">{{ fill(c.adm.valid, { n: check.info.blockCount, ...check.info.size }) }}</template>
        <template v-else>
          <p class="font-semibold">{{ c.adm.errors }}</p>
          <ul class="mt-1 list-disc space-y-0.5 pl-4 text-xs">
            <li v-for="e in check.errors" :key="e">{{ e }}</li>
          </ul>
        </template>
      </div>

      <div class="card space-y-3 p-4">
        <h3 class="section-title">{{ c.adm.meta }}</h3>
        <label class="block text-xs text-base-400">
          {{ c.adm.id }}
          <input v-model="meta.id" class="field adm-mono mt-1" maxlength="48" :disabled="!idEditable || readonly" pattern="[a-z0-9_]+" />
          <span v-if="idEditable" class="mt-0.5 block text-[11px]">{{ c.adm.idHint }}</span>
        </label>
        <div class="grid grid-cols-2 gap-3">
          <label class="text-xs text-base-400">{{ c.adm.category }}
            <select v-model="meta.category" class="field mt-1" :disabled="readonly">
              <option v-for="cat in CIRCUIT_CATEGORIES" :key="cat" :value="cat">{{ c.categories[cat] }}</option>
            </select>
          </label>
          <label class="text-xs text-base-400">{{ c.adm.difficulty }}
            <select v-model.number="meta.difficulty" class="field mt-1" :disabled="readonly">
              <option v-for="d in [1, 2, 3]" :key="d" :value="d">{{ c.difficulty[d] }}</option>
            </select>
          </label>
          <label class="text-xs text-base-400">{{ c.adm.since }}
            <input v-model="meta.since" class="field mt-1" maxlength="16" placeholder="1.8" :disabled="readonly" />
          </label>
          <label class="text-xs text-base-400">{{ c.adm.until }}
            <input v-model="meta.until" class="field mt-1" maxlength="16" :disabled="readonly" />
          </label>
        </div>
        <p class="text-[11px] text-base-400">{{ c.adm.versionHint }}<template v-if="showEffective"> · {{ fill(c.adm.effective, { v: effectiveSince }) }}</template></p>
        <label class="text-xs text-base-400">{{ c.adm.server }}
          <select v-model="meta.server" class="field mt-1" :disabled="readonly">
            <option value="ok">{{ c.adm.serverOk }}</option>
            <option value="note">{{ c.adm.serverNote }}</option>
          </select>
        </label>
        <p v-if="tests?.length" class="text-[11px] text-base-400">{{ fill(c.adm.tests, { n: tests.length }) }}</p>
      </div>

      <div class="card space-y-3 p-4">
        <div class="flex items-center justify-between gap-2">
          <h3 class="section-title">{{ c.adm.texts }}</h3>
          <div class="adm-seg">
            <button v-for="l in CIRCUIT_LANGS" :key="l" type="button" :aria-pressed="editLang === l" @click="editLang = l">
              {{ l.toUpperCase() }}<span v-if="!texts[l].name" class="text-redstone-300">*</span>
            </button>
          </div>
        </div>
        <label class="block text-xs text-base-400">{{ c.adm.name }} ({{ langName(editLang) }})
          <input v-model="texts[editLang].name" class="field mt-1" :maxlength="MAX_NAME" :disabled="readonly" />
        </label>
        <label class="block text-xs text-base-400">{{ c.adm.desc }}
          <textarea v-model="texts[editLang].desc" class="field mt-1 min-h-32" :maxlength="MAX_DESC" :disabled="readonly" />
        </label>
        <label v-if="meta.server === 'note'" class="block text-xs text-base-400">{{ c.adm.note }}
          <textarea v-model="texts[editLang].note" class="field mt-1 min-h-16" :maxlength="MAX_NOTE" :disabled="readonly" />
        </label>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.stage {
  background-color: var(--color-base-950);
  background-image: var(--deepslate);
  background-size: 48px 48px;
}
.size-field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  color: var(--color-base-400);
}
.size-field .field {
  width: 5.5rem;
  padding-top: 0.35rem;
  padding-bottom: 0.35rem;
}
.grid-wrap {
  overflow: auto;
  padding: 0.5rem;
  border-radius: 0.5rem;
  background: var(--color-base-950);
  touch-action: none;
}
.compass {
  font-size: 0.7rem;
  color: var(--color-base-400);
  margin-bottom: 0.25rem;
}
.cell-grid {
  display: grid;
  gap: 2px;
  width: max-content;
}
.cell {
  position: relative;
  display: grid;
  place-items: center;
  border-radius: 3px;
  background: var(--color-base-850);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.04);
  font-size: 0.7rem;
  font-weight: 700;
  line-height: 1;
  user-select: none;
}
.cell:hover {
  box-shadow: inset 0 0 0 2px var(--color-lamp-400);
}
.cell.below {
  background: color-mix(in srgb, var(--color-base-700) 60%, var(--color-base-850));
}
.cell.sel {
  outline: 2px solid var(--color-lamp-400);
  outline-offset: 1px;
  z-index: 1;
}
.arrow {
  display: inline-block;
  font-size: 0.85rem;
}
.mark {
  position: absolute;
  top: -1px;
  right: 1px;
  font-size: 0.55rem;
  color: #ffd48a;
  text-shadow: 0 0 2px #000;
}
.delay {
  position: absolute;
  bottom: 0;
  left: 2px;
  font-size: 0.55rem;
}
.block-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  border-radius: 0.4rem;
  border: 1px solid var(--color-base-800);
  padding: 0.25rem 0.5rem;
  font-size: 0.75rem;
  color: var(--color-base-200);
  background: var(--color-base-900);
}
.block-btn[aria-pressed='true'] {
  border-color: var(--color-redstone-500);
  color: var(--color-base-50);
  background: color-mix(in srgb, var(--color-redstone-500) 14%, var(--color-base-900));
}
.sw {
  width: 0.75rem;
  height: 0.75rem;
  border-radius: 2px;
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.35);
}
</style>
