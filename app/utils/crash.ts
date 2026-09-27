// Absturz-Helfer: Texte und Knöpfe aus den Befunden des Kerns
// (`crates/core/src/crash`). Reine Funktionen – der Store führt aus.
import type { CrashAction, CrashAnalysis, CrashFinding, CrashModRef } from '~/types'
import { formatNumber } from './format'
import { hasKey, t, tKey } from './i18n'

/** Übersetzungs-Schlüssel eines Befunds: `crashHelper.findings.<kind>.<variant|default>`. */
export function findingKey(f: CrashFinding): string {
  const base = `crashHelper.findings.${f.kind}`
  let variant = f.variant ?? 'default'
  // Fehlende Abhängigkeit ohne bekannte Mod, die sie braucht.
  if (f.kind === 'missing_dependency' && !f.params?.name) variant = 'unnamed'
  // Mixin ohne zuordenbare Mods.
  if (f.kind === 'mixin_conflict' && !f.params?.names) variant = `${variant}_nomods`
  const key = `${base}.${variant}`
  return hasKey(`${key}.title`) ? key : `${base}.default`
}

/** Werte für die Übersetzung – mit Rückfällen, damit nie `{name}` stehen bleibt. */
export function findingParams(f: CrashFinding): Record<string, string> {
  return {
    name: t('crashHelper.aMod'),
    names: t('crashHelper.someMods'),
    required: '?',
    present: '?',
    loader: 'Loader',
    target: '?',
    other: t('crashHelper.aMod'),
    deps: '?',
    current: '?',
    file: '?',
    version: '?',
    fixed: '?',
    files: '?',
    ...f.params,
  }
}

export function findingTitle(f: CrashFinding): string {
  return tKey(`${findingKey(f)}.title`, findingParams(f))
}

export function findingText(f: CrashFinding): string {
  return tKey(`${findingKey(f)}.text`, findingParams(f))
}

/** Beteiligte Mods eines Befunds (in der Reihenfolge des Kerns). */
export function findingMods(f: CrashFinding, crash: CrashAnalysis): CrashModRef[] {
  return f.mods.map((id) => crash.mods.find((m) => m.id === id)).filter((m): m is CrashModRef => !!m)
}

/** Anzeigename einer Datei (Mod-Name aus der Instanz, sonst der Dateiname). */
export function fileLabel(file: string, crash: CrashAnalysis): string {
  return crash.mods.find((m) => m.file === file)?.name ?? file
}

/** „4 GB“, „3,5 GB“, „768 MB“ (sprachabhängig). */
export function formatMb(mb: number): string {
  if (mb < 1024) return `${formatNumber(mb)} MB`
  return `${formatNumber(mb / 1024, 1)} GB`
}

/** Eindeutiger Schlüssel einer Aktion (für „läuft“/„erledigt“). */
export function actionKey(a: CrashAction): string {
  return JSON.stringify(a)
}

export function actionLabel(a: CrashAction, crash: CrashAnalysis): string {
  switch (a.type) {
    case 'disableMods':
      return t('crashHelper.actions.disable', { name: a.files.map((f) => fileLabel(f, crash)).join(', ') })
    case 'installDependencies':
      return t('crashHelper.actions.install', { name: a.dependencies.map((d) => crash.mods.find((m) => m.id === d)?.name ?? d).join(', ') })
    case 'removeDuplicates':
      return t('crashHelper.actions.removeDuplicates')
    case 'setMemory':
      return a.toMb > a.fromMb
        ? t('crashHelper.actions.raiseMemory', { to: formatMb(a.toMb) })
        : t('crashHelper.actions.lowerMemory', { to: formatMb(a.toMb) })
    case 'switchJava':
      return a.major ? t('crashHelper.actions.useJava', { major: a.major }) : t('crashHelper.actions.autoJava')
    case 'updateTrsClient':
      return t('crashHelper.actions.updateTrsClient')
    case 'fixConflict':
      return t('crash.fixConflict', { name: crash.mods.find((m) => m.id === a.modId)?.name ?? a.modId })
    case 'repair':
      return t('crash.checkFiles')
  }
}

/** Was genau passiert – steht in der Bestätigung. */
export function actionConfirm(a: CrashAction, crash: CrashAnalysis): string {
  switch (a.type) {
    case 'disableMods':
      return t('crashHelper.confirm.disable', { name: a.files.map((f) => fileLabel(f, crash)).join(', ') })
    case 'installDependencies':
      return t('crashHelper.confirm.install', { name: a.dependencies.map((d) => crash.mods.find((m) => m.id === d)?.name ?? d).join(', ') })
    case 'removeDuplicates':
      return t('crashHelper.confirm.removeDuplicates', { files: a.files.join(', '), keep: a.keep.join(', ') })
    case 'setMemory':
      return t('crashHelper.confirm.memory', { from: formatMb(a.fromMb), to: formatMb(a.toMb) })
    case 'switchJava':
      return a.major ? t('crashHelper.confirm.java', { major: a.major }) : t('crashHelper.confirm.autoJava')
    case 'updateTrsClient':
      return t('crashHelper.confirm.updateTrsClient')
    case 'fixConflict':
      return t('crashHelper.confirm.fixConflict', { name: crash.mods.find((m) => m.id === a.modId)?.name ?? a.modId })
    case 'repair':
      return t('crashHelper.confirm.repair')
  }
}

/** Alle Aktionen des Absturzes ohne Doppelte, Hauptbefund zuerst. */
export function allActions(crash: CrashAnalysis): { finding: CrashFinding; action: CrashAction }[] {
  const seen = new Set<string>()
  const out: { finding: CrashFinding; action: CrashAction }[] = []
  for (const finding of crash.findings) {
    for (const action of finding.actions) {
      const key = actionKey(action)
      if (seen.has(key)) continue
      seen.add(key)
      out.push({ finding, action })
    }
  }
  return out
}

/** Kurzname der Ursache (Verlauf, Listen). */
export function crashCauseLabel(kind: string): string {
  const key = `crash.cause.${kind}`
  return hasKey(key) ? tKey(key) : kind
}
