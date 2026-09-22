package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.camera.FreelookState;
import dev.theredstonee.trsclient.core.input.ToggleState;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Toggle-Sprint/-Schleichen und Freelook. Wird am Ende jedes Client-Ticks aufgerufen – nach der
 * Tastatur-Verarbeitung, damit der gesetzte Tastenzustand bis zur nächsten Spieler-Bewegung gilt.
 * (Die Treffer-Farbe der Fabric-Fassung fehlt hier: die Farbe ist in RendererLivingEntity fest
 * einprogrammiert und ohne Coremod/ASM nicht änderbar.)
 */
public final class PvpFeatures {
	private final TrsModules modules;
	private final ToggleState sprint = new ToggleState();
	private final ToggleState sneak = new ToggleState();
	private final FreelookState freelook = new FreelookState();
	/** Perspektive vor dem Freelook (0 = Ego), -1 = keine gemerkt. */
	private int viewBeforeFreelook = -1;
	/** Nur Selbsttest: Freelook ohne Tastendruck, mit Versatz zum Blick der Spielfigur. */
	private float forcedFreelookYaw = Float.NaN;

	public PvpFeatures(TrsModules modules) {
		this.modules = modules;
	}

	public void tick(Minecraft mc) {
		EntityPlayer player = Mc.player();
		boolean inGame = player != null && mc.currentScreen == null;
		// Vanilla hat bis 1.12.2 keine eigene Umschalt-Option für Sprint/Schleichen.
		tickToggle(sprint, mc.gameSettings.keyBindSprint, modules.toggleSprint.isEnabled(), inGame);
		tickToggle(sneak, mc.gameSettings.keyBindSneak, modules.toggleSneak.isEnabled(), inGame);
		if (player == null) {
			sprint.reset();
			sneak.reset();
		}
		tickFreelook(mc, player, inGame);
	}

	/**
	 * Jeder Druck (isPressed der Taste) schaltet um; aktiv → Taste gilt als gehalten.
	 */
	private static void tickToggle(ToggleState state, KeyBinding key, boolean enabled, boolean inGame) {
		int presses = 0;
		while (key.isPressed()) presses++;
		boolean wasActive = state.active();
		boolean active = state.update(presses, enabled, !inGame);
		if (key.getKeyCode() == 0) return;
		if (active && inGame) KeyBinding.setKeyBindState(key.getKeyCode(), true);
		else if (wasActive && !active) KeyBinding.setKeyBindState(key.getKeyCode(), false);
	}

	private void tickFreelook(Minecraft mc, EntityPlayer player, boolean inGame) {
		boolean forced = !Float.isNaN(forcedFreelookYaw);
		boolean want = (modules.freelook.isEnabled() && inGame && TrsKeys.freelook.isKeyDown()) || (forced && player != null);
		if (want && !freelook.active()) {
			freelook.start(player.rotationYaw + (forced ? forcedFreelookYaw : 0), player.rotationPitch);
			viewBeforeFreelook = mc.gameSettings.thirdPersonView;
			if (viewBeforeFreelook == 0) mc.gameSettings.thirdPersonView = 1;
		} else if (!want && freelook.active()) {
			freelook.stop();
			if (viewBeforeFreelook >= 0) mc.gameSettings.thirdPersonView = viewBeforeFreelook;
			viewBeforeFreelook = -1;
		}
	}

	/** Selbsttest: Freelook erzwingen (Kamera um {@code yawOffset} gedreht) bzw. mit NaN beenden. */
	public void forceFreelook(float yawOffset) {
		forcedFreelookYaw = yawOffset;
	}

	public ToggleState sprint() {
		return sprint;
	}

	public ToggleState sneak() {
		return sneak;
	}

	public FreelookState freelook() {
		return freelook;
	}
}
