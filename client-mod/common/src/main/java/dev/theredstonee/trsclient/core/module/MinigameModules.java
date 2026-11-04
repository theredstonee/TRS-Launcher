package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.minigames.MinigameBest;

/**
 * Minispiele (TRS Client {@link NewSince#MINIGAMES}): Redstone-Stapler und Loren-Rausch als Menü-Bildschirm, mit weltweiter
 * Rangliste. Reiner Menü-Inhalt, das Spiel läuft nicht in der Welt und bringt auf Servern keinen Vorteil.
 *
 * <p>Logik und Oberfläche liegen in {@code core.minigames}; die Bäume liefern nur Tastenzustand, Fensterfokus und das
 * Öffnen des Bildschirms ({@code MenuHost}).
 */
public final class MinigameModules {
	public final Module minigames;
	public final BoolSetting sound;
	public final NumberSetting volume;
	/** Wackeln und Blitze zeigen. */
	public final BoolSetting effects;
	/** Partien für die weltweite Rangliste einreichen. */
	public final BoolSetting ranked;
	public final MinigameBest best = new MinigameBest();

	MinigameModules(ModuleRegistry registry) {
		minigames = registry.register(new Module("minigames", "Minigames",
				"Redstone Stacker and Minecart Rush from the TRS menu, with the textures of your game and resource packs and a "
						+ "worldwide leaderboard. A menu screen, so no advantage on servers.", true));
		minigames.icon("game").category(Category.MISC);
		sound = minigames.add(new BoolSetting("sound", "Sound", true));
		volume = minigames.add(new NumberSetting("volume", "Volume", 80, 0, 100, 5, "", " %"));
		effects = minigames.add(new BoolSetting("effects", "Screen shake and flashes", true));
		ranked = minigames.add(new BoolSetting("ranked", "Submit runs to the leaderboard", true));
		registry.addPart(best);
	}
}
