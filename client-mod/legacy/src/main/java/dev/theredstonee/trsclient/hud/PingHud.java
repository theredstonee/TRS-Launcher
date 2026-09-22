package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.HudModule;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;

/** Latenz zum Server laut Spielerliste; im Einzelspieler ausgeblendet. */
public final class PingHud extends TextHudElement {
	private static final int PREVIEW_PING = 42;

	public PingHud(HudModule module) {
		super(module);
	}

	@Override
	public boolean visible() {
		return !mc.isSingleplayer() && entry() != null;
	}

	@Override
	protected long valueKey(boolean preview) {
		NetworkPlayerInfo info = entry();
		if (info == null || mc.isSingleplayer()) return PREVIEW_PING;
		return info.getResponseTime();
	}

	@Override
	protected String format(long key) {
		return key + " ms";
	}

	private NetworkPlayerInfo entry() {
		EntityPlayerSP player = Mc.player();
		NetHandlerPlayClient connection = Mc.connection();
		if (player == null || connection == null) return null;
		return connection.getPlayerInfo(player.getUniqueID());
	}
}
