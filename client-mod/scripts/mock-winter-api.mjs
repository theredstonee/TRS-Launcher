#!/usr/bin/env node
// Lokale Attrappe der TRS API für die Winter-Kosmetik 2026 im TRS Client – nur für Tests, nur 127.0.0.1.
//
//   node client-mod/scripts/mock-winter-api.mjs [port=8797]
//   → Spiel mit -PtrsApi=http://127.0.0.1:<port> -PtrsAutotest -PtrsAutotestOnly=pets starten.
//
// Liefert SYNTHETISCHE Platzhalter-Modelle (hier erzeugt, keine echte Kunst): test_santa (Mütze mit Option „beard“),
// test_cloud (Begleiter am Kopf, Wolke bis y 23, Flocken mit Skalierung 0,01), test_snow_wings / test_ice_wings
// (Flügel, Eis durchsichtig mit Leuchten + Höfen) und test_penguin (slot pet, attach ground, idle/walk/slide/swim/air).
//
// Nur für eine lokale Sichtprüfung: WINTER_REAL_DIR=<Ordner mit <id>.json/.png/-glow.png> (außerhalb des Repos) bietet
// stattdessen santa_hat, snow_cloud, snow_wings, ice_wings, penguin aus diesem Ordner an – zur Laufzeit gelesen, nie
// kopiert. WINTER_PET_COUNT (1–4, Standard 1) = Anzahl der Pinguine im Lookup.
//
// Kann: Anmeldung (challenge → Mojang-Join → verify), GET /v1/me, GET /v1/cosmetics (Katalog), Modell/Texturen,
//   GET/PUT /v1/me/cosmetics (Fächer hat/companion/wings/pet, options je Fach, nur deklarierte → sonst 400),
//   POST /v1/players/lookup (eigener Spieler mit cosmetics.* inkl. options und pet.count), SSE /v1/events/*.
import http from 'node:http';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import fs from 'node:fs';
import path from 'node:path';

const port = Number(process.argv[2] || 8797);
const REAL = process.env.WINTER_REAL_DIR || '';
const PET_COUNT = Math.max(1, Math.min(4, Number(process.env.WINTER_PET_COUNT || 1)));
const say = (s) => console.log(`${new Date().toISOString()} ${s}`);

// ---------- PNG ----------
const CRC = new Int32Array(256).map((_, n) => {
	let c = n;
	for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
	return c;
});
function crc32(buf) {
	let c = -1;
	for (const b of buf) c = CRC[(c ^ b) & 0xff] ^ (c >>> 8);
	return (c ^ -1) >>> 0;
}
function chunk(type, data) {
	const len = Buffer.alloc(4);
	len.writeUInt32BE(data.length);
	const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
	const crc = Buffer.alloc(4);
	crc.writeUInt32BE(crc32(td));
	return Buffer.concat([len, td, crc]);
}
function png(w, h, rgba) {
	const ihdr = Buffer.alloc(13);
	ihdr.writeUInt32BE(w, 0);
	ihdr.writeUInt32BE(h, 4);
	ihdr[8] = 8;
	ihdr[9] = 6;
	const raw = Buffer.alloc((w * 4 + 1) * h);
	for (let y = 0; y < h; y++) {
		raw[y * (w * 4 + 1)] = 0;
		rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
	}
	return Buffer.concat([
		Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
		chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0)),
	]);
}
const hex = (c) => [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)];

/**
 * Platzhalter-Textur: 8 Farbfelder je 4×4 Einheiten in einem 16×8-Blatt (Faktor `scale`), `frames` Bilder senkrecht;
 * Feld i hat die UV [ (i%4)*4, floor(i/4)*4, +4, +4 ]. alpha je Feld (0…255).
 */
