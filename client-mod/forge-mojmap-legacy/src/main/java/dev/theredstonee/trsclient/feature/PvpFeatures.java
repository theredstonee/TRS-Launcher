package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.camera.FreelookState;
import dev.theredstonee.trsclient.core.input.ToggleState;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
//? if >=1.15 {
import com.mojang.blaze3d.platform.NativeImage;
import dev.theredstonee.trsclient.mixin.OverlayTextureAccessor;
import net.minecraft.client.renderer.texture.DynamicTexture;
//?}

/**
 * Toggle-Sprint/-Schleichen, Freelook und Treffer-Farbe. Wird zu Beginn jedes Client-Ticks
 * aufgerufen (vor der Spieler-Bewegung, damit gesetzte Tasten im selben Tick wirken).
 * Freelook und Treffer-Farbe brauchen Mixins – ohne Mixin (Forge 1.14.4) sind sie aus.
 */
public final class PvpFeatures {
	/** Vanilla-Farbe der Treffer-Einfärbung (ARGB). */
	private static final int VANILLA_HIT = 0xB2FF0000;

	private final TrsModules modules;
	private final ToggleState sprint = new ToggleState();
	private final ToggleState sneak = new ToggleState();
	private final FreelookState freelook = new FreelookState();
	private int cameraBeforeFreelook = -1;
	/** Nur Selbsttest: Freelook ohne Tastendruck, mit Versatz zum Blick der Spielfigur. */
	private float forcedFreelookYaw = Float.NaN;
	private int appliedHitColor = VANILLA_HIT;
	private boolean hitColorFailed;

	public PvpFeatures(TrsModules modules) {
		this.modules = modules;
	}

	/** Gibt es auf dieser Version Freelook/Treffer-Farbe (Mixins vorhanden)? */
	public static boolean mixinFeatures() {
		//? if >=1.15 {
		return true;
		//?} else
		/*return false;*/
	}

	public void tick(Minecraft mc) {
		boolean inGame = mc.player != null && Mc.screen() == null;
		tickToggle(sprint, mc.options.keySprint, modules.toggleSprint.isEnabled() && !Mc.vanillaToggleSprint(), inGame);
		tickToggle(sneak, Mc.sneakKey(), modules.toggleSneak.isEnabled() && !Mc.vanillaToggleCrouch(), inGame);
		if (mc.player == null) {
			sprint.reset();
			sneak.reset();
		}
		if (mixinFeatures()) {
			tickFreelook(mc, inGame);
			tickHitColor(mc);
		}
	}

	/**
	 * Jeder Druck (clickCount der Taste) schaltet um; aktiv → Taste gilt als gehalten.
	 * Vanillas eigene Umschalt-Option hat Vorrang (dann ist das Modul wirkungslos).
	 */
	private static void tickToggle(ToggleState state, KeyMapping key, boolean enabled, boolean inGame) {
		int presses = 0;
		while (key.consumeClick()) presses++;
		boolean wasActive = state.active();
		boolean active = state.update(presses, enabled, !inGame);
		if (active && inGame) Mc.setDown(key, true);
		else if (wasActive && !active) Mc.setDown(key, false);
	}

	private void tickFreelook(Minecraft mc, boolean inGame) {
		boolean forced = !Float.isNaN(forcedFreelookYaw);
		boolean want = (modules.freelook.isEnabled() && inGame && TrsKeys.freelook.isDown()) || (forced && mc.player != null);
		if (want && !freelook.active()) {
			freelook.start(Mc.yRot(mc.player) + (forced ? forcedFreelookYaw : 0), Mc.xRot(mc.player));
			cameraBeforeFreelook = Mc.cameraMode();
			if (Mc.firstPerson()) Mc.setCameraMode(1);
		} else if (!want && freelook.active()) {
			freelook.stop();
			if (cameraBeforeFreelook >= 0) Mc.setCameraMode(cameraBeforeFreelook);
			cameraBeforeFreelook = -1;
		}
	}

	/** Färbt die obere Hälfte der Overlay-Textur (= Treffer-Einfärbung) um (ab 1.15). */
	private void tickHitColor(Minecraft mc) {
		int wanted = modules.hitColor.isEnabled()
				? (Math.round(modules.hitColorOpacity.getInt() * 2.55F) << 24) | modules.hitColorColor.rgb()
				: VANILLA_HIT;
		if (wanted == appliedHitColor || hitColorFailed || mc.gameRenderer == null) return;
		//? if >=1.15 {
		try {
			DynamicTexture texture = ((OverlayTextureAccessor) mc.gameRenderer.overlayTexture()).trsclient$getTexture();
			NativeImage image = texture.getPixels();
			if (image == null) return;
			// NativeImage erwartet bis 1.21.1 ABGR.
			int abgr = (wanted & 0xFF00FF00) | ((wanted >> 16) & 0xFF) | ((wanted & 0xFF) << 16);
			for (int y = 0; y < 8; y++) {
				for (int x = 0; x < 16; x++) image.setPixelRGBA(x, y, abgr);
			}
			texture.upload();
			appliedHitColor = wanted;
		} catch (RuntimeException e) {
			// Nie das Spiel abstürzen lassen – nur einmal melden.
			hitColorFailed = true;
			TrsClient.LOGGER.error("Treffer-Farbe konnte nicht gesetzt werden", e);
		}
		//?}
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
