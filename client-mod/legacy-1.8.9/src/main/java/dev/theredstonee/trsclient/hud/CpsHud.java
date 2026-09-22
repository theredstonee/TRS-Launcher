package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.HudModule;

/** Klicks pro Sekunde, links | rechts. */
public final class CpsHud extends TextHudElement {
	public CpsHud(HudModule module) {
		super(module);
	}

	@Override
	protected long valueKey(boolean preview) {
		long now = System.currentTimeMillis();
		TrsClient c = TrsClient.get();
		return ((long) c.leftClicks().count(now) << 32) | c.rightClicks().count(now);
	}

	@Override
	protected String format(long key) {
		return (key >>> 32) + " | " + (key & 0xFFFFFFFFL) + " CPS";
	}
}
