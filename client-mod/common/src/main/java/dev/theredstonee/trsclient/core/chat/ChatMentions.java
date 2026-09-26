package dev.theredstonee.trsclient.core.chat;

import dev.theredstonee.trsclient.core.module.ChoiceSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hervorhebung, wenn der eigene Name oder eigene Stichwörter im Chat fallen – optional mit Ton. Nur Anzeige: es wird
 * nichts gesendet. Die eigene Nachricht („&lt;Ich&gt; …“) zählt nicht als Erwähnung.
 */
public final class ChatMentions {
	/** Zwei Töne höchstens einmal je 1,5 s (Spam im Chat soll nicht dauerpiepen). */
	public static final long SOUND_GAP_MS = 1500;

	/** Ton bei einer Erwähnung (Vanilla-Klänge, je Version passend gewählt). */
	public enum Sound implements ChoiceSetting.Option {
		PLING("Note block: pling"),
		BELL("Note block: bell"),
		ORB("Experience orb"),
		CHIME("Note block: chime");

		private final String label;

		Sound(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	private List<String> words = new ArrayList<String>();
	private String builtFrom;
	private long lastSound;

	/** Liste neu aufbauen, wenn sich Name oder Stichwörter geändert haben (billig bei gleichem Stand). */
	public List<String> words(String ownName, boolean includeOwn, String custom) {
		String key = (includeOwn ? ownName : "") + "\u0000" + custom;
		if (!key.equals(builtFrom)) {
			builtFrom = key;
			List<String> list = new ArrayList<String>();
			if (includeOwn && ownName != null && ownName.length() >= ChatText.MIN_WORD) {
				list.add(ownName.toLowerCase(Locale.ROOT));
			}
			for (String w : ChatText.words(custom)) {
				if (!list.contains(w)) list.add(w);
			}
			words = list;
		}
		return words;
	}

	/**
	 * Fundstellen, die als Erwähnung zählen (ohne die Absender-Stelle eigener Nachrichten).
	 *
	 * @param raw Text mit Farbcodes
	 */
	public static List<int[]> mentions(String raw, List<String> words, String ownName) {
		List<int[]> found = ChatText.find(raw, words);
		if (found.isEmpty() || ownName == null || ownName.isEmpty()) return found;
		String own = ownName.toLowerCase(Locale.ROOT);
		List<int[]> out = new ArrayList<int[]>(found.size());
		for (int[] r : found) {
			String hit = ChatText.strip(raw.substring(r[0], r[1])).toLowerCase(Locale.ROOT);
			if (hit.equals(own) && ChatText.senderPosition(raw, r[0], r[1])) continue;
			out.add(r);
		}
		return out;
	}

	/** Darf jetzt ein Ton kommen? (höchstens einmal je {@link #SOUND_GAP_MS}) */
	public boolean soundAllowed(long now) {
		if (now - lastSound < SOUND_GAP_MS && lastSound != 0) return false;
		lastSound = now;
		return true;
	}
}
