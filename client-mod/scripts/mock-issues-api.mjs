#!/usr/bin/env node
// Lokale Attrappe der TRS API für „Bug melden“ im TRS Client (Vertrag API.md §28) – nur für Tests, nur 127.0.0.1.
//
//   node client-mod/scripts/mock-issues-api.mjs [port] [ausgabeordner]
//   → Spiel mit -PtrsApi=http://127.0.0.1:<port> starten (TRS API + Mojang-Join zeigen auf die Attrappe).
//
// Kann: Anmeldung wie die echte API (challenge → Mojang-Join → verify, Token trs_…), die Aufrufe, die der Client beim
// Start ohnehin macht (me, presence, lookup, friends … mit leeren Antworten), und die zwei Endpunkte von „Bug melden“:
//   POST /v1/issues/uploads  (roh, image/png|jpeg|webp, ≤ 8 MB) → 201 {upload:{id}}
//   POST /v1/issues          (JSON, Grenzen wie der Vertrag)    → 201 {issue:{number,url,…}}
// Fehler zum Ausprobieren über den Titel: „#limit“ → 429 issue_daily_limit, „#banned“ → 403 sanctioned,
// „#invalid“ → 400 invalid_request, „#lostupload“ → einmal 404 upload_not_found (der Client lädt dann neu hoch).
// Nach 10 Meldungen je Lauf gibt es ebenfalls 429 (Tageslimit).
//
// Alles, was ankommt, landet im Ausgabeordner (Standard: ./mock-issues-out): issue-<n>.json und upload-<id>.<ext>.
// Zusätzlich prüft die Attrappe das empfangene Log auf Dinge, die die Säuberung hätte entfernen müssen, und warnt.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const port = Number(process.argv[2] || 8799);
const outDir = path.resolve(process.argv[3] || 'mock-issues-out');
fs.mkdirSync(outDir, { recursive: true });

const MAX_UPLOAD = 8 * 1024 * 1024;
const SITE = 'https://trs-launcher.theredstonee.de';
const tokens = new Map(); // token → {uuid, name}
const joined = new Map(); // serverId → {uuid}
const uploads = new Map(); // id → {owner, mime, bytes}
const lostOnce = new Set();
let nextIssue = 100;
let issuesToday = 0;

function say(line) {
	console.log(`${new Date().toISOString()} ${line}`);
}

function send(res, status, body, headers = {}) {
	const data = body == null ? '' : JSON.stringify(body);
	res.writeHead(status, { 'Content-Type': 'application/json', ...headers });
	res.end(data);
}

function fail(res, status, code, message, headers = {}) {
	send(res, status, { error: { code, message: message || code } }, headers);
}

function readBody(req, limit) {
	return new Promise((resolve, reject) => {
		const chunks = [];
		let size = 0;
		req.on('data', (c) => {
			size += c.length;
			if (size > limit) {
				reject(Object.assign(new Error('too large'), { tooLarge: true }));
				req.destroy();
				return;
			}
			chunks.push(c);
		});
		req.on('end', () => resolve(Buffer.concat(chunks)));
		req.on('error', reject);
	});
}

function auth(req) {
	const h = req.headers.authorization || '';
	const m = /^Bearer (trs_[A-Za-z0-9_-]{43})$/.exec(h);
	return m ? tokens.get(m[1]) || null : null;
}

const b64url = (n) => crypto.randomBytes(n).toString('base64url');
const isStr = (v, min, max) => typeof v === 'string' && v.trim().length >= min && v.length <= max;

/** Dinge, die in einem gesäuberten Log nicht mehr stehen dürften. */
function privacyCheck(log) {
	const checks = [
		['JWT', /eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/],
		['TRS-Token', /trs_[A-Za-z0-9_-]{20,}/],
		['--accessToken mit Wert', /--accessToken[\s,=]+(?!<token>)[^\s,\]]+/],
		['Benutzerpfad', /[A-Za-z]:[\\/]+Users[\\/]+(?!<user>)[^\\/\s]+/i],
		['IPv4', /(?<![\d.])(?!127\.)(?:\d{1,3}\.){3}\d{1,3}(?![\d.])/],
		['E-Mail', /[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\.[A-Za-z]{2,}/],
		['UUID', /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i],
		['Chat-Inhalt', /\[CHAT\] (?!<chat removed>)\S/],
	];
	const found = [];
	for (const [name, re] of checks) {
		const m = re.exec(log);
		if (m) found.push(`${name}: ${m[0].slice(0, 60)}`);
	}
	return found;
}

