// Gemeinsamer Zustand des Einfach-Modus für die Gruppen/Feld-Komponenten.
import type { InjectionKey } from 'vue'
import type { DraftProblem } from './editor'
import type { ConfigEntry, ConfigValue } from './types'

export interface ConfigEditorContext {
  value(entry: ConfigEntry): ConfigValue
  set(entry: ConfigEntry, value: ConfigValue): void
  reset(entry: ConfigEntry): void
  changed(entry: ConfigEntry): boolean
  problem(entry: ConfigEntry): DraftProblem | null
  /** Bei aktiver Suche: sichtbare Einträge/Gruppen; sonst `null` (alles sichtbar). */
  visible(id: string): boolean
  open(groupId: string): boolean
  toggle(groupId: string): void
  /** Im erweiterten Modus an diese Zeile springen. */
  jump(line: number): void
}

export const configEditorKey: InjectionKey<ConfigEditorContext> = Symbol('config-editor')
