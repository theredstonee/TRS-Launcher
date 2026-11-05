#!/usr/bin/env node
// Lokale Attrappe der TRS API für die Live-Wegpunkte (API.md §41) im TRS Client – nur für Tests, nur 127.0.0.1.
//
//   node client-mod/scripts/mock-wpshare-api.mjs [port=8798]
//   → Spiel mit -PtrsApi=http://127.0.0.1:<port> -PtrsAutotest -PtrsAutotestOnly=maps starten (der Karten-Test erkennt
//     die Attrappe an der Adresse und hängt die Phase „Live-Wegpunkte“ an).
//
// Ein Freund („Notch“, echte UUID → echter Kopf über Mojang) teilt mit dem Testspieler: Sobald die erste eigene
// Position ankommt (POST /position), legt die Attrappe seine Wegpunkte um diese Stelle an (Turm, Mine, Lager weit weg
// für das Randkästchen, Todespunkt) und meldet das per wpshare_changed; danach läuft er im Kreis (wpshare_position alle
// 2 s über /v1/events/me). Nach 20 s kommt ein weiterer Wegpunkt dazu (wpshare_changed rev 3).
//
// Kann außerdem: Anmeldung (challenge → Mojang-Join → verify), GET /v1/me, /v1/friends (Notch als Freund),
// /v1/waypoint-share/grants (Notch gibt mir frei; PUT/DELETE schalten meine Freigabe), PUT/DELETE /session (peers send 1),
// PUT/GET /waypoints (eigene Liste, rev zählt hoch), GET /friends (nur mit Sitzung, sonst 409 not_in_world).
import http from 'node:http';
import crypto from 'node:crypto';

const port = Number(process.argv[2] || 8798);
const FRIEND = '069a79f444e94726a5befca90e38aaf5';
const FRIEND_NAME = 'Notch';
const say = (s) => console.log(`${new Date().toISOString()} ${s}`);

const tokens = new Map();
const joined = new Map();
const streams = new Set();
let session = null;
let anchor = null;
let rev = 1;
let ownRev = 0;
let ownList = [];
let myGrants = new Set();
let friendPos = null;
let positions = 0;
let started = 0;

function send(res, status, body, headers = {}) {
	res.writeHead(status, { 'Content-Type': 'application/json', ...headers });
	res.end(body == null ? '' : JSON.stringify(body));
}
const fail = (res, status, code, extra = {}) => send(res, status, { error: { code, message: code, ...extra } });
const noContent = (res) => {
	res.writeHead(204);
	res.end();
};
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
function event(type, data, withId = false) {
	const line = `${withId ? `id: ${Date.now()}\n` : ''}event: ${type}\ndata: ${JSON.stringify({ type, ...data })}\n\n`;
	for (const s of streams) s.write(line);
}

function friendWaypoints() {
	if (!anchor) return [];
	const { x, y, z } = anchor;
	const list = [
		{ id: 'turm', name: 'Turm', x: Math.round(x - 30), y: Math.round(y - 18), z: Math.round(z - 34), dimension: 'minecraft:overworld', color: 0x4dd8e0, death: false },
		{ id: 'mine', name: 'Mine von Notch', x: Math.round(x - 40), y: Math.round(y - 30), z: Math.round(z + 36), dimension: 'minecraft:overworld', color: 0xffb84d, death: false },
		{ id: 'lager', name: 'Lager', x: Math.round(x + 420), y: 70, z: Math.round(z - 300), dimension: 'minecraft:overworld', color: 0xb07cff, death: false },
		{ id: 'tod', name: 'Tod', x: Math.round(x + 18), y: Math.round(y - 20), z: Math.round(z - 30), dimension: 'minecraft:overworld', color: null, death: true },
		{ id: 'netherhub', name: 'Nether-Hub', x: 12, y: 70, z: -8, dimension: 'minecraft:the_nether', color: 0xe0281e, death: false },
	];
	if (rev >= 3) list.push({ id: 'hoehle', name: 'Neue Höhle', x: Math.round(x + 12), y: Math.round(y - 25), z: Math.round(z + 48), dimension: 'minecraft:overworld', color: 0x3ddc84, death: false });
	return list;
}