function validateIssue(b) {
	if (!b || typeof b !== 'object') return 'body';
	if (!['bug', 'feature'].includes(b.type)) return 'type';
	if (!['client', 'launcher', 'website'].includes(b.area)) return 'area';
	if (!isStr(b.title, 5, 120)) return 'title';
	if (typeof b.description !== 'string' || b.description.length > 8000) return 'description';
	if (b.attachments != null && (!Array.isArray(b.attachments) || b.attachments.length > 6
		|| b.attachments.some((a) => typeof a !== 'string' || !/^[A-Za-z0-9_-]{22}$/.test(a)))) return 'attachments';
	const m = b.meta;
	if (m != null) {
		if (typeof m !== 'object' || Array.isArray(m)) return 'meta';
		for (const k of ['modVersion', 'mcVersion', 'loader']) {
			if (m[k] != null && (typeof m[k] !== 'string' || m[k].length > 32)) return `meta.${k}`;
		}
		if (m.mods != null && (!Array.isArray(m.mods) || m.mods.length > 300
			|| m.mods.some((x) => typeof x !== 'string' || x.length > 100))) return 'meta.mods';
		if (m.log != null && (typeof m.log !== 'string' || m.log.length > 20000)) return 'meta.log';
		const known = new Set(['modVersion', 'mcVersion', 'loader', 'mods', 'log']);
		for (const k of Object.keys(m)) if (!known.has(k)) return `meta.${k} (unbekannt)`;
	}
	return null;
}

