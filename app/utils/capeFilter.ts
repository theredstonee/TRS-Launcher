import type { TrsCape } from './trs'

// Suche, Filter und Sortierung der TRS-Umhang-Galerie (Skins-Seite). Reine Logik,
// getestet in tests/cape-filter.test.ts. Nutzt nur Felder, die die API schon liefert.

export const capeFilters = ['all', 'animated', 'owned', 'available', 'friends', 'own'] as const
export type CapeFilter = (typeof capeFilters)[number]
export const capeSorts = ['default', 'name', 'nameDesc', 'ownedFirst', 'animatedFirst'] as const
export type CapeSort = (typeof capeSorts)[number]
/** Woher ein Umhang kommt. */
export type CapeOrigin = 'friend' | 'upload' | 'event' | 'code' | 'team' | 'free' | 'other'

export function capeOrigin(cape: Pick<TrsCape, 'kind' | 'unlock' | 'shared'>): CapeOrigin {
  if (cape.shared) return 'friend'
  if (cape.kind === 'upload') return 'upload'
  switch (cape.unlock) {
    case 'event':
      return 'event'
    case 'code':
      return 'code'
    case 'admin':
    case 'owner':
      return 'team'
    case 'free':
      return 'free'
    default:
      return 'other'
  }
}

const collator = new Intl.Collator(undefined, { sensitivity: 'base', numeric: true })

export function capeMatches(cape: TrsCape, filter: CapeFilter): boolean {
  switch (filter) {
    case 'animated':
      return cape.frames > 1
    case 'owned':
      return cape.owned
    case 'available':
      return !cape.owned
    case 'friends':
      return !!cape.shared
    case 'own':
      return cape.kind === 'upload' && !cape.shared
    default:
      return true
  }
}

/** Sucht im Namen (ohne Groß-/Kleinschreibung), filtert und sortiert; die Eingabe bleibt unverändert. */
export function filterCapes(capes: readonly TrsCape[], query: string, filter: CapeFilter, sort: CapeSort): TrsCape[] {
  const q = query.trim().toLocaleLowerCase()
  const out = capes.filter((c) => capeMatches(c, filter) && (!q || c.name.toLocaleLowerCase().includes(q)))
  const byName = (a: TrsCape, b: TrsCape) => collator.compare(a.name, b.name)
  switch (sort) {
    case 'name':
      return out.sort(byName)
    case 'nameDesc':
      return out.sort((a, b) => byName(b, a))
    case 'ownedFirst':
      return out.sort((a, b) => Number(b.owned) - Number(a.owned) || byName(a, b))
    case 'animatedFirst':
      return out.sort((a, b) => Number(b.frames > 1) - Number(a.frames > 1) || byName(a, b))
    default:
      return out
  }
}