function swatches(colors, scale = 8, frames = 1, alpha = null, glowFrame = null) {
	const W = 16 * scale;
	const H = 8 * scale;
	const buf = Buffer.alloc(W * H * frames * 4);
	for (let f = 0; f < frames; f++) {
		for (let y = 0; y < H; y++) {
			for (let x = 0; x < W; x++) {
				const i = Math.floor(y / (4 * scale)) * 4 + Math.floor(x / (4 * scale));
				const c = glowFrame ? glowFrame(i, f, x, y) : colors[i];
				if (!c) continue;
				const [r, g, b] = hex(c);
				const o = ((f * H + y) * W + x) * 4;
				// feines Karomuster, damit man die HD-Auflösung sieht
				const shade = ((x >> 2) + (y >> 2)) % 2 ? 0.92 : 1;
				buf[o] = Math.round(r * shade);
				buf[o + 1] = Math.round(g * shade);
				buf[o + 2] = Math.round(b * shade);
				buf[o + 3] = alpha ? alpha[i] ?? 255 : 255;
			}
		}
	}
	return png(W, H * frames, buf);
}
const uv = (i) => [(i % 4) * 4, Math.floor(i / 4) * 4, (i % 4) * 4 + 4, Math.floor(i / 4) * 4 + 4];
/** Würfel mit allen sechs Flächen aus Farbfeld `i` (und optional abweichender Oberseite). */
function cube(id, bone, from, to, i, extra = {}) {
	const faces = {};
	const flat = [0, 1, 2].filter((a) => to[a] === from[a]);
	const names = ['north', 'south', 'east', 'west', 'up', 'down'];
	for (const n of names) {
		if (flat.length) {
			const keep = { 0: 'east', 1: 'up', 2: 'south' }[flat[0]];
			if (n !== keep) continue;
		}
		faces[n] = { uv: uv(extra.up != null && n === 'up' ? extra.up : i) };
	}
	const c = { id, bone, from, to, faces };
	if (extra.material) c.material = extra.material;
	return c;
}
const key = (t, v, interpolation) => (interpolation ? { t, v, interpolation } : { t, v });
const track = (bone, channel, keys, interpolation = 'smooth') => ({ bone, channel, interpolation, keys });
const TEX = (id) => ({ file: `${id}.png`, width: 16, height: 8, scale: 8, frames: 1 });

// ---------- Synthetische Modelle ----------
const PAL = ['#1b1d26', '#f3f4f8', '#f39a2b', '#c4202a', '#ffffff', '#9fd8ff', '#dfe8f2', '#5c6a8a'];
// 0 schwarz, 1 weiß, 2 orange, 3 rot, 4 reinweiß, 5 eisblau, 6 schneeweiß, 7 graublau

