package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.ui.title.TitleHost;
import dev.theredstonee.trsclient.core.ui.title.TitleUi;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
//? if >=1.21 {
import net.minecraft.client.gui.screens.options.OptionsScreen;
//?} else
/*import net.minecraft.client.gui.screens.OptionsScreen;*/

/**
 * TRS-Startbildschirm (ersetzt den Vanilla-Titelbildschirm, abschaltbar im Modul "Startbildschirm").
 * Aussehen und Bedienung stehen versionsunabhängig in {@link TitleUi}; hier nur die Ziele der Knöpfe.
 */
public final class TrsTitleScreen extends TrsUiScreen {
	/** NeoForge-Modliste: bis 26.1.x in client.gui, ab 26.2 in client.gui.modlist. */
	private static final String[] MOD_LIST_SCREENS = {
			"net.neoforged.neoforge.client.gui.modlist.ModListScreen",
			"net.neoforged.neoforge.client.gui.ModListScreen"};

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
	public TitleUi title() {
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
		private final String versionLine = "Minecraft " + TrsClient.modVersion("minecraft") + " · NeoForge "
				+ TrsClient.modVersion("neoforge") + " · TRS Client " + TrsClient.modVersion(TrsClient.MOD_ID);
		TrsTitleScreen screen;

		@Override
		public void singleplayer() {
			Mc.setScreen(new SelectWorldScreen(screen));
		}

		@Override
		public void multiplayer() {
			Mc.setScreen(new JoinMultiplayerScreen(screen));
		}

		@Override
		public void options() {
			//? if >=26.1 && <26.3 {
			/*Mc.setScreen(new OptionsScreen(screen, Mc.mc().options, false));
			*///?} else
			Mc.setScreen(new OptionsScreen(screen, Mc.mc().options));
		}

		@Override
		public void trsMenu() {
			Mc.setScreen(new TrsMenuScreen(screen));
		}

		/** Einführung beim ersten Start (über dem Startbildschirm). */
		@Override
		public boolean openIntro() {
			new TrsMenuHost(screen).openIntro();
			return true;
		}

		@Override
		public boolean openAccounts() {
			if (!AccountsScreen.available()) return false;
			Mc.setScreen(AccountsScreen.create(screen));
			return true;
		}

		/** Die NeoForge-Modliste gibt es immer. */
		@Override
		public boolean hasMods() {
			return true;
		}

		/** NeoForge-Modliste – per Reflection, weil die Klasse in 26.2 umgezogen ist. */
		@Override
		public void mods() {
			for (String name : MOD_LIST_SCREENS) {
				try {
					Class<?> type = Class.forName(name);
					Mc.setScreen((Screen) type.getConstructor(Screen.class).newInstance(screen));
					return;
				} catch (ClassNotFoundException e) {
					// nächsten Kandidaten versuchen
				} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
					TrsClient.LOGGER.warn("Modliste konnte nicht geöffnet werden", e);
					return;
				}
			}
			TrsClient.LOGGER.warn("Keine NeoForge-Modliste gefunden");
		}

		@Override
		public void quit() {
			Mc.mc().stop();
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

		@Override
		public void narrate(String text) {
			try {
				Mc.narrate(text);
			} catch (RuntimeException | LinkageError e) {
				// Kein Erzähler auf diesem System – dann eben still.
			}
		}

		@Override
		public boolean animated() {
			return TrsClient.get().modules().titleAnimated.get();
		}

		@Override
		public boolean simpleAnimation() {
			return false;
		}

		/** Eigener Skin/Umhang für die Figur auf der Drehscheibe (kommt aus den TRS-Online-Funktionen). */
		@Override
		public dev.theredstonee.trsclient.core.skin.PlayerLook look() {
			dev.theredstonee.trsclient.core.online.OnlineFeatures<Object> f = dev.theredstonee.trsclient.online.OnlineHooks.features();
			return f == null ? null : f.look();
		}

		@Override
		public String playerName() {
			return Mc.mc().getUser().getName();
		}
	}
}
