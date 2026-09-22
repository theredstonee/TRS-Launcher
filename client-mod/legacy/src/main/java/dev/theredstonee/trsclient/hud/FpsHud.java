package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.module.HudModule;
import net.minecraft.client.Minecraft;

/** Bilder pro Sekunde. */
public final class FpsHud extends TextHudElement {
	public FpsHud(HudModule module) {
		super(module);
	}

	@Override
	protected long valueKey(boolean preview) {
		return Minecraft.getDebugFPS();
	}

	@Override
	protected String format(long key) {
		return key + " FPS";
	}
}
