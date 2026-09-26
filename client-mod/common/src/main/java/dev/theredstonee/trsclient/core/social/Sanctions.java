package dev.theredstonee.trsclient.core.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Die eigenen Strafen im Spiel (API.md §22): aktive und vergangene, aktualisiert aus {@code GET /v1/me/sanctions},
 * Ereignissen und Fehlern. Reine Logik, nur Spiel-Thread.
 */
public final class Sanctions {
	/** Einspruch: so viele Zeichen (UTF-16 wie die API, nach dem Säubern). */
	public static final int APPEAL_MIN = 20;
	public static final int APPEAL_MAX = 1000;

	/** Was sich an einer Strafe geändert hat (für die Benachrichtigung). */
	public enum Change {
		NONE, ADDED, LIFTED, SHORTENED, EXTENDED, EXPIRED, APPEAL_FILED
	}

	private final List<Sanction> active = new ArrayList<Sanction>();
	private final List<Sanction> past = new ArrayList<Sanction>();
	private boolean loaded;
	/** Server kennt Moderation v2 (Liste geladen oder ein Ereignis/Fehler mit Strafe gesehen). */
	private boolean supported;
	private int version;

	public boolean loaded() {
		return loaded;
	}

	public boolean supported() {
		return supported;
	}

	void supported(boolean value) {
		if (supported != value) version++;
		supported = value;
	}

	/** Wird bei jeder Änderung erhöht. */
	public int version() {
		return version;
	}

	/** Aktive Strafen (neueste zuerst). */
	public List<Sanction> active() {
		return Collections.unmodifiableList(active);
	}

	/** Vergangene (abgelaufen/aufgehoben, neueste zuerst). */
	public List<Sanction> past() {
		return Collections.unmodifiableList(past);
	}

	public Sanction find(long id) {
		for (Sanction s : active) if (s.id == id) return s;
		for (Sanction s : past) if (s.id == id) return s;
		return null;
	}

	/** Vollständige Liste vom Server. */
	void setAll(List<Sanction> newActive, List<Sanction> newPast, long now) {
		active.clear();
		past.clear();
		for (Sanction s : newActive) place(s, now);
		for (Sanction s : newPast) place(s, now);
		SanctionJson.sort(active);
		SanctionJson.sort(past);
		loaded = true;
		supported = true;
		version++;
	}

	/** Einzelne Strafe einspielen (Ereignis, Fehler, Einspruch). Rückgabe: was sich geändert hat. */
	Change apply(Sanction s, long now) {
		if (s == null) return Change.NONE;
		supported = true;
		Sanction prev = find(s.id);
		remove(s.id);
		place(s, now);
		SanctionJson.sort(active);
		SanctionJson.sort(past);
		version++;
		return classify(prev, s, now);
	}

	private void place(Sanction s, long now) {
		if (s.active(now)) active.add(s);
		else past.add("active".equals(s.status) ? s.expired() : s);
	}

	private void remove(long id) {
		for (Iterator<Sanction> it = active.iterator(); it.hasNext(); ) if (it.next().id == id) it.remove();
		for (Iterator<Sanction> it = past.iterator(); it.hasNext(); ) if (it.next().id == id) it.remove();
	}

	/** Zeitlich abgelaufene nach „vergangen“ schieben; Rückgabe: die abgelaufenen (für Hinweise). */
	List<Sanction> expire(long now) {
		List<Sanction> out = null;
		for (Iterator<Sanction> it = active.iterator(); it.hasNext(); ) {
			Sanction s = it.next();
			if (!s.active(now)) {
				it.remove();
				Sanction e = s.expired();
				past.add(e);
				if (out == null) out = new ArrayList<Sanction>();
				out.add(e);
			}
		}
		if (out == null) return Collections.emptyList();
		SanctionJson.sort(past);
		version++;
		return out;
	}

	/** Die am längsten laufende aktive Strafe dieser Art oder null. */
	public Sanction activeOf(String kind, long now) {
		Sanction best = null;
		for (Sanction s : active) {
			if (!s.kind.equals(kind) || !s.active(now)) continue;
			if (best == null || s.permanent() && !best.permanent() || !best.permanent() && !s.permanent() && s.endsAt > best.endsAt) {
				best = s;
			}
		}
		return best;
	}

	/** Die schwerste aktive Strafe (für das Banner) oder null. */
	public Sanction mostSevere(long now) {
		Sanction best = null;
		for (Sanction s : active) {
			if (s.active(now) && (best == null || s.severity() > best.severity())) best = s;
		}
		return best;
	}

	public int activeCount(long now) {
		int n = 0;
		for (Sanction s : active) if (s.active(now)) n++;
		return n;
	}

	void clear() {
		active.clear();
		past.clear();
		loaded = false;
		supported = false;
		version++;
	}

	/** Was hat sich von {@code prev} (null = neu) zu {@code next} geändert? */
	public static Change classify(Sanction prev, Sanction next, long now) {
		if (next == null) return Change.NONE;
		if (prev == null) return next.lifted() ? Change.LIFTED : next.active(now) ? Change.ADDED : Change.NONE;
		if (next.lifted() && !prev.lifted()) return Change.LIFTED;
		if (prev.appeal == null && next.appeal != null && next.appeal.open()) return Change.APPEAL_FILED;
		if (prev.endsAt != next.endsAt) {
			if (prev.permanent()) return Change.SHORTENED;
			if (next.permanent()) return Change.EXTENDED;
			return next.endsAt < prev.endsAt ? Change.SHORTENED : Change.EXTENDED;
		}
		if (prev.active(now) && !next.active(now)) return Change.EXPIRED;
		return Change.NONE;
	}

	// --- Einspruch ---

	/** Einspruchstext säubern wie die API: CRLF → LF, Steuer-/Format-/private Zeichen raus (außer Zeilenumbruch), trim. */
	public static String cleanAppeal(String raw) {
		if (raw == null) return "";
		String t = raw.replace("\r\n", "\n").replace('\r', '\n');
		StringBuilder b = new StringBuilder(t.length());
		for (int i = 0; i < t.length(); ) {
			int cp = t.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == '\t') cp = ' ';
			if (cp != '\n' && !allowed(cp)) continue;
			b.appendCodePoint(cp);
		}
		return b.toString().trim();
	}

	private static boolean allowed(int cp) {
		int type = Character.getType(cp);
		return type != Character.CONTROL && type != Character.FORMAT && type != Character.PRIVATE_USE
				&& type != Character.UNASSIGNED && type != Character.SURROGATE && cp != 0xA7;
	}

	/** Länge wie die API zählt (UTF-16-Einheiten des gesäuberten Texts). */
	public static int appealLength(String raw) {
		return cleanAppeal(raw).length();
	}

	/** Fehler-Schlüssel für den Einspruch oder null (gültig). */
	public static String appealProblem(String raw) {
		int n = appealLength(raw);
		if (n < APPEAL_MIN) return "sanction.appeal.tooShort";
		if (n > APPEAL_MAX) return "sanction.appeal.tooLong";
		return null;
	}
}
