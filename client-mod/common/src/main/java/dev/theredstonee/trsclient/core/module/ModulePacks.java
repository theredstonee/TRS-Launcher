package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.hud.HudAnchor;
import dev.theredstonee.trsclient.core.hud.HudPosition;
import dev.theredstonee.trsclient.core.i18n.I18n;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modul-Pakete: fertige Zusammenstellungen der TRS-Module für einen Spielstil, mit einem Klick anwendbar (Vorschau,
 * Rückgängig) – in der Einführung und im TRS-Menü. Ein Paket schaltet nur die Module um, die es nennt; alles andere
 * (TRS-Online, Umhänge, Emotes, Leistung, Startbildschirm …) bleibt, wie es ist. Dazu gehört je Paket eine
 * HUD-Vorlage (Positionen der HUD-Elemente im aktiven HUD-Profil), die man auch weglassen kann.
 *
 * <ul>
 *   <li><b>PvP</b>: FPS, CPS, Ping, Tastenanzeige, Rüstung, Effekte, Reichweite, Combo, Toggle-Sprint, eigenes
 *   Fadenkreuz, Treffer-Farbe, 1.7-Animationen, niedriges Feuer, kein Schadens-Wackeln, Block-Umrandung – ohne
 *   Minimap, Koordinaten und Redstone-Anzeigen.</li>
 *   <li><b>Redstone</b>: FPS, Koordinaten, Uhrzeit, Signalstärke, Takt-Messer, Zoom, Freelook, Wegpunkte,
 *   Block-Umrandung – ohne PvP-Anzeigen.</li>
 *   <li><b>Komfort</b>: FPS, Koordinaten, Uhrzeit, Minimap, Wegpunkte, Rüstung, Effekte, Toggle-Sprint, Zoom,
 *   Freelook, Chat-Verbesserungen – ohne PvP- und Redstone-Anzeigen.</li>
 *   <li><b>Minimal</b>: nur die FPS-Anzeige und Zoom; alle anderen HUD-Anzeigen und Optik-Module aus.</li>
 * </ul>
 */
public final class ModulePacks {
	/** Ein Paket. */
	public static final class Pack {
		public final String id;
		public final String icon;
		/** Modul-ID → an/aus (nur diese Module werden geändert). */
		public final Map<String, Boolean> modules;
		/** HUD-Vorlage: Modul-ID → Position. */
		public final Map<String, HudPosition> hud;

		Pack(String id, String icon, Map<String, Boolean> modules, Map<String, HudPosition> hud) {
			this.id = id;
			this.icon = icon;
			this.modules = Collections.unmodifiableMap(modules);
			this.hud = Collections.unmodifiableMap(hud);
		}

		public String name() {
			return I18n.trOr("packs.mod." + id, id);
		}

		public String description() {
			return I18n.trOr("packs.mod." + id + ".desc", "");
		}
	}

	/** Was ein Paket ändern würde. */
	public static final class Preview {
		public final List<Module> enable = new ArrayList<Module>();
		public final List<Module> disable = new ArrayList<Module>();
		/** HUD-Elemente, die die Vorlage an eine andere Stelle setzt. */
		public final List<Module> moved = new ArrayList<Module>();

		public boolean isEmpty() {
			return enable.isEmpty() && disable.isEmpty() && moved.isEmpty();
		}
	}

	/** Welche Module es in dieser Minecraft-Version gibt (sonst werden sie nicht angefasst). */
	public interface Support {
		boolean supports(Module module);
	}

	public static final Pack PVP;
	public static final Pack REDSTONE;
	public static final Pack COMFORT;
	public static final Pack MINIMAL;
	private static final List<Pack> ALL;

	static {
		PVP = new Builder("pvp", "sword")
				.on("fps", "cps", "ping", "keystrokes", "armor", "effects", "reach", "combo", "toggleSprint", "crosshair",
						"hitColor", "oldAnimations", "lowFire", "noHurtCam", "blockOutline")
				.off("minimap", "coords", "clock", "memory", "server", "packs", "speed", "redstoneSignal", "redstoneClock",
						"redstoneOverlay", "fullbright")
				.hud("fps", HudAnchor.TOP_LEFT, 0.005, 0.01)
				.hud("cps", HudAnchor.TOP_LEFT, 0.005, 0.075)
				.hud("ping", HudAnchor.TOP_LEFT, 0.005, 0.14)
				.hud("keystrokes", HudAnchor.BOTTOM_RIGHT, -0.005, -0.08)
				.hud("armor", HudAnchor.CENTER_LEFT, 0.005, 0.0)
				.hud("effects", HudAnchor.CENTER_RIGHT, -0.005, 0.0)
				.hud("reach", HudAnchor.CENTER_LEFT, 0.005, -0.12)
				.hud("combo", HudAnchor.CENTER_LEFT, 0.005, -0.06)
				.hud("toggleSprint", HudAnchor.BOTTOM_RIGHT, -0.005, -0.42)
				.build();
		REDSTONE = new Builder("redstone", "torch")
				.on("fps", "coords", "clock", "redstoneSignal", "redstoneClock", "zoom", "freelook", "waypoints", "blockOutline",
						"effects")
				.off("cps", "keystrokes", "ping", "reach", "combo", "speed", "hitColor", "oldAnimations", "lowFire", "noHurtCam",
						"armor", "memory", "server")
				.hud("fps", HudAnchor.TOP_LEFT, 0.005, 0.01)
				.hud("coords", HudAnchor.TOP_LEFT, 0.005, 0.075)
				.hud("clock", HudAnchor.TOP_RIGHT, -0.005, 0.01)
				.hud("effects", HudAnchor.CENTER_RIGHT, -0.005, 0.0)
				.hud("redstoneSignal", HudAnchor.CENTER, 0.2, -0.1)
				.hud("redstoneClock", HudAnchor.CENTER, 0.2, 0.16)
				.build();
		COMFORT = new Builder("comfort", "sun")
				.on("fps", "coords", "clock", "minimap", "waypoints", "armor", "effects", "toggleSprint", "zoom", "freelook", "chat")
				.off("cps", "keystrokes", "reach", "combo", "speed", "hitColor", "oldAnimations", "redstoneSignal", "redstoneClock",
						"redstoneOverlay", "memory", "server")
				.hud("fps", HudAnchor.TOP_LEFT, 0.005, 0.01)
				.hud("coords", HudAnchor.TOP_LEFT, 0.005, 0.075)
				.hud("clock", HudAnchor.TOP_LEFT, 0.005, 0.2)
				.hud("minimap", HudAnchor.TOP_RIGHT, -0.005, 0.01)
				.hud("armor", HudAnchor.CENTER_LEFT, 0.005, 0.0)
				.hud("effects", HudAnchor.CENTER_RIGHT, -0.005, 0.0)
				.hud("toggleSprint", HudAnchor.BOTTOM_RIGHT, -0.005, -0.08)
				.build();
		MINIMAL = new Builder("minimal", "eye")
				.on("fps", "zoom")
				.off("cps", "keystrokes", "ping", "armor", "effects", "coords", "clock", "memory", "server", "packs", "reach",
						"combo", "speed", "minimap", "redstoneSignal", "redstoneClock", "redstoneOverlay", "crosshair", "hitColor",
						"oldAnimations", "lowFire", "blockOutline", "hitboxes", "noHurtCam", "toggleSprint", "toggleSneak")
				.hud("fps", HudAnchor.TOP_LEFT, 0.005, 0.01)
				.build();
		List<Pack> all = new ArrayList<Pack>();
		all.add(PVP);
		all.add(REDSTONE);
		all.add(COMFORT);
		all.add(MINIMAL);
		ALL = Collections.unmodifiableList(all);
	}

