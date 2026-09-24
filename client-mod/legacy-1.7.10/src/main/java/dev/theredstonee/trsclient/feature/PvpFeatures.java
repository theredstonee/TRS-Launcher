package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.core.input.MovementToggles;
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
		net.minecraft.entity.player.EntityPlayer player = mc.thePlayer;
		boolean inGame = player != null && mc.currentScreen == null;
		KeyBinding sprintKey = mc.gameSettings.keyBindSprint;
		KeyBinding sneakKey = mc.gameSettings.keyBindSneak;
		MovementToggles.Input in = toggles.input().clear();
		// isPressed() verbraucht nur den Klick-Zähler; Vanilla liest Sprint/Schleichen über getIsKeyPressed().
		while (sprintKey.isPressed()) in.sprintPresses++;
		while (sneakKey.isPressed()) in.sneakPresses++;
		if (player != null) {
			in.hasPlayer = true;
			in.inGame = inGame;
			in.dead = player.getHealth() <= 0;
			// Respawn und Dimensionswechsel erzeugen eine neue Spielfigur bzw. Welt.
			in.context = System.identityHashCode(player) * 31 + System.identityHashCode(mc.theWorld);
			in.forwardDown = mc.gameSettings.keyBindForward.getIsKeyPressed();
			in.sprintKeyDown = sprintKey.getIsKeyPressed();
			in.sneakKeyDown = sneakKey.getIsKeyPressed();
			in.creativeFlying = player.capabilities.isFlying && player.capabilities.isCreativeMode;
			in.sprinting = player.isSprinting();
		}
		toggles.tick();
		sprintHeld = toggles.sprintAction() == MovementToggles.PRESS;
		sneakHeld = toggles.sneakAction() == MovementToggles.PRESS;
		// Umschaltung beendet → echten Tastenzustand wiederherstellen.
		if (toggles.sprintAction() == MovementToggles.RELEASE) {
			KeyBinding.setKeyBindState(sprintKey.getKeyCode(), physicallyDown(sprintKey.getKeyCode()));
		}
		if (toggles.sneakAction() == MovementToggles.RELEASE) {
			KeyBinding.setKeyBindState(sneakKey.getKeyCode(), physicallyDown(sneakKey.getKeyCode()));
		}
		if (player != null) {
			float current = player.capabilities.getFlySpeed();
			float wanted = toggles.flySpeed(current);
			if (wanted != current) player.capabilities.setFlySpeed(wanted);
		}
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
		return toggles.sprint();
	}

	public ToggleState sneak() {
		return toggles.sneak();
	}

	public MovementToggles toggles() {
		return toggles;
	}
}