function penguin() {
	const id = 'test_penguin';
	return {
		format: 2, id, name: 'Test-Pinguin', slot: 'pet', attach: 'ground', texture: TEX(id),
		bones: [
			{ id: 'pen', pivot: [0, 0, 0] },
			{ id: 'body', parent: 'pen', pivot: [0, 1, 0] },
			{ id: 'head', parent: 'body', pivot: [0, 8.5, 0] },
			{ id: 'flip', parent: 'body', pivot: [3, 7.5, 0] },
			{ id: 'flip_m', parent: 'body', pivot: [-3, 7.5, 0] },
			{ id: 'foot_l', parent: 'pen', pivot: [1.25, 0.75, 0.5] },
			{ id: 'foot_r', parent: 'pen', pivot: [-1.25, 0.75, 0.5] },
		],
		cubes: [
			cube('body', 'body', [-3, 1, -2.5], [3, 8.5, 2.5], 0),
			cube('belly', 'body', [-2, 1.5, 2.5], [2, 7.5, 2.5], 1),
			cube('head', 'head', [-2.5, 8.5, -2.25], [2.5, 12.5, 2.25], 0),
			cube('face', 'head', [-1.75, 9, 2.25], [1.75, 11.5, 2.25], 1),
			cube('beak', 'head', [-0.75, 9.75, 2.25], [0.75, 10.75, 4], 2),
			cube('scarf', 'body', [-3.25, 7.25, -2.75], [3.25, 8.5, 2.75], 3),
			cube('flip', 'flip', [3, 2.5, -1.5], [3.75, 7.5, 1.5], 0),
			cube('flip_m', 'flip_m', [-3.75, 2.5, -1.5], [-3, 7.5, 1.5], 0),
			cube('foot_l', 'foot_l', [0.5, 0, -0.5], [2.25, 0.75, 2.5], 2),
			cube('foot_r', 'foot_r', [-2.25, 0, -0.5], [-0.5, 0.75, 2.5], 2),
		],
		animations: [
			{ id: 'idle', driver: 'idle', lengthMs: 4800, tracks: [
				track('head', 'rotation', [key(0, [0, 0, 0]), key(1200, [0, 25, 0]), key(2400, [0, 0, 0]), key(3600, [0, -25, 0]), key(4800, [0, 0, 0])]),
				track('flip', 'rotation', [key(0, [0, 0, 0]), key(600, [0, 0, -25]), key(1200, [0, 0, 0]), key(4800, [0, 0, 0])]),
				track('flip_m', 'rotation', [key(0, [0, 0, 0]), key(600, [0, 0, 25]), key(1200, [0, 0, 0]), key(4800, [0, 0, 0])]),
			] },
			{ id: 'walk', driver: 'walk', lengthMs: 560, tracks: [
				track('pen', 'rotation', [key(0, [0, 0, -9]), key(280, [0, 0, 9]), key(560, [0, 0, -9])]),
				track('pen', 'position', [key(0, [0, 0, 0]), key(140, [0, 0.4, 0]), key(280, [0, 0, 0]), key(420, [0, 0.4, 0]), key(560, [0, 0, 0])]),
				track('foot_l', 'rotation', [key(0, [-22, 0, 0]), key(280, [18, 0, 0]), key(560, [-22, 0, 0])]),
				track('foot_r', 'rotation', [key(0, [18, 0, 0]), key(280, [-22, 0, 0]), key(560, [18, 0, 0])]),
			] },
			{ id: 'slide', driver: 'slide', lengthMs: 1200, tracks: [
				track('pen', 'rotation', [key(0, [80, 0, -2]), key(600, [80, 0, 2]), key(1200, [80, 0, -2])]),
				track('pen', 'position', [key(0, [0, 2.75, 0]), key(1200, [0, 2.75, 0])]),
				track('head', 'rotation', [key(0, [-55, 0, 0]), key(1200, [-55, 0, 0])]),
				track('flip', 'rotation', [key(0, [-20, 0, -10]), key(1200, [-20, 0, -10])]),
				track('flip_m', 'rotation', [key(0, [-20, 0, 10]), key(1200, [-20, 0, 10])]),
			] },
			{ id: 'swim', driver: 'swim', lengthMs: 900, tracks: [
				track('pen', 'rotation', [key(0, [78, 0, -5]), key(450, [78, 0, 5]), key(900, [78, 0, -5])]),
				track('pen', 'position', [key(0, [0, 2.5, 0]), key(450, [0, 3, 0]), key(900, [0, 2.5, 0])]),
				track('head', 'rotation', [key(0, [-50, 0, 0]), key(900, [-50, 0, 0])]),
				track('flip', 'rotation', [key(0, [-30, 0, -40]), key(450, [20, 0, -10]), key(900, [-30, 0, -40])]),
				track('flip_m', 'rotation', [key(0, [-30, 0, 40]), key(450, [20, 0, 10]), key(900, [-30, 0, 40])]),
			] },
			{ id: 'air', driver: 'air', lengthMs: 400, tracks: [
				track('flip', 'rotation', [key(0, [0, 0, -70]), key(200, [0, 0, -40]), key(400, [0, 0, -70])]),
				track('flip_m', 'rotation', [key(0, [0, 0, 70]), key(200, [0, 0, 40]), key(400, [0, 0, 70])]),
			] },
		],
	};
}

function santa() {
	const id = 'test_santa';
	return {
		format: 2, id, name: 'Test-Mütze', slot: 'hat', attach: 'head', texture: TEX(id),
		options: [{ id: 'beard', bones: ['beard'], default: true, name: { de: 'Bart', en: 'Beard', es: 'Barba' } }],
		bones: [
			{ id: 'cap', pivot: [0, 8, 0] },
			{ id: 'tip', parent: 'cap', pivot: [0, 12, 0] },
			{ id: 'beard', parent: 'cap', pivot: [0, 1.5, 4.5] },
			{ id: 'beard_tip', parent: 'beard', pivot: [0, -2, 4.75] },
		],
		cubes: [
			cube('brim', 'cap', [-4.75, 7.75, -4.75], [4.75, 9.25, 4.75], 1),
			cube('cap1', 'cap', [-4.25, 9.25, -4.25], [4.25, 12, 4.25], 3),
			cube('cap2', 'tip', [-3, 12, -3], [3, 14.5, 3], 3),
			cube('pompom', 'tip', [-1.5, 14.5, -1.5], [1.5, 17.5, 1.5], 4),
			cube('beard', 'beard', [-4.5, -1.5, 4.25], [4.5, 3.5, 5.25], 6),
			cube('beard_tip', 'beard_tip', [-2.5, -4, 4.25], [2.5, -1.5, 5], 6),
		],
		animations: [
			{ id: 'idle', driver: 'idle', lengthMs: 3600, tracks: [
				track('tip', 'rotation', [key(0, [0, 0, 0]), key(1800, [6, 0, 8]), key(3600, [0, 0, 0])]),
			] },
			{ id: 'air', driver: 'air', lengthMs: 600, tracks: [
				track('tip', 'rotation', [key(0, [-25, 0, 0]), key(600, [-25, 0, 0])]),
			] },
		],
	};
}

