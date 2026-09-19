import type { LoaderKind } from '~/types'

export const loaderLabels: Record<LoaderKind, string> = {
  vanilla: 'Vanilla',
  fabric: 'Fabric',
  quilt: 'Quilt',
  forge: 'Forge',
  neoforge: 'NeoForge',
}

const relative = new Intl.RelativeTimeFormat('de', { numeric: 'auto' })

export function formatRelative(iso: string | null): string {
  if (!iso) return 'Noch nie gespielt'
  const diffSec = (new Date(iso).getTime() - Date.now()) / 1000
  const steps: [Intl.RelativeTimeFormatUnit, number][] = [
    ['year', 31_536_000],
    ['month', 2_592_000],
    ['day', 86_400],
    ['hour', 3_600],
    ['minute', 60],
  ]
  for (const [unit, seconds] of steps) {
    if (Math.abs(diffSec) >= seconds) return relative.format(Math.round(diffSec / seconds), unit)
  }
  return 'Gerade eben'
}

export function formatMemory(mb: number): string {
  return mb >= 1024 ? `${(mb / 1024).toLocaleString('de', { maximumFractionDigits: 1 })} GB` : `${mb} MB`
}
