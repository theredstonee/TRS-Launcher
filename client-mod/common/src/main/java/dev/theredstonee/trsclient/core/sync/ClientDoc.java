package dev.theredstonee.trsclient.core.sync;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.theredstonee.trsclient.core.config.ModuleConfig;
import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.ModuleRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aufbau des {@code client}-Sync-Dokuments (API.md §17.3, höchstens 64 KiB) aus der Client-Config und zurück.
 *
 * <pre>
 * {
 *   "format": 1,
 *   "mod": "0.6.0",
 *   "modules": { "at": "…Z", "data": { "&lt;modul&gt;": { enabled, flags, numbers, colors, choices, texts } } },
 *   "hud":     { "at": "…Z", "data": { "active": "Standard", "profiles": [ { "name", "modules": { … } } ] } },
 *   "keys":    { "at": "…Z", "data": { "&lt;modul&gt;": { "&lt;einstellung&gt;": "key.keyboard.b" } } },
 *   "prefs":   { "at": "…Z", "data": { "fpsMode": "pretty" } },
 *   "intro":   { "done": true, "how": "finished", "pack": "pvp", "at": "…Z" },
 *   "seen":    { "baseline": "0.5.0", "ids": [ "trsOnline.sync" ] }
 * }
 * </pre>
 *
 * <ul>
 *   <li>{@code modules}: alle Module, die NICHT zu den HUD-Profilen gehören (An/Aus + Einstellungen, ohne Tasten).</li>
 *   <li>{@code hud}: alle HUD-Profile (HUD-Module und {@code profiled()}-Module: Position, Größe, Aussehen, An/Aus).</li>
 *   <li>{@code keys}: TRS-Tastenbelegungen der Module (Wegpunkte, Text-Hotkeys …). Zoom/Freelook und die übrigen
 *   TRS-Tasten liegen in Minecrafts options.txt und werden nicht synchronisiert.</li>
 *   <li>{@code prefs}: kleine Client-Vorlieben (Config-Modus der Optimierungs-Mods).</li>
 *   <li>{@code intro}/{@code seen}: Einführung erledigt, „NEU“-Stand – werden zusammengeführt statt überschrieben.</li>
 * </ul>
 * Jeder überschreibbare Abschnitt trägt die Zeit seiner letzten Änderung ({@code at}); beim Zusammenführen gewinnt
 * je Abschnitt die neuere Änderung. Nie im Dokument: Wegpunkte, Server-Adressen (Freelook-Serverliste), der
 * Sync-Schalter selbst, options.txt, Dateien, Pfade, Tokens.
 */
public final class ClientDoc {
	public static final int FORMAT = 1;
	public static final String MODULES = "modules";
	public static final String HUD = "hud";
	public static final String KEYS = "keys";
	public static final String PREFS = "prefs";
	public static final String INTRO = "intro";
	public static final String SEEN = "seen";
	/** Abschnitte mit „neuere Änderung gewinnt“. */
	public static final String[] LWW = {MODULES, HUD, KEYS, PREFS};
	/** Obergrenze der API ist 64 KiB – mit etwas Luft. */
	public static final int MAX_BYTES = 60 * 1024;

	static final Gson GSON = new Gson();

	/** Bleibt auf jedem PC für sich: Modul-ID → {Art, Schlüssel}. */
	private static final String[][] LOCAL_ONLY = {
			{"trsOnline", "flags", "sync"},
			{"freelook", "texts", "servers"},
	};

	private final ModuleRegistry registry;

	public ClientDoc(ModuleRegistry registry) {
		this.registry = registry;
	}

	// --- Lokale Abschnitte ---

