import { ref } from 'vue'
import type { DuplicateModGroup } from '~/types'

export type DuplicateChoice = 'fix' | 'anyway' | 'cancel'

export interface DuplicatePrompt {
  instanceId: string
  groups: DuplicateModGroup[]
  resolve: (choice: DuplicateChoice) => void
}

/** Offen, solange der Start auf eine Entscheidung wartet. */
export const duplicatePrompt = ref<DuplicatePrompt | null>(null)

export function askDuplicateMods(instanceId: string, groups: DuplicateModGroup[]): Promise<DuplicateChoice> {
  return new Promise((resolve) => {
    duplicatePrompt.value = { instanceId, groups, resolve }
  })
}

export function settleDuplicateMods(choice: DuplicateChoice) {
  const current = duplicatePrompt.value
  if (!current) return
  duplicatePrompt.value = null
  current.resolve(choice)
}
