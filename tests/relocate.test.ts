import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  dataSourceKey,
  instanceLocationSchema,
  moveBlocker,
  movePlanSchema,
  pickedFolderSchema,
  targetFolderSchema,
  unavailableInstancesSchema,
} from '../app/utils/relocate'
import { hasKey } from '../app/utils/i18n'

const plan = { target: 'D:\\Spiele\\TRS-Launcher', bytes: 1024, files: 3, links: 0, free: 10_000, sameVolume: false, enoughSpace: true }

describe('Umziehen', () => {
  it('prüft die Antworten des Kerns', () => {
    expect(movePlanSchema.parse(plan)).toEqual(plan)
    expect(movePlanSchema.parse({ ...plan, free: null }).free).toBeNull()
    expect(movePlanSchema.safeParse({ ...plan, bytes: -1 }).success).toBe(false)
    expect(movePlanSchema.safeParse({ ...plan, target: '' }).success).toBe(false)
    expect(instanceLocationSchema.safeParse({ path: '/a', custom: true, defaultPath: '/b' }).success).toBe(true)
    expect(unavailableInstancesSchema.safeParse([{ id: 'pack', path: 'D:\\Minecraft\\Modpack' }]).success).toBe(true)
    expect(unavailableInstancesSchema.safeParse([{ id: '', path: 'x' }]).success).toBe(false)
    expect(pickedFolderSchema.parse(null)).toBeNull()
  })

  it('nimmt nur vernünftige Ordner-Eingaben an', () => {
    expect(targetFolderSchema.parse('  D:\\Spiele  ')).toBe('D:\\Spiele')
    expect(targetFolderSchema.safeParse('').success).toBe(false)
    expect(targetFolderSchema.safeParse('D:\\a\nb').success).toBe(false)
    expect(targetFolderSchema.safeParse('x'.repeat(1025)).success).toBe(false)
  })

  it('sagt, warum der Umzug nicht starten kann', () => {
    expect(moveBlocker(plan, false)).toBeNull()
    expect(moveBlocker(plan, true)).toBe('relocate.blockedGames')
    expect(moveBlocker({ ...plan, enoughSpace: false }, false)).toBe('relocate.blockedSpace')
  })

  it('hat für jede Herkunft des Datenordners einen Text', () => {
    for (const source of ['default', 'custom', 'portable', 'env'] as const) expect(hasKey(dataSourceKey(source))).toBe(true)
  })

  it('baut im Release eine portable ZIP mit Marker, README und TRS Client', () => {
    const workflow = fs.readFileSync(path.resolve(__dirname, '..', '.github', 'workflows', 'release.yml'), 'utf8')
    expect(workflow).toContain('_x64-portable.zip')
    expect(workflow).toContain('portable.txt')
    expect(workflow).toContain('README.txt')
    expect(workflow).toContain('src-tauri/resources/client-mod')
  })
})
