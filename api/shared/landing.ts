// Themen-Seiten (Landing Pages) der Website: /minecraft-launcher, /redstone-launcher, /modpacks,
// /fps-boost-pvp-client. Inhalt je Sprache in shared/landing-{en,de,es}.ts; dieselben Texte nutzt
// server/lib/llms.ts für /llms-full.txt. Jede Aussage muss durch CHANGELOG.md (Branch main) oder die
// Funktionsseite gedeckt sein – keine erfundenen Funktionen, keine Namen anderer Launcher oder Clients.

import type { SeoLang } from './seo'
import { landingEn } from './landing-en'
import { landingDe } from './landing-de'
import { landingEs } from './landing-es'

export type LandingId = 'minecraft-launcher' | 'redstone-launcher' | 'modpacks' | 'fps-boost-pvp-client'

/** Reihenfolge = Reihenfolge in Menü, Fußzeile und Karten. */
export const LANDING_IDS: readonly LandingId[] = ['minecraft-launcher', 'redstone-launcher', 'modpacks', 'fps-boost-pvp-client']

export function landingPath(id: LandingId): string {
  return `/${id}`
}

/** Screenshot aus public/news/<version>/ auf main (wie die Blog-Beiträge); `file` = `<version>/<name>.png`. */
export interface LandingShot {
  file: string
  alt: string
  caption: string
}

export interface LandingSection {
  id: string
  title: string
  /** Absätze. */
  text: string[]
  points?: string[]
  shot?: LandingShot
  /** Interner Link (Pfad ohne Sprache). */
  link?: { to: string, label: string }
}

export interface LandingFaq {
  q: string
  a: string
}

export interface LandingPageText {
  seo: { title: string, description: string }
  /** Kurzer Name (Brotkrumen, Menü, Fußzeile). */
  name: string
  /** Ein Satz für Karten auf anderen Seiten. */
  teaser: string
  kicker: string
  /** Text der h1. */
  title: string
  lead: string
  sections: LandingSection[]
  faq: LandingFaq[]
  cta: { title: string, text: string }
}

export interface LandingCommon {
  /** Überschrift über den Themen-Links in Menü und Fußzeile. */
  topics: string
  faqTitle: string
  related: string
  download: string
  features: string
  allQuestions: string
  note: string
  learnMore: string
  onThisPage: string
}

export interface LandingTexts {
  common: LandingCommon
  pages: Record<LandingId, LandingPageText>
}

export const NEWS_RAW_BASE = 'https://raw.githubusercontent.com/theredstonee/TRS-Launcher/main/public/news'

/** Maße der genutzten Screenshots (gegen Layout-Sprünge). */
export const SHOT_SIZES: Record<string, [number, number]> = {
  '0.2.0/content.png': [2560, 1392],
  '0.2.1/library.png': [1280, 800],
  '0.3.0/hud-editor.png': [854, 480],
  '0.4.0/running.png': [1100, 720],
  '0.5.0/emote-wheel.png': [854, 480],
  '0.5.0/redstone-overlay.png': [854, 480],
  '0.8.0/worlds.png': [1400, 860],
  '0.9.0/pvp-hud.png': [854, 480],
  '0.10.0/circuit-ghost.png': [854, 480],
  '0.10.0/circuits.png': [854, 480],
  '0.10.0/crash-helper.png': [1280, 900],
  '0.10.0/modpack-choice.png': [1280, 820],
  '0.12.0/share-result.png': [1280, 800],
  '0.12.0/pack-update.png': [1280, 800],
  '0.13.0/minimap.png': [854, 480],
  '0.14.0/achievements.png': [1280, 830],
}

export function shotUrl(file: string): string {
  return `${NEWS_RAW_BASE}/${file}`
}

export function shotSize(file: string): [number, number] {
  return SHOT_SIZES[file] ?? [854, 480]
}

export const landingTexts: Record<SeoLang, LandingTexts> = { en: landingEn, de: landingDe, es: landingEs }

/** Sichtbare Wörter einer Seite (Überschrift, Einleitung, Abschnitte, FAQ) – für den Umfangs-Test. */
export function landingWords(p: LandingPageText): number {
  const parts = [
    p.title,
    p.lead,
    ...p.sections.flatMap((s) => [s.title, ...s.text, ...(s.points ?? []), s.shot?.caption ?? '']),
    ...p.faq.flatMap((f) => [f.q, f.a]),
    p.cta.title,
    p.cta.text,
  ]
  return parts.join(' ').split(/\s+/).filter((w) => /[\p{L}\p{N}]/u.test(w)).length
}
