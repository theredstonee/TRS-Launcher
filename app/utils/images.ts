import { convertFileSrc, isTauri } from '@tauri-apps/api/core'
import type { Instance, LoaderKind } from '~/types'

/** Akzentfarbe je Modloader (für Platzhalter-Bilder und Badges). */
export const loaderColors: Record<LoaderKind, string> = {
  vanilla: '#6cc24a',
  fabric: '#d9b98c',
  quilt: '#a879e6',
  forge: '#7a9be0',
  neoforge: '#f0923f',
}

/** URL des Instanz-Bilds fürs Webview (Datei ist einzeln freigegeben). */
export function instanceIconSrc(instance: Pick<Instance, 'iconPath'>): string | null {
  if (!instance.iconPath || !isTauri()) return null
  return convertFileSrc(instance.iconPath)
}
