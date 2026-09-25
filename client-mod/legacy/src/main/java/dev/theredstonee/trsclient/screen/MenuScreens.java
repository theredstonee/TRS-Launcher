package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.ui.clips.ClipsHost;
import dev.theredstonee.trsclient.core.ui.clips.ClipsUi;
import dev.theredstonee.trsclient.core.ui.friends.FriendsHost;
import dev.theredstonee.trsclient.core.ui.friends.FriendsUi;
import dev.theredstonee.trsclient.core.ui.menus.ServerInfoHost;
import dev.theredstonee.trsclient.core.ui.menus.ServerInfoUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Einstiege in die TRS-Bildschirme Freunde, Clips &amp; Bilder und Server-Info unter Minecraft 1.8.9–1.12.2.
 * Inhalt und Bedienung stehen versionsunabhängig in {@code core.ui}.
 */
public final class MenuScreens {
	private MenuScreens() {
	}

	// --- Freunde ---

	public static boolean friendsAvailable() {
		return TrsOnline.current() != null;
	}

	public static GuiScreen friends(final GuiScreen parent) {
		final TrsMenuHost back = new TrsMenuHost(parent);
		FriendsHost host = new FriendsHost() {
			@Override
			public void playClick() {
				back.playClick();
			}

			@Override
			public void closeScreen() {
				back.closeScreen();
			}

			@Override
			public String userAgent() {
				return "TRS-Client";
			}
		};
		return new TrsUiScreen(I18n.tr("friends.title"), new FriendsUi(host, TrsOnline.current()));
	}

	// --- Clips & Bilder ---

	public static GuiScreen clips(final GuiScreen parent) {
		final TrsMenuHost back = new TrsMenuHost(parent);
		final Path gameDir = Mc.gameDir().toPath().toAbsolutePath();
		ClipsHost host = new ClipsHost() {
			@Override
			public void playClick() {
				back.playClick();
			}

			@Override
			public void closeScreen() {
				back.closeScreen();
			}

			@Override
			public Path gameDir() {
				return gameDir;
			}

			@Override
			public Path configDir() {
				return gameDir.resolve("config");
			}
		};
		return new TrsUiScreen(I18n.tr("clips.title"), new ClipsUi(host));
	}

	// --- Server-Info ---

	public static boolean serverInfoAvailable() {
		return Mc.world() != null;
	}

	public static GuiScreen serverInfo(final GuiScreen parent) {
		final TrsMenuHost back = new TrsMenuHost(parent);
		ServerInfoHost host = new ServerInfoHost() {
			@Override
			public void playClick() {
				back.playClick();
			}

			@Override
			public void closeScreen() {
				back.closeScreen();
			}

			@Override
			public Info info() {
				return serverInfo();
			}

			@Override
			public boolean copy(String text) {
				Mc.setClipboard(text);
				return true;
			}

			@Override
			public String userAgent() {
				return "TRS-Client";
			}
		};
		return new TrsUiScreen(I18n.tr("menus.serverInfo.title"), new ServerInfoUi(host, TrsOnline.current()));
	}

	static ServerInfoHost.Info serverInfo() {
		Minecraft mc = Mc.mc();
		ServerInfoHost.Info info = new ServerInfoHost.Info();
		info.singleplayer = mc.getIntegratedServer() != null;
		if (info.singleplayer) {
			info.name = Mc.levelName();
			info.lan = mc.getIntegratedServer().getPublic();
		} else {
			ServerData data = mc.getCurrentServerData();
			if (data != null) {
				info.name = data.serverName;
				info.address = data.serverIP;
				info.version = data.gameVersion;
			}
		}
		NetHandlerPlayClient net = Mc.connection();
		EntityPlayerSP player = Mc.player();
		if (net != null) {
			info.players = net.getPlayerInfoMap().size();
			if (player != null) {
				NetworkPlayerInfo self = net.getPlayerInfo(player.getUniqueID());
				if (self != null) info.ping = self.getResponseTime();
			}
		}
		String dim = Mc.dimensionId();
		info.dimension = dim == null || dim.isEmpty() ? null : dim;
		if (player != null) {
			info.position = String.format(Locale.ROOT, "%.1f / %.1f / %.1f", player.posX, player.posY, player.posZ);
		}
		return info;
	}

	/** Garderobe als Bildschirm oder null, solange es sie hier nicht gibt. */
	public static GuiScreen wardrobe(GuiScreen parent) {
		return null;
	}
}
