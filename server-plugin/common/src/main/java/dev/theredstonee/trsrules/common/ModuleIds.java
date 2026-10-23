package dev.theredstonee.trsrules.common;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Module ids of the TRS Client (ids of its ModuleRegistry), grouped like the in-game menu.
 *
 * <p>Only used for config warnings (typos) and the default config; unknown ids are still sent –
 * a newer TRS Client may know them, an older one ignores them silently.</p>
 */
public final class ModuleIds {
	/** Category name → module ids (menu order). */
	public static final Map<String, List<String>> BY_CATEGORY;
	/** All known ids. */
	public static final Set<String> ALL;

	static {
		Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
		m.put("HUD", ids("fps", "ping", "effects", "clock", "memory", "packs", "scoreboard", "tabPing", "bossBar", "titles",
				"warnings", "hungerOverlay", "pinnedNote"));
		m.put("PVP", ids("cps", "keystrokes", "armor", "toggleSprint", "toggleSneak", "reach", "combo", "speed", "crosshair",
				"hitColor", "oldAnimations", "lowFire", "shieldPosition", "hitboxes", "noHurtCam", "itemCounter", "hitFeedback"));
		m.put("WORLD", ids("coords", "server", "minimap", "worldMap", "freelook", "zoom", "fullbright", "autorefill",
				"blockOutline", "waypoints", "colors", "panorama", "notes"));
		m.put("CHAT", ids("chat", "autoGg", "textHotkeys", "social", "chatMentions", "chatHeads", "chatFilter"));
		m.put("REDSTONE", ids("redstoneSignal", "redstoneOverlay", "redstoneClock", "circuitLibrary"));
		m.put("PERFORMANCE", ids("fpsBoost", "dynamicFps", "borderlessFullscreen", "entityCulling", "particles",
				"worldDetails", "builtinOptimizations", "netOptimize", "lowLatency", "fastConnect"));
		m.put("MISC", ids("titleScreen", "menuStyle", "trsOnline", "capePhysics", "emotes", "clips", "cinematic", "halloween",
				"tooltips", "serverProfiles", "screenshots", "keySearch", "autoReconnect", "queueAlerts", "streamerMode"));
		BY_CATEGORY = Collections.unmodifiableMap(m);
		Set<String> all = new LinkedHashSet<String>();
		for (List<String> l : m.values()) all.addAll(l);
		ALL = Collections.unmodifiableSet(all);
	}

	private ModuleIds() {
	}

	private static List<String> ids(String... ids) {
		return Collections.unmodifiableList(Arrays.asList(ids));
	}

	public static boolean isKnown(String id) {
		return ALL.contains(id);
	}
}
