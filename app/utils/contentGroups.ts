import { z } from 'zod'
import type { ContentItem, ContentKind } from '~/types'
import { compareText } from './format'

// Eigene Gruppen im Inhalte-Tab (Name + Farbe, klappbar, als Ganzes schaltbar)
// und Herkunft „vom Modpack“ / „selbst hinzugefügt“. Gespeichert und auf die
// Dateien aufgelöst wird im Kern (`trs_core::content_groups`).

export const groupColors = ['red', 'orange', 'yellow', 'green', 'cyan', 'blue', 'purple', 'pink', 'gray'] as const
export type GroupColor = (typeof groupColors)[number]

/** Feste Farben (kein Nutzer-Input) – in hellem und dunklem Thema gut sichtbar. */
export const GROUP_COLOR_HEX: Record<GroupColor, string> = {
  red: '#e0443a',
  orange: '#f08a24',
  yellow: '#e8c13a',
  green: '#3fbf6a',
  cyan: '#2fb8c4',
  blue: '#4a7ff0',
  purple: '#9a62e8',
  pink: '#e05aa8',
  gray: '#8b8ba2',
}

export const MAX_GROUP_NAME = 40

const contentKindSchema = z.enum(['mod', 'resourcepack', 'shaderpack', 'datapack'])

export const contentGroupSchema = z.object({
  id: z.string().regex(/^[A-Za-z0-9]{1,40}$/),
  name: z.string().min(1).max(MAX_GROUP_NAME),
  color: z.enum(groupColors),
  collapsed: z.boolean(),
})
export type ContentGroup = z.output<typeof contentGroupSchema>

const contentRefSchema = z.object({ kind: contentKindSchema, fileName: z.string().max(200) })

export const contentOrganizationSchema = z.object({
  groups: z.array(contentGroupSchema),
  assignments: z.array(contentRefSchema.extend({ groupId: z.string() })),
  fromPack: z.array(contentRefSchema),
  packKnown: z.boolean(),
})
export type ContentOrganization = z.output<typeof contentOrganizationSchema>

// eslint-disable-next-line no-control-regex
const noControl = /^[^\u0000-\u001f\u007f]*$/
/** Wie der Kern: getrimmt, 1–40 Zeichen, keine Steuerzeichen. */
export const groupNameSchema = z.string().trim().min(1).max(MAX_GROUP_NAME).regex(noControl)

export const emptyOrganization = (): ContentOrganization => ({ groups: [], assignments: [], fromPack: [], packKnown: false })

export type OriginFilter = 'all' | 'pack' | 'manual'
/** `all`, `none` (ohne Gruppe) oder eine Gruppen-ID. */
export type GroupFilter = string

export const contentKey = (i: { kind: ContentKind; fileName: string }) => `${i.kind}/${i.fileName}`

/** Schnelle Nachschlage-Tabellen für die Liste. */
export function indexOrganization(org: ContentOrganization) {
  const groupOf = new Map<string, string>()
  for (const a of org.assignments) groupOf.set(contentKey(a), a.groupId)
  const fromPack = new Set(org.fromPack.map(contentKey))
  const groups = new Map(org.groups.map((g) => [g.id, g]))
  return { groupOf, fromPack, groups }
}
export type OrganizationIndex = ReturnType<typeof indexOrganization>

export interface ContentSection {
  /** `null` = ohne Gruppe. */
  group: ContentGroup | null
  items: ContentItem[]
  /** Alle Inhalte der Gruppe (auch weggefilterte) – für Zähler und Schalter. */
  all: ContentItem[]
}

/** Passt der Inhalt zu Suche, Herkunfts- und Gruppenfilter? Die Suche findet auch den Gruppennamen. */
export function matchesFilters(
  item: ContentItem,
  index: OrganizationIndex,
  opts: { needle: string; origin: OriginFilter; group: GroupFilter },
): boolean {
  const groupId = index.groupOf.get(contentKey(item))
  const group = groupId ? index.groups.get(groupId) : undefined
  if (opts.group === 'none' ? !!group : opts.group !== 'all' && group?.id !== opts.group) return false
  if (opts.origin !== 'all' && index.fromPack.has(contentKey(item)) !== (opts.origin === 'pack')) return false
  if (!opts.needle) return true
  return `${item.title ?? ''} ${item.fileName} ${item.author ?? ''} ${group?.name ?? ''}`.toLowerCase().includes(opts.needle)
}

/**
 * Teilt die (gefilterte, sortierte) Liste in Abschnitte: erst die Gruppen in
 * ihrer Reihenfolge, dann „ohne Gruppe“. Leere Abschnitte fallen weg, außer
 * eine Gruppe ist ganz leer und es wird nicht gesucht/gefiltert (dann bleibt sie
 * als Ziel zum Hineinziehen sichtbar).
 */
export function buildSections(visible: ContentItem[], all: ContentItem[], org: ContentOrganization, filtering: boolean): ContentSection[] {
  const index = indexOrganization(org)
  const bucket = (list: ContentItem[]) => {
    const map = new Map<string | null, ContentItem[]>()
    for (const item of list) {
      const id = index.groupOf.get(contentKey(item))
      const key = id && index.groups.has(id) ? id : null
      const arr = map.get(key) ?? []
      arr.push(item)
      map.set(key, arr)
    }
    return map
  }
  const shown = bucket(visible)
  const total = bucket(all)
  const sections: ContentSection[] = []
  for (const group of org.groups) {
    const items = shown.get(group.id) ?? []
    const everything = total.get(group.id) ?? []
    if (items.length || (!filtering && !everything.length)) sections.push({ group, items, all: everything })
  }
  const loose = shown.get(null) ?? []
  if (loose.length) sections.push({ group: null, items: loose, all: total.get(null) ?? [] })
  return sections
}

/** Schalter einer Gruppe: alle an, alle aus oder gemischt. */
export function groupToggleState(items: ContentItem[]): 'on' | 'off' | 'mixed' {
  const on = items.filter((i) => i.enabled).length
  if (!items.length || on === 0) return 'off'
  return on === items.length ? 'on' : 'mixed'
}

/** Gruppen alphabetisch für Menüs (Anzeige in der Liste bleibt in Anlege-Reihenfolge). */
export function sortedGroups(groups: ContentGroup[]): ContentGroup[] {
  return [...groups].sort((a, b) => compareText(a.name, b.name))
}
