#!/usr/bin/env node
// Lokale Attrappe der TRS API für Halloween 2026 im TRS Client (API.md §36) – nur für Tests, nur 127.0.0.1.
//
//   node client-mod/scripts/mock-halloween-api.mjs [port=8796]
//   → Spiel mit -PtrsApi=http://127.0.0.1:<port> -PtrsAutotest -PtrsAutotestOnly=halloween starten.
//
// Kann: Anmeldung wie die echte API (challenge → Mojang-Join → verify), GET /v1/me mit events ["halloween"],
//   GET  /v1/me/halloween              Suche 7/13 (Launcher + Website gefunden), Rätsel, Süßes oder Saures offen
//   POST /v1/me/halloween/pumpkins/:id gefunden (idempotent, unbekannt → 404), danach halloween_updated im Stream
//   POST /v1/me/halloween/trick        erstes Mal Süßes (Emote ghost_float), danach 409 trick_used + next
//   PUT  /v1/me/halloween/effects      merkt sich {hits, trail}
//   POST /v1/players/lookup            eigener Spieler: Abzeichen halloween2026, fx hits+trail
//   GET  /v1/badges/:id.png, /v1/stickers, /v1/stickers/:id.png (Pixel-Art ×8, ohne Abhängigkeiten erzeugt)
//   GET  /v1/me/cosmetics              stickers: pumpkin_hihi, ghost_boo
//   GET  /v1/events/me                 SSE (hello, halloween_updated nach Funden)
import http from 'node:http';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import fs from 'node:fs';
import path from 'node:path';

const port = Number(process.argv[2] || 8796);
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
/** Sprite (Zeilen + Palette) ×scale als PNG. */
function sprite(rows, pal, scale = 8) {
	const w = Math.max(...rows.map((r) => r.length)) * scale;
	const h = rows.length * scale;
	const buf = Buffer.alloc(w * h * 4);
	for (let y = 0; y < h; y++) {
		for (let x = 0; x < w; x++) {
			const ch = rows[Math.floor(y / scale)][Math.floor(x / scale)];
			const c = pal[ch];
			if (!c) continue;
			const [r, g, b] = hex(c);
			const i = (y * w + x) * 4;
			buf[i] = r; buf[i + 1] = g; buf[i + 2] = b; buf[i + 3] = 255;
		}
	}
	return png(w, h, buf);
}
const PUMPKIN_PAL = { o: '#8a3f16', O: '#d9772f', g: '#4d7a45', y: '#ffd27a' };
const BADGES = {
	halloween2026: sprite(['...sss...', '..ss.....', '.ss......', '.ss.k...k', '.ss.kkkkk', '.ss..k.k.', '..ss.....', '...sss...'], { s: '#e6e9f2', k: '#b9a2ea' }),
	pumpkinhunter2026: sprite(['....g...', '..oOOo..', '.oOyOyO.', '.oOOOOO.', '.oOyyyO.', '..oOOo..', '........', '........'], PUMPKIN_PAL),
};
const STICKERS = {
	pumpkin_hihi: [{ en: 'Hehe', de: 'Hihi', es: 'Jiji' }, sprite(['............', '.....gg.....', '..ooOgOoo...', '.oOOOoOOOo..', 'oOyOOoOOyOo.', 'oOyyOoOyyOo.', 'oOOOOoOOOOo.', 'oOyOyoyOyOo.', 'oOOyOyOyOOo.', '.oOOOoOOOo..', '..ooooooo...', '............'], PUMPKIN_PAL)],
	ghost_boo: [{ en: 'Boo!', de: 'Buh!', es: '¡Bu!' }, sprite(['....wwww....', '..wwwwwwww..', '.wwwwwwwwww.', '.wwkkwwkkww.', '.wwkkwwkkww.', '.wwwwwwwwww.', '.wwwwkkwwww.', '.wwwwkkwwws.', '.wwwwwwwwws.', '.wwwwwwwwss.', '.ww.ww.ww.s.', '.w...w...w..'], { w: '#eef1f8', s: '#b8bdcc', k: '#1a1426' })],
};

