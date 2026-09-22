package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.core.input.ToggleState;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

/**
 * Toggle-Sprint/-Schleichen für 1.7.10 (Vanilla hat hier noch keine eigene Umschalt-Option).
 * <p>
 * {@link #countPresses} läuft zu Beginn des Client-Ticks und zählt neue Tastendrücke;
 * {@link #apply} läuft im {@code LivingUpdateEvent} des eigenen Spielers – also NACH der
 * Tastatur-Verarbeitung des Ticks und direkt BEVOR {@code EntityPlayerSP.onLivingUpdate}
 * die Tasten liest. Dadurch flackert Schleichen beim Loslassen der Taste nicht.
 */
public final class PvpFeatures {
	private final TrsModules modules;
	private final ToggleState sprint = new ToggleState();
	private final ToggleState sneak = new ToggleState();
	private boolean sprintHeld;
	private boolean sneakHeld;

	public PvpFeatures(TrsModules modules) {
		this.modules = modules;
	}

	/** Anfang des Client-Ticks: Tastendrücke seit dem letzten Tick zählen und umschalten. */
	public void countPresses(Minecraft mc) {
		boolean inGame = mc.thePlayer != null && mc.currentScreen == null;
		sprintHeld = tick(sprint, mc.gameSettings.keyBindSprint, modules.toggleSprint.isEnabled(), inGame, sprintHeld);
		sneakHeld = tick(sneak, mc.gameSettings.keyBindSneak, modules.toggleSneak.isEnabled(), inGame, sneakHeld);
		if (mc.thePlayer == null) {
			sprint.reset();
			sneak.reset();
		}
	}

	private static boolean tick(ToggleState state, KeyBinding key, boolean enabled, boolean inGame, boolean held) {
		int presses = 0;
		// isPressed() verbraucht nur den Klick-Zähler; Vanilla liest Sprint/Schleichen über getIsKeyPressed().
		while (key.isPressed()) presses++;
		boolean active = state.update(presses, enabled, !inGame);
		if (held && !active) {
			// Umschaltung beendet → echten Tastenzustand wiederherstellen.
			KeyBinding.setKeyBindState(key.getKeyCode(), physicallyDown(key.getKeyCode()));
		}
		return active;
	}

	/** Direkt vor der Bewegungs-Auswertung des Spielers: umgeschaltete Tasten als gehalten markieren. */
	public void apply(Minecraft mc) {
		if (mc.currentScreen != null) return;
		if (sprintHeld) KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), true);
		if (sneakHeld) KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), true);
	}

	private static boolean physicallyDown(int code) {
		if (code == 0) return false;
		return code < 0 ? org.lwjgl.input.Mouse.isButtonDown(code + 100) : org.lwjgl.input.Keyboard.isKeyDown(code);
	}

	public ToggleState sprint() {
		return sprint;
	}

	public ToggleState sneak() {
		return sneak;
	}
}
