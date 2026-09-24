import { describe, expect, it } from 'vitest'
import {
  categoryLabels,
  categoryTypeFor,
  isCurseForgeImage,
  isPlatform,
  pageSizesFor,
  projectKey,
  projectRoute,
  sortIndexesFor,
  sourcePlatform,
} from '../app/utils/platform'
import { modrinthSearchSchema } from '../app/utils/schemas'

describe('Quellen Modrinth und CurseForge', () => {
  it('unterscheidet Projekte beider Quellen eindeutig', () => {
    expect(projectKey('modrinth', 'AANobbMI')).toBe('AANobbMI')
    expect(projectKey('curseforge', '238222')).toBe('cf:238222')
    expect(sourcePlatform(null)).toBe('modrinth')
    expect(sourcePlatform({})).toBe('modrinth')
    expect(sourcePlatform({ platform: 'curseforge' })).toBe('curseforge')
    expect(isPlatform('curseforge') && isPlatform('modrinth') && !isPlatform('forge') && !isPlatform(undefined)).toBe(true)
  })

  it('verlinkt die Projektseite mit Quelle und Zielinstanz', () => {
    expect(projectRoute('modrinth', 'sodium')).toEqual({ path: '/project/sodium', query: {} })
    expect(projectRoute('curseforge', '238222', 'meine-welt')).toEqual({
      path: '/project/238222',
      query: { platform: 'curseforge', instance: 'meine-welt' },
    })
  })

  it('bietet je Quelle nur, was sie kann', () => {
    expect(sortIndexesFor('curseforge')).not.toContain('follows')
    expect(sortIndexesFor('modrinth')).toContain('follows')
    expect(Math.max(...pageSizesFor('curseforge'))).toBe(50)
    expect(pageSizesFor('modrinth')).toContain(100)
    expect(categoryTypeFor('curseforge', 'datapack')).toBe('datapack')
    expect(categoryTypeFor('modrinth', 'datapack')).toBe('mod')
    expect(categoryTypeFor('curseforge', 'shaderpack')).toBe('shader')
  })

  it('zeigt CurseForge-Kategorien mit Namen statt ID', () => {
    const labels = categoryLabels([
      { name: '419', projectType: 'mod', header: 'categories', icon: null, label: 'Magic' },
      { name: 'magic', projectType: 'mod', header: 'categories', icon: '<svg/>' },
    ])
    expect(labels.get('419')).toBe('Magic')
    expect(labels.has('magic')).toBe(false)
    // CurseForge-Kategorie-IDs gehen durch dieselbe Prüfung wie Modrinth-Slugs.
    const params = {
      query: '', kind: 'mod', gameVersions: ['1.20.1'], loaders: ['forge'], categories: ['419', '6814'], categoryMatch: 'all',
      excludeCategories: [], environments: [], excludeProjectIds: [], openSource: false, index: 'relevance', offset: 0, limit: 50,
    }
    expect(modrinthSearchSchema.safeParse(params).success).toBe(true)
  })

  it('lässt nur Bilder von CurseForges CDN zu', () => {
    expect(isCurseForgeImage('https://media.forgecdn.net/avatars/thumbnails/29/69/256/256/635838945588716414.jpeg')).toBe(true)
    for (const bad of [
      null,
      '',
      'https://media.forgecdn.net/',
      'http://media.forgecdn.net/x.png',
      'https://media.forgecdn.net.evil.example/x.png',
      'https://media.forgecdn.net/x.png" onerror="x',
      'https://edge.forgecdn.net/files/1/2/x.jar',
    ]) {
      expect(isCurseForgeImage(bad), String(bad)).toBe(false)
    }
  })
})
