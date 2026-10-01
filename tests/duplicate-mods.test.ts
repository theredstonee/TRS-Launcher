import { describe, expect, it } from 'vitest'
import { askDuplicateMods, duplicatePrompt, settleDuplicateMods } from '../app/utils/duplicateMods'

const sodium = {
  id: 'sodium',
  name: 'Sodium',
  keep: { fileName: 'sodium-0.8.14.jar', version: '0.8.14' },
  disable: [{ fileName: 'sodium-0.8.7.jar', version: '0.8.7' }],
}

describe('duplicate mod prompt', () => {
  it('resolves the choice and clears the prompt', async () => {
    const pending = askDuplicateMods('inst', [sodium])
    expect(duplicatePrompt.value?.instanceId).toBe('inst')
    expect(duplicatePrompt.value?.groups[0]?.keep.fileName).toBe('sodium-0.8.14.jar')
    settleDuplicateMods('fix')
    expect(await pending).toBe('fix')
    expect(duplicatePrompt.value).toBeNull()
  })

  it('ignores a second settle', async () => {
    const pending = askDuplicateMods('inst', [sodium])
    settleDuplicateMods('cancel')
    settleDuplicateMods('anyway')
    expect(await pending).toBe('cancel')
    expect(duplicatePrompt.value).toBeNull()
  })
})
