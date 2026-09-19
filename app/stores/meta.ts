import { defineStore } from 'pinia'
import type { VersionManifest } from '~/types'

export const useMetaStore = defineStore('meta', () => {
  const manifest = ref<VersionManifest | null>(null)
  let pending: Promise<VersionManifest> | null = null

  /** Lädt das Manifest höchstens einmal parallel; das Backend cached zusätzlich. */
  async function loadManifest(forceRefresh = false) {
    if (manifest.value && !forceRefresh) return manifest.value
    pending ??= backend.getVersionManifest(forceRefresh).finally(() => (pending = null))
    manifest.value = await pending
    return manifest.value
  }

  return { manifest, loadManifest }
})
