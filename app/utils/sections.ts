import type { ShellSection } from '~/components/SettingsShell.vue'
import type { IconName } from '~/utils/icons'
import type { MessageKey } from '~/utils/i18n'

/**
 * Bereich mit übersetztem Namen: `label` und `group` sind Getter und liefern
 * immer die aktuelle Sprache – in Templates und `computed` also reaktiv.
 */
function section(key: string, labelKey: MessageKey, icon: IconName, groupKey?: MessageKey): ShellSection {
  return {
    key,
    icon,
    get label() {
      return t(labelKey)
    },
    get group() {
      return groupKey ? t(groupKey) : undefined
    },
  }
}

/** Bereiche der globalen Einstellungen – auch die Befehlspalette springt hierher. */
const allAppSettingsSections: ShellSection[] = [
  section('appearance', 'settingsSections.app.appearance', 'palette', 'settingsSections.groups.interface'),
  section('features', 'settingsSections.app.features', 'toggles', 'settingsSections.groups.interface'),
  section('behavior', 'settingsSections.app.behavior', 'behavior', 'settingsSections.groups.interface'),
  section('language', 'settingsSections.app.language', 'language', 'settingsSections.groups.interface'),
  section('profile', 'settingsSections.app.profile', 'user', 'settingsSections.groups.account'),
  section('privacy', 'settingsSections.app.privacy', 'shield', 'settingsSections.groups.account'),
  section('defaults', 'settingsSections.app.defaults', 'defaults', 'settingsSections.groups.instances'),
  section('java', 'settingsSections.app.java', 'java', 'settingsSections.groups.instances'),
  section('clips', 'settingsSections.app.clips', 'clips', 'settingsSections.groups.instances'),
  section('storage', 'settingsSections.app.storage', 'storage', 'settingsSections.groups.instances'),
  section('network', 'settingsSections.app.network', 'network', 'settingsSections.groups.instances'),
]

/** Clips (Spielaufnahme) gibt es vorerst nur unter Windows. */
export const appSettingsSections: ShellSection[] = allAppSettingsSections.filter((s) => s.key !== 'clips' || !isLinux)

/** Bereiche der Instanz-Einstellungen. */
export const instanceSettingsSections: ShellSection[] = [
  section('general', 'settingsSections.instance.general', 'general'),
  section('installation', 'settingsSections.instance.installation', 'install'),
  section('window', 'settingsSections.instance.window', 'window'),
  section('java', 'settingsSections.instance.java', 'java'),
  section('hooks', 'settingsSections.instance.hooks', 'hooks'),
  section('sync', 'settingsSections.instance.sync', 'sync'),
]
