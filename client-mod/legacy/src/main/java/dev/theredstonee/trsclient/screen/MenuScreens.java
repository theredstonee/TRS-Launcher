package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.ui.clips.ClipsHost;
import dev.theredstonee.trsclient.core.ui.clips.ClipsUi;
import dev.theredstonee.trsclient.core.social.SocialOverlay;
import dev.theredstonee.trsclient.core.ui.UiScreen;
import dev.theredstonee.trsclient.core.ui.social.QuickReplyUi;
import dev.theredstonee.trsclient.core.ui.social.SocialHost;
import dev.theredstonee.trsclient.core.ui.social.SocialUi;
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
 * Einstiege in die TRS-Bildschirme Sozial (Chat + Freunde), Clips &amp; Bilder und Server-Info unter Minecraft 1.8.9–1.12.2.
 * Inhalt und Bedienung stehen versionsunabhängig in {@code core.ui}.
 */
public final class MenuScreens {
	private MenuScreens() {
	}

	// --- Sozial (Chat + Freunde) ---

	public static boolean friendsAvailable() {
		return TrsOnline.current() != null;
	}

	/** Sozial-Bildschirm (Reiter Chat / Freunde) – ersetzt den früheren Freunde-Bildschirm. */
	public static GuiScreen friends(final GuiScreen parent) {
		return social(parent);
	}

	public static GuiScreen social(final GuiScreen parent) {
		return new TrsUiScreen(I18n.tr("social.title"), new SocialUi(host(parent), TrsOnline.current()));
	}

	/** Bildschirm zur Schnelltaste (Antwort/Beitreten → kleine Einblendung, Anfragen → Sozial-Bildschirm). */
	public static GuiScreen socialAction(SocialOverlay.QuickAction action, GuiScreen parent) {
		UiScreen ui = SocialUi.forAction(host(parent), TrsOnline.current(), action);
		String title = ui instanceof QuickReplyUi ? I18n.tr("social.quickReply.title") : I18n.tr("social.title");
		return new TrsUiScreen(title, ui);
	}

	static SocialHost host(final GuiScreen parent) {
		final TrsMenuHost back = new TrsMenuHost(parent);
		return new SocialHost() {
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

			@Override
			public Path gameDir() {
				return Mc.gameDir().toPath().toAbsolutePath();
			}

			@Override
			public String currentServer() {
				return Mc.mc().getIntegratedServer() != null ? null : Mc.serverAddress();
			}

			@Override
			public boolean inWorld() {
				return Mc.world() != null;
			}

			@Override
			public void joinServer(String address, String label) {
				Mc.leaveWorld();
				Mc.connect(address, label);
			}

			@Override
			public void copy(String text) {
				Mc.setClipboard(text);
			}

			@Override
			public String paste() {
				return Mc.clipboard();
			}
		};
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

	/** Garderobe (Skins, Outfits, Umhänge, Emotes) als Bildschirm oder null, wo es sie nicht gibt. */
	public static GuiScreen wardrobe(GuiScreen parent) {
		return WardrobeScreen.available() ? WardrobeScreen.create(parent) : null;
	}
}
