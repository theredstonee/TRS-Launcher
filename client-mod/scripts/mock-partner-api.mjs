#!/usr/bin/env node
// Lokale Attrappe der TRS API für die Partner-Server im TRS Client (API.md §44.3/§44.10) – nur für Tests, nur 127.0.0.1.
//
//   node client-mod/scripts/mock-partner-api.mjs [port=8796]
//   → Spiel mit -PtrsApi=http://127.0.0.1:<port> -PtrsAutotest -PtrsAutotestOnly=partnerservers starten.
//
// Kann: Anmeldung wie die echte API (challenge → Mojang-Join → verify, Token trs_…), die Aufrufe, die der Client beim
// Start ohnehin macht (leere Antworten), und:
//   GET  /v1/partners/servers?mc=…           drei Server, nach Version gefiltert, ETag/304, ttl 600
//   GET  /v1/partners/<slug>/logo.png?v=…    128×128-Logos (Würfel blau, Netzwerk grün)
//   POST /v1/partners/servers/<id>/join      204, zählt mit/ohne Anmeldung
//   GET  /mock/stats                         Zähler als JSON
// Dazu antworten auf 127.0.0.1:25601–25603 kleine Minecraft-Server auf den Status-Ping (Spielerzahl, MOTD); ein
// Beitritt wird mit einer Meldung abgewiesen.
import http from 'node:http';
import net from 'node:net';
import crypto from 'node:crypto';
import zlib from 'node:zlib';

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
function png(w, h, paint) {
	const ihdr = Buffer.alloc(13);
	ihdr.writeUInt32BE(w, 0);
	ihdr.writeUInt32BE(h, 4);
	ihdr[8] = 8;
	ihdr[9] = 6;
	const raw = Buffer.alloc((w * 4 + 1) * h);
	for (let y = 0; y < h; y++) {
		for (let x = 0; x < w; x++) {
			const [r, g, b, a] = paint(x / w, y / h);
			const i = y * (w * 4 + 1) + 1 + x * 4;
			raw[i] = r; raw[i + 1] = g; raw[i + 2] = b; raw[i + 3] = a;
		}
	}
	return Buffer.concat([
		Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
		chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0)),
	]);
}
// Isometrischer Würfel (oben hell, links mittel, rechts dunkel blau)
const cube = png(128, 128, (u, v) => {
	const x = u - 0.5;
	const y = v - 0.5;
	const top = Math.abs(x) * 0.58 + Math.abs(y + 0.2) < 0.3 && y < -0.02;
	if (top) return [120, 180, 255, 255];
	if (Math.abs(x) < 0.36 && y >= -0.2 && y < 0.38 - Math.abs(x) * 0.58) return x < 0 ? [61, 139, 255, 255] : [30, 82, 180, 255];
	return [0, 0, 0, 0];
});
// Drei verbundene Punkte auf grünem Grund
const network = png(128, 128, (u, v) => {
	const d = (a, b) => Math.hypot(u - a, v - b);
	if (d(0.3, 0.35) < 0.12 || d(0.7, 0.35) < 0.12 || d(0.5, 0.72) < 0.12) return [255, 255, 255, 255];
	const line = (ax, ay, bx, by) => {
		const t = Math.max(0, Math.min(1, ((u - ax) * (bx - ax) + (v - ay) * (by - ay)) / ((bx - ax) ** 2 + (by - ay) ** 2)));
		return Math.hypot(u - (ax + t * (bx - ax)), v - (ay + t * (by - ay))) < 0.03;
	};
	if (line(0.3, 0.35, 0.7, 0.35) || line(0.3, 0.35, 0.5, 0.72) || line(0.7, 0.35, 0.5, 0.72)) return [255, 255, 255, 255];
	return d(0.5, 0.5) < 0.5 ? [40, 170, 90, 255] : [0, 0, 0, 0];
});
const LOGOS = {
	craftinghosting: { data: cube, sha: crypto.createHash('sha256').update(cube).digest('hex').slice(0, 16) },
	beispiel: { data: network, sha: crypto.createHash('sha256').update(network).digest('hex').slice(0, 16) },
};

const SERVERS = [
	{ id: 'ps_ch1', partner: { slug: 'craftinghosting', name: 'CraftingHosting' }, name: 'CraftingHosting Lobby',
		address: '127.0.0.1:25601', motd: 'Kostenloser Server in 2 Minuten – teste hier', minVersion: '1.8', maxVersion: '26.3' },
	{ id: 'ps_bsp1', partner: { slug: 'beispiel', name: 'Beispiel-Netzwerk' }, name: 'Beispiel-Netzwerk',
		address: '127.0.0.1:25602', motd: 'Bedwars · Skywars · 1.8–1.21', minVersion: '1.8', maxVersion: '1.21.11' },
	{ id: 'ps_retro', partner: { slug: 'retro-pvp', name: 'Retro PvP' }, name: 'Retro PvP',
		address: '127.0.0.1:25604', motd: '§cNur §l1.8§r bis 1.12', minVersion: '1.8', maxVersion: '1.12.2' },
];
const STATUS = {
	25601: { online: 38, max: 200, motd: 'CraftingHosting Lobby' },
	25602: { online: 1204, max: 5000, motd: 'Beispiel-Netzwerk' },
	25603: { online: 2, max: 20, motd: 'Mein SMP' },
	25604: { online: 77, max: 300, motd: 'Retro PvP' },
};

function cmp(a, b) {
	const x = a.split('.').map(Number);
	const y = b.split('.').map(Number);
	for (let i = 0; i < Math.max(x.length, y.length); i++) {
		const p = x[i] || 0;
		const q = y[i] || 0;
		if (p !== q) return p < q ? -1 : 1;
	}
	return 0;
}
function view(s) {
	const logo = LOGOS[s.partner.slug];
	return { ...s, icon: logo ? `/v1/partners/${s.partner.slug}/logo.png?v=${logo.sha}` : null };
}

