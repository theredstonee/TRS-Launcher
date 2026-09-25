#!/usr/bin/env node
// Erzeugt die Minecraft-Sprachdateien (Namen der Tastenbelegungen im Steuerungsmenü) aus den
// Übersetzungen des TRS Clients: common/src/main/resources/assets/trsclient/i18n/<code>.json.
//
//   node client-mod/scripts/gen-lang.mjs
//
// JSON (ab 1.13) für fabric, neoforge, forge, forge-mojmap-legacy, forge-1.13.2;
// .lang für legacy (1.8.9–1.12.2, klein geschrieben – der Build benennt sie bis 1.10.2 in xx_XX um)
// und legacy-1.7.10 (xx_XX.lang).
import { readFileSync, writeFileSync, readdirSync, rmSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const i18nDir = join(root, 'common/src/main/resources/assets/trsclient/i18n');

/** Minecraft-Sprachcodes je TRS-Sprache (alle Varianten bekommen dieselbe Übersetzung). */
const LOCALES = {
  en: ['en_us', 'en_gb', 'en_ca', 'en_au', 'en_nz'],
  de: ['de_de', 'de_at', 'de_ch'],
  es: ['es_es', 'es_mx', 'es_ar', 'es_cl', 'es_ec', 'es_uy', 'es_ve'],
  fr: ['fr_fr', 'fr_ca'],
  pl: ['pl_pl'],
  'pt-BR': ['pt_br', 'pt_pt'],
  tr: ['tr_tr'],
  nl: ['nl_nl', 'nl_be'],
};

/** Minecraft-Schlüssel ← i18n-Schlüssel. */
const KEYS = [
  ['key.categories.trsclient', 'key.trsclient.category'],
  ['key.category.trsclient.main', 'key.trsclient.category'],
  ['key.trsclient.menu', 'key.trsclient.menu'],
  ['key.trsclient.zoom', 'key.trsclient.zoom'],
  ['key.trsclient.fullbright', 'key.trsclient.fullbright'],
  ['key.trsclient.freelook', 'key.trsclient.freelook'],
  ['key.trsclient.hudProfile', 'key.trsclient.hudProfile'],
  ['key.trsclient.emoteWheel', 'key.trsclient.emoteWheel'],
  ['key.trsclient.redstoneOverlay', 'key.trsclient.redstoneOverlay'],
  ['key.trsclient.saveClip', 'key.trsclient.saveClip'],
  ['key.trsclient.toggleRecording', 'key.trsclient.toggleRecording'],
  ['key.trsclient.wardrobe', 'key.trsclient.wardrobe'],
  ['key.trsclient.worldMap', 'key.trsclient.worldMap'],
];

const load = (code) => JSON.parse(readFileSync(join(i18nDir, `${code}.json`), 'utf8'));
const en = load('en');

function entries(code) {
  const table = { ...en, ...load(code) };
  return KEYS.map(([mc, key]) => {
    const value = table[key];
    if (!value) throw new Error(`${code}: ${key} fehlt`);
    return [mc, value];
  });
}

function langDir(project) {
  const dir = join(root, project, 'src/main/resources/assets/trsclient/lang');
  mkdirSync(dir, { recursive: true });
  // Alte Dateien weg (auch umbenannte Varianten), dann neu schreiben.
  for (const f of readdirSync(dir)) rmSync(join(dir, f));
  return dir;
}

let count = 0;
for (const project of ['fabric', 'neoforge', 'forge', 'forge-mojmap-legacy', 'forge-1.13.2']) {
  const dir = langDir(project);
  for (const [code, locales] of Object.entries(LOCALES)) {
    const json = '{\n' + entries(code).map(([k, v]) => `\t${JSON.stringify(k)}: ${JSON.stringify(v)}`).join(',\n') + '\n}\n';
    for (const locale of locales) {
      writeFileSync(join(dir, `${locale}.json`), json, 'utf8');
      count++;
    }
  }
}

const lang = (code) => entries(code).map(([k, v]) => `${k}=${v}`).join('\n') + '\n';
const upper = (locale) => locale.slice(0, 3) + locale.slice(3).toUpperCase();
for (const [project, name] of [['legacy', (l) => l], ['legacy-1.7.10', upper]]) {
  const dir = langDir(project);
  for (const [code, locales] of Object.entries(LOCALES)) {
    for (const locale of locales) {
      writeFileSync(join(dir, `${name(locale)}.lang`), lang(code), 'utf8');
      count++;
    }
  }
}
console.log(`${count} Sprachdateien geschrieben`);
