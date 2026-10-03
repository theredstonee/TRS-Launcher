import type { PlatformCapabilities } from '~/types'
import type { IconName } from './icons'
import type { MessageKey } from './i18n'

/** Handy-Navigation: vier Bereiche unten in der Tab-Leiste, der Rest im Sheet „Mehr“. */
export interface MobileNavItem {
  /** Ziel-Seite; ohne `to` löst `action` etwas aus (Dialog, Suche, Hilfe). */
  to?: string
  action?: 'settings' | 'search' | 'create' | 'import' | 'docs'
  label: MessageKey
  icon: IconName
  /** Nur zeigen, wenn diese Fähigkeit da ist. */
  needs?: 'clips' | 'gameLaunch'
}

export const mobileTabs: MobileNavItem[] = [
  { to: '/', label: 'nav.home', icon: 'home' },
  { to: '/instances', label: 'nav.library', icon: 'library' },
  { to: '/browse', label: 'nav.discover', icon: 'compass' },
  { to: '/social', label: 'nav.social', icon: 'chat' },
]

const moreAll: MobileNavItem[] = [
  { to: '/skins', label: 'nav.skins', icon: 'skins' },
  { to: '/screenshots', label: 'nav.screenshots', icon: 'screenshots' },
  { to: '/achievements', label: 'nav.achievements', icon: 'trophy' },
  { to: '/presets', label: 'nav.presets', icon: 'presets' },
  { to: '/servers', label: 'nav.servers', icon: 'server' },
  { to: '/clips', label: 'nav.clips', icon: 'clips', needs: 'clips' },
  { to: '/accounts', label: 'mobile.accounts', icon: 'user' },
  { action: 'settings', label: 'nav.settings', icon: 'gear' },
  { action: 'create', label: 'nav.newInstance', icon: 'plus' },
  { action: 'import', label: 'mobile.import', icon: 'import' },
  { action: 'search', label: 'common.actions.search', icon: 'search' },
  { action: 'docs', label: 'mobile.help', icon: 'help' },
]

/** Einträge im Sheet „Mehr“ – ohne das, was dieses Gerät nicht kann. Der Team-Bereich bleibt Desktop-only. */
export function mobileMoreItems(caps: Pick<PlatformCapabilities, 'clips' | 'gameLaunch'>): MobileNavItem[] {
  return moreAll.filter((item) => !item.needs || caps[item.needs])
}

/** Welcher Tab leuchtet: Bereichsseiten und ihre Unterseiten, alles andere gehört zu „Mehr“. */
export function activeMobileTab(path: string): string {
  if (path === '/') return '/'
  for (const tab of mobileTabs) {
    if (tab.to && tab.to !== '/' && (path === tab.to || path.startsWith(`${tab.to}/`))) return tab.to
  }
  // Projektseiten öffnet man aus „Entdecken“.
  if (path.startsWith('/project/')) return '/browse'
  if (path === '/friends') return '/social'
  return 'more'
}
