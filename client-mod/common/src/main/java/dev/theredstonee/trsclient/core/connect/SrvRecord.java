package dev.theredstonee.trsclient.core.connect;

import java.util.List;

/**
 * Ein SRV-Eintrag {@code _minecraft._tcp.<host>} ("Priorität Gewicht Port Ziel."). Das Ziel bleibt genau so, wie es
 * aus dem DNS kommt (samt Schlusspunkt) – Vanilla gibt diesen Namen ab 1.17 unverändert im Handshake weiter, und
 * der Handshake soll Byte für Byte Vanilla bleiben.
 */
public final class SrvRecord {
	public final int priority;
	public final int weight;
	public final int port;
	public final String target;

	public SrvRecord(int priority, int weight, int port, String target) {
		this.priority = priority;
		this.weight = weight;
		this.port = port;
		this.target = target;
	}

	/**
	 * Eine Zeile "0 5 25565 mc.example.com." lesen; null = ungültig (falsche Feldzahl, Port außerhalb 1–65535,
	 * Ziel leer, "." (= „kein Dienst“, RFC 2782) oder länger als ein DNS-Name sein darf).
	 */
	public static SrvRecord parse(String line) {
		if (line == null) return null;
		String[] p = line.trim().split("\\s+");
		if (p.length != 4) return null;
		try {
			int prio = Integer.parseInt(p[0]);
			int weight = Integer.parseInt(p[1]);
			int port = Integer.parseInt(p[2]);
			String target = p[3];
			if (prio < 0 || prio > 65535 || weight < 0 || weight > 65535 || port < 1 || port > 65535) return null;
			if (target.isEmpty() || target.equals(".") || target.length() > 254) return null;
			for (int i = 0; i < target.length(); i++) {
				char ch = target.charAt(i);
				boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '-' || ch == '.' || ch == '_';
				if (!ok) return null;
			}
			return new SrvRecord(prio, weight, port, target);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * Den zu nutzenden Eintrag wählen: kleinste Priorität, darin das größte Gewicht (bei Gleichstand der erste).
	 * Fest statt zufällig, damit Launcher und Spiel dasselbe Ziel nehmen. null = kein gültiger Eintrag.
	 */
	public static SrvRecord pick(List<String> lines) {
		SrvRecord best = null;
		if (lines == null) return null;
		for (String l : lines) {
			SrvRecord r = parse(l);
			if (r == null) continue;
			if (best == null || r.priority < best.priority || (r.priority == best.priority && r.weight > best.weight)) best = r;
		}
		return best;
	}

	@Override
	public String toString() {
		return priority + " " + weight + " " + port + " " + target;
	}
}
