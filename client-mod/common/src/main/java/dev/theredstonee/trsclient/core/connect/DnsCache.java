package dev.theredstonee.trsclient.core.connect;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Kleiner DNS-Zwischenspeicher für „Schnell verbinden“: A/AAAA-Adressen und SRV-Ziele je Hostname, mit Ablaufzeit.
 * Die DNS-TTL gilt, wo sie bekannt ist (Launcher-Hinweise), sonst {@link #DEFAULT_TTL_MS}; nie länger als
 * {@link #MAX_TTL_MS}. Außerdem merkt er sich, welche Adresse zuletzt am schnellsten antwortete (die kommt beim
 * nächsten Mal zuerst dran). Thread-sicher; alle Zeiten in ms der Wanduhr (Launcher-Hinweise kommen von außen).
 */
public final class DnsCache {
	/** Höchstens so lange gilt ein Eintrag, egal was die TTL sagt. */
	public static final long MAX_TTL_MS = 10 * 60_000L;
	/** Ohne bekannte TTL (Java-Resolver, JNDI) – kurz, weil eine IP sich bei Heimservern schnell ändern kann. */
	public static final long DEFAULT_TTL_MS = 2 * 60_000L;
	/** Mindestens so lange (TTL 0 heißt bei manchen DNS „nicht speichern“, ein Verbindungsaufbau dauert aber). */
	public static final long MIN_TTL_MS = 5_000L;
	/** So lange gilt „diese Adresse war zuletzt die schnellste“. */
	public static final long WINNER_MS = 30 * 60_000L;
	static final int MAX_HOSTS = 256;

	/** Woher ein Eintrag stammt (für Log und Seite im TRS-Menü). */
	public enum Source {
		/** Vom Spiel selbst aufgelöst. */
		GAME,
		/** Vom TRS Launcher gemessen ({@code connect-hints.json}). */
		LAUNCHER
	}

	/** Gespeicherte Adressen eines Hosts. */
	public static final class Addresses {
		/** Rohadressen (4 oder 16 Byte) in Reihenfolge des Resolvers bzw. nach Messung des Launchers. */
		public final List<byte[]> ips;
		public final long expires;
		public final Source source;

		Addresses(List<byte[]> ips, long expires, Source source) {
			this.ips = ips;
			this.expires = expires;
			this.source = source;
		}
	}

	/** SRV-Ergebnis: {@link #record} null = „kein SRV-Eintrag“ (sicher gewusst). */
	public static final class Srv {
		public final SrvRecord record;
		public final long expires;

		Srv(SrvRecord record, long expires) {
			this.record = record;
			this.expires = expires;
		}
	}

	private static final class Winner {
		final byte[] ip;
		final long until;

		Winner(byte[] ip, long until) {
			this.ip = ip;
			this.until = until;
		}
	}

	/** Uhr (für Tests ersetzbar). */
	public interface Clock {
		long millis();
	}

	public static final Clock WALL = new Clock() {
		@Override
		public long millis() {
			return System.currentTimeMillis();
		}
	};

	private final Clock clock;
	private final Map<String, Addresses> addresses = new LinkedHashMap<String, Addresses>();
	private final Map<String, Srv> srv = new LinkedHashMap<String, Srv>();
	private final Map<String, Winner> winners = new LinkedHashMap<String, Winner>();

	public DnsCache() {
		this(WALL);
	}

	public DnsCache(Clock clock) {
		this.clock = clock;
	}

	public long now() {
		return clock.millis();
	}

	/** Schlüssel eines Hostnamens: klein, ohne Schlusspunkt ("Mc.Example.com." = "mc.example.com"). */
	public static String key(String host) {
		if (host == null) return "";
		String h = host.trim().toLowerCase(Locale.ROOT);
		while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
		return h;
	}

	/** Ablaufzeit für eine TTL in Sekunden (&lt; 0 = unbekannt). */
	public long expiry(long ttlSeconds) {
		long ms = ttlSeconds < 0 ? DEFAULT_TTL_MS : Math.max(MIN_TTL_MS, Math.min(MAX_TTL_MS, ttlSeconds * 1000L));
		return now() + ms;
	}

	/** Adressen speichern (leere Liste = nichts tun). */
	public void putAddresses(String host, List<byte[]> ips, long ttlSeconds, Source source) {
		putAddressesUntil(host, ips, expiry(ttlSeconds), source);
	}

	/** Wie {@link #putAddresses}, mit fester Ablaufzeit (höchstens {@link #MAX_TTL_MS} ab jetzt). */
	public void putAddressesUntil(String host, List<byte[]> ips, long expires, Source source) {
		String k = key(host);
		if (k.isEmpty() || ips == null || ips.isEmpty()) return;
		long now = now();
		long until = Math.min(expires, now + MAX_TTL_MS);
		if (until <= now) return;
		List<byte[]> copy = new ArrayList<byte[]>();
		for (byte[] ip : ips) {
			if (ip == null || (ip.length != 4 && ip.length != 16) || contains(copy, ip)) continue;
			copy.add(ip.clone());
			if (copy.size() >= 16) break;
		}
		if (copy.isEmpty()) return;
		synchronized (this) {
			addresses.remove(k);
			addresses.put(k, new Addresses(Collections.unmodifiableList(copy), until, source));
			trim(addresses);
		}
	}

	/** Gültige Adressen eines Hosts oder null. */
	public synchronized Addresses addresses(String host) {
		String k = key(host);
		Addresses a = addresses.get(k);
		if (a == null) return null;
		if (a.expires <= now()) {
			addresses.remove(k);
			return null;
		}
		return a;
	}

	/**
	 * Gültige Adressen als {@link InetAddress} mit genau dem Namen {@code host} (wie Vanilla ihn sieht – wichtig,
	 * weil Minecraft daraus den Handshake-Namen liest); die zuletzt schnellste zuerst. null = nichts gespeichert.
	 */
	public List<InetAddress> inetAddresses(String host) {
		Addresses a = addresses(host);
		if (a == null) return null;
		List<byte[]> ips = new ArrayList<byte[]>(a.ips);
		byte[] w = winner(host);
		if (w != null) {
			for (int i = 0; i < ips.size(); i++) {
				if (Arrays.equals(ips.get(i), w)) {
					ips.add(0, ips.remove(i));
					break;
				}
			}
		}
		List<InetAddress> out = new ArrayList<InetAddress>();
		for (byte[] ip : ips) {
			try {
				out.add(InetAddress.getByAddress(host, ip));
			} catch (UnknownHostException ignored) {
				// nur bei falscher Länge – oben schon geprüft
			}
		}
		return out.isEmpty() ? null : out;
	}

	/** SRV-Ergebnis speichern ({@code record} null = sicher kein Eintrag). */
	public void putSrv(String host, SrvRecord record, long ttlSeconds) {
		String k = key(host);
		if (k.isEmpty()) return;
		long until = expiry(ttlSeconds);
		synchronized (this) {
			srv.remove(k);
			srv.put(k, new Srv(record, until));
			trim(srv);
		}
	}

	/** Wie {@link #putSrv} mit fester Ablaufzeit. */
	public void putSrvUntil(String host, SrvRecord record, long expires) {
		String k = key(host);
		long now = now();
		long until = Math.min(expires, now + MAX_TTL_MS);
		if (k.isEmpty() || until <= now) return;
		synchronized (this) {
			srv.remove(k);
			srv.put(k, new Srv(record, until));
			trim(srv);
		}
	}

	/** Gültiges SRV-Ergebnis oder null (= unbekannt, nachschlagen). */
	public synchronized Srv srv(String host) {
		String k = key(host);
		Srv s = srv.get(k);
		if (s == null) return null;
		if (s.expires <= now()) {
			srv.remove(k);
			return null;
		}
		return s;
	}

	/** Diese Adresse hat gerade gewonnen (kommt beim nächsten Mal zuerst). */
	public void winner(String host, byte[] ip) {
		String k = key(host);
		if (k.isEmpty() || ip == null) return;
		synchronized (this) {
			winners.remove(k);
			winners.put(k, new Winner(ip.clone(), now() + WINNER_MS));
			trim(winners);
		}
	}

	/** Zuletzt schnellste Adresse oder null. */
	public synchronized byte[] winner(String host) {
		Winner w = winners.get(key(host));
		if (w == null || w.until <= now()) return null;
		return w.ip.clone();
	}

	/** Alles zu einem Host vergessen (z. B. wenn keine gespeicherte Adresse mehr antwortete). */
	public synchronized void invalidate(String host) {
		String k = key(host);
		addresses.remove(k);
		srv.remove(k);
		winners.remove(k);
	}

	public synchronized int size() {
		return addresses.size();
	}

	public synchronized void clear() {
		addresses.clear();
		srv.clear();
		winners.clear();
	}

	private static boolean contains(List<byte[]> list, byte[] ip) {
		for (byte[] b : list) if (Arrays.equals(b, ip)) return true;
		return false;
	}

	/** Älteste Einträge (Einfügereihenfolge) über {@link #MAX_HOSTS} verwerfen. */
	private static void trim(Map<String, ?> map) {
		Iterator<String> it = map.keySet().iterator();
		while (map.size() > MAX_HOSTS && it.hasNext()) {
			it.next();
			it.remove();
		}
	}
}
