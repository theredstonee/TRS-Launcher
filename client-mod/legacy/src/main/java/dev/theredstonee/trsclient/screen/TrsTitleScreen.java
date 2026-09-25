package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.ui.title.TitleHost;
import dev.theredstonee.trsclient.core.ui.title.TitleUi;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraftforge.fml.client.GuiModList;

/**
 * TRS-Startbildschirm (ersetzt den Vanilla-Titelbildschirm, abschaltbar im Modul "Startbildschirm").
 * Aussehen und Bedienung stehen versionsunabhängig in {@link TitleUi}; hier nur die Ziele der Knöpfe.
 * Diese Versionen zeichnen jedes Rechteck einzeln – daher die sparsame Hintergrund-Animation.
 */
public final class TrsTitleScreen extends TrsUiScreen {
	private final TitleUi title;

	public TrsTitleScreen() {
		this(new Host());
	}

	private TrsTitleScreen(Host host) {
		super("TRS Client", new TitleUi(host));
		host.screen = this;
		this.title = (TitleUi) ui();
	}

	/** Die Oberfläche (Selbsttest: Auswahl, Messung). */
	public TitleUi titleUi() {
		return title;
	}

	@Override
	protected boolean customBackground() {
		return true;
	}

	@Override
	protected void drawBackground(Gfx g, float partialTick) {
		// Den Hintergrund zeichnet TitleUi (Redstone-Schaltung).
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	/** Ziele der Knöpfe. */
	private static final class Host implements TitleHost {
		private final String versionLine = "Minecraft " + Mc.version() + " · Forge · TRS Client " + TrsClient.get().version();
		TrsTitleScreen screen;

		@Override
		public void singleplayer() {
			Mc.setScreen(Mc.worldSelectScreen(screen));
		}

		@Override
		public void multiplayer() {
			Mc.setScreen(new GuiMultiplayer(screen));
		}

		@Override
		public void options() {
			Mc.setScreen(new GuiOptions(screen, Mc.mc().gameSettings));
		}

		@Override
		public void trsMenu() {
			Mc.setScreen(new TrsMenuScreen(screen));
		}

		@Override
		public boolean openAccounts() {
			if (!AccountsScreen.available()) return false;
			Mc.setScreen(AccountsScreen.create(screen));
			return true;
		}

		/** Forge bringt immer eine Mod-Liste mit. */
		@Override
		public boolean openFriends() {
			if (!MenuScreens.friendsAvailable()) return false;
			Mc.setScreen(MenuScreens.friends(screen));
			return true;
		}

		@Override
		public boolean openClips() {
			Mc.setScreen(MenuScreens.clips(screen));
			return true;
		}

		@Override
		public boolean hasMods() {
			return true;
		}

		@Override
		public void mods() {
			Mc.setScreen(new GuiModList(screen));
		}

		@Override
		public void quit() {
			Mc.mc().shutdown();
		}

		@Override
		public void classicTitle() {
			TrsClient.get().openVanillaTitle();
		}

		@Override
		public String versionLine() {
			return versionLine;
		}

		@Override
		public void playClick() {
			screen.clickSound();
		}

		/** Den Erzähler gibt es erst ab 1.12 – hier bleibt es still. */
		@Override
		public void narrate(String text) {
		}

		@Override
		public boolean animated() {
			return TrsClient.get().modules().titleAnimated.get();
		}

		@Override
		public boolean simpleAnimation() {
			return true;
		}

		/** Eigener Skin/Umhang für die Figur auf der Drehscheibe (kommt aus den TRS-Online-Funktionen). */
		@Override
		public dev.theredstonee.trsclient.core.skin.PlayerLook look() {
			dev.theredstonee.trsclient.core.online.OnlineFeatures<Object> f = dev.theredstonee.trsclient.online.LegacyOnline.features();
			return f == null ? null : f.look();
		}

		@Override
		public String playerName() {
			return Mc.mc().getSession().getUsername();
		}
	}
}
