package dev.theredstonee.trsclient.core.camera;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Serverliste eines Moduls (z. B. „Freelook auf diesen Servern aus“): Einträge getrennt durch
 * Semikolon, Komma oder Leerzeichen. Ein Eintrag {@code example.net} trifft den Server selbst und
 * alle Subdomains ({@code mc.example.net}), {@code *.example.net} nur die Subdomains. Port und
 * Groß-/Kleinschreibung zählen nicht. Einzelspieler (keine Adresse) trifft nie.
 * <p>
 * Das Ergebnis wird für die letzte Kombination aus Adresse und Liste zwischengespeichert –
 * pro Tick aufgerufen entsteht dadurch keine Arbeit.
 */
public final class ServerList {
	private String lastAddress;
	private String lastList;
	private boolean lastResult;

	/** Steht die Adresse in der Liste? (zwischengespeichert) */
	public boolean contains(String address, String list) {
		if (address == null || list == null) return false;
		if (address.equals(lastAddress) && list.equals(lastList)) return lastResult;
		lastAddress = address;
		lastList = list;
		lastResult = matches(address, list);
		return lastResult;
	}

	/** Steht die Adresse in der Liste? */
	public static boolean matches(String address, String list) {
		String host = host(address);
		if (host.isEmpty() || list == null) return false;
		for (String entry : entries(list)) {
			if (entry.startsWith("*.")) {
				String domain = entry.substring(2);
				if (!domain.isEmpty() && host.endsWith("." + domain)) return true;
			} else if (host.equals(entry) || host.endsWith("." + entry)) {
				return true;
			}
		}
		return false;
	}

	/** Einträge der Liste, bereinigt (klein, ohne Port/Schema/Punkt am Ende). */
	public static List<String> entries(String list) {
		List<String> out = new ArrayList<String>();
		if (list == null) return out;
		for (String raw : list.split("[;,\\s]+")) {
			String e = raw.trim().toLowerCase(Locale.ROOT);
			boolean wildcard = e.startsWith("*.");
			String h = host(wildcard ? e.substring(2) : e);
			if (h.isEmpty()) continue;
			out.add(wildcard ? "*." + h : h);
		}
		return out;
	}

	/** Hostname einer Serveradresse: klein, ohne Schema, Port und abschließenden Punkt. */
	public static String host(String address) {
		if (address == null) return "";
		String a = address.trim().toLowerCase(Locale.ROOT);
		int scheme = a.indexOf("://");
		if (scheme >= 0) a = a.substring(scheme + 3);
		int slash = a.indexOf('/');
		if (slash >= 0) a = a.substring(0, slash);
		if (a.startsWith("[")) {
			// IPv6 in eckigen Klammern: [::1]:25565
			int end = a.indexOf(']');
			return end > 1 ? a.substring(1, end) : "";
		}
		int colon = a.indexOf(':');
		if (colon >= 0 && colon == a.lastIndexOf(':')) a = a.substring(0, colon);
		while (a.endsWith(".")) a = a.substring(0, a.length() - 1);
		return a;
	}
}
