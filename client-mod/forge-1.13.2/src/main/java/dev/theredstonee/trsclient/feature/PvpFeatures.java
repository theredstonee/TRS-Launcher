package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.core.input.ToggleState;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.util.InputMappings;
import org.lwjgl.glfw.GLFW;

/**
 * Toggle-Sprint/-Schleichen für 1.13.2 (Vanilla hat hier noch keine eigene Umschalt-Option).
 * <p>
 * {@link #countPresses} läuft zu Beginn des Client-Ticks und zählt neue Tastendrücke;
 * {@link #apply} läuft im {@code LivingUpdateEvent} des eigenen Spielers – direkt BEVOR
 * {@code EntityPlayerSP.livingTick} die Tasten liest. Dadurch flackert Schleichen beim Loslassen nicht.
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
		boolean inGame = mc.player != null && mc.currentScreen == null;
		sprintHeld = tick(mc, sprint, mc.gameSettings.keyBindSprint, modules.toggleSprint.isEnabled(), inGame, sprintHeld);
		sneakHeld = tick(mc, sneak, mc.gameSettings.keyBindSneak, modules.toggleSneak.isEnabled(), inGame, sneakHeld);
		if (mc.player == null) {
			sprint.reset();
			sneak.reset();
		}
	}

	private static boolean tick(Minecraft mc, ToggleState state, KeyBinding key, boolean enabled, boolean inGame, boolean held) {
		int presses = 0;
		// isPressed() verbraucht nur den Klick-Zähler; Vanilla liest Sprint/Schleichen über isKeyDown().
		while (key.isPressed()) presses++;
		boolean active = state.update(presses, enabled, !inGame);
		if (held && !active) {
			// Umschaltung beendet → echten Tastenzustand wiederherstellen.
			KeyBinding.setKeyBindState(key.getKey(), physicallyDown(mc, key.getKey()));
		}
		return active;
	}

	/** Direkt vor der Bewegungs-Auswertung des Spielers: umgeschaltete Tasten als gehalten markieren. */
	public void apply(Minecraft mc) {
		if (mc.currentScreen != null) return;
		if (sprintHeld) KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKey(), true);
		if (sneakHeld) KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKey(), true);
	}

	private static boolean physicallyDown(Minecraft mc, InputMappings.Input input) {
		if (input == null || input == InputMappings.INPUT_INVALID) return false;
		if (input.getType() == InputMappings.Type.MOUSE) {
			return GLFW.glfwGetMouseButton(mc.mainWindow.getHandle(), input.getKeyCode()) == GLFW.GLFW_PRESS;
		}
		return input.getType() == InputMappings.Type.KEYSYM && InputMappings.isKeyDown(input.getKeyCode());
	}

	public ToggleState sprint() {
		return sprint;
	}

	public ToggleState sneak() {
		return sneak;
	}
}