const stats = { serverRequests: 0, notModified: 0, logos: 0, joins: [], mc: [] };
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
			say(`login ${b.username}`);
			return send(res, 200, { token, expiresAt: new Date(Date.now() + 86400000).toISOString(), user });
		}
		if (m === 'GET' && p === '/v1/partners/servers') {
			stats.serverRequests++;
			const mc = url.searchParams.get('mc');
			stats.mc.push(mc);
			const list = SERVERS.filter((s) => !mc || !/^[0-9.]+$/.test(mc) || (cmp(mc, s.minVersion) >= 0 && cmp(mc, s.maxVersion) <= 0))
				.slice(0, 3).map(view);
			const body = JSON.stringify({ servers: list, ttl: 600 });
			const etag = '"' + crypto.createHash('sha1').update(body).digest('hex').slice(0, 20) + '"';
			if (req.headers['if-none-match'] === etag) {
				stats.notModified++;
				say(`servers mc=${mc} → 304`);
				res.writeHead(304, { ETag: etag, 'Cache-Control': 'public, max-age=300' });
				return res.end();
			}
			say(`servers mc=${mc} → ${list.map((s) => s.id).join(', ')}`);
			res.writeHead(200, { 'Content-Type': 'application/json', ETag: etag, 'Cache-Control': 'public, max-age=300' });
			return res.end(body);
		}
		const logo = /^\/v1\/partners\/([a-z0-9-]+)\/logo\.png$/.exec(p);
		if (m === 'GET' && logo) {
			const l = LOGOS[logo[1]];
			if (!l) return fail(res, 404, 'partner_not_found');
			stats.logos++;
			say(`logo ${logo[1]}`);
			res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': l.data.length, 'Cache-Control': 'public, max-age=31536000, immutable' });
			return res.end(l.data);
		}
		const join = /^\/v1\/partners\/servers\/([A-Za-z0-9_-]+)\/join$/.exec(p);
		if (m === 'POST' && join) {
			await readBody(req);
			const user = auth(req);
			stats.joins.push({ id: join[1], auth: !!user });
			say(`join ${join[1]} ${user ? `(angemeldet: ${user.name})` : '(ohne Anmeldung)'}`);
			res.writeHead(204);
			return res.end();
		}
		if (m === 'GET' && p === '/mock/stats') return send(res, 200, stats);
		if (p.startsWith('/v1/events/')) {
			res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
			res.write(': hi\n\n');
			const iv = setInterval(() => res.write(': ping\n\n'), 15000);
			req.on('close', () => clearInterval(iv));
			return;
		}
		const user = auth(req);
		if (m === 'GET' && p === '/v1/me') return user ? send(res, 200, user) : fail(res, 401, 'unauthorized');
		if (p === '/v1/presence') {
			await readBody(req);
			return send(res, 200, { ok: true });
		}
		if (m === 'POST' && p === '/v1/players/lookup') {
			await readBody(req);
			return send(res, 200, { players: [] });
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
server.listen(port, '127.0.0.1', () => say(`TRS Partner-Attrappe auf http://127.0.0.1:${port}`));

// ---------- Minecraft-Status (Server List Ping) ----------
function varint(n) {
	const out = [];
	let v = n >>> 0;
	do {
		let b = v & 0x7f;
		v >>>= 7;
		if (v) b |= 0x80;
		out.push(b);
	} while (v);
	return Buffer.from(out);
}
function readVarint(buf, pos) {
	let n = 0;
	let shift = 0;
	for (;;) {
		if (pos >= buf.length) return null;
		const b = buf[pos++];
		n |= (b & 0x7f) << shift;
		if (!(b & 0x80)) return [n, pos];
		shift += 7;
		if (shift > 35) throw new Error('varint');
	}
}
const packet = (id, payload) => {
	const body = Buffer.concat([varint(id), payload]);
	return Buffer.concat([varint(body.length), body]);
};
const mcString = (s) => {
	const b = Buffer.from(s, 'utf8');
	return Buffer.concat([varint(b.length), b]);
};

for (const [p, info] of Object.entries(STATUS)) {
	net.createServer((sock) => {
		let buf = Buffer.alloc(0);
		let state = 0;
		let protocol = 47;
		sock.on('error', () => {});
		sock.on('data', (d) => {
			buf = Buffer.concat([buf, d]);
			for (;;) {
				const len = readVarint(buf, 0);
				if (!len || buf.length < len[1] + len[0]) return;
				const pkt = buf.subarray(len[1], len[1] + len[0]);
				buf = buf.subarray(len[1] + len[0]);
				const [id, at] = readVarint(pkt, 0);
				if (state === 0 && id === 0) {
					const [proto, a2] = readVarint(pkt, at);
					protocol = proto;
					const [hl, a3] = readVarint(pkt, a2);
					const [next] = readVarint(pkt, a3 + hl + 2);
					state = next;
				} else if (state === 1 && id === 0) {
					const status = { version: { name: 'TRS Mock', protocol }, players: { online: info.online, max: info.max, sample: [] },
						description: { text: info.motd } };
					sock.write(packet(0, mcString(JSON.stringify(status))));
				} else if (state === 1 && id === 1) {
					sock.write(packet(1, pkt.subarray(at, at + 8)));
					sock.end();
				} else if (state === 2) {
					sock.write(packet(0, mcString(JSON.stringify({ text: 'TRS-Attrappe: nur Status-Ping' }))));
					sock.end();
				}
			}
		});
	}).listen(Number(p), '127.0.0.1', () => say(`Status-Server ${info.motd} auf 127.0.0.1:${p}`));
}
