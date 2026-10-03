<script setup lang="ts">
import type { ControlAction, ControlButton, ControlLayout, ShowMode, Special, StoredLayout } from '~/utils/controls'
import type { MessageKey } from '~/utils/i18n'
import {
  MAX_BUTTONS,
  MAX_LABEL_CHARS,
  MAX_NAME_CHARS,
  MAX_SENSITIVITY,
  MIN_OPACITY,
  MIN_SENSITIVITY,
  buttonLabelFor,
  addButton,
  defaultShow,
  iconNames,
  joystickModes,
  keyName,
  layoutIssue,
  layoutName,
  mouseButtonKeys,
  pickableKeys,
  removeButton,
  specialName,
  specials,
  updateButton,
} from '~/utils/controls'

// Editor für ein Layout auf einem Handy-Bildschirm (gleiches JSON wie im
// Spiel): Knöpfe ziehen/vergrößern, Aktion, Symbol, Deckkraft, Gesten.
const props = defineProps<{ stored: StoredLayout; busy?: boolean }>()
const emit = defineEmits<{ save: [layout: ControlLayout]; close: [] }>()

const layout = ref<ControlLayout>(structuredClone(toRaw(props.stored.layout)))
const initial = JSON.stringify(props.stored.layout)
const dirty = computed(() => JSON.stringify(layout.value) !== initial)
const selectedId = ref<string | null>(null)
const grid = ref(true)
const notch = ref(false)
const confirmDiscard = ref(false)
const error = ref<string | null>(null)

const selected = computed(() => layout.value.buttons.find((b) => b.id === selectedId.value) ?? null)
const title = computed(() => t('controls.editor.title', { name: layoutName(props.stored) }))

type ActionType = ControlAction['type']
const actionTypes: { value: ActionType; label: MessageKey }[] = [
  { value: 'key', label: 'controls.action.types.key' },
  { value: 'mouse', label: 'controls.action.types.mouse' },
  { value: 'toggle', label: 'controls.action.types.toggle' },
  { value: 'joystick', label: 'controls.action.types.joystick' },
  { value: 'special', label: 'controls.action.types.special' },
]

const keys = computed(() => pickableKeys.map((code) => ({ code, name: keyName(code) ?? `#${code}` })))

function patch(p: Partial<Omit<ControlButton, 'id'>>) {
  if (!selected.value) return
  layout.value = updateButton(layout.value, selected.value.id, p)
  error.value = null
}

function defaultAction(type: ActionType): ControlAction {
  switch (type) {
    case 'key':
      return { type: 'key', key: 32 }
    case 'mouse':
      return { type: 'mouse', button: 0 }
    case 'toggle':
      return { type: 'toggle', key: 340 }
    case 'joystick':
      return { type: 'joystick', mode: 'wasd' }
    case 'special':
      return { type: 'special', special: 'keyboard' }
  }
}

function setActionType(type: ActionType) {
  const action = defaultAction(type)
  patch({ action, toggle: undefined })
}

function setAction(action: ControlAction) {
  patch({ action })
}

const chordKey = computed(() => {
  const a = selected.value?.action
  return a && (a.type === 'key' || a.type === 'mouse') ? (a.chord?.[0] ?? 0) : 0
})

function setChord(code: number) {
  const a = selected.value?.action
  if (!a || (a.type !== 'key' && a.type !== 'mouse')) return
  const chord = code && !(a.type === 'key' && a.key === code) ? [code] : undefined
  setAction({ ...a, chord })
}

function setLabel(value: string) {
  const label = [...value].slice(0, MAX_LABEL_CHARS).join('')
  patch({ label: label.trim() ? label : undefined })
}

function add() {
  const r = addButton(layout.value)
  if (!r.id) return
  layout.value = r.layout
  selectedId.value = r.id
}

function remove() {
  if (!selected.value) return
  layout.value = removeButton(layout.value, selected.value.id)
  selectedId.value = null
}

function relabel() {
  if (selected.value) setLabel(buttonLabelFor(selected.value.action))
}

