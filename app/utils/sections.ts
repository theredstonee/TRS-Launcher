import type { ShellSection } from '~/components/SettingsShell.vue'

/** Bereiche der globalen Einstellungen – auch die Befehlspalette springt hierher. */
export const appSettingsSections: ShellSection[] = [
  { key: 'appearance', label: 'Aussehen', icon: 'palette', group: 'Darstellung' },
  { key: 'features', label: 'Funktionen', icon: 'toggles', group: 'Darstellung' },
  { key: 'behavior', label: 'Verhalten', icon: 'behavior', group: 'Darstellung' },
  { key: 'language', label: 'Sprache', icon: 'language', group: 'Darstellung' },
  { key: 'profile', label: 'Profil', icon: 'user', group: 'Konto' },
  { key: 'privacy', label: 'Datenschutz', icon: 'shield', group: 'Konto' },
  { key: 'defaults', label: 'Standard-Einstellungen', icon: 'defaults', group: 'Instanzen' },
  { key: 'java', label: 'Java-Installationen', icon: 'java', group: 'Instanzen' },
  { key: 'storage', label: 'Speicherverwaltung', icon: 'storage', group: 'Instanzen' },
  { key: 'network', label: 'Netzwerk', icon: 'network', group: 'Instanzen' },
]

/** Bereiche der Instanz-Einstellungen. */
export const instanceSettingsSections: ShellSection[] = [
  { key: 'general', label: 'Allgemein', icon: 'general' },
  { key: 'installation', label: 'Installation', icon: 'install' },
  { key: 'window', label: 'Fenster', icon: 'window' },
  { key: 'java', label: 'Java & Arbeitsspeicher', icon: 'java' },
  { key: 'hooks', label: 'Start-Hooks', icon: 'hooks' },
  { key: 'sync', label: 'Synchronisierung', icon: 'sync' },
]
