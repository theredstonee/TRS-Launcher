// Zeile über dem Update-Namen im Banner: „Update 0.4.3 · 24.09.2026“.
export function updateKicker(entry: Pick<ChangelogEntry, 'version' | 'date'>): string {
  const head = t('updateNews.kicker', { version: entry.version ?? '' })
  return entry.date ? `${head} · ${formatShortDate(`${entry.date}T12:00:00`)}` : head
}