// ---------- MiyakoMC (privates Paket, Part A) ----------
// Medien liegen privat ausserhalb des Repos; Standardpfad kann per MIYAKO_MEDIA überschrieben werden.
const MIYAKO_MEDIA = process.env.MIYAKO_MEDIA || 'E:/ai/trs-private-assets/miyako';
let miyakoIntroDone = false;
let miyakoZip = null;
let miyakoEtag = null;

/** Ein einfaches, unkomprimiertes (store) ZIP aus [{name, data}] – ohne Abhängigkeiten. */
function zipStore(entries) {
	const locals = [];
	const central = [];
	let offset = 0;
	for (const e of entries) {
		const nameBuf = Buffer.from(e.name, 'utf8');
		const crc = crc32(e.data);
		const lh = Buffer.alloc(30);
		lh.writeUInt32LE(0x04034b50, 0);
		lh.writeUInt16LE(20, 4);
		lh.writeUInt16LE(0, 6);
		lh.writeUInt16LE(0, 8); // store
		lh.writeUInt16LE(0, 10);
		lh.writeUInt16LE(0, 12);
		lh.writeUInt32LE(crc, 14);
		lh.writeUInt32LE(e.data.length, 18);
		lh.writeUInt32LE(e.data.length, 22);
		lh.writeUInt16LE(nameBuf.length, 26);
		lh.writeUInt16LE(0, 28);
		locals.push(lh, nameBuf, e.data);
		const ch = Buffer.alloc(46);
		ch.writeUInt32LE(0x02014b50, 0);
		ch.writeUInt16LE(20, 4);
		ch.writeUInt16LE(20, 6);
		ch.writeUInt16LE(0, 8);
		ch.writeUInt16LE(0, 10);
		ch.writeUInt16LE(0, 12);
		ch.writeUInt16LE(0, 14);
		ch.writeUInt32LE(crc, 16);
		ch.writeUInt32LE(e.data.length, 20);
		ch.writeUInt32LE(e.data.length, 24);
		ch.writeUInt16LE(nameBuf.length, 28);
		ch.writeUInt16LE(0, 30);
		ch.writeUInt16LE(0, 32);
		ch.writeUInt16LE(0, 34);
		ch.writeUInt16LE(0, 36);
		ch.writeUInt32LE(0, 38);
		ch.writeUInt32LE(offset, 42);
		central.push(ch, nameBuf);
		offset += lh.length + nameBuf.length + e.data.length;
	}
	const cd = Buffer.concat(central);
	const eocd = Buffer.alloc(22);
	eocd.writeUInt32LE(0x06054b50, 0);
	eocd.writeUInt16LE(entries.length, 8);
	eocd.writeUInt16LE(entries.length, 10);
	eocd.writeUInt32LE(cd.length, 12);
	eocd.writeUInt32LE(offset, 16);
	return Buffer.concat([...locals, cd, eocd]);
}

function miyakoBundle() {
	if (miyakoZip) return miyakoZip;
	const entries = [];
	const framesDir = path.join(MIYAKO_MEDIA, 'frames');
	if (fs.existsSync(framesDir)) {
		for (const f of fs.readdirSync(framesDir).filter((n) => /^[0-9]+\.jpg$/.test(n)).sort()) {
			entries.push({ name: `frames/${f}`, data: fs.readFileSync(path.join(framesDir, f)) });
		}
	}
	for (const f of ['intro.ogg', 'jumpscare.png']) {
		const p = path.join(MIYAKO_MEDIA, f);
		if (fs.existsSync(p)) entries.push({ name: f, data: fs.readFileSync(p) });
	}
	if (!entries.length) return null;
	miyakoZip = zipStore(entries);
	miyakoEtag = '"' + crc32(miyakoZip).toString(16) + '"';
	say(`miyako bundle: ${entries.length} entries, ${miyakoZip.length} bytes`);
	return miyakoZip;
}