	/** Daten der überschreibbaren Abschnitte aus einem Config-Stand (Reihenfolge = Modul-Reihenfolge, stabil). */
	public Map<String, JsonObject> sections(TrsConfig config) {
		Map<String, JsonObject> out = new LinkedHashMap<String, JsonObject>();
		JsonObject modules = new JsonObject();
		JsonObject keys = new JsonObject();
		for (Module m : registry.all()) {
			if (m.inProfiles()) continue;
			ModuleConfig mc = config.modules == null ? null : config.modules.get(m.id());
			if (mc == null) continue;
			mc.normalized();
			modules.add(m.id(), moduleJson(m.id(), mc, false));
			if (!mc.keys.isEmpty()) {
				JsonObject k = new JsonObject();
				for (Map.Entry<String, String> e : mc.keys.entrySet()) {
					if (e.getKey() != null && e.getValue() != null) k.addProperty(e.getKey(), e.getValue());
				}
				keys.add(m.id(), k);
			}
		}
		out.put(MODULES, modules);
		out.put(HUD, hud(config));
		out.put(KEYS, keys);
		JsonObject prefs = new JsonObject();
		if (config.clientState != null && config.clientState.fpsMode != null) prefs.addProperty("fpsMode", config.clientState.fpsMode);
		out.put(PREFS, prefs);
		return out;
	}

	private JsonObject hud(TrsConfig config) {
		JsonObject hud = new JsonObject();
		TrsConfig.HudProfiles p = config.hudProfiles;
		if (p == null || p.profiles == null) return hud;
		if (p.active != null) hud.addProperty("active", p.active);
		JsonArray profiles = new JsonArray();
		for (TrsConfig.Profile profile : p.profiles) {
			if (profile == null || profile.name == null) continue;
			JsonObject o = new JsonObject();
			o.addProperty("name", profile.name);
			JsonObject mods = new JsonObject();
			if (profile.modules != null) {
				for (Map.Entry<String, ModuleConfig> e : profile.modules.entrySet()) {
					if (e.getKey() == null || e.getValue() == null) continue;
					mods.add(e.getKey(), moduleJson(e.getKey(), e.getValue().normalized(), true));
				}
			}
			o.add("modules", mods);
			profiles.add(o);
		}
		hud.add("profiles", profiles);
		return hud;
	}

	/** Kompakte Form eines Moduls: leere Teile fallen weg, lokale Werte ebenso. */
	static JsonObject moduleJson(String id, ModuleConfig mc, boolean withKeys) {
		JsonObject o = new JsonObject();
		if (mc.enabled != null) o.addProperty("enabled", mc.enabled);
		if (mc.anchor != null) o.addProperty("anchor", mc.anchor);
		if (mc.offsetX != null) o.addProperty("offsetX", mc.offsetX);
		if (mc.offsetY != null) o.addProperty("offsetY", mc.offsetY);
		map(o, id, "flags", mc.flags);
		map(o, id, "numbers", mc.numbers);
		map(o, id, "colors", mc.colors);
		map(o, id, "choices", mc.choices);
		map(o, id, "texts", mc.texts);
		if (withKeys) map(o, id, "keys", mc.keys);
		return o;
	}

	private static void map(JsonObject o, String id, String kind, Map<String, ?> values) {
		if (values == null || values.isEmpty()) return;
		JsonObject m = new JsonObject();
		for (Map.Entry<String, ?> e : values.entrySet()) {
			if (e.getKey() == null || e.getValue() == null || localOnly(id, kind, e.getKey())) continue;
			Object v = e.getValue();
			if (v instanceof Boolean) m.addProperty(e.getKey(), (Boolean) v);
			else if (v instanceof Number) m.addProperty(e.getKey(), (Number) v);
			else m.addProperty(e.getKey(), v.toString());
		}
		if (m.entrySet().isEmpty()) return;
		o.add(kind, m);
	}

	static boolean localOnly(String module, String kind, String key) {
		for (String[] l : LOCAL_ONLY) {
			if (l[0].equals(module) && l[1].equals(kind) && l[2].equals(key)) return true;
		}
		return false;
	}

	/** Kanonischer Text (für Vergleiche und die Größe). */
	public static String json(JsonElement e) {
		return GSON.toJson(e);
	}

