package dev.theredstonee.trsclient.core.hosting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.hosting.net.P2pKeys;
import dev.theredstonee.trsclient.core.hosting.net.UdpLink;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Direkte Verbindung aufbauen (P2P, API.md §21.4 „Suggested flow“): Kandidaten sammeln (eigene Adressen + STUN über
 * denselben UDP-Anschluss), per Signal tauschen ({@code offer} vom Gast, {@code answer} vom Host), Löcher stanzen.
 * Klappt das nicht innerhalb des Zeitbudgets, liefert der Versuch null und der Aufrufer nimmt das Relay.
 *
 * <p>Blockierend – nur aus Hintergrund-Threads.
 *
 * <p>Signal-Daten (JSON, ≤ 4096 Zeichen): {@code {"v":1,"k":"<16 Bytes base64>","c":["ip:port",…],"mc":"…","l":"…"}}.
 */
public final class DirectConnect {
	public static final long STUN_MS = 1500L;
	/** Gesamtbudget eines Versuchs (dann Relay). */
	public static final long BUDGET_MS = 7000L;
	private static final SecureRandom RANDOM = new SecureRandom();

	private DirectConnect() {
	}

	/** Protokoll der Schritte (Zeiten, Anzahl Kandidaten – nie Adressen oder Schlüssel). */
	public static volatile java.util.function.Consumer<String> log;

	static void log(String m) {
		java.util.function.Consumer<String> l = log;
		if (l != null) l.accept("TRS Hosting P2P: " + m);
	}

	/** Signale verschicken (über die API). */
	public interface Signaller {
		void send(String to, String kind, String sid, String data) throws Exception;
	}

	/** Zerlegtes Angebot/Antwort. */
	public static final class Ice {
		public final byte[] nonce;
		public final List<InetSocketAddress> candidates;
		public final String mc;
		public final String loader;

		public Ice(byte[] nonce, List<InetSocketAddress> candidates, String mc, String loader) {
			this.nonce = nonce;
			this.candidates = candidates;
			this.mc = mc;
			this.loader = loader;
		}

		public String json() {
			JsonObject o = new JsonObject();
			o.addProperty("v", 1);
			o.addProperty("k", Base64.getEncoder().encodeToString(nonce));
			JsonArray c = new JsonArray();
			for (InetSocketAddress a : candidates) c.add(new com.google.gson.JsonPrimitive(UdpLink.formatCandidate(a)));
			o.add("c", c);
			if (mc != null) o.addProperty("mc", mc);
			if (loader != null) o.addProperty("l", loader);
			return o.toString();
		}

		/** Kaputt/fremd → null. Höchstens 12 Kandidaten, nur IPv4-Literale. */
		@SuppressWarnings("deprecation")
		public static Ice parse(String data) {
			if (data == null || data.length() > 4096) return null;
			try {
				JsonElement e = new JsonParser().parse(data);
				if (!e.isJsonObject()) return null;
				JsonObject o = e.getAsJsonObject();
				if (!o.has("v") || o.get("v").getAsInt() != 1 || !o.has("k")) return null;
				byte[] k = Base64.getDecoder().decode(o.get("k").getAsString());
				if (k.length != P2pKeys.NONCE) return null;
				List<InetSocketAddress> cands = new ArrayList<InetSocketAddress>();
				if (o.has("c") && o.get("c").isJsonArray()) {
					for (JsonElement c : o.getAsJsonArray("c")) {
						if (cands.size() >= 12) break;
						InetSocketAddress a = c.isJsonPrimitive() ? UdpLink.parseCandidate(c.getAsString()) : null;
						if (a != null) cands.add(a);
					}
				}
				String mc = o.has("mc") ? Rooms.version(o.get("mc").getAsString()) : null;
				String l = o.has("l") ? Rooms.loader(o.get("l").getAsString()) : null;
				return new Ice(k, cands, mc, l);
			} catch (RuntimeException ex) {
				return null;
			}
		}
	}

	/** Neue Versuchs-ID ({@code sid}, 16 Zeichen). */
	public static String newSid() {
		byte[] b = new byte[12];
		RANDOM.nextBytes(b);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}

	/** "host:port" → Adressen (DNS-Abfrage, daher Hintergrund-Thread). */
	public static List<InetSocketAddress> resolve(List<String> servers) {
		List<InetSocketAddress> out = new ArrayList<InetSocketAddress>();
		if (servers == null) return out;
		for (String s : servers) {
			int c = s.lastIndexOf(':');
			if (c <= 0) continue;
			try {
				InetSocketAddress a = new InetSocketAddress(s.substring(0, c), Integer.parseInt(s.substring(c + 1)));
				if (!a.isUnresolved()) out.add(a);
			} catch (RuntimeException ignored) {
				// ungültig
			}
		}
		return out;
	}

