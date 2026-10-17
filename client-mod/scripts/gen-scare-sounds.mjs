#!/usr/bin/env node
// Erzeugt die drei generischen Grusel-Töne der Halloween-Schreck-Erweiterung als kleine OGG-Vorbis-Dateien.
// GENERISCH und selbst erzeugt (keine Fremdaufnahmen, kein Urheberrecht). Reproduzierbar: nur FFmpeg-Filter,
// keine zufälligen Seeds ausser dem deterministischen random(1) von FFmpeg.
//
//   node client-mod/scripts/gen-scare-sounds.mjs
//   -> schreibt common/src/main/resources/assets/trsclient/sounds/scare_{scream,whisper,heartbeat}.ogg
//
// FFmpeg muss erreichbar sein (PATH oder Umgebungsvariable FFMPEG).
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const out = path.resolve(here, '..', 'common', 'src', 'main', 'resources', 'assets', 'trsclient', 'sounds');
mkdirSync(out, { recursive: true });
const ff = process.env.FFMPEG || 'ffmpeg';

// Jeder Ton: ein lavfi-Ausdruck -> Mono 44.1 kHz -> OGG Vorbis, kleine Bitrate (klein, generisch).
const sounds = {
	// Kreischen: schnell aufsteigende, dissonante Töne mit etwas Rauschen, exponentiell abklingend.
	scare_scream:
		"aevalsrc=exprs='(0.55*sin(2*PI*(620+1500*t)*t)+0.30*sin(2*PI*(930+2500*t)*t)+0.22*(random(1)*2-1))*min(1,10*t)*exp(-2.6*t)':d=0.85:s=44100,"
		+ 'highpass=f=240,lowpass=f=7000,volume=0.9',
	// Flüstern: gefiltertes Rauschen mit langsamer Amplitudenmodulation, ein- und ausblenden.
	scare_whisper:
		'anoisesrc=d=1.4:a=0.6:c=pink,'
		+ 'highpass=f=700,lowpass=f=4200,tremolo=f=6.5:d=0.85,'
		+ 'afade=t=in:d=0.35,afade=t=out:st=0.95:d=0.45,volume=0.6',
	// Herzschlag: zwei tiefe Schläge (~60 Hz) mit scharfem Abfall.
	scare_heartbeat:
		"aevalsrc=exprs='sin(2*PI*58*t)*(lt(t,0.4)*exp(-15*t)+gte(t,0.45)*0.85*exp(-15*(t-0.45)))':d=1.05:s=44100,"
		+ 'lowpass=f=320,volume=1.0',
};

for (const [name, filter] of Object.entries(sounds)) {
	const file = path.join(out, `${name}.ogg`);
	execFileSync(ff, ['-y', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', filter,
		'-ac', '1', '-ar', '44100', '-c:a', 'libvorbis', '-q:a', '1', file], { stdio: 'inherit' });
	console.log('wrote', path.relative(path.resolve(here, '..'), file));
}
console.log('done');
