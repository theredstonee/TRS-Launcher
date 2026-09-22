package dev.theredstonee.trsclient;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.camera.FreelookState;
import dev.theredstonee.trsclient.core.input.MouseScaler;
import dev.theredstonee.trsclient.dev.HookStats;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.MouseHelper;

/**
 * Ersetzt {@code Minecraft.mouseHelper}: teilt die Mausbewegung während des Zooms durch den
 * Zoom-Faktor (langsamere Maus) und lenkt sie bei Freelook auf die Kamera statt auf die Spielfigur.
 */
final class ZoomMouseHelper extends MouseHelper {
	private final TrsClient client;
	private final MouseScaler scaler = new MouseScaler();

	ZoomMouseHelper(TrsClient client) {
		this.client = client;
	}

	@Override
	public void mouseXYChange() {
		super.mouseXYChange();
		HookStats.mouse++;
		FreelookState freelook = client.pvp().freelook();
		if (freelook.active()) {
			// Gleiche Umrechnung wie EntityRenderer#updateCameraAndRender → Entity#setAngles.
			GameSettings o = Mc.mc().gameSettings;
			float f = o.mouseSensitivity * 0.6F + 0.2F;
			float f1 = f * f * f * 8.0F;
			int invert = o.invertMouse ? -1 : 1;
			freelook.turn(deltaX * f1, -deltaY * f1 * invert);
			deltaX = 0;
			deltaY = 0;
			return;
		}
		scaler.scale(deltaX, deltaY, client.mouseDivisor());
		deltaX = scaler.x();
		deltaY = scaler.y();
	}
}