function cloud() {
	const id = 'test_cloud';
	const flakes = [];
	const flakeTracks = [];
	for (let i = 0; i < 6; i++) {
		const fx = -3 + i * 1.25;
		const fz = i % 2 ? 1 : -1;
		flakes.push({ id: `f${i}`, parent: 'c1', pivot: [fx, 18.75, fz] });
		const off = i * 250;
		flakeTracks.push(track(`f${i}`, 'position', [key(0, [0, 0, 0]), key(1800, [0.3, -9, 0]), key(2000, [0.3, -9.6, 0], 'step')]));
		flakeTracks.push(track(`f${i}`, 'scale', [key(0, [1, 1, 1]), key(Math.min(1990, 1700 + off / 5), [0.4, 0.4, 0.4]),
			key(2000, [0.01, 0.01, 0.01])]));
	}
	return {
		format: 2, id, name: 'Test-Wolke', slot: 'companion', attach: 'head', texture: TEX(id),
		bones: [{ id: 'c1', pivot: [0, 20, 0] }, { id: 'c2', pivot: [7.5, 17.75, 0] }, ...flakes],
		cubes: [
			cube('cloud1', 'c1', [-6, 18.5, -3], [6, 21.5, 3], 6, { up: 4 }),
			cube('cloud1b', 'c1', [-3.5, 21.5, -2.5], [3, 23, 2.5], 4),
			cube('cloud2', 'c2', [5, 16.5, -2], [10, 19, 2], 7),
			...flakes.map((f, i) => cube(`flake${i}`, f.id, [f.pivot[0] - 0.375, 18.375, f.pivot[2] - 0.375],
				[f.pivot[0] + 0.375, 19.125, f.pivot[2] + 0.375], 4, { material: 'emissive' })),
		],
		animations: [
			{ id: 'idle', driver: 'idle', lengthMs: 2000, tracks: [
				track('c1', 'position', [key(0, [-1, 0, 0]), key(1000, [1, 0.5, 0.5]), key(2000, [-1, 0, 0])]),
				track('c2', 'position', [key(0, [0, 0, 0]), key(1000, [-15, 0.4, 5.5]), key(2000, [0, 0, 0])]),
				...flakeTracks,
			] },
		],
	};
}

