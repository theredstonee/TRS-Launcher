package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.camera.FreelookState;
import dev.theredstonee.trsclient.core.input.ToggleState;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.mixin.OverlayTextureAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * Toggle-Sprint/-Schleichen, Freelook und Treffer-Farbe. Wird zu Beginn jedes Client-Ticks
 * aufgerufen (vor der Spieler-Bewegung, damit gesetzte Tasten im selben Tick wirken).
 */
public final class PvpFeatures {
	/** Vanilla-Farbe der Treffer-Einfärbung (ARGB). */
	private static final int VANILLA_HIT = 0xB2FF0000;

	private final TrsModules modules;
	private final ToggleState sprint = new ToggleState();
	private final ToggleState sneak = new ToggleState();
	private final FreelookState freelook = new FreelookState();
	private CameraType cameraBeforeFreelook;
	/** Nur Selbsttest: Freelook ohne Tastendruck, mit Versatz zum Blick der Spielfigur. */
	private float forcedFreelookYaw = Float.NaN;
	private int appliedHitColor = VANILLA_HIT;
	private boolean hitColorFailed;

	public PvpFeatures(TrsModules modules) {
		this.modules = modules;
	}

	public void tick(Minecraft mc) {
		boolean inGame = mc.player != null && Mc.screen() == null;
		tickToggle(sprint, mc.options.keySprint, modules.toggleSprint.isEnabled() && !mc.options.toggleSprint().get(), inGame);
		tickToggle(sneak, mc.options.keyShift, modules.toggleSneak.isEnabled() && !mc.options.toggleCrouch().get(), inGame);
		if (mc.player == null) {
			sprint.reset();
			sneak.reset();
		}
		tickFreelook(mc, inGame);
		tickHitColor(mc);
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
		if (active && inGame) key.setDown(true);
		else if (wasActive && !active) key.setDown(false);
	}

	private void tickFreelook(Minecraft mc, boolean inGame) {
		boolean forced = !Float.isNaN(forcedFreelookYaw);
		boolean want = (modules.freelook.isEnabled() && inGame && TrsKeys.freelook.isDown()) || (forced && mc.player != null);
		if (want && !freelook.active()) {
			freelook.start(mc.player.getYRot() + (forced ? forcedFreelookYaw : 0), mc.player.getXRot());
			cameraBeforeFreelook = mc.options.getCameraType();
			if (cameraBeforeFreelook.isFirstPerson()) mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		} else if (!want && freelook.active()) {
			freelook.stop();
			if (cameraBeforeFreelook != null) mc.options.setCameraType(cameraBeforeFreelook);
			cameraBeforeFreelook = null;
		}
	}

	/** Färbt die obere Hälfte der Overlay-Textur (= Treffer-Einfärbung) um. */
	private void tickHitColor(Minecraft mc) {
		int wanted = modules.hitColor.isEnabled()
				? (Math.round(modules.hitColorOpacity.getInt() * 2.55F) << 24) | modules.hitColorColor.rgb()
				: VANILLA_HIT;
		if (wanted == appliedHitColor || hitColorFailed || mc.gameRenderer == null) return;
		try {
			DynamicTexture texture = ((OverlayTextureAccessor) mc.gameRenderer.overlayTexture()).trsclient$getTexture();
			NativeImage image = texture.getPixels();
			if (image == null) return;
			for (int y = 0; y < 8; y++) {
				for (int x = 0; x < 16; x++) setPixel(image, x, y, wanted);
			}
			texture.upload();
			appliedHitColor = wanted;
		} catch (RuntimeException e) {
			// Nie das Spiel abstürzen lassen – nur einmal melden.
			hitColorFailed = true;
			dev.theredstonee.trsclient.TrsClient.LOGGER.error("Treffer-Farbe konnte nicht gesetzt werden", e);
		}
	}

	/** ARGB-Pixel setzen (bis 1.21.1 erwartet NativeImage ABGR). */
	private static void setPixel(NativeImage image, int x, int y, int argb) {
		//? if >=1.21.2 {
		/*image.setPixel(x, y, argb);
		*///?} else {
		int abgr = (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
		image.setPixelRGBA(x, y, abgr);
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