const server = http.createServer(async (req, res) => {
	const url = new URL(req.url, `http://127.0.0.1:${port}`);
	const p = url.pathname;
	const m = req.method;
	try {
		// --- Anmeldung (wie TrsApi.login) ---
		if (m === 'POST' && p === '/v1/auth/challenge') {
			await readBody(req, 4096);
			return send(res, 201, { serverId: crypto.randomBytes(20).toString('hex') });
		}
		if (m === 'POST' && p === '/session/minecraft/join') {
			const b = JSON.parse((await readBody(req, 16384)).toString('utf8') || '{}');
			joined.set(b.serverId, { uuid: b.selectedProfile });
			res.writeHead(204);
			return res.end();
		}
		if (m === 'POST' && p === '/v1/auth/verify') {
			const b = JSON.parse((await readBody(req, 4096)).toString('utf8') || '{}');
			const j = joined.get(b.serverId);
			if (!j) return fail(res, 403, 'not_joined');
			const token = 'trs_' + b64url(32).slice(0, 43);
			const user = { uuid: j.uuid, name: b.username, settings: { showBadge: true, showCapeToOthers: true, shareServer: false } };
			tokens.set(token, user);
			say(`Anmeldung ${b.username} (${j.uuid})`);
			return send(res, 200, { token, user });
		}

		// --- Was der Client sonst beim Start fragt: leere, gültige Antworten ---
		const user = auth(req);
		if (m === 'GET' && p === '/v1/me') return user ? send(res, 200, user) : fail(res, 401, 'unauthorized');
		if (p === '/v1/presence') {
			await readBody(req, 65536);
			res.writeHead(204);
			return res.end();
		}
		if (m === 'POST' && p === '/v1/players/lookup') {
			await readBody(req, 65536);
			return send(res, 200, { players: [] });
		}
		if (m === 'GET' && p === '/v1/me/cosmetics') return send(res, 200, { emotes: [] });
		if (m === 'GET' && p === '/v1/friends') return send(res, 200, { friends: [], requests: { incoming: [], outgoing: [] } });
		if (m === 'GET' && p === '/v1/blocks') return send(res, 200, { blocked: [] });

		// --- Bug melden (§28) ---
		if (m === 'POST' && p === '/v1/issues/uploads') {
			if (!user) return fail(res, 401, 'unauthorized');
			const type = String(req.headers['content-type'] || '').split(';')[0].trim();
			if (!['image/png', 'image/jpeg', 'image/webp'].includes(type)) return fail(res, 400, 'invalid_request', 'Bildtyp');
			let bytes;
			try {
				bytes = await readBody(req, MAX_UPLOAD);
			} catch (e) {
				if (e.tooLarge) return fail(res, 413, 'payload_too_large');
				throw e;
			}
			if (bytes.length === 0) return fail(res, 400, 'invalid_request', 'leer');
			const id = b64url(16).slice(0, 22);
			uploads.set(id, { owner: user.uuid, mime: type, bytes });
			const ext = type === 'image/png' ? 'png' : type === 'image/jpeg' ? 'jpg' : 'webp';
			fs.writeFileSync(path.join(outDir, `upload-${id}.${ext}`), bytes);
			say(`Upload ${id} ${type} ${bytes.length} Bytes von ${user.name}`);
			return send(res, 201, { upload: { id } });
		}
		if (m === 'POST' && p === '/v1/issues') {
			if (!user) return fail(res, 401, 'unauthorized');
			let b;
			try {
				b = JSON.parse((await readBody(req, 256 * 1024)).toString('utf8'));
			} catch {
				return fail(res, 400, 'invalid_request', 'JSON');
			}
			const title = String(b?.title || '');
			if (title.includes('#limit') || issuesToday >= 10) {
				return fail(res, 429, 'issue_daily_limit', 'Tageslimit erreicht', { 'Retry-After': '3600' });
			}
			if (title.includes('#banned')) return fail(res, 403, 'sanctioned', 'Konto hat eine Strafe');
			if (title.includes('#invalid')) return fail(res, 400, 'invalid_request', 'absichtlich ungültig');
			const bad = validateIssue(b);
			if (bad) {
				say(`ABGELEHNT (${bad})`);
				return fail(res, 400, 'invalid_request', bad);
			}
			for (const a of b.attachments || []) {
				if (title.includes('#lostupload') && !lostOnce.has(title)) {
					lostOnce.add(title);
					return fail(res, 404, 'upload_not_found');
				}
				const u = uploads.get(a);
				if (!u || u.owner !== user.uuid) return fail(res, 404, 'upload_not_found');
			}
			issuesToday++;
			const number = nextIssue++;
			const issue = {
				number, url: `${SITE}/issues/${number}`, type: b.type, area: b.area, title: b.title.trim(), status: 'open',
				createdAt: new Date().toISOString(),
			};
			fs.writeFileSync(path.join(outDir, `issue-${number}.json`),
				JSON.stringify({ received: b, author: user, issue }, null, 2));
			const meta = b.meta || {};
			say(`Issue #${number} „${issue.title}“ von ${user.name}: ${(b.attachments || []).length} Anhang/Anhänge, `
				+ `Mods ${meta.mods ? meta.mods.length : '-'}, Log ${meta.log ? meta.log.length + ' Zeichen' : '-'}, `
				+ `Version ${meta.modVersion || '-'} / ${meta.mcVersion || '-'} ${meta.loader || ''}`);
			if (meta.log) {
				const leaks = privacyCheck(meta.log);
				if (user.name && new RegExp(`(^|[^A-Za-z0-9_])${user.name}([^A-Za-z0-9_]|$)`, 'i').test(meta.log)) {
					leaks.push(`Spielername ${user.name}`);
				}
				if (leaks.length) say(`WARNUNG: Log enthält noch: ${leaks.join(' | ')}`);
				else say('Log-Prüfung: nichts Persönliches gefunden');
			}
			return send(res, 201, { issue });
		}

		return fail(res, 404, 'not_found');
	} catch (e) {
		say(`Fehler ${m} ${p}: ${e.message}`);
		if (!res.headersSent) fail(res, 500, 'internal');
	}
});

server.listen(port, '127.0.0.1', () => say(`Issue-Attrappe auf http://127.0.0.1:${port} – Ausgabe: ${outDir}`));
