package dev.theredstonee.trsclient.core.hosting;

import dev.theredstonee.trsclient.core.online.Uuids;
import dev.theredstonee.trsclient.core.social.SafeText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Bereinigte Ansichten der Welt-Hosting-API (API.md §21.1). Alles, was aus der API kommt, wird hier geprüft: IDs per
 * Muster, Namen ohne Steuerzeichen, Zahlen in Grenzen. Unveränderlich.
 */
public final class Rooms {
	public static final int MIN_PLAYERS = 2;
	public static final int MAX_PLAYERS = 10;
	public static final int MAX_NAME = 32;
	/** Zeichen eines Beitrittscodes (ohne 0/O, 1/I/L). */
	public static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

	private Rooms() {
	}

	/** Mitglied einer Welt (nur der Host sieht die Liste). */
	public static final class Member {
		public final String uuid;
		public final String name;
		/** invited | requested | accepted | banned */
		public final String state;
		public final long since;

		public Member(String uuid, String name, String state, long since) {
			this.uuid = uuid;
			this.name = name;
			this.state = state;
			this.since = since;
		}
	}

	/** Eine Welt. Für Gäste sind {@link #code}, {@link #visibility} und {@link #members} leer. */
	public static final class Room {
		public final String id;
		public final String code;
		public final String name;
		public final String hostUuid;
		public final String hostName;
		public final String mcVersion;
		public final String loader;
		public final int maxPlayers;
		public final String gameMode;
		public final boolean pvp;
		public final boolean cheats;
		public final boolean open;
		/** friends | invited (nur Host) */
		public final String visibility;
		public final int players;
		public final long expiresAt;
		public final List<Member> members;
		/** Eigener Stand als Gast: invited | requested | accepted | null. */
		public final String myState;

		public Room(String id, String code, String name, String hostUuid, String hostName, String mcVersion,
				String loader, int maxPlayers, String gameMode, boolean pvp, boolean cheats, boolean open,
				String visibility, int players, long expiresAt, List<Member> members, String myState) {
			this.id = id;
			this.code = code;
			this.name = name;
			this.hostUuid = hostUuid;
			this.hostName = hostName;
			this.mcVersion = mcVersion;
			this.loader = loader;
			this.maxPlayers = maxPlayers;
			this.gameMode = gameMode;
			this.pvp = pvp;
			this.cheats = cheats;
			this.open = open;
			this.visibility = visibility;
			this.players = players;
			this.expiresAt = expiresAt;
			this.members = members == null ? Collections.<Member>emptyList() : Collections.unmodifiableList(members);
			this.myState = myState;
		}

		public Member member(String uuid) {
			for (Member m : members) if (m.uuid.equals(uuid)) return m;
			return null;
		}

		public List<Member> members(String state) {
			List<Member> out = new ArrayList<Member>();
			for (Member m : members) if (m.state.equals(state)) out.add(m);
			return out;
		}

		/** Code als "ABC-DEF" (null ohne Code). */
		public String prettyCode() {
			return code == null ? null : Rooms.prettyCode(code);
		}
	}

	/** Verbindungsdaten (Relay + STUN). Das Token ist geheim – nie loggen (toString verrät es nicht). */
	public static final class ConnectInfo {
		public final String role;
		public final String relayHost;
		public final int tcpPort;
		public final int udpPort;
		public final String token;
		public final long expiresAt;
		public final List<String> stun;

		public ConnectInfo(String role, String relayHost, int tcpPort, int udpPort, String token, long expiresAt,
				List<String> stun) {
			this.role = role;
			this.relayHost = relayHost;
			this.tcpPort = tcpPort;
			this.udpPort = udpPort;
			this.token = token;
			this.expiresAt = expiresAt;
			this.stun = stun == null ? Collections.<String>emptyList() : Collections.unmodifiableList(stun);
		}

		@Override
		public String toString() {
			return "ConnectInfo{" + role + " " + relayHost + ":" + tcpPort + "}";
		}
	}

	// --- Prüfen ---

	public static boolean validRoomId(String id) {
		return id != null && id.matches("h[0-9a-f]{20}");
	}

	/** Eingabe "abc-def", "ABC DEF" … → "ABCDEF" oder null (falsche Zeichen/Länge). */
	public static String normalizeCode(String input) {
		if (input == null) return null;
		StringBuilder sb = new StringBuilder(6);
		for (int i = 0; i < input.length(); i++) {
			char c = Character.toUpperCase(input.charAt(i));
			if (c == ' ' || c == '-' || c == '\t') continue;
			if (CODE_ALPHABET.indexOf(c) < 0) return null;
			sb.append(c);
			if (sb.length() > 6) return null;
		}
		return sb.length() == 6 ? sb.toString() : null;
	}

	public static String prettyCode(String code) {
		return code.length() == 6 ? code.substring(0, 3) + "-" + code.substring(3) : code;
	}

	static String gameMode(String g) {
		return g != null && g.matches("survival|creative|adventure|spectator") ? g : "survival";
	}

	static String loader(String l) {
		return l != null && l.matches("vanilla|fabric|forge|neoforge|quilt") ? l : "vanilla";
	}

	static String version(String v) {
		return v != null && v.matches("[0-9A-Za-z][0-9A-Za-z._+ -]{0,31}") ? v : "?";
	}

	static String name(String n) {
		String s = n == null ? null : SafeText.line(n, MAX_NAME);
		return s == null || s.isEmpty() ? "?" : s;
	}

	static String player(String n) {
		String s = n == null ? null : SafeText.playerName(n);
		return s == null ? "?" : s;
	}

	static String uuid(String u) {
		return Uuids.normalize(u);
	}

	static int clamp(Integer v, int min, int max, int def) {
		return v == null ? def : Math.max(min, Math.min(max, v));
	}

	/** Gleiche Version + Loader? (Vanilla-Gäste passen zu jedem Loader, nicht umgekehrt.) */
	public static boolean compatible(Room room, String myVersion, String myLoader) {
		if (room == null) return false;
		if (!room.mcVersion.equals(myVersion)) return false;
		String l = room.loader.toLowerCase(Locale.ROOT);
		return l.equals(myLoader) || l.equals("vanilla");
	}
}
