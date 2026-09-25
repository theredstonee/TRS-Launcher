package dev.theredstonee.trsclient.core.menus;

import dev.theredstonee.trsclient.core.module.TrsModules;

/**
 * Welche Vanilla-Menüs im Redstone-Stil gezeichnet werden (Modul „Menü-Stil“). Jede Loader-Variante ordnet
 * den offenen Minecraft-Bildschirm einer {@link Kind} zu und fragt hier nach; ausgeschaltete Menüs bleiben
 * „klassisch“. Die Menüs selbst werden nie ersetzt – nur Hintergrund, Knopfflächen und Listen neu gezeichnet,
 * damit alle Knöpfe (auch die anderer Mods) weiter funktionieren.
 */
public final class MenuStyle {
	/** Arten von Vanilla-Menüs mit eigenem Schalter. */
	public enum Kind {
		PAUSE, MULTIPLAYER, LOADING, OPTIONS, WORLDS
	}

	private static volatile TrsModules modules;

	private MenuStyle() {
	}

	/** Einmal beim Start (je Loader). */
	public static void install(TrsModules m) {
		modules = m;
	}

	/** Im Redstone-Stil zeichnen? {@code null} = kein bekanntes Menü. */
	public static boolean enabled(Kind kind) {
		TrsModules m = modules;
		if (kind == null || m == null || !m.menuStyle.isEnabled()) return false;
		switch (kind) {
			case PAUSE:
				return m.menuPause.get();
			case MULTIPLAYER:
				return m.menuMultiplayer.get();
			case LOADING:
				return m.menuLoading.get();
			case OPTIONS:
				return m.menuOptions.get();
			case WORLDS:
				return m.menuWorlds.get();
			default:
				return false;
		}
	}

	/** Zusätzliche TRS-Knöpfe (Garderobe, Konten, Clips, Freunde, Server-Info) im Pausenmenü? */
	public static boolean pauseButtons() {
		TrsModules m = modules;
		return m != null && m.menuStyle.isEnabled() && m.menuPauseButtons.get();
	}

	/** Bewegter Redstone-Hintergrund (Einstellung des Startbildschirms)? */
	public static boolean animated() {
		TrsModules m = modules;
		return m == null || m.titleAnimated.get();
	}
}
