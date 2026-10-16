#!/usr/bin/env node
// Lokale Attrappe der TRS API für das Creator-Programm im TRS Client (Vertrag §35.4/§35.5) – nur für Tests, nur 127.0.0.1.
//
//   node client-mod/scripts/mock-creator-api.mjs [port=8795]
//   → Spiel mit -PtrsApi=http://127.0.0.1:<port> -PtrsAutotest -PtrsAutotestOnly=creator starten.
//
// Kann: Anmeldung wie die echte API (challenge → Mojang-Join → verify, Token trs_…), die Aufrufe, die der Client beim
// Start ohnehin macht (me, presence, friends … mit leeren Antworten) und:
//   POST /v1/players/lookup
//     - eigener Spieler: TRS-Abzeichen, Creator-Stern (partner) und Fan von „Pixelmaus“ (Symbol 16 px, 4 Bilder,
//       150 ms, Meilenstein gold)
//     - Testspieler FAN_UUID (der Autotest stellt ihn als Figur in die Welt): Creator-Stern (top) und Fan von „Redfox“
//       (Symbol 32 px, 1 Bild, Meilenstein diamond)
//   GET /v1/creator-symbols/<sha256>.png  (unveränderlich, Cache-Control immutable)
//   GET /v1/events/players               (SSE: nach 6 s ein fan_creator für Pixelmaus → der Client fragt den eigenen
//                                         Spieler neu nach; die Attrappe zählt das im Log)
// Die PNGs werden ohne Abhängigkeiten erzeugt (zlib).
import http from 'node:http';
import crypto from 'node:crypto';
import zlib from 'node:zlib';

