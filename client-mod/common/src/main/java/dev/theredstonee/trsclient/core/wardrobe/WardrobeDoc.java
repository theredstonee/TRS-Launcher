package dev.theredstonee.trsclient.core.wardrobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Inhalt des {@code wardrobe}-Dokuments (API.md §17.3, ≤ 64 KiB, „letzter Schreiber gewinnt“): Favoriten (Skin-IDs
 * der Bibliothek), benannte Outfits (Skin + Umhang + Kosmetik) und die Belegung des Emote-Rads. Unveränderlich –
 * Änderungen liefern eine neue Instanz. Beim Lesen wird alles Unbekannte/Ungültige verworfen (Daten kommen vom Server).
 */
public final class WardrobeDoc {
	public static final int MAX_FAVORITES = 60;
	public static final int MAX_OUTFITS = 30;
	public static final int MAX_EMOTE_SLOTS = 8;
	public static final int MAX_COSMETICS = 16;

	/** Ein gespeichertes Outfit. */
	public static final class Outfit {
		public final String id;
		public final String name;
		/** Skin-ID der Bibliothek oder null (Skin bleibt). */
		public final String skin;
		/** {@code mojang:<id>}, {@code trs:<id>}, {@code none} oder null (Umhang bleibt). */
		public final String cape;
		/** Kosmetik-IDs (für später; die Garderobe reicht sie nur durch). */
		public final List<String> cosmetics;

		public Outfit(String id, String name, String skin, String cape, List<String> cosmetics) {
			this.id = id;
			this.name = name;
			this.skin = skin;
			this.cape = cape;
			this.cosmetics = cosmetics == null ? Collections.<String>emptyList() : Collections.unmodifiableList(cosmetics);
		}

		public Outfit rename(String newName) {
			return new Outfit(id, SkinFiles.cleanName(newName, name), skin, cape, cosmetics);
		}
	}

	public static final WardrobeDoc EMPTY = new WardrobeDoc(Collections.<String>emptyList(), Collections.<Outfit>emptyList(),
			Collections.<String>emptyList());

	public final List<String> favorites;
	public final List<Outfit> outfits;
	/** Emote-IDs der Rad-Plätze in Reihenfolge; leer = alle Emotes. */
	public final List<String> emoteSlots;

	public WardrobeDoc(List<String> favorites, List<Outfit> outfits, List<String> emoteSlots) {
		this.favorites = Collections.unmodifiableList(new ArrayList<String>(favorites));
		this.outfits = Collections.unmodifiableList(new ArrayList<Outfit>(outfits));
		this.emoteSlots = Collections.unmodifiableList(new ArrayList<String>(emoteSlots));
	}

	public boolean favorite(String skinId) {
		return favorites.contains(skinId);
	}

	/** Favorit umschalten (neue Favoriten stehen vorn). */
	public WardrobeDoc toggleFavorite(String skinId) {
		if (!SkinFiles.validId(skinId)) return this;
		List<String> f = new ArrayList<String>(favorites);
		if (!f.remove(skinId)) {
			f.add(0, skinId);
			while (f.size() > MAX_FAVORITES) f.remove(f.size() - 1);
		}
		return new WardrobeDoc(f, outfits, emoteSlots);
	}

	/** Entfernt eine gelöschte Skin-ID aus Favoriten und Outfits. */
	public WardrobeDoc forgetSkin(String skinId) {
		List<String> f = new ArrayList<String>(favorites);
		boolean changed = f.remove(skinId);
		List<Outfit> o = new ArrayList<Outfit>();
		for (Outfit x : outfits) {
			if (skinId.equals(x.skin)) {
				o.add(new Outfit(x.id, x.name, null, x.cape, x.cosmetics));
				changed = true;
			} else {
				o.add(x);
			}
		}
		return changed ? new WardrobeDoc(f, o, emoteSlots) : this;
	}

	public WardrobeDoc addOutfit(Outfit outfit) {
		if (outfits.size() >= MAX_OUTFITS) return this;
		List<Outfit> o = new ArrayList<Outfit>(outfits);
		o.add(outfit);
		return new WardrobeDoc(favorites, o, emoteSlots);
	}

	public WardrobeDoc replaceOutfit(Outfit outfit) {
		List<Outfit> o = new ArrayList<Outfit>();
		for (Outfit x : outfits) o.add(x.id.equals(outfit.id) ? outfit : x);
		return new WardrobeDoc(favorites, o, emoteSlots);
	}

	public WardrobeDoc removeOutfit(String id) {
		List<Outfit> o = new ArrayList<Outfit>();
		for (Outfit x : outfits) if (!x.id.equals(id)) o.add(x);
		return new WardrobeDoc(favorites, o, emoteSlots);
	}

	/** Setzt Platz {@code slot} (0-basiert) auf ein Emote; null leert ihn. Doppelte werden entfernt. */
	public WardrobeDoc setEmoteSlot(int slot, String emoteId) {
		if (slot < 0 || slot >= MAX_EMOTE_SLOTS) return this;
		String[] s = new String[MAX_EMOTE_SLOTS];
		for (int i = 0; i < emoteSlots.size() && i < MAX_EMOTE_SLOTS; i++) s[i] = emoteSlots.get(i);
		if (emoteId != null) {
			for (int i = 0; i < s.length; i++) if (emoteId.equals(s[i])) s[i] = null;
		}
		s[slot] = emoteId;
		return new WardrobeDoc(favorites, outfits, compactSlots(s));
	}

