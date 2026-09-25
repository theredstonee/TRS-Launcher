package dev.theredstonee.trsclient.core.wardrobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.theredstonee.trsclient.core.emote.EmoteDef;
import dev.theredstonee.trsclient.core.emote.Emotes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Belegung des Emote-Rads aus der Garderobe (Teil des {@code wardrobe}-Dokuments). Das Rad fragt {@link #forWheel};
 * ohne Belegung zeigt es wie bisher alle Emotes. Zusätzlich lokal in {@code config/trsclient/wardrobe/emote-slots.json}
 * gemerkt, damit das Rad die Belegung auch kennt, bevor die Garderobe geöffnet wurde.
 */
public final class EmoteSlots {
	private static volatile List<String> slots;
	private static volatile Path file;

	private EmoteSlots() {
	}

	/** Beim Start (oder beim ersten Öffnen der Garderobe): Ordner festlegen und gespeicherte Belegung lesen. */
	public static synchronized void init(Path configDir) {
		if (configDir == null || file != null) return;
		file = configDir.resolve("trsclient").resolve("wardrobe").resolve("emote-slots.json");
		if (slots != null) return;
		try {
			if (Files.isRegularFile(file) && Files.size(file) < 8192) {
				JsonElement e = new JsonParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
				List<String> out = new ArrayList<String>();
				if (e.isJsonArray()) {
					for (JsonElement x : e.getAsJsonArray()) {
						if (out.size() >= WardrobeDoc.MAX_EMOTE_SLOTS) break;
						String s = x.isJsonPrimitive() ? x.getAsString() : "";
						out.add(s.matches("[a-z0-9_]{1,40}") ? s : "");
					}
				}
				slots = Collections.unmodifiableList(out);
			}
		} catch (Exception ignored) {
			slots = null;
		}
	}

	/** Neue Belegung (aus dem Garderoben-Thread). */
	static void set(List<String> value) {
		List<String> v = Collections.unmodifiableList(new ArrayList<String>(value));
		if (v.equals(slots)) return;
		slots = v;
		Path f = file;
		if (f == null) return;
		try {
			Files.createDirectories(f.getParent());
			JsonArray a = new JsonArray();
			for (String s : v) a.add(new JsonPrimitive(s == null ? "" : s));
			Files.write(f, a.toString().getBytes(StandardCharsets.UTF_8));
		} catch (Exception ignored) {
			// nur ein Komfort-Cache
		}
	}

	/** Aktuelle Belegung (leere Liste = keine). */
	public static List<String> current() {
		List<String> s = slots;
		return s == null ? Collections.<String>emptyList() : s;
	}

	/** Emotes für das Rad: die belegten Plätze in Reihenfolge, ohne Belegung {@code all}. */
	public static List<EmoteDef> forWheel(List<EmoteDef> all) {
		if (file == null) init(dev.theredstonee.trsclient.core.i18n.I18n.configDir());
		List<String> s = slots;
		if (s == null || s.isEmpty()) return all;
		List<EmoteDef> out = new ArrayList<EmoteDef>();
		for (String id : s) {
			if (id == null || id.isEmpty()) continue;
			EmoteDef d = Emotes.byId(id);
			if (d != null && !out.contains(d)) out.add(d);
		}
		return out.size() < 2 ? all : out;
	}
}