const port = Number(process.argv[2] || 8795);
/** Fester Testspieler (gleiche UUID im Autotest dev/CreatorTest). */
export const FAN_UUID = 'c7ea7e00f00d4000a000000000000001';
const PIXELMAUS = '11112222333344445555666677778888';
const REDFOX = '99990000aaaabbbbccccddddeeeeffff';

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
/** RGBA-Pixel (w×h×4) → PNG. */
function png(w, h, rgba) {
	const ihdr = Buffer.alloc(13);
	ihdr.writeUInt32BE(w, 0);
	ihdr.writeUInt32BE(h, 4);
	ihdr[8] = 8; // Bittiefe
	ihdr[9] = 6; // RGBA
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
/** Senkrechter Streifen aus n quadratischen Bildern s×s; paint(f, x, y) → [r,g,b,a]. */
function strip(s, n, paint) {
	const buf = Buffer.alloc(s * s * n * 4);
	for (let f = 0; f < n; f++) {
		for (let y = 0; y < s; y++) {
			for (let x = 0; x < s; x++) {
				const [r, g, b, a] = paint(f, x / s, y / s);
				const i = ((f * s + y) * s + x) * 4;
				buf[i] = r; buf[i + 1] = g; buf[i + 2] = b; buf[i + 3] = a;
			}
		}
	}
	return png(s, s * n, buf);
}
// Pixelmaus: rosa Herz, das pulsiert (4 Bilder).
const heart = strip(16, 4, (f, u, v) => {
	const scale = 1 - 0.08 * [0, 1, 2, 1][f];
	const x = (u - 0.5) / scale;
	const y = (v - 0.55) / scale;
	const inside = (x * x + (y - Math.sqrt(Math.abs(x)) * 0.55) ** 2) < 0.16;
	return inside ? [255, 80 + 40 * f, 170, 255] : [0, 0, 0, 0];
});
// Redfox: orangefarbener Fuchskopf (Dreieck mit Ohren), ein Bild.
const fox = strip(32, 1, (f, u, v) => {
	const ear = (v < 0.35 && (Math.abs(u - 0.25) < (0.35 - v) * 0.5 || Math.abs(u - 0.75) < (0.35 - v) * 0.5));
	const head = v >= 0.3 && Math.abs(u - 0.5) < (1 - v) * 0.62;
	if (!(ear || head)) return [0, 0, 0, 0];
	const eye = v > 0.48 && v < 0.58 && (Math.abs(u - 0.36) < 0.05 || Math.abs(u - 0.64) < 0.05);
	if (eye) return [20, 20, 30, 255];
	return v > 0.72 ? [250, 245, 235, 255] : [240, 120, 30, 255];
});
const SYMBOLS = {};
for (const [name, data, size, frames, frameMs] of [['heart', heart, 16, 4, 150], ['fox', fox, 32, 1, 150]]) {
	const sha = crypto.createHash('sha256').update(data).digest('hex');
	SYMBOLS[name] = { data, sha, size, frames, frameMs };
	say(`symbol ${name}: ${data.length} bytes, sha ${sha.slice(0, 12)}…`);
}
const symbolView = (name) => {
	const s = SYMBOLS[name];
	return { url: `/v1/creator-symbols/${s.sha}.png`, size: s.size, frames: s.frames, frameMs: s.frameMs, sha256: s.sha };
};

// ---------- HTTP ----------
const tokens = new Map();
const joined = new Map();
let lookups = 0;
let selfLookupsAfterEvent = 0;
let eventSent = false;

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
			const user = { uuid: j.uuid, name: b.username, settings: { showBadge: true, showCapeToOthers: true, shareServer: false } };
			tokens.set(token, user);
			say(`login ${b.username} (${j.uuid})`);
			return send(res, 200, { token, expiresAt: new Date(Date.now() + 86400000).toISOString(), user });
		}
		const sym = /^\/v1\/creator-symbols\/([0-9a-f]{64})\.png$/.exec(p);
		if (m === 'GET' && sym) {
			const s = Object.values(SYMBOLS).find((x) => x.sha === sym[1]);
			if (!s) return fail(res, 404, 'not_found');
			say(`symbol ${sym[1].slice(0, 12)}… served`);
			res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': s.data.length, 'Cache-Control': 'public, max-age=31536000, immutable' });
			return res.end(s.data);
		}
		if (p.startsWith('/v1/events/')) {
			res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
			res.write(': hi\n\n');
			const iv = setInterval(() => res.write(': ping\n\n'), 15000);
			let timer = null;
			if (p === '/v1/events/players' && !eventSent) {
				timer = setTimeout(() => {
					eventSent = true;
					say('event fan_creator (Pixelmaus) → eigener Spieler sollte neu nachgeschlagen werden');
					res.write(`event: fan_creator\ndata: ${JSON.stringify({ type: 'fan_creator', creator: PIXELMAUS })}\n\n`);
				}, 6000);
			}
			req.on('close', () => {
				clearInterval(iv);
				if (timer) clearTimeout(timer);
			});
			return;
		}
		const user = auth(req);
		if (m === 'GET' && p === '/v1/me') return user ? send(res, 200, user) : fail(res, 401, 'unauthorized');
		if (p === '/v1/presence') {
			await readBody(req);
			return send(res, 200, { ok: true });
		}
		if (m === 'POST' && p === '/v1/players/lookup') {
			const b = JSON.parse((await readBody(req)) || '{}');
			const uuids = Array.isArray(b.uuids) ? b.uuids : [];
			const own = user ? norm(user.uuid) : null;
			const players = [];
			for (const u of uuids) {
				if (norm(u) === own) {
					if (eventSent) selfLookupsAfterEvent++;
					players.push({ uuid: u, badge: true, creator: { tier: 'partner' },
						fan: { creator: PIXELMAUS, name: 'Pixelmaus', symbol: symbolView('heart'), milestone: 'gold' } });
				} else if (norm(u) === FAN_UUID) {
					players.push({ uuid: u, badge: false, creator: { tier: 'top' },
						fan: { creator: REDFOX, name: 'Redfox', symbol: symbolView('fox'), milestone: 'diamond' } });
				}
			}
			lookups++;
			say(`lookup #${lookups}: ${uuids.length} uuid(s) → ${players.length} mit Creator-Daten` +
				(eventSent ? ` (nach fan_creator: eigener Spieler ${selfLookupsAfterEvent}× neu gefragt)` : ''));
			return send(res, 200, { players });
		}
		if (m === 'GET' && p === '/v1/me/cosmetics') return send(res, 200, { emotes: [], hats: [] });
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
server.listen(port, '127.0.0.1', () => say(`TRS Creator-Attrappe auf http://127.0.0.1:${port} (Testspieler ${FAN_UUID})`));
