package dev.theredstonee.trsclient.core.online;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Unveränderlicher Stand der Freundesliste ({@code GET /v1/friends}, {@code GET /v1/blocks}): Freunde mit
 * Online-Status, eingehende/ausgehende Anfragen, Blockierte. Streng bereinigt (ungültige UUIDs/Namen fallen
 * weg, unbekannte Loader werden „vanilla“, Server nur als gültige Adresse).
 */
public final class FriendsView {
	public static final FriendsView EMPTY = new FriendsView(Collections.<Friend>emptyList(),
			Collections.<User>emptyList(), Collections.<User>emptyList());

	/** Ein Spieler (Anfrage, Blockierter). */
	public static final class User {
		public final String uuid;
		public final String name;
		/** Zeitpunkt (ISO 8601) oder null. */
		public final String at;

		public User(String uuid, String name, String at) {
			this.uuid = uuid;
			this.name = name;
			this.at = at;
		}
	}

	/** Ein Freund mit Online-Status. */
	public static final class Friend {
		public final String uuid;
		public final String name;
		/** "in-game", "online" oder null (offline/unsichtbar). */
		public final String state;
		public final String version;
		public final String loader;
		/** Server-Adresse (nur wenn der Freund sie teilt) oder null. */
		public final String server;

		public Friend(String uuid, String name, String state, String version, String loader, String server) {
			this.uuid = uuid;
			this.name = name;
			this.state = state;
			this.version = version;
			this.loader = loader;
			this.server = server;
		}

		public boolean inGame() {
			return "in-game".equals(state);
		}

		public boolean online() {
			return state != null;
		}
	}

	public final List<Friend> friends;
	public final List<User> incoming;
	public final List<User> outgoing;

	public FriendsView(List<Friend> friends, List<User> incoming, List<User> outgoing) {
		this.friends = Collections.unmodifiableList(new ArrayList<>(friends));
		this.incoming = Collections.unmodifiableList(new ArrayList<>(incoming));
		this.outgoing = Collections.unmodifiableList(new ArrayList<>(outgoing));
	}

	/** Wie viele Freunde online (im Spiel oder im Launcher) sind. */
	public int onlineCount() {
		int n = 0;
		for (Friend f : friends) if (f.online()) n++;
		return n;
	}

	/** Freunde, die gerade auf diesem Server spielen (Adressen normalisiert verglichen). */
	public List<Friend> onServer(String address) {
		String want = dev.theredstonee.trsclient.core.menus.ServerPins.normalize(address);
		if (want == null) return Collections.emptyList();
		List<Friend> out = new ArrayList<>();
		for (Friend f : friends) {
			if (f.inGame() && f.server != null && want.equals(dev.theredstonee.trsclient.core.menus.ServerPins.normalize(f.server))) {
				out.add(f);
			}
		}
		return out;
	}

	/** Im Spiel zuerst, dann online, dann offline – jeweils nach Name. */
	public static List<Friend> sorted(List<Friend> friends) {
		List<Friend> out = new ArrayList<>(friends);
		Collections.sort(out, new Comparator<Friend>() {
			@Override
			public int compare(Friend a, Friend b) {
				int ra = a.inGame() ? 0 : a.online() ? 1 : 2;
				int rb = b.inGame() ? 0 : b.online() ? 1 : 2;
				if (ra != rb) return ra - rb;
				return a.name.toLowerCase(Locale.ROOT).compareTo(b.name.toLowerCase(Locale.ROOT));
			}
		});
		return out;
	}

	// --- Bereinigen (API-Antwort → Ansicht) ---

	static final String NAME = "[A-Za-z0-9_]{1,16}";

	static String name(String raw) {
		return raw != null && raw.matches(NAME) ? raw : null;
	}

	static String time(String raw) {
		return raw != null && raw.length() <= 40 && raw.matches("[0-9T:.\\-+Z]+") ? raw : null;
	}

	static String state(String raw) {
		return "in-game".equals(raw) || "online".equals(raw) ? raw : null;
	}

	static String loader(String raw) {
		if (raw == null) return null;
		switch (raw) {
			case "vanilla":
			case "fabric":
			case "quilt":
			case "forge":
			case "neoforge":
				return raw;
			default:
				return "vanilla";
		}
	}

	static String version(String raw) {
		return raw != null && raw.matches("[0-9A-Za-z._+ -]{1,32}") ? raw : null;
	}

	/** Server-Adresse nur in erlaubter Form (Host/IPv4 + Port), sonst null. */
	static String server(String raw) {
		String s = TrsOnline.cleanServer(raw);
		return s != null && s.length() <= 261 ? s : null;
	}

	/** Anzeige-Text einer Adresse ohne Standard-Port. */
	public static String displayServer(String server) {
		if (server == null) return null;
		return server.endsWith(":25565") ? server.substring(0, server.length() - 6) : server;
	}

	/** Gültige Eingabe für „Freund hinzufügen“/„Blockieren“: Minecraft-Name oder UUID. */
	public static String target(String raw) {
		if (raw == null) return null;
		String t = raw.trim();
		if (t.matches(NAME)) return t;
		return Uuids.normalize(t);
	}
}
