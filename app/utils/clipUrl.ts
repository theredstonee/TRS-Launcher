import { convertFileSrc } from '@tauri-apps/api/core'
import { clipAssetUrl, type ClipAsset } from './clipPlayer'

let base: string | null = null

/**
 * Adresse eines Clips im Protokoll `trsclip:` – Video (`v`), Vorschaubild (`p`)
 * oder Vorschau-Leiste (`s`). Das Webview sieht nie einen Dateipfad.
 */
export function clipUrl(asset: ClipAsset, clip: { instanceId: string; fileName: string }): string {
  if (base === null) {
    try {
      base = convertFileSrc('', 'trsclip')
    } catch {
      base = 'trsclip://localhost/'
    }
  }
  return clipAssetUrl(base, asset, clip.instanceId, clip.fileName)
}