// ---------- Zustand ----------
const IDS = ['launcher-titlebar', 'launcher-settings', 'launcher-skins', 'launcher-social', 'launcher-logs',
	'web-home', 'web-features', 'web-roadmap', 'web-creator', 'client-title', 'client-wardrobe', 'client-hud', 'client-modules'];
const HINTS = {
	'client-title': { de: 'Im Spiel, wo die Version steht – noch bevor du spielst.', en: 'In game, where the version is shown – before you even play.', es: 'En el juego, donde se ve la versión, antes de jugar.' },
	'client-wardrobe': { de: 'Hinter dir, wenn du dich umziehst.', en: 'Behind you while you change clothes.', es: 'Detrás de ti mientras te cambias.' },
	'client-hud': { de: 'In der linken unteren Ecke deiner Anzeigen.', en: 'In the bottom-left corner of your overlays.', es: 'En la esquina inferior izquierda de tus indicadores.' },
	'client-modules': { de: 'Ganz am Ende aller Module.', en: 'At the very end of all modules.', es: 'Al final de todos los módulos.' },
};
const found = new Set(IDS.slice(0, 9));
let trickUsed = false;
let trickResult = null;
let effects = { hits: true, trail: true };
const streams = new Set();
const nextMidnight = () => { const d = new Date(); d.setHours(24, 0, 0, 0); return d.toISOString(); };

