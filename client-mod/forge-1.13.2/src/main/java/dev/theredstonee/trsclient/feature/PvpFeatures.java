package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.core.input.MovementToggles;
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
	/** Toggle-Sprint/-Schleichen + Flug-Boost (Logik in common). */
	private final MovementToggles toggles;
	private boolean sprintHeld;
	private boolean sneakHeld;

	public PvpFeatures(TrsModules modules) {
		this.modules = modules;
		this.toggles = new MovementToggles(modules);
	}

	/**
	 * Anfang des Client-Ticks: Tastendrücke seit dem letzten Tick zählen und die Logik in
	 * {@link MovementToggles} entscheiden lassen (Umschalten, Tod/Weltwechsel, Flug-Boost).
	 */
	public void countPresses(Minecraft mc) {
		net.minecraft.entity.player.EntityPlayer player = mc.player;
		boolean inGame = player != null && mc.currentScreen == null;
		KeyBinding sprintKey = mc.gameSettings.keyBindSprint;
		KeyBinding sneakKey = mc.gameSettings.keyBindSneak;
		MovementToggles.Input in = toggles.input().clear();
		// isPressed() verbraucht nur den Klick-Zähler; Vanilla liest Sprint/Schleichen über isKeyDown().
		while (sprintKey.isPressed()) in.sprintPresses++;
		while (sneakKey.isPressed()) in.sneakPresses++;
		if (player != null) {
			in.hasPlayer = true;
			in.inGame = inGame;
			in.dead = player.getHealth() <= 0;
			// Respawn und Dimensionswechsel erzeugen eine neue Spielfigur bzw. Welt.
			in.context = System.identityHashCode(player) * 31 + System.identityHashCode(mc.world);
			in.forwardDown = mc.gameSettings.keyBindForward.isKeyDown();
			in.sprintKeyDown = sprintKey.isKeyDown();
			in.sneakKeyDown = sneakKey.isKeyDown();
			in.creativeFlying = player.abilities.isFlying && player.abilities.isCreativeMode;
			in.sprinting = player.isSprinting();
		}
		toggles.tick();
		sprintHeld = toggles.sprintAction() == MovementToggles.PRESS;
		sneakHeld = toggles.sneakAction() == MovementToggles.PRESS;
		// Umschaltung beendet → echten Tastenzustand wiederherstellen.
		if (toggles.sprintAction() == MovementToggles.RELEASE) {
			KeyBinding.setKeyBindState(sprintKey.getKey(), physicallyDown(mc, sprintKey.getKey()));
		}
		if (toggles.sneakAction() == MovementToggles.RELEASE) {
			KeyBinding.setKeyBindState(sneakKey.getKey(), physicallyDown(mc, sneakKey.getKey()));
		}
		if (player != null) {
			float current = player.abilities.getFlySpeed();
			float wanted = toggles.flySpeed(current);
			if (wanted != current) player.abilities.setFlySpeed(wanted);
		}
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
		return toggles.sprint();
	}

	public ToggleState sneak() {
		return toggles.sneak();
	}

	public MovementToggles toggles() {
		return toggles;
	}
}