function wings(id, ice) {
	const mat = ice ? { material: 'translucent' } : {};
	return {
		format: 2, id, name: ice ? 'Test-Eisflügel' : 'Test-Schneeflügel', slot: 'wings', attach: 'body', texture: TEX(id),
		glow: { file: `${id}-glow.png`, frames: 4, frameTimeMs: 180, blend: 'additive' },
		bones: [{ id: 'w_r', pivot: [-1.5, -3, -2.5] }, { id: 'w_l', pivot: [1.5, -3, -2.5] },
			{ id: 'flake', parent: 'w_r', pivot: [-8, -5, -3] }],
		cubes: [
			cube('wing_r', 'w_r', [-17, -10.75, -3], [-1.5, -0.5, -3], ice ? 5 : 6, mat),
			cube('wing_l', 'w_l', [1.5, -10.75, -3], [17, -0.5, -3], ice ? 5 : 6, mat),
			cube('flake', 'flake', [-8.5, -5.5, -3.5], [-7.5, -4.5, -2.5], 4, { material: 'emissive' }),
		],
		halos: ice ? [
			{ bone: 'w_r', pos: [-10, -4, -3.2], size: 8, color: '#9fd8ff', intensity: 0.9, normal: [0, 0, -1],
				pulse: { periodMs: 1800, min: 0.4, max: 1, phaseMs: 0 } },
			{ bone: 'w_l', pos: [10, -4, -3.2], size: 8, color: '#9fd8ff', intensity: 0.9, normal: [0, 0, -1],
				pulse: { periodMs: 1800, min: 0.4, max: 1, phaseMs: 900 } },
		] : [],
		animations: [
			{ id: 'idle', driver: 'idle', lengthMs: 4000, tracks: [
				track('w_r', 'rotation', [key(0, [0, 20, 0]), key(2000, [0, 32, 0]), key(4000, [0, 20, 0])]),
				track('w_l', 'rotation', [key(0, [0, -20, 0]), key(2000, [0, -32, 0]), key(4000, [0, -20, 0])]),
				track('flake', 'position', [key(0, [0, 0, 0]), key(3800, [0, -6, 0]), key(4000, [0, -6, 0])]),
				track('flake', 'scale', [key(0, [1, 1, 1]), key(3800, [0.01, 0.01, 0.01]), key(4000, [0.01, 0.01, 0.01])]),
			] },
			{ id: 'air', driver: 'air', lengthMs: 720, tracks: [
				track('w_r', 'rotation', [key(0, [0, -10, 0]), key(360, [0, 45, 0]), key(720, [0, -10, 0])]),
				track('w_l', 'rotation', [key(0, [0, 10, 0]), key(360, [0, -45, 0]), key(720, [0, 10, 0])]),
			] },
		],
	};
}

// ---------- Teile ----------
const ITEMS = new Map();
function addSynthetic(model, glowColor) {
	const body = JSON.stringify(model);
	const alpha = model.id === 'test_ice_wings' ? { 5: 150 } : null;
	const tex = swatches(PAL, 8, 1, alpha);
	let glow = null;
	if (model.glow) {
		glow = swatches(null, 8, model.glow.frames, null, (i, f) => (i === 4 || i === 5) ? (f % 2 ? glowColor : '#203040') : null);
	}
	ITEMS.set(model.id, { model, json: body, tex, glow, card: tex, hash: crypto.createHash('sha256').update(body).digest('hex').slice(0, 12) });
}
function addReal(id) {
	const json = fs.readFileSync(path.join(REAL, `${id}.json`), 'utf8');
	const model = JSON.parse(json);
	const glowPath = path.join(REAL, `${id}-glow.png`);
	const tex = fs.readFileSync(path.join(REAL, `${id}.png`));
	const glow = fs.existsSync(glowPath) ? fs.readFileSync(glowPath) : null;
	ITEMS.set(id, { model, json, tex, glow, card: tex, hash: crypto.createHash('sha256').update(json).digest('hex').slice(0, 12) });
}
if (REAL) {
	for (const id of ['santa_hat', 'snow_cloud', 'snow_wings', 'ice_wings', 'penguin']) addReal(id);
	say(`Echte Exporte aus ${REAL} (nur zur Laufzeit gelesen)`);
} else {
	addSynthetic(santa());
	addSynthetic(cloud());
	addSynthetic(wings('test_snow_wings', false), '#c8dcff');
	addSynthetic(wings('test_ice_wings', true), '#7fd0ff');
	addSynthetic(penguin());
}

// ---------- Zustand ----------
const SLOTS = ['hat', 'companion', 'wings', 'pet'];
const equipped = { hat: null, companion: null, wings: null, pet: null };
const options = { hat: {}, companion: {}, wings: {}, pet: {} };
const streams = new Set();