function moveFriend() {
	if (!anchor) return;
	const t = (Date.now() - started) / 1000;
	// Kleiner Kreis neben dem Turm (so passen Turm und Kopf ins selbe Bild).
	const a = t * 0.25;
	const x = anchor.x - 24 + 6 * Math.cos(a);
	const z = anchor.z - 30 + 4 * Math.sin(a);
	// Blickrichtung tangential (Minecraft: yaw 0 = Süden/+z, 90 = Westen/−x).
	const dx = -6 * Math.sin(a);
	const dz = 4 * Math.cos(a);
	let yaw = (-Math.atan2(dx, dz) * 180) / Math.PI;
	friendPos = { x: +x.toFixed(2), y: +(anchor.y - 20).toFixed(2), z: +z.toFixed(2), dimension: 'minecraft:overworld', yaw: +yaw.toFixed(1), t: new Date().toISOString() };
	if (session) event('wpshare_position', { uuid: FRIEND, ...friendPos });
}
setInterval(moveFriend, 2000);

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
			return noContent(res);
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
		if (p.startsWith('/v1/events/')) {
			res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
			res.write(': hi\n\n');
			if (p === '/v1/events/me') {
				res.write(`event: hello\ndata: {"resumed":false}\n\n`);
				streams.add(res);
				say(`events/me verbunden (${streams.size})`);
			}
			const iv = setInterval(() => res.write('event: ping\ndata: {}\n\n'), 15000);
			req.on('close', () => {
				clearInterval(iv);
				streams.delete(res);
			});
			return;
		}
		const user = auth(req);
		if (m === 'GET' && p === '/v1/me') return user ? send(res, 200, user) : fail(res, 401, 'unauthorized');
		if (p === '/v1/presence') {
			await readBody(req);
			return send(res, 200, { ok: true });
		}
		if (m === 'GET' && p === '/v1/friends') {
			return send(res, 200, {
				friends: [{ uuid: FRIEND, name: FRIEND_NAME, since: '2026-10-01T10:00:00Z', presence: { state: 'in-game', game: { version: '1.21.11', loader: 'fabric', server: 'wpshare.test.example' } } }],
				requests: { incoming: [], outgoing: [] }, capeOffers: 0,
			});
		}
		// --- §41 ---
		const base = '/v1/waypoint-share';
		if (p.startsWith(base)) {
			if (!user) return fail(res, 401, 'unauthorized');
			const sub = p.slice(base.length);
			if (m === 'GET' && sub === '/grants') return send(res, 200, { grants: [...myGrants], grantedToMe: [FRIEND] });
			const g = /^\/grants\/([0-9a-f]{32})$/.exec(sub);
			if (g && (m === 'PUT' || m === 'DELETE')) {
				await readBody(req);
				if (g[1] !== FRIEND) return fail(res, 403, 'not_friends');
				if (m === 'PUT') myGrants.add(g[1]);
				else myGrants.delete(g[1]);
				say(`Freigabe ${m === 'PUT' ? 'an' : 'aus'} für ${g[1].slice(0, 8)}`);
				return noContent(res);
			}
			if (sub === '/session' && m === 'PUT') {
				const b = JSON.parse((await readBody(req)) || '{}');
				const first = !session;
				session = { world: b.world, dimension: b.dimension, at: Date.now() };
				if (!started) started = Date.now();
				say(`Sitzung ${first ? 'neu' : 'erneuert'}: ${JSON.stringify(b.world)} ${b.dimension}`);
				return send(res, 200, { peers: { send: 1, receive: [FRIEND] }, expiresInSec: 180 });
			}
			if (sub === '/session' && m === 'DELETE') {
				session = null;
				say('Sitzung beendet');
				return noContent(res);
			}
			if (sub === '/position' && m === 'POST') {
				const b = JSON.parse((await readBody(req)) || '{}');
				positions++;
				if (positions <= 3 || positions % 10 === 0) say(`Position #${positions}: ${b.x} ${b.y} ${b.z} ${b.dimension} yaw ${b.yaw}`);
				if (!anchor && typeof b.x === 'number') {
					anchor = { x: b.x, y: b.y, z: b.z };
					rev = 2;
					moveFriend();
					say(`Anker gesetzt → wpshare_changed rev 2`);
					event('wpshare_changed', { uuid: FRIEND, rev }, true);
					setTimeout(() => {
						rev = 3;
						say('neuer Wegpunkt → wpshare_changed rev 3');
						event('wpshare_changed', { uuid: FRIEND, rev }, true);
					}, 20000);
				}
				return noContent(res);
			}
			if (sub === '/waypoints' && m === 'PUT') {
				const b = JSON.parse((await readBody(req)) || '{}');
				if (b.rev != null && b.rev !== ownRev) return fail(res, 409, 'rev_mismatch', { rev: ownRev });
				ownList = Array.isArray(b.waypoints) ? b.waypoints : [];
				ownRev = ownList.length ? ownRev + 1 : 0;
				say(`eigene Liste rev ${ownRev}: ${ownList.map((w) => w.name + (w.death ? ' (Tod)' : '')).join(', ') || '(leer)'}`);
				return send(res, 200, { world: b.world, rev: ownRev, waypoints: ownList, updatedAt: new Date().toISOString() });
			}
			if (sub === '/waypoints' && m === 'GET') return send(res, 200, { world: JSON.parse(url.searchParams.get('world') || '{}'), rev: ownRev, waypoints: ownList, updatedAt: null });
			if (sub === '/friends' && m === 'GET') {
				if (!session) return fail(res, 409, 'not_in_world');
				const owners = [{ uuid: FRIEND, name: FRIEND_NAME, rev: anchor ? rev : 0, waypoints: friendWaypoints(), ...(friendPos ? { position: friendPos } : {}) }];
				say(`GET /friends → ${owners[0].waypoints.length} Wegpunkte (rev ${owners[0].rev})`);
				return send(res, 200, { owners });
			}
			say(`404 ${m} ${p}`);
			return fail(res, 404, 'not_found');
		}
		if (m === 'GET' && p === '/v1/me/cosmetics') return send(res, 200, { emotes: [], hats: [] });
		if (m === 'POST' && p === '/v1/players/lookup') {
			await readBody(req);
			return send(res, 200, { players: [] });
		}
		if (m === 'GET' && p === '/v1/blocks') return send(res, 200, { blocked: [] });
		if (m === 'GET' && p === '/v1/cape-offers') return send(res, 200, { incoming: [], outgoing: [] });
		if (m === 'GET' && p === '/v1/chat/unread') return send(res, 200, { total: 0, conversations: [] });
		if (m === 'GET' && p === '/v1/chat/conversations') return send(res, 200, { conversations: [], nextCursor: null });
		if (m === 'GET' && p.startsWith('/v1/me/sanctions')) return send(res, 200, { sanctions: [], active: [] });
		return fail(res, 404, 'not_found');
	} catch (e) {
		say(`error ${m} ${p}: ${e.message}`);
		if (!res.headersSent) fail(res, 500, 'internal');
	}
});
server.listen(port, '127.0.0.1', () => say(`TRS Live-Wegpunkte-Attrappe auf http://127.0.0.1:${port} (Freund ${FRIEND_NAME} ${FRIEND})`));
