package dev.theredstonee.trsclient.core.perf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Was in dieser Minecraft-Version und mit diesen Mods von den Leistungs-Funktionen übrig bleibt:
 * <ul>
 *   <li>{@link #supported}: gibt es die Funktion in dieser Version überhaupt (Loader meldet es)?</li>
 *   <li>{@link #owner}: übernimmt ein installierter Mod sie (dann bleibt TRS aus)?</li>
 *   <li>{@link #missingRecommended}: welche empfohlenen Leistungs-Mods fehlen (nur Hinweis)?</li>
 * </ul>
 * Wird einmal beim Start gebaut – Mods kommen im laufenden Spiel nicht dazu.
 */
public final class PerfCompat {
	/** Fragt den Loader, ob ein Mod mit dieser ID geladen ist. */
	public interface ModCheck {
		boolean loaded(String id);
	}

	/** Loader-Familien (für die Empfehlungen). */
	public static final String FABRIC = "fabric";
	public static final String NEOFORGE = "neoforge";
	public static final String FORGE = "forge";

	private final Set<PerfFeature> supported;
	private final List<PerfMod> detected;
	private final Map<PerfFeature, PerfMod> owners = new EnumMap<PerfFeature, PerfMod>(PerfFeature.class);
	private final List<String> missing;

	public PerfCompat(ModCheck check, boolean optifineClasses, String loader, String minecraft, Set<PerfFeature> supported) {
		this.supported = supported.isEmpty() ? EnumSet.noneOf(PerfFeature.class) : EnumSet.copyOf(supported);
		List<PerfMod> found = new ArrayList<PerfMod>();
		for (PerfMod mod : PerfMod.values()) {
			boolean present = mod == PerfMod.OPTIFINE && optifineClasses;
			for (String id : mod.ids()) {
				if (present) break;
				try {
					present = check.loaded(id);
				} catch (RuntimeException e) {
					present = false;
				}
			}
			if (!present) continue;
			found.add(mod);
			for (PerfFeature f : mod.takesOver()) {
				if (!owners.containsKey(f)) owners.put(f, mod);
			}
		}
		this.detected = Collections.unmodifiableList(found);
		this.missing = Collections.unmodifiableList(missing(loader, minecraft, found));
	}

	/** Ohne Mods, alles unterstützt (Tests, Werkzeuge). */
	public static PerfCompat all() {
		return new PerfCompat(new ModCheck() {
			@Override
			public boolean loaded(String id) {
				return false;
			}
		}, false, FABRIC, "1.21.11", EnumSet.allOf(PerfFeature.class));
	}

	/** Gibt es die Funktion in dieser Minecraft-Version? */
	public boolean supported(PerfFeature feature) {
		return supported.contains(feature);
	}

	/** Mod, der die Funktion übernimmt, oder null. */
	public PerfMod owner(PerfFeature feature) {
		return owners.get(feature);
	}

	/** Die TRS-Variante darf laufen: vorhanden und von keinem Mod übernommen. */
	public boolean ours(PerfFeature feature) {
		return supported.contains(feature) && !owners.containsKey(feature);
	}

	/** Installierte Leistungs-Mods (Reihenfolge wie {@link PerfMod}). */
	public List<PerfMod> detected() {
		return detected;
	}

	public boolean has(PerfMod mod) {
		return detected.contains(mod);
	}

	/** Namen empfohlener Leistungs-Mods, von denen keine Variante installiert ist. */
	public List<String> missingRecommended() {
		return missing;
	}

	private static List<String> missing(String loader, String minecraft, List<PerfMod> found) {
		List<PerfMod[]> groups = new ArrayList<PerfMod[]>();
		String mc = minecraft == null ? "" : minecraft;
		boolean legacy = mc.startsWith("1.7") || mc.startsWith("1.8") || mc.startsWith("1.9") || mc.startsWith("1.10")
				|| mc.startsWith("1.11") || mc.startsWith("1.12");
		if (legacy) {
			groups.add(new PerfMod[]{PerfMod.OPTIFINE});
			if (mc.startsWith("1.8")) groups.add(new PerfMod[]{PerfMod.PATCHER});
			else if (mc.startsWith("1.12")) groups.add(new PerfMod[]{PerfMod.VINTAGEFIX, PerfMod.FOAMFIX, PerfMod.VANILLAFIX});
			else if (!mc.startsWith("1.7")) groups.add(new PerfMod[]{PerfMod.FOAMFIX});
		} else if (FABRIC.equals(loader)) {
			groups.add(new PerfMod[]{PerfMod.SODIUM, PerfMod.OPTIFINE});
			groups.add(new PerfMod[]{PerfMod.ENTITY_CULLING});
			groups.add(new PerfMod[]{PerfMod.IMMEDIATELY_FAST});
			groups.add(new PerfMod[]{PerfMod.FERRITECORE});
		} else if (NEOFORGE.equals(loader)) {
			groups.add(new PerfMod[]{PerfMod.SODIUM, PerfMod.EMBEDDIUM});
			groups.add(new PerfMod[]{PerfMod.ENTITY_CULLING});
			groups.add(new PerfMod[]{PerfMod.IMMEDIATELY_FAST});
			groups.add(new PerfMod[]{PerfMod.FERRITECORE});
			groups.add(new PerfMod[]{PerfMod.MODERNFIX});
		} else {
			groups.add(new PerfMod[]{PerfMod.EMBEDDIUM, PerfMod.RUBIDIUM, PerfMod.SODIUM, PerfMod.OPTIFINE});
			groups.add(new PerfMod[]{PerfMod.ENTITY_CULLING});
			groups.add(new PerfMod[]{PerfMod.FERRITECORE});
			groups.add(new PerfMod[]{PerfMod.MODERNFIX});
		}
		List<String> out = new ArrayList<String>();
		for (PerfMod[] group : groups) {
			boolean any = false;
			for (PerfMod m : group) any |= found.contains(m);
			if (!any) out.add(group[0].displayName());
		}
		return out;
	}
}
