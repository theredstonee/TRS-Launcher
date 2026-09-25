package dev.theredstonee.trsclient.core.perf;

import dev.theredstonee.trsclient.core.i18n.I18n;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Leistungs-Check: sucht typische FPS-Bremsen in den Vanilla-Einstellungen und am System und
 * schlägt je eine Korrektur vor. Geändert wird nichts von selbst – nur nach Klick auf „Beheben“,
 * und {@link UndoLog} merkt sich den alten Wert für „Rückgängig“.
 */
public final class PerfCheck {
	public enum Level {
		/** Deutliche Bremse (VSync, Bildraten-Grenze, Simulationsdistanz, Onboard-Grafik, Fabelhaft). */
		HIGH,
		/** Lohnt sich bei niedriger Bildrate. */
		MEDIUM,
		/** Tipp. */
		TIP
	}

	/** Ein Fund mit optionaler Korrektur (Option → neuer Wert). */
	public static final class Finding {
		public final String id;
		public final Level level;
		public final String text;
		public final Map<GameOptions.Opt, Integer> fix;

		Finding(String id, Level level, String text, Map<GameOptions.Opt, Integer> fix) {
			this.id = id;
			this.level = level;
			this.text = text;
			this.fix = fix == null ? Collections.<GameOptions.Opt, Integer>emptyMap() : fix;
		}

		public boolean fixable() {
			return !fix.isEmpty();
		}
	}

	/** Bildrate, unter der Sichtweite & Co. vorgeschlagen werden. */
	static final int LOW_FPS = 60;
	/** Bildraten-Grenze bis hierher (Vanilla-Standard 120) gilt als deutliche Bremse. */
	static final int FPS_LIMIT_HIGH = 120;

	private PerfCheck() {
	}