function save() {
  layout.value = { ...layout.value, name: layout.value.name.trim() }
  const issue = layoutIssue(layout.value)
  if (issue) {
    error.value = t('controls.editor.invalid', { reason: issue })
    return
  }
  emit('save', layout.value)
}

function cancel() {
  if (dirty.value && !confirmDiscard.value) {
    confirmDiscard.value = true
    return
  }
  emit('close')
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') cancel()
  if ((e.key === 'Delete' || e.key === 'Backspace') && selected.value && !(e.target instanceof HTMLInputElement || e.target instanceof HTMLSelectElement)) {
    remove()
  }
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

const showKeys: Record<ShowMode, MessageKey> = {
  game: 'controls.editor.showGame',
  menu: 'controls.editor.showMenu',
  always: 'controls.editor.showAlways',
}
const showLabel = (b: ControlButton) => (b.show ? b.show : 'auto')
function setShow(value: string) {
  patch({ show: value === 'auto' ? undefined : (value as ControlButton['show']) })
}
const autoShowText = computed(() => {
  if (!selected.value) return ''
  const mode = defaultShow(selected.value.action)
  return t('controls.editor.showAutoIs', { mode: t(showKeys[mode]) })
})
</script>

<template>
  <div class="fixed inset-0 z-50 flex flex-col bg-base-950" role="dialog" aria-modal="true" :aria-label="title" data-testid="controls-editor">
    <header class="flex flex-wrap items-center gap-2 border-b border-base-800 bg-base-900 px-3 py-2 pt-[max(0.5rem,env(safe-area-inset-top))]">
      <h2 class="min-w-0 flex-1 truncate font-semibold">{{ title }}</h2>
      <label class="flex items-center gap-2 text-xs text-base-400">
        {{ t('controls.editor.grid') }}
        <ToggleSwitch v-model="grid" :label="t('controls.editor.grid')" />
      </label>
      <label class="flex items-center gap-2 text-xs text-base-400">
        {{ t('controls.editor.notch') }}
        <ToggleSwitch v-model="notch" :label="t('controls.editor.notch')" />
      </label>
      <button class="btn btn-ghost" :disabled="layout.buttons.length >= MAX_BUTTONS" :title="t('controls.editor.maxButtons', { max: MAX_BUTTONS })" @click="add">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5"><path :d="icons.plus" /></svg>
        {{ t('controls.editor.add') }}
      </button>
      <button class="btn btn-ghost" @click="cancel">{{ confirmDiscard ? t('controls.editor.discard') : t('common.actions.cancel') }}</button>
      <button class="btn btn-primary" :disabled="busy || !dirty" @click="save">{{ t('common.actions.save') }}</button>
    </header>

    <p v-if="error" role="alert" class="border-b border-redstone-600/50 bg-redstone-900/40 px-4 py-2 text-sm text-redstone-300">{{ error }}</p>

    <div class="min-h-0 flex-1 overflow-y-auto p-3 lg:flex lg:gap-4">
      <div class="min-w-0 flex-1">
        <ControlsScreen
          :layout="layout"
          editable
          :selected="selectedId"
          :grid="grid"
          :notch="notch"
          :label="title"
          @select="selectedId = $event"
          @change="layout = $event"
        />
        <p class="mt-2 text-xs text-base-400">{{ t('controls.editor.selectHint') }}</p>
      </div>

      <aside class="mt-3 space-y-4 lg:mt-0 lg:w-80 lg:shrink-0">
        <!-- Gewählter Knopf -->
        <section v-if="selected" class="card space-y-3 p-4" :aria-label="t('controls.editor.button')">
          <div class="flex items-center justify-between gap-2">
            <h3 class="text-sm font-semibold">{{ t('controls.editor.button') }}</h3>
            <button class="btn btn-danger" @click="remove">
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path :d="icons.trash" /></svg>
              {{ t('controls.editor.remove') }}
            </button>
          </div>

          <label class="block text-xs text-base-400">
            {{ t('controls.editor.actionType') }}
            <select class="field mt-1 w-full" :value="selected.action.type" @change="setActionType(($event.target as HTMLSelectElement).value as ActionType)">
              <option v-for="a in actionTypes" :key="a.value" :value="a.value">{{ t(a.label) }}</option>
            </select>
          </label>

          <label v-if="selected.action.type === 'key' || selected.action.type === 'toggle'" class="block text-xs text-base-400">
            {{ t('controls.editor.key') }}
            <select
              class="field mt-1 w-full"
              :value="selected.action.key"
              @change="setAction({ ...selected.action, key: Number(($event.target as HTMLSelectElement).value) } as ControlAction)"
            >
              <option v-for="k in keys" :key="k.code" :value="k.code">{{ k.name }}</option>
            </select>
          </label>

          <label v-if="selected.action.type === 'mouse'" class="block text-xs text-base-400">
            {{ t('controls.editor.mouseButton') }}
            <select
              class="field mt-1 w-full"
              :value="selected.action.button"
              @change="setAction({ ...selected.action, button: Number(($event.target as HTMLSelectElement).value) } as ControlAction)"
            >
              <option v-for="(m, i) in mouseButtonKeys" :key="m" :value="i">{{ t(m) }}</option>
            </select>
          </label>

          <label v-if="selected.action.type === 'key' || selected.action.type === 'mouse'" class="block text-xs text-base-400">
            {{ t('controls.editor.chord') }}
            <select class="field mt-1 w-full" :value="chordKey" @change="setChord(Number(($event.target as HTMLSelectElement).value))">
              <option :value="0">{{ t('controls.editor.chordNone') }}</option>
              <option v-for="k in keys" :key="k.code" :value="k.code">{{ k.name }}</option>
            </select>
          </label>

          <label v-if="selected.action.type === 'joystick'" class="block text-xs text-base-400">
            {{ t('controls.editor.mode') }}
            <select class="field mt-1 w-full" :value="selected.action.mode" @change="setAction({ type: 'joystick', mode: ($event.target as HTMLSelectElement).value as 'wasd' | 'camera' })">
              <option v-for="m in joystickModes" :key="m" :value="m">{{ m === 'wasd' ? t('controls.action.joystickMove') : t('controls.action.joystickCamera') }}</option>
            </select>
          </label>

          <label v-if="selected.action.type === 'special'" class="block text-xs text-base-400">
            {{ t('controls.editor.special') }}
            <select class="field mt-1 w-full" :value="selected.action.special" @change="setAction({ type: 'special', special: ($event.target as HTMLSelectElement).value as Special })">
              <option v-for="s in specials" :key="s" :value="s">{{ specialName(s) }}</option>
            </select>
          </label>

          <div class="flex items-end gap-2">
            <label class="block min-w-0 flex-1 text-xs text-base-400">
              {{ t('controls.editor.label') }}
              <input class="field mt-1 w-full" :value="selected.label ?? ''" :maxlength="MAX_LABEL_CHARS" @input="setLabel(($event.target as HTMLInputElement).value)" />
            </label>
            <button class="btn btn-ghost" :title="t('controls.editor.labelFromAction')" @click="relabel">{{ t('controls.editor.labelFromAction') }}</button>
          </div>

          <div>
            <p class="text-xs text-base-400">{{ t('controls.editor.icon') }}</p>
            <div class="mt-1 grid grid-cols-7 gap-1">
              <button
                type="button"
                class="grid h-9 place-items-center rounded-md border text-[10px]"
                :class="!selected.icon ? 'border-redstone-500 bg-redstone-900/40' : 'border-base-700 bg-base-900'"
                :aria-pressed="!selected.icon"
                :disabled="!selected.label"
                :title="t('controls.editor.noIcon')"
                @click="patch({ icon: undefined })"
              >
                Aa
              </button>
              <button
                v-for="name in iconNames"
                :key="name"
                type="button"
                class="grid h-9 place-items-center rounded-md border text-base-50"
                :class="selected.icon === name ? 'border-redstone-500 bg-redstone-900/40' : 'border-base-700 bg-base-900'"
                :aria-pressed="selected.icon === name"
                :aria-label="name"
                :title="name"
                @click="patch({ icon: name })"
              >
                <ControlsIcon :icon="name" class="size-5" />
              </button>
            </div>
          </div>

          <div class="flex gap-2">
            <button
              v-for="s in (['round', 'rect'] as const)"
              :key="s"
              type="button"
              class="btn flex-1"
              :class="selected.shape === s ? 'btn-primary' : 'btn-ghost'"
              :aria-pressed="selected.shape === s"
              @click="patch({ shape: s })"
            >
              {{ s === 'round' ? t('controls.editor.round') : t('controls.editor.rect') }}
            </button>
          </div>

          <label class="block text-xs text-base-400">
            {{ t('controls.editor.opacity') }} · {{ Math.round(selected.opacity * 100) }} %
            <input
              type="range"
              class="mt-1 w-full accent-redstone-500"
              :min="MIN_OPACITY"
              max="1"
              step="0.05"
              :value="selected.opacity"
              @input="patch({ opacity: Number(($event.target as HTMLInputElement).value) })"
            />
          </label>

          <label v-if="selected.action.type === 'key' || selected.action.type === 'mouse'" class="flex items-center justify-between gap-3 text-sm">
            {{ t('controls.editor.toggle') }}
            <ToggleSwitch :model-value="!!selected.toggle" :label="t('controls.editor.toggle')" @update:model-value="patch({ toggle: $event || undefined })" />
          </label>
          <label v-if="selected.action.type !== 'joystick'" class="flex items-center justify-between gap-3 text-sm">
            {{ t('controls.editor.passThrough') }}
            <ToggleSwitch :model-value="!!selected.passThrough" :label="t('controls.editor.passThrough')" @update:model-value="patch({ passThrough: $event || undefined })" />
          </label>

          <label class="block text-xs text-base-400">
            {{ t('controls.editor.show') }}
            <select class="field mt-1 w-full" :value="showLabel(selected)" @change="setShow(($event.target as HTMLSelectElement).value)">
              <option value="auto">{{ autoShowText }}</option>
              <option value="game">{{ t('controls.editor.showGame') }}</option>
              <option value="menu">{{ t('controls.editor.showMenu') }}</option>
              <option value="always">{{ t('controls.editor.showAlways') }}</option>
            </select>
          </label>
        </section>

        <!-- Layout + Gesten -->
        <section class="card space-y-3 p-4" :aria-label="t('controls.editor.gestures')">
          <label v-if="!stored.builtin" class="block text-xs text-base-400">
            {{ t('controls.editor.name') }}
            <input v-model="layout.name" class="field mt-1 w-full" :maxlength="MAX_NAME_CHARS" />
          </label>
          <h3 class="text-sm font-semibold">{{ t('controls.editor.gestures') }}</h3>
          <SettingRow :title="t('controls.editor.tapAttack')" :description="t('controls.editor.tapAttackHint')">
            <ToggleSwitch v-model="layout.gestures.tapAttack" :label="t('controls.editor.tapAttack')" />
          </SettingRow>
          <SettingRow :title="t('controls.editor.holdUse')" :description="t('controls.editor.holdUseHint')">
            <ToggleSwitch v-model="layout.gestures.holdUse" :label="t('controls.editor.holdUse')" />
          </SettingRow>
          <SettingRow :title="t('controls.editor.swipeHotbar')">
            <ToggleSwitch v-model="layout.gestures.swipeHotbar" :label="t('controls.editor.swipeHotbar')" />
          </SettingRow>
          <SettingRow :title="t('controls.editor.haptics')">
            <ToggleSwitch v-model="layout.gestures.haptics" :label="t('controls.editor.haptics')" />
          </SettingRow>
          <label class="block text-xs text-base-400">
            {{ t('controls.editor.sensitivity') }} · {{ layout.gestures.cameraSensitivity.toFixed(1) }}×
            <input
              v-model.number="layout.gestures.cameraSensitivity"
              type="range"
              class="mt-1 w-full accent-redstone-500"
              :min="MIN_SENSITIVITY"
              :max="MAX_SENSITIVITY"
              step="0.1"
            />
          </label>
        </section>
      </aside>
    </div>
  </div>
</template>