function flat(slot) {
	const id = equipped[slot];
	if (!id) return null;
	const it = ITEMS.get(id);
	const m = it.model;
	const out = {
		id, format: 2, model: `/v1/cosmetics/${id}/model.json?v=${it.hash}`, url: `/v1/cosmetics/${id}.png?v=${it.hash}`,
		scale: m.texture.scale, frames: m.texture.frames ?? 1, glow: it.glow ? `/v1/cosmetics/${id}-glow.png?v=${it.hash}` : null,
		glowFrames: m.glow ? m.glow.frames : 0, glowFrameTimeMs: m.glow ? m.glow.frameTimeMs : null, hash: it.hash,
	};
	if (Object.keys(options[slot]).length) out.options = { ...options[slot] };
	if (slot === 'pet') out.count = PET_COUNT;
	return out;
}
function view(slot) {
	const f = flat(slot);
	return f ? { ...f, slot, name: ITEMS.get(f.id).model.name } : null;
}
function mine() {
	const eq = {};
	for (const s of SLOTS) eq[s] = view(s);
	return { equipped: eq, emotes: [] };
}
function catalog() {
	return {
		cosmetics: [...ITEMS.values()].map(({ model: m, hash, glow }) => ({
			id: m.id, name: m.name, slot: m.slot, kind: 'builtin', unlock: 'code', status: 'approved', format: 2, owned: true,
			equipped: equipped[m.slot] === m.id, template: null, emissive: false,
			model: `/v1/cosmetics/${m.id}/model.json?v=${hash}`,
			texture: { url: `/v1/cosmetics/${m.id}.png?v=${hash}`, width: m.texture.width * m.texture.scale, height: m.texture.height * m.texture.scale, scale: m.texture.scale },
			glow: glow ? `/v1/cosmetics/${m.id}-glow.png?v=${hash}` : null, glowFrames: m.glow ? m.glow.frames : 0, hash,
			card: `/v1/cosmetics/${m.id}-card.png?v=${hash}`, cardNight: null,
			options: (m.options || []).map((o) => ({ id: o.id, default: o.default, name: o.name })),
		})),
	};
}
function broadcast(event, data) {
	for (const s of streams) s.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
}

// ---------- HTTP ----------
const tokens = new Map();
const joined = new Map();
function send(res, status, body, headers = {}) {
	res.writeHead(status, { 'Content-Type': 'application/json', ...headers });
	res.end(body == null ? '' : JSON.stringify(body));
}
const fail = (res, status, code) => send(res, status, { error: { code, message: code } });
function readBody(req) {
	return new Promise((resolve, reject) => {
		const chunks = [];
		req.on('data', (c) => chunks.push(c));
		req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
		req.on('error', reject);
	});
}
function auth(req) {
	const m = /^Bearer (trs_[A-Za-z0-9_-]{43})$/.exec(req.headers.authorization || '');
	return m ? tokens.get(m[1]) || null : null;
}
const norm = (u) => String(u || '').replace(/-/g, '').toLowerCase();
const out = (res, type, data) => { res.writeHead(200, { 'Content-Type': type, 'Content-Length': data.length }); res.end(data); };