	/** Belegte Plätze als Liste mit Lücken ("" = leer), Länge {@link #MAX_EMOTE_SLOTS}. */
	public String[] slotArray() {
		String[] s = new String[MAX_EMOTE_SLOTS];
		for (int i = 0; i < MAX_EMOTE_SLOTS; i++) {
			String v = i < emoteSlots.size() ? emoteSlots.get(i) : "";
			s[i] = v == null || v.isEmpty() ? null : v;
		}
		return s;
	}

	private static List<String> compactSlots(String[] s) {
		int last = -1;
		for (int i = 0; i < s.length; i++) if (s[i] != null) last = i;
		List<String> out = new ArrayList<String>();
		for (int i = 0; i <= last; i++) out.add(s[i] == null ? "" : s[i]);
		return out;
	}

	// --- JSON ---

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("v", 1);
		JsonArray fav = new JsonArray();
		for (String f : favorites) fav.add(new JsonPrimitive(f));
		o.add("favorites", fav);
		JsonArray outs = new JsonArray();
		for (Outfit x : outfits) {
			JsonObject j = new JsonObject();
			j.addProperty("id", x.id);
			j.addProperty("name", x.name);
			if (x.skin != null) j.addProperty("skin", x.skin);
			if (x.cape != null) j.addProperty("cape", x.cape);
			if (!x.cosmetics.isEmpty()) {
				JsonArray c = new JsonArray();
				for (String s : x.cosmetics) c.add(new JsonPrimitive(s));
				j.add("cosmetics", c);
			}
			outs.add(j);
		}
		o.add("outfits", outs);
		JsonArray slots = new JsonArray();
		for (String s : emoteSlots) slots.add(new JsonPrimitive(s == null ? "" : s));
		o.add("emoteSlots", slots);
		return o;
	}

	/** Liest ein Dokument; alles Ungültige fällt weg. null → leer. */
	public static WardrobeDoc fromJson(JsonObject o) {
		if (o == null) return EMPTY;
		List<String> fav = new ArrayList<String>();
		for (String s : strings(o.get("favorites"), MAX_FAVORITES * 2)) {
			if (SkinFiles.validId(s) && !fav.contains(s) && fav.size() < MAX_FAVORITES) fav.add(s);
		}
		List<Outfit> outs = new ArrayList<Outfit>();
		JsonElement oe = o.get("outfits");
		if (oe != null && oe.isJsonArray()) {
			for (JsonElement e : oe.getAsJsonArray()) {
				if (outs.size() >= MAX_OUTFITS) break;
				if (!e.isJsonObject()) continue;
				JsonObject j = e.getAsJsonObject();
				String id = string(j.get("id"));
				if (!SkinFiles.validId(id)) continue;
				boolean dup = false;
				for (Outfit x : outs) dup |= x.id.equals(id);
				if (dup) continue;
				String name = SkinFiles.cleanName(string(j.get("name")), "Outfit");
				String skin = string(j.get("skin"));
				if (!SkinFiles.validId(skin)) skin = null;
				String cape = validCape(string(j.get("cape"))) ? string(j.get("cape")) : null;
				List<String> cos = new ArrayList<String>();
				for (String s : strings(j.get("cosmetics"), MAX_COSMETICS * 2)) {
					if (s.matches("[a-z0-9][a-z0-9_-]{0,39}") && cos.size() < MAX_COSMETICS) cos.add(s);
				}
				outs.add(new Outfit(id, name, skin, cape, cos));
			}
		}
		String[] slots = new String[MAX_EMOTE_SLOTS];
		int i = 0;
		for (String s : strings(o.get("emoteSlots"), MAX_EMOTE_SLOTS)) {
			if (i >= MAX_EMOTE_SLOTS) break;
			slots[i++] = s.matches("[a-z0-9_]{1,40}") ? s : null;
		}
		for (int a = 0; a < slots.length; a++) {
			for (int b = 0; b < a; b++) if (slots[a] != null && slots[a].equals(slots[b])) slots[a] = null;
		}
		return new WardrobeDoc(fav, outs, compactSlots(slots));
	}

	/** Gültige Umhang-Angabe eines Outfits? */
	public static boolean validCape(String cape) {
		if (cape == null) return false;
		if (cape.equals("none")) return true;
		if (cape.startsWith("mojang:")) return cape.substring(7).matches("[0-9a-fA-F-]{8,64}");
		if (cape.startsWith("trs:")) return cape.substring(4).matches("[a-z0-9][a-z0-9_-]{0,39}");
		return false;
	}

	private static String string(JsonElement e) {
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return null;
		return e.getAsString();
	}

	private static List<String> strings(JsonElement e, int max) {
		List<String> out = new ArrayList<String>();
		if (e == null || !e.isJsonArray()) return out;
		for (JsonElement x : e.getAsJsonArray()) {
			if (out.size() >= max) break;
			String s = string(x);
			out.add(s == null ? "" : s);
		}
		return out;
	}
}
