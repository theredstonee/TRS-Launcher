package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.module.HudModule;
import net.minecraft.client.gui.GuiPlayerInfo;
import net.minecraft.client.network.NetHandlerPlayClient;

import java.util.List;

/** Latenz zum Server laut Spielerliste (1.7.10: Einträge nach Spielernamen); im Einzelspieler ausgeblendet. */
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
		GuiPlayerInfo info = entry();
		if (info == null || mc.isSingleplayer()) return PREVIEW_PING;
		return info.responseTime;
	}

	@Override
	protected String format(long key) {
		return key + " ms";
	}

	private GuiPlayerInfo entry() {
		NetHandlerPlayClient net = mc.getNetHandler();
		if (mc.thePlayer == null || net == null) return null;
		String name = mc.thePlayer.getCommandSenderName();
		@SuppressWarnings("unchecked")
		List<GuiPlayerInfo> list = net.playerInfoList;
		for (int i = 0, n = list.size(); i < n; i++) {
			GuiPlayerInfo info = list.get(i);
			if (info != null && name.equals(info.name)) return info;
		}
		return null;
	}
}