	/**
	 * Alle Funde für den aktuellen Stand.
	 * @param fps        gemessene Bildrate im Spiel (≤ 0 = unbekannt)
	 * @param dedicated  eingebaute dedizierte Grafikkarte (null = keine/unbekannt)
	 * @param missing    fehlende empfohlene Leistungs-Mods
	 */
	public static List<Finding> run(GameOptions o, double fps, String dedicated, List<String> missing) {
		List<Finding> out = new ArrayList<Finding>();
		if (o == null) return out;
		boolean low = fps > 0 && fps < LOW_FPS;

		String renderer = o.renderer();
		if (GpuInfo.classify(renderer) == GpuInfo.Kind.INTEGRATED && dedicated != null) {
			out.add(new Finding("gpu", Level.HIGH, I18n.tr("perf.check.gpu", short30(renderer), short30(dedicated)), null));
		}
		int vsync = o.get(GameOptions.Opt.VSYNC);
		if (vsync == 1) out.add(fix("vsync", Level.HIGH, I18n.tr("perf.check.vsync"), GameOptions.Opt.VSYNC, 0));
		// „Max. Bildrate“ unter „Unbegrenzt“: frische Vanilla-Optionen stehen auf 120 – das Spiel wird nie schneller,
		// egal wie stark der PC ist. Ab 144 (Monitor-Takt) hat der Spieler sie vermutlich bewusst gesetzt → nur Tipp.
		int maxFps = o.get(GameOptions.Opt.MAX_FPS);
		if (maxFps != GameOptions.NONE && maxFps < GameOptions.UNLIMITED_FPS) {
			out.add(fix("fpsLimit", maxFps <= FPS_LIMIT_HIGH ? Level.HIGH : Level.TIP, I18n.tr("perf.check.fpsLimit", maxFps),
					GameOptions.Opt.MAX_FPS, GameOptions.UNLIMITED_FPS));
		}

		int view = o.get(GameOptions.Opt.VIEW_DISTANCE);
		int sim = o.get(GameOptions.Opt.SIMULATION_DISTANCE);
		if (sim != GameOptions.NONE && view != GameOptions.NONE && sim >= view && sim > 5) {
			int target = Math.max(5, view - 2);
			if (target < sim) {
				out.add(fix("simulation", Level.HIGH, I18n.tr("perf.check.simulation", sim, view, target),
						GameOptions.Opt.SIMULATION_DISTANCE, target));
			}
		}
		if (view != GameOptions.NONE && fps > 0) {
			int target = view;
			if (fps < 30 && view > 10) target = fps < 20 ? 8 : 10;
			else if (fps < LOW_FPS && view > 16) target = 12;
			if (target < view) {
				Map<GameOptions.Opt, Integer> f = map(GameOptions.Opt.VIEW_DISTANCE, target);
				if (sim != GameOptions.NONE && sim > Math.max(5, target - 2)) f.put(GameOptions.Opt.SIMULATION_DISTANCE, Math.max(5, target - 2));
				out.add(new Finding("view", Level.MEDIUM, I18n.tr("perf.check.view", view, Math.round(fps), target), f));
			}
		}

		int graphics = o.get(GameOptions.Opt.GRAPHICS);
		if (graphics == 2) {
			out.add(fix("fabulous", Level.HIGH, I18n.tr("perf.check.fabulous"), GameOptions.Opt.GRAPHICS, 0));
		} else if (graphics == 1) {
			out.add(fix("fancy", low ? Level.MEDIUM : Level.TIP, I18n.tr("perf.check.fancy"), GameOptions.Opt.GRAPHICS, 0));
		}
		if (o.get(GameOptions.Opt.CLOUDS) == 2) {
			out.add(fix("clouds", Level.TIP, I18n.tr("perf.check.clouds"), GameOptions.Opt.CLOUDS, 1));
		}
		if (o.get(GameOptions.Opt.PARTICLES) == 0) {
			out.add(fix("particles", low ? Level.MEDIUM : Level.TIP, I18n.tr("perf.check.particles"), GameOptions.Opt.PARTICLES, 1));
		}
		int mip = o.get(GameOptions.Opt.MIPMAP);
		if (mip != GameOptions.NONE && mip > 2) {
			out.add(fix("mipmap", Level.TIP, I18n.tr("perf.check.mipmap", mip), GameOptions.Opt.MIPMAP, 2));
		}
		int entity = o.get(GameOptions.Opt.ENTITY_DISTANCE);
		if (entity != GameOptions.NONE && entity > 100) {
			out.add(fix("entityDistance", low ? Level.MEDIUM : Level.TIP, I18n.tr("perf.check.entityDistance", entity),
					GameOptions.Opt.ENTITY_DISTANCE, 100));
		}
		int blend = o.get(GameOptions.Opt.BIOME_BLEND);
		if (blend != GameOptions.NONE && blend > 2) {
			out.add(fix("biomeBlend", Level.TIP, I18n.tr("perf.check.biomeBlend", blend), GameOptions.Opt.BIOME_BLEND, 2));
		}
		if (o.get(GameOptions.Opt.SMOOTH_LIGHTING) == 2 && low) {
			int target = o.smoothLightingIsBoolean() ? 0 : 1;
			out.add(fix("smoothLighting", Level.TIP, I18n.tr(target == 0 ? "perf.check.smoothLightingOff" : "perf.check.smoothLighting"),
					GameOptions.Opt.SMOOTH_LIGHTING, target));
		}
		if (o.get(GameOptions.Opt.FULLSCREEN) == 0 && low) {
			out.add(new Finding("fullscreen", Level.TIP, I18n.tr("perf.check.fullscreen"), null));
		}
		if (missing != null && !missing.isEmpty()) {
			out.add(new Finding("mods", Level.TIP, I18n.tr("perf.check.mods", join(missing)), null));
		}
		return out;
	}

	/** Alle behebbaren Funde zusammen (für „Alle beheben“). */
	public static Map<GameOptions.Opt, Integer> allFixes(List<Finding> findings) {
		Map<GameOptions.Opt, Integer> all = new EnumMap<GameOptions.Opt, Integer>(GameOptions.Opt.class);
		for (Finding f : findings) all.putAll(f.fix);
		return all;
	}

	private static Finding fix(String id, Level level, String text, GameOptions.Opt opt, int value) {
		return new Finding(id, level, text, map(opt, value));
	}

	private static Map<GameOptions.Opt, Integer> map(GameOptions.Opt opt, int value) {
		Map<GameOptions.Opt, Integer> m = new EnumMap<GameOptions.Opt, Integer>(GameOptions.Opt.class);
		m.put(opt, Integer.valueOf(value));
		return m;
	}

	private static String join(List<String> names) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < names.size(); i++) {
			if (i > 0) sb.append(", ");
			sb.append(names.get(i));
		}
		return sb.toString();
	}

	private static String short30(String s) {
		if (s == null) return "";
		String t = s.replace("(R)", "").replace("(TM)", "").replace("(tm)", "").replaceAll("\\s+", " ").trim();
		int slash = t.indexOf('/');
		if (slash > 0) t = t.substring(0, slash).trim();
		return t.length() > 30 ? t.substring(0, 29) + "…" : t;
	}
}