	/** SHA-256 (hex, gekürzt) des kanonischen Texts. */
	public static String hash(JsonElement e) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(json(e).getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 16; i++) sb.append(String.format("%02x", d[i] & 0xFF));
			return sb.toString();
		} catch (NoSuchAlgorithmException ex) {
			return Integer.toHexString(json(e).hashCode());
		}
	}

	// --- Anwenden (Konto → lokal) ---

	/**
	 * Übernimmt die Abschnitte {@code apply} aus {@code remote} (Dokument des Kontos) in eine Kopie von {@code local}.
	 * Module, die das Konto nicht kennt, und lokale Werte (Sync-Schalter, Freelook-Server) bleiben unverändert.
	 */
	public TrsConfig apply(TrsConfig local, JsonObject remote, java.util.Set<String> apply) {
		TrsConfig out = copy(local);
		if (out.modules == null) out.modules = new LinkedHashMap<String, ModuleConfig>();
		if (apply.contains(MODULES)) {
			JsonObject data = data(remote, MODULES);
			if (data != null) {
				for (Module m : registry.all()) {
					if (m.inProfiles()) continue;
					JsonElement e = data.get(m.id());
					if (e == null || !e.isJsonObject()) continue;
					ModuleConfig incoming = parseModule(e.getAsJsonObject());
					if (incoming == null) continue;
					ModuleConfig old = out.modules.get(m.id());
					if (old != null) {
						old.normalized();
						incoming.keys.putAll(old.keys);
						keepLocal(m.id(), old, incoming);
					}
					incoming.anchor = null;
					incoming.offsetX = null;
					incoming.offsetY = null;
					out.modules.put(m.id(), incoming);
				}
			}
		}
		if (apply.contains(KEYS)) {
			JsonObject data = data(remote, KEYS);
			if (data != null) {
				for (Module m : registry.all()) {
					if (m.inProfiles()) continue;
					JsonElement e = data.get(m.id());
					if (e == null || !e.isJsonObject()) continue;
					ModuleConfig mc = out.modules.get(m.id());
					if (mc == null) {
						mc = new ModuleConfig();
						out.modules.put(m.id(), mc);
					}
					mc.normalized();
					for (Map.Entry<String, JsonElement> k : e.getAsJsonObject().entrySet()) {
						if (k.getValue() != null && k.getValue().isJsonPrimitive()) mc.keys.put(k.getKey(), k.getValue().getAsString());
					}
				}
			}
		}
		if (apply.contains(HUD)) {
			JsonObject data = data(remote, HUD);
			if (data != null) applyHud(out, data);
		}
		if (apply.contains(PREFS)) {
			JsonObject data = data(remote, PREFS);
			if (data != null) {
				if (out.clientState == null) out.clientState = new TrsConfig.ClientStateData();
				JsonElement mode = data.get("fpsMode");
				out.clientState.fpsMode = mode != null && mode.isJsonPrimitive() ? mode.getAsString() : null;
			}
		}
		return out;
	}

	private void applyHud(TrsConfig out, JsonObject data) {
		JsonElement list = data.get("profiles");
		if (list == null || !list.isJsonArray()) return;
		TrsConfig.HudProfiles hp = new TrsConfig.HudProfiles();
		JsonElement active = data.get("active");
		hp.active = active != null && active.isJsonPrimitive() ? active.getAsString() : null;
		TrsConfig.Profile activeProfile = null;
		for (JsonElement pe : list.getAsJsonArray()) {
			if (!pe.isJsonObject()) continue;
			JsonObject po = pe.getAsJsonObject();
			JsonElement name = po.get("name");
			JsonElement mods = po.get("modules");
			if (name == null || !name.isJsonPrimitive() || mods == null || !mods.isJsonObject()) continue;
			TrsConfig.Profile p = new TrsConfig.Profile();
			p.name = name.getAsString();
			for (Map.Entry<String, JsonElement> me : mods.getAsJsonObject().entrySet()) {
				if (!me.getValue().isJsonObject()) continue;
				ModuleConfig mc = parseModule(me.getValue().getAsJsonObject());
				if (mc != null) p.modules.put(me.getKey(), mc);
			}
			hp.profiles.add(p);
			if (hp.active != null && hp.active.equalsIgnoreCase(p.name)) activeProfile = p;
		}
		if (hp.profiles.isEmpty()) return;
		if (activeProfile == null) {
			activeProfile = hp.profiles.get(0);
			hp.active = activeProfile.name;
		}
		out.hudProfiles = hp;
		// Das aktive Profil steht in den Modulen selbst (HudProfiles liest es von dort).
		for (Map.Entry<String, ModuleConfig> e : activeProfile.modules.entrySet()) {
			Module m = registry.byId(e.getKey());
			if (m == null || !m.inProfiles()) continue;
			ModuleConfig incoming = e.getValue().copy();
			ModuleConfig old = out.modules.get(m.id());
			if (old != null) keepLocal(m.id(), old.normalized(), incoming);
			out.modules.put(m.id(), incoming);
		}
	}

	private static void keepLocal(String id, ModuleConfig old, ModuleConfig incoming) {
		for (String[] l : LOCAL_ONLY) {
			if (!l[0].equals(id)) continue;
			if (l[1].equals("flags")) put(incoming.flags, l[2], old.flags.get(l[2]));
			else if (l[1].equals("texts")) put(incoming.texts, l[2], old.texts.get(l[2]));
		}
	}

	private static <V> void put(Map<String, V> map, String key, V value) {
		if (value == null) map.remove(key);
		else map.put(key, value);
	}

	static ModuleConfig parseModule(JsonObject o) {
		try {
			ModuleConfig mc = GSON.fromJson(o, ModuleConfig.class);
			return mc == null ? null : mc.normalized();
		} catch (RuntimeException e) {
			return null;
		}
	}

	static JsonObject data(JsonObject doc, String section) {
		JsonObject s = object(doc, section);
		return s == null ? null : object(s, "data");
	}

	static JsonObject object(JsonObject o, String key) {
		if (o == null) return null;
		JsonElement e = o.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	/** Zeit {@code at} eines Abschnitts in ms, -1 = keine. */
	static long at(JsonObject doc, String section) {
		JsonObject s = object(doc, section);
		if (s == null) return -1;
		JsonElement at = s.get("at");
		return at != null && at.isJsonPrimitive() ? SyncTime.parse(at.getAsString()) : -1;
	}

	/** Tiefe Kopie über JSON (Gson-DTO). */
	static TrsConfig copy(TrsConfig c) {
		return GSON.fromJson(GSON.toJson(c), TrsConfig.class);
	}

	// --- Hochladen (lokal → Konto) ---

	/**
	 * Übernimmt aus {@code remote} Einträge, die dieser Client nicht kennt (neuere Mod-Version auf einem anderen PC),
	 * in {@code local} – so löscht ein älterer Client beim Hochladen nichts, was er nicht versteht.
	 */
	public JsonObject carry(String section, JsonObject local, JsonObject remote) {
		if (remote == null) return local;
		if (section.equals(MODULES) || section.equals(KEYS)) {
			for (Map.Entry<String, JsonElement> e : remote.entrySet()) {
				JsonElement mine = local.get(e.getKey());
				if (mine == null) {
					Module m = registry.byId(e.getKey());
					if (m == null && e.getValue().isJsonObject()) local.add(e.getKey(), e.getValue());
				} else if (mine.isJsonObject() && e.getValue().isJsonObject()) {
					carryModule(e.getKey(), mine.getAsJsonObject(), e.getValue().getAsJsonObject(), section.equals(KEYS));
				}
			}
		} else if (section.equals(HUD)) {
			JsonElement lp = local.get("profiles");
			JsonElement rp = remote.get("profiles");
			if (lp != null && lp.isJsonArray() && rp != null && rp.isJsonArray()) {
				for (JsonElement l : lp.getAsJsonArray()) {
					JsonObject lo = l.getAsJsonObject();
					for (JsonElement r : rp.getAsJsonArray()) {
						if (!r.isJsonObject()) continue;
						JsonObject ro = r.getAsJsonObject();
						if (!String.valueOf(lo.get("name")).equals(String.valueOf(ro.get("name")))) continue;
						JsonObject lm = object(lo, "modules");
						JsonObject rm = object(ro, "modules");
						if (lm == null || rm == null) continue;
						for (Map.Entry<String, JsonElement> e : rm.entrySet()) {
							if (lm.get(e.getKey()) == null && registry.byId(e.getKey()) == null) lm.add(e.getKey(), e.getValue());
						}
					}
				}
			}
		}
		return local;
	}

	private static void carryModule(String id, JsonObject mine, JsonObject theirs, boolean flat) {
		if (flat) {
			for (Map.Entry<String, JsonElement> e : theirs.entrySet()) {
				if (mine.get(e.getKey()) == null) mine.add(e.getKey(), e.getValue());
			}
			return;
		}
		for (String kind : new String[]{"flags", "numbers", "colors", "choices", "texts"}) {
			JsonObject t = object(theirs, kind);
			if (t == null) continue;
			JsonObject m = object(mine, kind);
			for (Map.Entry<String, JsonElement> e : t.entrySet()) {
				if (localOnly(id, kind, e.getKey())) continue;
				if (m != null && m.get(e.getKey()) != null) continue;
				if (m == null) {
					m = new JsonObject();
					mine.add(kind, m);
				}
				m.add(e.getKey(), e.getValue());
			}
		}
	}

	/** Abschnitt {@code {at, data}}. */
	public static JsonObject section(long at, JsonObject data) {
		JsonObject s = new JsonObject();
		s.addProperty("at", SyncTime.iso(at));
		s.add("data", data);
		return s;
	}

	/** Intro-Abschnitt aus dem Client-Zustand (null = nichts zu melden). */
	public static JsonObject intro(TrsConfig.ClientStateData cs) {
		if (cs == null || !Boolean.TRUE.equals(cs.introDone)) return null;
		JsonObject o = new JsonObject();
		o.addProperty("done", true);
		if (cs.introHow != null) o.addProperty("how", cs.introHow);
		if (cs.introPack != null) o.addProperty("pack", cs.introPack);
		if (cs.introAt != null && cs.introAt > 0) o.addProperty("at", SyncTime.iso(cs.introAt));
		return o;
	}

	/** Seen-Abschnitt aus dem Client-Zustand. */
	public static JsonObject seen(String baseline, List<String> ids) {
		JsonObject o = new JsonObject();
		o.addProperty("baseline", baseline);
		JsonArray a = new JsonArray();
		if (ids != null) {
			for (String id : ids) a.add(new JsonPrimitive(id));
		}
		o.add("ids", a);
		return o;
	}

	static List<String> strings(JsonElement e) {
		List<String> out = new ArrayList<String>();
		if (e == null || !e.isJsonArray()) return out;
		for (JsonElement x : e.getAsJsonArray()) {
			if (x.isJsonPrimitive() && out.size() < 1024) out.add(x.getAsString());
		}
		return out;
	}

	/** Größe des Dokuments in Bytes (UTF-8, kompakt). */
	public static int size(JsonObject doc) {
		return json(doc).getBytes(StandardCharsets.UTF_8).length;
	}

	/**
	 * Hält das Dokument unter {@link #MAX_BYTES}: notfalls fallen die hintersten nicht aktiven HUD-Profile weg.
	 *
	 * @return false, wenn es trotzdem zu groß ist (dann nicht hochladen)
	 */
	public static boolean fit(JsonObject doc) {
		if (size(doc) <= MAX_BYTES) return true;
		JsonObject hud = data(doc, HUD);
		if (hud != null && hud.get("profiles") != null && hud.get("profiles").isJsonArray()) {
			JsonArray profiles = hud.getAsJsonArray("profiles");
			String active = hud.get("active") != null ? hud.get("active").getAsString() : null;
			while (size(doc) > MAX_BYTES && profiles.size() > 1) {
				JsonArray kept = new JsonArray();
				int drop = -1;
				for (int i = profiles.size() - 1; i >= 0; i--) {
					JsonElement n = profiles.get(i).getAsJsonObject().get("name");
					if (active == null || n == null || !active.equals(n.getAsString())) {
						drop = i;
						break;
					}
				}
				if (drop < 0) break;
				for (int i = 0; i < profiles.size(); i++) {
					if (i != drop) kept.add(profiles.get(i));
				}
				hud.add("profiles", kept);
				profiles = kept;
			}
		}
		return size(doc) <= MAX_BYTES;
	}
}
