package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.ui.menu.ModMenu;
import net.minecraft.client.gui.screens.Screen;

/**
 * Das TRS-Menü (Rechte Umschalttaste). Der Inhalt – Kacheln, Suche, Kategorien, Einstellungen,
 * Profile – steht versionsunabhängig in {@link ModMenu}.
 */
public final class TrsMenuScreen extends TrsUiScreen {
	private final ModMenu menu;

	public TrsMenuScreen(Screen parent) {
		this(new ModMenu(new TrsMenuHost(parent)));
	}

	private TrsMenuScreen(ModMenu menu) {
		super("TRS Client", menu);
		this.menu = menu;
	}

	/** Öffnet das Menü direkt bei einem Modul (z. B. aus dem Autotest). */
	public TrsMenuScreen select(Module module) {
		menu.select(module);
		return this;
	}

	/** Öffnet das Menü direkt bei den HUD-Profilen. */
	public TrsMenuScreen showProfiles() {
		menu.showProfiles();
		return this;
	}
}
