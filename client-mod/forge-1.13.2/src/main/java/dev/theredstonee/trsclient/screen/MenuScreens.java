package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.core.ui.clips.ClipsHost;
import dev.theredstonee.trsclient.core.ui.clips.ClipsUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import java.nio.file.Path;

/**
 * Bildschirm „Clips &amp; Bilder“ in dieser Version (aus dem TRS-Menü). Freunde, Server-Info und der Menü-Stil
 * brauchen die TRS-Online-Funktionen bzw. Mixins und fehlen hier.
 */
public final class MenuScreens {
	private MenuScreens() {
	}

	public static GuiScreen clips(final GuiScreen parent) {
		final TrsMenuHost back = new TrsMenuHost(parent);
		final Path gameDir = Minecraft.getInstance().gameDir.toPath().toAbsolutePath();
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
		return new TrsUiScreen(new ClipsUi(host));
	}
}
