import { z } from 'zod'

export const dependencyGraphSchema = z.array(z.object({
  fileName: z.string(),
  title: z.string(),
  enabled: z.boolean(),
  readable: z.boolean(),
  requires: z.array(z.object({ id: z.string(), fileNames: z.array(z.string()) })),
}))
export type DependencyNode = z.infer<typeof dependencyGraphSchema>[number]

export function planModEnable(graph: readonly DependencyNode[], roots: readonly string[]) {
  const byFile = new Map(graph.map(node => [node.fileName, node]))
  const visited = new Set<string>()
  const files = new Set(roots)
  const missing = new Set<string>()
  const ambiguous = new Set<string>()
  const unknown = new Set<string>()
  function visit(file: string) {
    if (visited.has(file)) return
    visited.add(file)
    const node = byFile.get(file)
    if (!node?.readable) unknown.add(file)
    for (const dependency of node?.requires ?? []) {
      const providers = dependency.fileNames.map(name => byFile.get(name)).filter((provider): provider is DependencyNode => !!provider)
      const present = providers.find(provider => provider.enabled || files.has(provider.fileName))
      if (present) visit(present.fileName)
      else if (providers.length === 1) {
        files.add(providers[0]!.fileName)
        visit(providers[0]!.fileName)
      } else if (providers.length) ambiguous.add(dependency.id)
      else missing.add(dependency.id)
    }
  }
  roots.forEach(visit)
  return { files: [...files], missing: [...missing], ambiguous: [...ambiguous], unknown: [...unknown] }
}

export function dependentMods(graph: readonly DependencyNode[], files: readonly string[], enabledOnly = false) {
  const removed = new Set(files)
  const affected = new Set<string>()
  let changed = true
  while (changed) {
    changed = false
    for (const node of graph) {
      if (removed.has(node.fileName) || affected.has(node.fileName) || (enabledOnly && !node.enabled)) continue
      if (node.requires.some(dependency => dependency.fileNames.some(file => removed.has(file) || affected.has(file)) && dependency.fileNames.every(file =>
        removed.has(file) || affected.has(file) || (enabledOnly && !graph.find(provider => provider.fileName === file)?.enabled),
      ))) {
        affected.add(node.fileName)
        changed = true
      }
    }
  }
  return graph.filter(node => affected.has(node.fileName))
}