	private ModulePacks() {
	}

	public static List<Pack> all() {
		return ALL;
	}

	public static Pack byId(String id) {
		for (Pack p : ALL) {
			if (p.id.equals(id)) return p;
		}
		return null;
	}

	/** Was {@code pack} gegenüber dem jetzigen Zustand ändern würde. */
	public static Preview preview(TrsModules modules, Pack pack, boolean withHud, Support support) {
		Preview p = new Preview();
		for (Map.Entry<String, Boolean> e : pack.modules.entrySet()) {
			Module m = modules.registry.byId(e.getKey());
			if (m == null || (support != null && !support.supports(m))) continue;
			if (m.isEnabled() != e.getValue().booleanValue()) (e.getValue() ? p.enable : p.disable).add(m);
		}
		if (withHud) {
			for (Map.Entry<String, HudPosition> e : pack.hud.entrySet()) {
				Module m = modules.registry.byId(e.getKey());
				if (!(m instanceof HudModule) || (support != null && !support.supports(m))) continue;
				if (!Boolean.TRUE.equals(pack.modules.get(e.getKey())) && !m.isEnabled()) continue;
				if (!same(((HudModule) m).position(), e.getValue())) p.moved.add(m);
			}
		}
		return p;
	}

	/**
	 * Wendet {@code pack} an.
	 *
	 * @return der Zustand davor (für „Rückgängig“: {@code modules.registry.apply(undo)})
	 */
	public static TrsConfig apply(TrsModules modules, Pack pack, boolean withHud, Support support) {
		TrsConfig undo = modules.registry.capture();
		for (Map.Entry<String, Boolean> e : pack.modules.entrySet()) {
			Module m = modules.registry.byId(e.getKey());
			if (m == null || (support != null && !support.supports(m))) continue;
			m.setEnabled(e.getValue().booleanValue());
		}
		if (withHud) {
			for (Map.Entry<String, HudPosition> e : pack.hud.entrySet()) {
				Module m = modules.registry.byId(e.getKey());
				if (!(m instanceof HudModule) || (support != null && !support.supports(m))) continue;
				((HudModule) m).position().set(e.getValue());
			}
		}
		return undo;
	}

	/** Stellt den Zustand vor {@link #apply} wieder her. */
	public static void undo(TrsModules modules, TrsConfig undo) {
		if (undo == null) return;
		// Der NEU-/Einführungs-Stand ändert sich beim Anwenden nicht – ihn nicht auf den alten Stand zurückdrehen.
		TrsConfig now = modules.registry.capture();
		undo.clientState = now.clientState;
		modules.registry.apply(undo);
	}

	private static boolean same(HudPosition a, HudPosition b) {
		return a.anchor == b.anchor && Math.abs(a.offsetX - b.offsetX) < 1e-6 && Math.abs(a.offsetY - b.offsetY) < 1e-6;
	}

	private static final class Builder {
		private final String id;
		private final String icon;
		private final Map<String, Boolean> modules = new LinkedHashMap<String, Boolean>();
		private final Map<String, HudPosition> hud = new LinkedHashMap<String, HudPosition>();

		Builder(String id, String icon) {
			this.id = id;
			this.icon = icon;
		}

		Builder on(String... ids) {
			for (String i : ids) modules.put(i, Boolean.TRUE);
			return this;
		}

		Builder off(String... ids) {
			for (String i : ids) modules.put(i, Boolean.FALSE);
			return this;
		}

		Builder hud(String id, HudAnchor anchor, double x, double y) {
			hud.put(id, new HudPosition(anchor, x, y));
			return this;
		}

		Pack build() {
			return new Pack(id, icon, modules, hud);
		}
	}
}