function state() {
	const next = IDS.find((id) => !found.has(id));
	return {
		active: true,
		endsAt: '2026-11-02T23:00:00Z',
		pumpkins: { total: 13, found: [...found], nextHint: next ? HINTS[next] || null : null },
		trick: trickUsed ? { usedToday: true, result: trickResult.result, reward: trickResult.reward, next: nextMidnight() } : { usedToday: false, next: nextMidnight() },
		badges: found.size >= 13 ? ['halloween2026', 'pumpkinhunter2026'] : ['halloween2026'],
		effects,
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
const fail = (res, status, code, extra = {}) => send(res, status, { error: { code, message: code }, ...extra });
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
const pngOut = (res, data) => { res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': data.length }); res.end(data); };

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
			const user = { uuid: j.uuid, name: b.username, settings: { showBadge: true, showCapeToOthers: true, shareServer: false }, events: ['halloween'] };
			tokens.set(token, user);
			say(`login ${b.username} (${j.uuid})`);
			return send(res, 200, { token, expiresAt: new Date(Date.now() + 86400000).toISOString(), user });
		}
		const badge = /^\/v1\/badges\/([a-z0-9_]+)\.png$/.exec(p);
		if (m === 'GET' && badge) return BADGES[badge[1]] ? pngOut(res, BADGES[badge[1]]) : fail(res, 404, 'not_found');
		const st = /^\/v1\/stickers\/([a-z0-9_]+)\.png$/.exec(p);
		if (m === 'GET' && st) return STICKERS[st[1]] ? pngOut(res, STICKERS[st[1]][1]) : fail(res, 404, 'not_found');
		if (m === 'GET' && p === '/v1/stickers') {
			return send(res, 200, { stickers: Object.entries(STICKERS).map(([id, [name]]) => ({ id, name, url: `/v1/stickers/${id}.png` })) });
		}
		if (p.startsWith('/v1/events/')) {
			res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
			res.write(`event: hello\ndata: {}\n\n`);
			const iv = setInterval(() => res.write(': ping\n\n'), 15000);
			if (p === '/v1/events/me') streams.add(res);
			req.on('close', () => {
				clearInterval(iv);
				streams.delete(res);
			});
			return;
		}
		const user = auth(req);
		if (m === 'GET' && p === '/v1/me') return user ? send(res, 200, user) : fail(res, 401, 'unauthorized');
		if (p.startsWith('/v1/me/halloween') && !user) return fail(res, 401, 'unauthorized');
		if (m === 'GET' && p === '/v1/me/halloween') return send(res, 200, state());
		const pk = /^\/v1\/me\/halloween\/pumpkins\/([a-z-]+)$/.exec(p);
		if (m === 'POST' && pk) {
			await readBody(req);
			if (!IDS.includes(pk[1])) return fail(res, 404, 'pumpkin_unknown');
			found.add(pk[1]);
			const complete = found.size >= 13;
			say(`pumpkin ${pk[1]} → ${found.size}/13`);
			setTimeout(() => broadcast('halloween_updated', state()), 300);
			return send(res, 200, { found: [...found], count: found.size, complete, ...(complete ? { reward: { badge: 'pumpkinhunter2026', cape: 'halloween_hunter_2026' } } : {}) });
		}
		if (m === 'POST' && p === '/v1/me/halloween/trick') {
			await readBody(req);
			if (trickUsed) return fail(res, 409, 'trick_used', { next: nextMidnight() });
			trickUsed = true;
			trickResult = { result: 'sweet', reward: { kind: 'emote', id: 'ghost_float', name: { de: 'Spuk-Schweben', en: 'Ghost Float', es: 'Flotar fantasmal' } } };
			say('trick → sweet (ghost_float)');
			setTimeout(() => broadcast('halloween_updated', state()), 300);
			return send(res, 200, { ...trickResult, next: nextMidnight() });
		}
		// --- MiyakoMC (Part A): privat; hier dient jeder angemeldete Testspieler als „Miyako“. ---
		if (p.startsWith('/v1/me/miyako')) {
			if (!user) return fail(res, 401, 'unauthorized');
			if (m === 'GET' && p === '/v1/me/miyako') {
				return send(res, 200, { introDone: miyakoIntroDone, timerMaxMs: 300000 });
			}
			if (m === 'POST' && p === '/v1/me/miyako/intro-done') {
				await readBody(req);
				miyakoIntroDone = true;
				say('miyako intro-done');
				return send(res, 200, { introDone: true });
			}
			if (m === 'GET' && p === '/v1/me/miyako/bundle') {
				const zip = miyakoBundle();
				if (!zip) return fail(res, 404, 'not_found');
				if ((req.headers['if-none-match'] || '') === miyakoEtag) {
					res.writeHead(304, { ETag: miyakoEtag, 'Cache-Control': 'private, no-store' });
					return res.end();
				}
				res.writeHead(200, {
					'Content-Type': 'application/zip',
					'Content-Length': zip.length,
					ETag: miyakoEtag,
					'Cache-Control': 'private, no-store',
				});
				return res.end(zip);
			}
			return fail(res, 404, 'not_found');
		}
		// --- Besitzer-Auslöser (Part C): schickt ein jumpscare-Ereignis auf den /v1/events/me-Stream. ---
		if (m === 'POST' && p === '/v1/admin/halloween/scare') {
			if (!user) return fail(res, 401, 'unauthorized');
			const b = JSON.parse((await readBody(req)) || '{}');
			const kind = ['face', 'whisper', 'eye', 'flash'].includes(b.kind) ? b.kind : 'face';
			if (!streams.size) return fail(res, 404, 'player_offline');
			broadcast('jumpscare', { type: 'jumpscare', kind });
			say(`scare → ${kind}`);
			return send(res, 200, { sent: true });
		}
		if (m === 'PUT' && p === '/v1/me/halloween/effects') {
			const b = JSON.parse((await readBody(req)) || '{}');
			effects = { hits: !!b.hits, trail: !!b.trail };
			say(`effects ${JSON.stringify(effects)}`);
			return send(res, 200, { effects });
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
				if (norm(u) === own) {
					const fx = [];
					if (effects.hits) fx.push('hits');
					if (effects.trail) fx.push('trail');
					players.push({ uuid: u, badge: true, badges: state().badges, fx });
				}
			}
			return send(res, 200, { players });
		}
		if (m === 'GET' && p === '/v1/me/cosmetics') return send(res, 200, { emotes: [], hats: [], stickers: ['pumpkin_hihi', 'ghost_boo'] });
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
server.listen(port, '127.0.0.1', () => say(`TRS Halloween-Attrappe auf http://127.0.0.1:${port}`));
