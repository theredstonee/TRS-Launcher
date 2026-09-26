package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.ui.clips.ClipsHost;
import dev.theredstonee.trsclient.core.ui.clips.ClipsUi;
import dev.theredstonee.trsclient.core.social.SocialOverlay;
import dev.theredstonee.trsclient.core.ui.UiScreen;
import dev.theredstonee.trsclient.core.ui.social.SocialHost;
import dev.theredstonee.trsclient.core.ui.social.SocialUi;
import dev.theredstonee.trsclient.social.SocialHooks;
import dev.theredstonee.trsclient.core.ui.menus.ServerInfoHost;
import dev.theredstonee.trsclient.core.ui.menus.ServerInfoUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Einstiege in die TRS-Bildschirme Sozial (Chat + Freunde), Clips &amp; Bilder und Server-Info (Titelbildschirm, TRS-Menü,
 * Pausenmenü). Inhalt und Bedienung stehen versionsunabhängig in {@code core.ui}; hier nur die Anbindung an
 * Minecraft. Dieselbe Datei in allen Mojmap-Bäumen (Fabric, NeoForge, Forge, Forge-Mojmap-Legacy).
 */
public final class MenuScreens {
	private MenuScreens() {
	}

	private static void click(Screen parent) {
		new TrsMenuHost(parent).playClick();
	}

	// --- Freunde ---

	/** Gibt es Freunde im Spiel (TRS-Online-Funktionen gestartet)? */
	public static boolean friendsAvailable() {
		return TrsOnline.current() != null;
	}

	/** Freunde = Sozial-Bildschirm (Reiter Chat / Freunde). */
	public static Screen friends(final Screen parent) {
		return social(parent);
	}

	/** Sozial-Bildschirm: Chat mit Freunden und Gruppen, Freunde, Anfragen, Blockierte. */
	public static Screen social(final Screen parent) {
		SocialHost host = SocialHooks.host(parent);
		return new TrsUiScreen(I18n.tr("social.title"), new SocialUi(host, TrsOnline.current()));
	}

	/** Welt-Hosting: Einstellungen bzw. Verwaltung der offenen Welt (Pausemenü). */
	public static Screen hosting(final Screen parent) {
		SocialHost host = SocialHooks.host(parent);
		return new TrsUiScreen(I18n.tr("hosting.title"), new dev.theredstonee.trsclient.core.ui.hosting.HostingUi(host));
	}

	/** Welt-Hosting: „Mit Code beitreten“ + Welten der Freunde (Mehrspieler-Menü). */
	public static Screen join(final Screen parent) {
		SocialHost host = SocialHooks.host(parent);
		return new TrsUiScreen(I18n.tr("hosting.join.title"), new dev.theredstonee.trsclient.core.ui.hosting.JoinUi(host));
	}

	/** Bildschirm zur Schnelltaste (Schnellantwort/Beitreten bzw. Anfragen). */
	public static Screen socialAction(SocialOverlay.QuickAction action, final Screen parent) {
		SocialHost host = SocialHooks.host(parent);
		UiScreen ui = SocialUi.forAction(host, TrsOnline.current(), action);
		String title = ui instanceof SocialUi ? "social.title"
				: ui instanceof dev.theredstonee.trsclient.core.ui.hosting.HostingUi ? "hosting.title"
				: ui instanceof dev.theredstonee.trsclient.core.ui.hosting.JoinUi ? "hosting.join.title" : "social.quickReply.title";
		return new TrsUiScreen(I18n.tr(title), ui);
	}

	// --- Clips & Bilder ---

	public static Screen clips(final Screen parent) {
		final Path gameDir = Mc.mc().gameDirectory.toPath().toAbsolutePath();
		ClipsHost host = new ClipsHost() {
			@Override
			public void playClick() {
				click(parent);
			}

			@Override
			public void closeScreen() {
				Mc.setScreen(parent);
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

	/** Nur in einer Welt. */
	public static boolean serverInfoAvailable() {
		return Mc.mc().level != null;
	}

	public static Screen serverInfo(final Screen parent) {
		ServerInfoHost host = new ServerInfoHost() {
			@Override
			public void playClick() {
				click(parent);
			}

			@Override
			public void closeScreen() {
				Mc.setScreen(parent);
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

	/** Momentaufnahme der Verbindung (jedes Bild; billig). */
	static ServerInfoHost.Info serverInfo() {
		Minecraft mc = Mc.mc();
		ServerInfoHost.Info info = new ServerInfoHost.Info();
		info.singleplayer = mc.getSingleplayerServer() != null;
		if (info.singleplayer) {
			info.name = Mc.levelName();
			info.lan = mc.getSingleplayerServer().isPublished();
		} else {
			ServerData data = mc.getCurrentServer();
			if (data != null) {
				info.name = data.name;
				info.address = data.ip;
				//? if >=1.16 {
				info.version = data.version == null ? null : data.version.getString();
				//?} else
				/*info.version = data.version;*/
			}
			info.brand = brand(mc);
		}
		if (mc.getConnection() != null) {
			info.players = mc.getConnection().getOnlinePlayers().size();
			if (mc.player != null) {
				PlayerInfo self = mc.getConnection().getPlayerInfo(mc.player.getUUID());
				if (self != null) info.ping = self.getLatency();
			}
		}
		String dim = Mc.dimensionId();
		info.dimension = dim == null || dim.isEmpty() ? null : dim;
		if (mc.player != null) {
			//? if >=1.15 {
			info.position = String.format(Locale.ROOT, "%.1f / %.1f / %.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ());
			//?} else
			/*info.position = String.format(Locale.ROOT, "%.1f / %.1f / %.1f", mc.player.x, mc.player.y, mc.player.z);*/
		}
		return info;
	}

	private static String brand(Minecraft mc) {
		try {
			//? if >=1.20.2 {
			/*return mc.getConnection() == null ? null : mc.getConnection().serverBrand();
			*///?} else
			return mc.player == null ? null : mc.player.getServerBrand();
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	// --- Garderobe (Skins, Outfits, Umhänge, Emotes) ---

	/** Garderobe als Bildschirm oder null, solange es sie in dieser Version nicht gibt. */
	public static Screen wardrobe(Screen parent) {
		return WardrobeScreen.available() ? WardrobeScreen.create(parent) : null;
	}
}
