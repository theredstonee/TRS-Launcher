package dev.theredstonee.trsrules.common;

import java.util.HashMap;
import java.util.Map;

/** English display names of the TRS Client modules (as in the client menu) – for the default config and status output. */
public final class ModuleLabels {
	private static final Map<String, String> LABELS = new HashMap<String, String>();

	static {
		// HUD
		put("fps", "FPS");
		put("ping", "Ping");
		put("effects", "Potion Effects");
		put("clock", "Clock");
		put("memory", "Memory");
		put("packs", "Active Resource Packs");
		put("scoreboard", "Scoreboard");
		put("tabPing", "Tab List Ping");
		put("bossBar", "Boss Bar");
		put("titles", "Titles");
		put("warnings", "Warnings (durability, hunger, health)");
		put("hungerOverlay", "Hunger Overlay");
		put("pinnedNote", "Pinned Note");
		// PVP
		put("cps", "CPS");
		put("keystrokes", "Keystrokes");
		put("armor", "Armor");
		put("toggleSprint", "Toggle Sprint");
		put("toggleSneak", "Toggle Sneak");
		put("reach", "Reach display");
		put("combo", "Combo counter");
		put("speed", "Speed display");
		put("crosshair", "Custom Crosshair");
		put("hitColor", "Hit Color");
		put("oldAnimations", "1.7 Animations");
		put("lowFire", "Low Fire");
		put("shieldPosition", "Shield Position");
		put("hitboxes", "Hitboxes");
		put("noHurtCam", "No Hurt Cam");
		put("itemCounter", "Item Counter");
		put("hitFeedback", "Hit Feedback");
		// WORLD
		put("coords", "Coordinates");
		put("server", "Server Address");
		put("minimap", "Minimap");
		put("worldMap", "World Map");
		put("freelook", "Freelook");
		put("zoom", "Zoom");
		put("fullbright", "Gamma / Fullbright");
		put("autorefill", "Auto-Refill");
		put("blockOutline", "Block Outline");
		put("waypoints", "Waypoints");
		put("colors", "Colors");
		put("panorama", "Panorama Screenshots");
		put("notes", "World Notes");
		// CHAT
		put("chat", "Chat Improvements");
		put("autoGg", "Auto-GG");
		put("textHotkeys", "Text Hotkeys");
		put("social", "Social (TRS chat)");
		put("chatMentions", "Mentions");
		put("chatHeads", "Chat Heads");
		put("chatFilter", "Chat Filter");
		// REDSTONE
		put("redstoneSignal", "Signal Strength");
		put("redstoneOverlay", "Signal Overlay");
		put("redstoneClock", "Clock Meter");
		put("circuitLibrary", "Circuit Library (ghost blocks)");
		// PERFORMANCE
		put("fpsBoost", "FPS Boost");
		put("dynamicFps", "Dynamic FPS");
		put("borderlessFullscreen", "Borderless Fullscreen");
		put("entityCulling", "Entity Culling");
		put("particles", "Particles");
		put("worldDetails", "World Details");
		put("builtinOptimizations", "Built-in Optimizations");
		put("netOptimize", "Network Optimization");
		put("lowLatency", "Low Input Latency");
		put("fastConnect", "Fast Connect");
		// MISC
		put("titleScreen", "Title Screen");
		put("menuStyle", "Menu Style");
		put("trsOnline", "TRS Online Features");
		put("capePhysics", "Cape Physics");
		put("emotes", "Emotes");
		put("clips", "Clips & Recording");
		put("cinematic", "Camera Paths / Freecam");
		put("halloween", "Halloween");
		put("tooltips", "Better Tooltips");
		put("serverProfiles", "Server Profiles");
		put("screenshots", "Screenshot Tools");
		put("keySearch", "Key Binding Search");
		put("autoReconnect", "Auto Reconnect");
		put("queueAlerts", "Queue & Alerts");
		put("streamerMode", "Streamer Mode");
	}

	private ModuleLabels() {
	}

	private static void put(String id, String label) {
		LABELS.put(id, label);
	}

	/** Display name, or the id itself for unknown modules. */
	public static String label(String id) {
		String l = LABELS.get(id);
		return l == null ? id : l;
	}

	static boolean has(String id) {
		return LABELS.containsKey(id);
	}
}
