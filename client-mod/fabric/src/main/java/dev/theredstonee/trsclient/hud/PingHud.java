package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.module.HudModule;
import net.minecraft.client.multiplayer.PlayerInfo;

/** Latenz zum Server laut Spielerliste; im Einzelspieler ausgeblendet. */
public final class PingHud extends TextHudElement {
	private static final int PREVIEW_PING = 42;

	public PingHud(HudModule module) {
		super(module);
	}

	@Override
	public boolean visible() {
		return mc.getSingleplayerServer() == null && entry() != null;
	}

	@Override
	protected long valueKey(boolean preview) {
		PlayerInfo info = entry();
		if (info == null || mc.getSingleplayerServer() != null) return PREVIEW_PING;
		return info.getLatency();
	}

	@Override
	protected String format(long key) {
		return key + " ms";
	}

	private PlayerInfo entry() {
		if (mc.player == null || mc.getConnection() == null) return null;
		return mc.getConnection().getPlayerInfo(mc.player.getUUID());
	}
}