	static List<InetSocketAddress> gather(UdpLink link, List<InetSocketAddress> stun, boolean loopback) throws IOException {
		List<InetSocketAddress> c = new ArrayList<InetSocketAddress>(link.hostCandidates(loopback));
		InetSocketAddress srflx = link.stun(stun, STUN_MS);
		if (srflx != null && !c.contains(srflx)) c.add(0, srflx);
		return c;
	}

	/**
	 * Gast (wählt das Paar): Angebot schicken, auf Antwort warten, stanzen. Liefert eine bereite, noch nicht
	 * gestartete Verbindung oder null (dann ist {@code bye} verschickt).
	 */
	public static UdpLink guest(SignalBox box, Signaller sig, String hostUuid, List<InetSocketAddress> stun,
			boolean loopback, long budgetMs, String mc, String loader) {
		long end = System.currentTimeMillis() + budgetMs;
		String sid = newSid();
		SignalBox.Session session = box.open(hostUuid, sid);
		UdpLink link = null;
		boolean ok = false;
		try {
			long t0 = System.currentTimeMillis();
			link = UdpLink.open();
			byte[] mine = P2pKeys.nonce();
			List<InetSocketAddress> cands = gather(link, stun, loopback);
			log("Gast: " + cands.size() + " Kandidaten nach " + (System.currentTimeMillis() - t0) + " ms");
			sig.send(hostUuid, "offer", sid, new Ice(mine, cands, mc, loader).json());
			Ice answer = null;
			while (answer == null) {
				long left = end - System.currentTimeMillis();
				if (left <= 0) {
					log("Gast: keine Antwort nach " + (System.currentTimeMillis() - t0) + " ms");
					return null;
				}
				SignalBox.Signal s = session.poll(left);
				if (s == null) {
					log("Gast: keine Antwort nach " + (System.currentTimeMillis() - t0) + " ms");
					return null;
				}
				if ("bye".equals(s.kind)) {
					log("Gast: Host lehnt Direktverbindung ab");
					return null;
				}
				if ("answer".equals(s.kind)) answer = Ice.parse(s.data);
			}
			log("Gast: Antwort mit " + answer.candidates.size() + " Kandidaten nach " + (System.currentTimeMillis() - t0) + " ms");
			P2pKeys keys = new P2pKeys(mine, answer.nonce, sid);
			long left = end - System.currentTimeMillis();
			if (left <= 0) return null;
			ok = link.punch(keys, UdpLink.Role.CONTROLLING, answer.candidates, left);
			log("Gast: Lochstanzen " + (ok ? "ok" : "gescheitert") + " nach " + (System.currentTimeMillis() - t0) + " ms");
			return ok ? link : null;
		} catch (Exception e) {
			return null;
		} finally {
			session.close();
			if (!ok) {
				if (link != null) link.close("no direct path");
				try {
					sig.send(hostUuid, "bye", sid, "");
				} catch (Exception ignored) {
					// egal – der Host gibt nach seinem Budget auf
				}
			}
		}
	}

	/**
	 * Host (übernimmt das Paar): auf ein Angebot antworten und stanzen. Liefert eine bereite Verbindung oder null.
	 */
	public static UdpLink host(SignalBox box, Signaller sig, SignalBox.Signal offer, List<InetSocketAddress> stun,
			boolean loopback, long budgetMs, String mc, String loader) {
		Ice o = Ice.parse(offer.data);
		if (o == null || offer.sid == null) return null;
		long end = System.currentTimeMillis() + budgetMs;
		final SignalBox.Session session = box.open(offer.from, offer.sid);
		UdpLink link = null;
		boolean ok = false;
		try {
			long t0 = System.currentTimeMillis();
			link = UdpLink.open();
			byte[] mine = P2pKeys.nonce();
			List<InetSocketAddress> cands = gather(link, stun, loopback);
			sig.send(offer.from, "answer", offer.sid, new Ice(mine, cands, mc, loader).json());
			log("Host: Antwort mit " + cands.size() + " Kandidaten nach " + (System.currentTimeMillis() - t0) + " ms");
			P2pKeys keys = new P2pKeys(o.nonce, mine, offer.sid);
			// „bye“ des Gasts bricht das Stanzen ab.
			final UdpLink watched = link;
			Thread watch = new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						while (!session.isClosed()) {
							SignalBox.Signal s = session.poll(500);
							if (s != null && "bye".equals(s.kind)) {
								watched.close("bye");
								return;
							}
						}
					} catch (InterruptedException ignored) {
						// Ende
					}
				}
			}, "TRS-P2P-Signal");
			watch.setDaemon(true);
			watch.start();
			long left = end - System.currentTimeMillis();
			if (left <= 0) return null;
			ok = link.punch(keys, UdpLink.Role.CONTROLLED, o.candidates, left) && link.isOpen();
			log("Host: Lochstanzen " + (ok ? "ok" : "gescheitert") + " nach " + (System.currentTimeMillis() - t0) + " ms");
			return ok ? link : null;
		} catch (Exception e) {
			return null;
		} finally {
			session.close();
			if (!ok && link != null) link.close("no direct path");
		}
	}
}
