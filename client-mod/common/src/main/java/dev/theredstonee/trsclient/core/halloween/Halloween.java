package dev.theredstonee.trsclient.core.halloween;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Ist ein TRS-Event (gerade nur {@value #ID}) für diesen Spieler aktiv? Drei Quellen:
 * <ol>
 *   <li>Testschalter {@code -Dtrsclient.halloween=true|false} (bzw. {@link #force}) – gilt vor allem anderen.</li>
 *   <li>Die TRS API: {@code GET /v1/me} → {@code events} und das SSE-Ereignis {@code events_changed} – sobald sie einmal
 *   geantwortet hat, gilt nur noch sie (das Team kann das Event auch mitten im Spiel beenden).</li>
 *   <li>Vorher die Datei des Launchers ({@code launcher-theme.json} → {@code "event": "halloween"}), damit schon der
 *   Ladebildschirm beim Start passt.</li>
 * </ol>
 *
 * <p>Zwei Signale (Vertrag „Event-Look“): {@link #active()} = Event-<b>Funktionen</b> (Kürbis-Suche, Süßes oder Saures,
 * Sticker, Kosmetik/Umhang abholen, Abzeichen) und {@link #lookActive()} = Event-<b>Look</b> (Thema, Szenen, Ladebalken,
 * Atmosphäre/Filmkorn, Spuk-Effekte, Jumpscares, Spuk-Töne). Der Look ist an, wenn das Event aktiv ist, das Team ihn für
 * diesen Spieler nicht gesperrt hat ({@code me.eventLook} / {@code events_changed.look}), der Launcher ihn zeigt
 * ({@code launcher-theme.json → eventLook}) und der eigene Schalter „Event-Design anzeigen“ an ist.
 *
 * <p>Lesen ist billig ({@code volatile}-Felder) – die Oberfläche fragt in jedem Bild. Schreiben aus beliebigen Threads.
 */
public final class Halloween {
	/** ID des Events (API, launcher-theme.json, Freischaltung der Kosmetik). */
	public static final String ID = "halloween";
	/** Höchstens so viele Event-IDs werden übernommen (Schutz vor kaputten Antworten). */
	static final int MAX_EVENTS = 16;

	private static volatile Set<String> launcher = Collections.emptySet();
	/** null = die API hat noch nicht geantwortet. */
	private static volatile Set<String> online;
	/** null = kein Testschalter. */
	private static volatile Boolean forced = parseForce(System.getProperty("trsclient.halloween"));
	/** Steigt bei jeder Änderung (Oberflächen bauen damit Zwischenspeicher neu). */
	private static volatile int version;
	/** „Flackern &amp; Rauschen“ laut Launcher ({@code launcher-theme.json → halloweenFlicker}); null = Launcher sagt nichts. */
	private static volatile Boolean launcherFlicker;
	/**
	 * Events, deren Look dieser Spieler sehen soll, laut TRS API ({@code me.eventLook} / {@code events_changed.look}).
	 * null = die API hat (noch) keine Look-Liste geschickt – dann gilt die Event-Liste selbst.
	 */
	private static volatile Set<String> onlineLook;
	/** {@code launcher-theme.json → eventLook} (Server-Sperre oder Launcher-Schalter); fehlt = true. */
	private static volatile boolean launcherLook = true;
	/** Eigener Schalter „Event-Design anzeigen“ (Halloween-Modul); null = keiner registriert (Tests ohne Spiel) = an. */
	private static volatile BooleanSupplier ownLook;

	private Halloween() {
	}

	/**
	 * Halloween-<b>Funktionen</b> für diesen Spieler (Kürbis-Suche, Süßes oder Saures, Sticker, Kosmetik)? Für den Look
	 * {@link #lookActive()} nehmen.
	 */
	public static boolean active() {
		return eventActive(ID);
	}

	/** Halloween-<b>Look</b> (Thema, Szenen, Atmosphäre, Spuk-Effekte, Jumpscares, Spuk-Töne) für diesen Spieler? */
	public static boolean lookActive() {
		return eventLookActive(ID);
	}

	/**
	 * Look des Events {@code id}: Event aktiv ∧ nicht vom Team gesperrt (API) ∧ Launcher zeigt ihn ∧ eigener Schalter an.
	 * Der Testschalter erzwingt nur das Event – API-Sperre und Launcher-Feld zählen dann nicht, der eigene Schalter schon.
	 */
	public static boolean eventLookActive(String id) {
		if (!eventActive(id)) return false;
		if (!ownLookOn()) return false;
		Boolean f = forced;
		if (f != null && ID.equals(id)) return true;
		if (!launcherLook) return false;
		Set<String> look = onlineLook;
		return online == null || look == null || look.contains(id);
	}

	/** Eigener Schalter „Event-Design anzeigen“ (ohne registrierten Schalter: an). */
	public static boolean ownLookOn() {
		BooleanSupplier s = ownLook;
		if (s == null) return true;
		try {
			return s.getAsBoolean();
		} catch (RuntimeException e) {
			return true;
		}
	}

	/** Ist der Look nur durch Launcher/Team aus (Event aktiv, eigener Schalter an)? Für den Hinweis im TRS-Menü. */
	public static boolean lookBlocked() {
		return active() && ownLookOn() && !lookActive();
	}

	/** Quelle des eigenen Schalters (das Halloween-Modul registriert sich beim Start). */
	public static void ownLook(BooleanSupplier source) {
		ownLook = source;
	}

	/** Feld {@code eventLook} aus {@code launcher-theme.json} (null = Feld fehlt, alter Launcher = an). */
	public static void lookFromLauncher(Boolean show) {
		boolean next = show == null || show;
		if (next != launcherLook) {
			launcherLook = next;
			version++;
		}
	}

	/** Ist das Event {@code id} gerade für diesen Spieler aktiv (Testschalter beachtet nur {@value #ID})? */
	public static boolean eventActive(String id) {
		if (id == null) return false;
		Boolean f = forced;
		if (f != null && ID.equals(id)) return f;
		Set<String> o = online;
		return (o != null ? o : launcher).contains(id);
	}

	/** Zähler für Änderungen (z. B. Szene neu bauen). */
	public static int version() {
		return version;
	}

	/**
	 * Feld {@code halloweenFlicker} aus {@code launcher-theme.json} (Einstellung „Flackern &amp; Rauschen“ im Launcher):
	 * Standard des Clients, solange dort niemand selbst umgeschaltet hat. null = Feld fehlt.
	 */
	public static void flickerFromLauncher(Boolean on) {
		launcherFlicker = on;
	}

	/** Wert aus dem Launcher oder null. */
	public static Boolean launcherFlicker() {
		return launcherFlicker;
	}

	/** Feld {@code event} aus {@code launcher-theme.json} (null/leer = keins). */
	public static void fromLauncher(String event) {
		String e = clean(event);
		Set<String> next = e == null ? Collections.<String>emptySet() : Collections.singleton(e);
		if (!next.equals(launcher)) {
			launcher = next;
			version++;
		}
	}

	/**
	 * Aktive Events laut TRS API ({@code me.events} bzw. {@code events_changed}). {@code null} = die Antwort kennt das Feld
	 * nicht (alte API) – dann bleibt es bei der Launcher-Datei.
	 */
	public static void fromApi(Collection<String> events) {
		fromApi(events, null);
	}

	/**
	 * Aktive Events und Look-Liste laut TRS API ({@code me.events}/{@code me.eventLook} bzw. {@code events_changed}
	 * {@code events}/{@code look}). {@code events == null} = alte API, nichts ändert sich. {@code look == null} (alter
	 * Server ohne das Feld) = der Look folgt den Events.
	 */
	public static void fromApi(Collection<String> events, Collection<String> look) {
		if (events == null) return;
		Set<String> now = ids(events);
		Set<String> nowLook = look == null ? null : ids(look);
		boolean changed = false;
		if (!now.equals(online)) {
			online = now;
			changed = true;
		}
		Set<String> before = onlineLook;
		if (nowLook == null ? before != null : !nowLook.equals(before)) {
			onlineLook = nowLook;
			changed = true;
		}
		if (changed) version++;
	}

	private static Set<String> ids(Collection<String> raw) {
		Set<String> next = new LinkedHashSet<String>();
		for (String s : raw) {
			String e = clean(s);
			if (e != null && next.size() < MAX_EVENTS) next.add(e);
		}
		return Collections.unmodifiableSet(next);
	}

	/** Testschalter: {@code true}/{@code false} erzwingt, {@code null} hebt auf. */
	public static void force(Boolean on) {
		forced = on;
		version++;
	}

	/** Aktive Events (für Tests und Protokolle). */
	public static List<String> activeEvents() {
		Set<String> o = online;
		List<String> out = new ArrayList<String>(o != null ? o : launcher);
		Boolean f = forced;
		if (f != null) {
			out.remove(ID);
			if (f) out.add(0, ID);
		}
		return out;
	}

	/** Nur für Tests: alles zurücksetzen (Testschalter aus). */
	public static void reset() {
		launcher = Collections.emptySet();
		online = null;
		forced = null;
		launcherFlicker = null;
		onlineLook = null;
		launcherLook = true;
		ownLook = null;
		version++;
	}

	static Boolean parseForce(String value) {
		if (value == null) return null;
		String v = value.trim().toLowerCase(Locale.ROOT);
		if (v.equals("true") || v.equals("on") || v.equals("1")) return Boolean.TRUE;
		if (v.equals("false") || v.equals("off") || v.equals("0")) return Boolean.FALSE;
		return null;
	}

	/** Event-ID prüfen: {@code [a-z0-9_-]{1,32}}, sonst null. */
	static String clean(String s) {
		if (s == null) return null;
		String t = s.trim().toLowerCase(Locale.ROOT);
		if (t.isEmpty() || t.length() > 32) return null;
		for (int i = 0; i < t.length(); i++) {
			char c = t.charAt(i);
			if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) return null;
		}
		return t;
	}
}
