import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Instance, Preset, PresetApplyReport, PresetInput } from '../types'
import { backend } from '../utils/backend'
import { movePreset } from '../utils/presets'

/**
 * Mod-Presets: die Liste (eigene + fertige TRS-Presets) und der zuletzt
 * gezeigte Installationsbericht. Gespeichert wird alles im Kern.
 */
export const usePresetsStore = defineStore('presets', () => {
  const items = ref<Preset[]>([])
  const loaded = ref(false)
  let loading: Promise<Preset[]> | null = null
  /** Bericht im Dialog (nach „Details“ im Toast). */
  const report = ref<{ report: PresetApplyReport; instance: Pick<Instance, 'id' | 'name'> } | null>(null)

  async function load(force = false): Promise<Preset[]> {
    if (loaded.value && !force) return items.value
    loading ??= backend
      .listPresets()
      .then((list) => {
        items.value = list
        loaded.value = true
        return list
      })
      .finally(() => {
        loading = null
      })
    return loading
  }

  function replace(preset: Preset) {
    const i = items.value.findIndex((p) => p.id === preset.id)
    if (i >= 0) items.value[i] = preset
    else items.value.push(preset)
  }

  async function create(input: PresetInput): Promise<Preset> {
    const preset = await backend.createPreset(input)
    replace(preset)
    return preset
  }

  async function update(id: string, input: PresetInput): Promise<Preset> {
    const preset = await backend.updatePreset(id, input)
    replace(preset)
    return preset
  }

  async function setAuto(id: string, auto: boolean) {
    replace(await backend.setPresetAuto(id, auto))
  }

  async function remove(id: string) {
    await backend.deletePreset(id)
    items.value = items.value.filter((p) => p.id !== id)
  }

  /** `visible`: IDs in der angezeigten Reihenfolge (ohne ausgeblendete). */
  async function move(id: string, delta: number, visible?: string[]) {
    const ids = items.value.map((p) => p.id)
    const next = movePreset(ids, id, delta, visible)
    if (next === ids) return
    items.value = await backend.reorderPresets(next)
  }

  /** `false` = Dialog abgebrochen. */
  function exportPreset(id: string): Promise<boolean> {
    return backend.exportPreset(id)
  }

  async function importPreset(): Promise<Preset | null> {
    const preset = await backend.importPreset()
    if (preset) replace(preset)
    return preset
  }

  function openReport(value: PresetApplyReport, instance: Pick<Instance, 'id' | 'name'>) {
    report.value = { report: value, instance: { id: instance.id, name: instance.name } }
  }

  function closeReport() {
    report.value = null
  }

  return { items, loaded, report, load, create, update, setAuto, remove, move, exportPreset, importPreset, openReport, closeReport }
})
