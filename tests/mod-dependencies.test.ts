import { describe, expect, it } from 'vitest'
import { dependentMods, planModEnable, type DependencyNode } from '../app/utils/modDependencies'

const node = (fileName: string, requires: DependencyNode['requires'] = [], enabled = false): DependencyNode => ({
  fileName, title: fileName, requires, enabled, readable: true,
})
const edge = (id: string, ...fileNames: string[]) => ({ id, fileNames })

describe('mod dependency planning', () => {
  it('includes transitive dependencies once and terminates cycles', () => {
    const graph = [node('root', [edge('library', 'library')]), node('library', [edge('helper', 'helper')]), node('helper', [edge('root', 'root')])]
    expect(planModEnable(graph, ['root']).files).toEqual(['root', 'library', 'helper'])
  })
  it('reports missing and ambiguous dependencies without choosing a duplicate', () => {
    const graph = [node('root', [edge('missing'), edge('duplicate', 'first', 'second')]), node('first'), node('second')]
    expect(planModEnable(graph, ['root'])).toEqual({ files: ['root'], missing: ['missing'], ambiguous: ['duplicate'], unknown: [] })
  })
  it('reuses enabled providers but checks their missing dependencies', () => {
    const graph = [node('root', [edge('library', 'library')]), node('library', [edge('missing')], true)]
    expect(planModEnable(graph, ['root']).files).toEqual(['root'])
    expect(planModEnable(graph, ['root']).missing).toEqual(['missing'])
  })
  it('reports unreadable metadata', () => {
    expect(planModEnable([{ ...node('root'), readable: false }], ['root']).unknown).toEqual(['root'])
  })
  it('finds transitive dependents and respects active alternatives', () => {
    const graph = [node('root', [edge('library', 'library')], true), node('library', [], true), node('top', [edge('root', 'root')], true)]
    expect(dependentMods(graph, ['library'], true).map(item => item.fileName)).toEqual(['root', 'top'])
    const alternatives = [node('root', [edge('library', 'library', 'other')], true), node('library', [], true), node('other', [], true)]
    expect(dependentMods(alternatives, ['library'], true)).toEqual([])
    expect(dependentMods([node('unrelated', [edge('off', 'off')], true), node('off'), node('library', [], true)], ['library'], true)).toEqual([])
  })
})