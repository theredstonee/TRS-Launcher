package dev.theredstonee.trsclient.core.online;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache der Lookup-Ergebnisse (Abzeichen/Umhang je UUID) mit Stapelung der Anfragen.
 *
 * <ul>
 *   <li>Sichtbare Spieler (Tabliste + Sichtweite) meldet der Spiel-Thread jede Sekunde über {@link #observe}.</li>
 *   <li>Gefragt wird in Stapeln von höchstens 100 UUIDs, nie öfter als alle {@link #MIN_INTERVAL_MS}.</li>
 *   <li>Ergebnisse (auch "nichts") gelten {@link #TTL_MS}; betritt ein Spieler die Tabliste neu, wird er nach
 *       {@link #REJOIN_MS} erneut gefragt (neuer Umhang nach Wiederbeitritt).</li>
 *   <li>429/Fehler: Retry-After bzw. Backoff für alle Anfragen.</li>
 * </ul>
 * Lesen ({@link #get}) ist aus jedem Thread erlaubt; alles andere nur aus einem Thread (dem Spiel-Thread).
 */
public final class PlayerDirectory {
	public static final int MAX_BATCH = 100;
	public static final long TTL_MS = 5 * 60_000L;
	public static final long MIN_INTERVAL_MS = 2_000L;
	public static final long REJOIN_MS = 30_000L;
	/** Nicht mehr gesehene Spieler fliegen nach dieser Zeit aus dem Cache. */
	public static final long FORGET_MS = 15 * 60_000L;
	private static final long ERROR_BACKOFF_MS = 30_000L;

	private static final class Entry {
		volatile PlayerInfo info = PlayerInfo.NONE;
		long fetchedAt;
		long lastSeen;
		boolean stale = true;
	}

	private final Map<String, Entry> entries = new ConcurrentHashMap<>();
	/** Dieselben Einträge nach UUID – für {@link #get(UUID)} aus dem Render-Thread ohne Text-Umwandlung. */
	private final Map<UUID, Entry> byId = new ConcurrentHashMap<>();
	private Set<String> visible = new HashSet<>();
	private long lastRequest = Long.MIN_VALUE / 2;
	private long blockedUntil;
	private boolean inFlight;

	/** Aktuell sichtbare UUIDs (normalisiert). Neu hinzugekommene werden bei Bedarf neu gefragt. */
	public void observe(Collection<String> uuids, long now) {
		Set<String> next = new HashSet<>();
		for (String raw : uuids) {
			String uuid = Uuids.normalize(raw);
			if (uuid == null) continue;
			next.add(uuid);
			Entry e = entries.get(uuid);
			if (e == null) {
				e = add(uuid);
			} else if (!visible.contains(uuid) && now - e.fetchedAt >= REJOIN_MS) {
				e.stale = true;
			}
			e.lastSeen = now;
		}
		visible = next;
	}

	/**
	 * Nächster Stapel, der gefragt werden soll (höchstens 100), oder eine leere Liste. Liefert nur etwas,
	 * wenn keine Anfrage läuft, keine Sperre (429) aktiv ist und das Mindestintervall um ist.
	 */
	public List<String> nextBatch(long now) {
		List<String> batch = new ArrayList<>();
		if (inFlight || now < blockedUntil || now - lastRequest < MIN_INTERVAL_MS) return batch;
		for (String uuid : visible) {
			Entry e = entries.get(uuid);
			if (e == null) continue;
			if (e.stale || now - e.fetchedAt >= TTL_MS) {
				batch.add(uuid);
				if (batch.size() >= MAX_BATCH) break;
			}
		}
		if (!batch.isEmpty()) {
			inFlight = true;
			lastRequest = now;
		}
		return batch;
	}

	/** Antwort eingetroffen: gefundene UUIDs bekommen ihre Infos, fehlende = kein TRS. */
	public void complete(Collection<String> batch, Map<String, PlayerInfo> results, long now) {
		inFlight = false;
		for (String uuid : batch) {
			Entry e = entries.get(uuid);
			if (e == null) {
				e = add(uuid);
				e.lastSeen = now;
			}
			PlayerInfo info = results.get(uuid);
			e.info = info == null ? PlayerInfo.NONE : info;
			e.fetchedAt = now;
			e.stale = false;
		}
	}

	/** Anfrage fehlgeschlagen: Sperre für {@code retryAfterMs} (0 = Standard-Backoff). */
	public void failed(long now, long retryAfterMs) {
		inFlight = false;
		blockedUntil = now + (retryAfterMs > 0 ? retryAfterMs : ERROR_BACKOFF_MS);
	}

	/** Anfrage abgebrochen, ohne dass der Server etwas gesagt hat (z. B. neu anmelden) – gleich nochmal. */
	public void aborted() {
		inFlight = false;
		lastRequest = Long.MIN_VALUE / 2;
	}

	private Entry add(String uuid) {
		Entry e = new Entry();
		entries.put(uuid, e);
		UUID id = Uuids.toUuid(uuid);
		if (id != null) byId.put(id, e);
		return e;
	}

	/** Infos zu einem Spieler (aus jedem Thread, ohne Allokation); unbekannt → {@link PlayerInfo#NONE}. */
	public PlayerInfo get(UUID uuid) {
		if (uuid == null) return PlayerInfo.NONE;
		Entry e = byId.get(uuid);
		return e == null ? PlayerInfo.NONE : e.info;
	}

	/** Infos zu einem Spieler; unbekannt → {@link PlayerInfo#NONE}. */
	public PlayerInfo get(String uuid) {
		if (uuid == null) return PlayerInfo.NONE;
		Entry e = entries.get(uuid);
		return e == null ? PlayerInfo.NONE : e.info;
	}

	/** Eigenes Konto sofort neu fragen (z. B. nach dem Anmelden). */
	public void invalidate(String uuid) {
		Entry e = entries.get(uuid);
		if (e != null) e.stale = true;
	}

	/** Alles vergessen (Konto gewechselt, API abgeschaltet). */
	public void clear() {
		entries.clear();
		byId.clear();
		visible = new HashSet<>();
		inFlight = false;
		blockedUntil = 0;
	}

	/** Lange nicht gesehene Spieler entfernen. */
	public void prune(long now) {
		Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, Entry> e = it.next();
			if (!visible.contains(e.getKey()) && now - e.getValue().lastSeen > FORGET_MS) {
				it.remove();
				UUID id = Uuids.toUuid(e.getKey());
				if (id != null) byId.remove(id);
			}
		}
	}

	/**
	 * Sichtbare Spieler, die laut Lookup TRS nutzen (für den Ereignis-Stream, API.md §13.2). Aus dem
	 * Spiel-Thread.
	 */
	public List<String> visibleUsers() {
		List<String> out = new ArrayList<>();
		for (String uuid : visible) {
			Entry e = entries.get(uuid);
			if (e != null && e.info != PlayerInfo.NONE) out.add(uuid);
		}
		return out;
	}

	/** Alle aktuell bekannten Umhänge (für das Vorladen der Texturen). */
	public Set<CapeInfo> capes() {
		Set<CapeInfo> out = new LinkedHashSet<>();
		for (Entry e : entries.values()) {
			if (e.info.cape != null) out.add(e.info.cape);
		}
		return out;
	}

	public boolean inFlight() {
		return inFlight;
	}

	public long blockedUntil() {
		return blockedUntil;
	}

	int size() {
		return entries.size();
	}
}