const server = http.createServer(async (req, res) => {
	const url = new URL(req.url, `http://127.0.0.1:${port}`);
	const p = url.pathname;
	const m = req.method;
	try {
		if (m === 'POST' && p === '/v1/auth/challenge') {
			await readBody(req);
			return send(res, 201, { serverId: crypto.randomBytes(20).toString('hex'), expiresAt: new Date(Date.now() + 60000).toISOString() });
		}
		if (m === 'POST' && p === '/session/minecraft/join') {
			const b = JSON.parse((await readBody(req)) || '{}');
			joined.set(b.serverId, { uuid: b.selectedProfile });
			res.writeHead(204);
			return res.end();
		}
		if (m === 'POST' && p === '/v1/auth/verify') {
			const b = JSON.parse((await readBody(req)) || '{}');
			const j = joined.get(b.serverId);
			if (!j) return fail(res, 403, 'not_joined');
			const token = 'trs_' + crypto.randomBytes(33).toString('base64url').slice(0, 43);
			const user = { uuid: j.uuid, name: b.username, settings: { showBadge: true, showCapeToOthers: true, shareServer: false }, events: [] };
			tokens.set(token, user);
			say(`login ${b.username} (${j.uuid})`);
			return send(res, 200, { token, expiresAt: new Date(Date.now() + 86400000).toISOString(), user });
		}
		const asset = /^\/v1\/cosmetics\/([a-z0-9_]+)(\/model\.json|\.png|-glow\.png|-card\.png)$/.exec(p);
		if (m === 'GET' && asset) {
			const it = ITEMS.get(asset[1]);
			if (!it) return fail(res, 404, 'cosmetic_not_found');
			if (asset[2] === '/model.json') return out(res, 'application/json', Buffer.from(it.json));
			if (asset[2] === '.png') return out(res, 'image/png', it.tex);
			if (asset[2] === '-card.png') return out(res, 'image/png', it.card);
			return it.glow ? out(res, 'image/png', it.glow) : fail(res, 404, 'not_found');
		}
		if (p.startsWith('/v1/events/')) {
			res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
			res.write(`event: hello\ndata: {}\n\n`);
			const iv = setInterval(() => res.write(': ping\n\n'), 15000);
			streams.add(res);
			req.on('close', () => {
				clearInterval(iv);
				streams.delete(res);
			});
			return;
		}
		const user = auth(req);
		if (m === 'GET' && p === '/v1/me') return user ? send(res, 200, user) : fail(res, 401, 'unauthorized');
		if (m === 'GET' && p === '/v1/cosmetics') return send(res, 200, catalog());
		if (m === 'GET' && p === '/v1/me/cosmetics') return user ? send(res, 200, mine()) : fail(res, 401, 'unauthorized');
		if (m === 'PUT' && p === '/v1/me/cosmetics') {
			if (!user) return fail(res, 401, 'unauthorized');
			const b = JSON.parse((await readBody(req)) || '{}');
			// erst alles prüfen, dann übernehmen
			for (const s of SLOTS) {
				if (!(s in b) || b[s] === null) continue;
				const it = ITEMS.get(b[s]);
				if (!it || it.model.slot !== s) return fail(res, 400, 'cosmetic_slot_mismatch');
			}
			if (b.options != null) {
				if (typeof b.options !== 'object') return fail(res, 400, 'invalid_options');
				for (const [s, o] of Object.entries(b.options)) {
					const id = s in b ? b[s] : equipped[s];
					const declared = new Set(((id && ITEMS.get(id)?.model.options) || []).map((x) => x.id));
					for (const [k, v] of Object.entries(o || {})) {
						if (!declared.has(k) || typeof v !== 'boolean') return fail(res, 400, 'invalid_options');
					}
				}
			}
			for (const s of SLOTS) if (s in b) equipped[s] = b[s];
			if (b.options) for (const [s, o] of Object.entries(b.options)) options[s] = { ...options[s], ...o };
			say(`PUT cosmetics ${JSON.stringify(b)} → ${JSON.stringify(equipped)} options ${JSON.stringify(options)}`);
			setTimeout(() => broadcast('cosmetics', { type: 'cosmetics', uuid: user.uuid }), 200);
			return send(res, 200, mine());
		}
		if (p === '/v1/presence') {
			await readBody(req);
			return send(res, 200, { ok: true });
		}
		if (m === 'POST' && p === '/v1/players/lookup') {
			const b = JSON.parse((await readBody(req)) || '{}');
			const own = user ? norm(user.uuid) : null;
			const players = [];
			for (const u of Array.isArray(b.uuids) ? b.uuids : []) {
				if (norm(u) !== own) continue;
				const cosmetics = {};
				for (const s of SLOTS) cosmetics[s] = flat(s);
				players.push({ uuid: u, badge: true, cosmetics });
			}
			return send(res, 200, { players });
		}
		if (m === 'GET' && p === '/v1/friends') return send(res, 200, { friends: [], requests: { incoming: [], outgoing: [] }, capeOffers: 0 });
		if (m === 'GET' && p === '/v1/blocks') return send(res, 200, { blocked: [] });
		if (m === 'GET' && p === '/v1/cape-offers') return send(res, 200, { incoming: [], outgoing: [] });
		if (m === 'GET' && p === '/v1/emotes') return send(res, 200, { emotes: [] });
		if (m === 'GET' && p === '/v1/capes') return send(res, 200, { capes: [] });
		if (m === 'GET' && p === '/v1/chat/unread') return send(res, 200, { total: 0, conversations: [] });
		if (m === 'GET' && p === '/v1/chat/conversations') return send(res, 200, { conversations: [], nextCursor: null });
		if (m === 'GET' && p.startsWith('/v1/me/sanctions')) return send(res, 200, { sanctions: [], active: [] });
		say(`404 ${m} ${p}`);
		return fail(res, 404, 'not_found');
	} catch (e) {
		say(`error ${m} ${p}: ${e.message}`);
		if (!res.headersSent) fail(res, 500, 'internal');
	}
});
server.listen(port, '127.0.0.1', () => say(`TRS Winter-Attrappe auf http://127.0.0.1:${port} (${[...ITEMS.keys()].join(', ')})`));
