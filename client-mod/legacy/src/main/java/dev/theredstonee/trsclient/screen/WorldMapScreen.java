package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.WorldMapUi;
import dev.theredstonee.trsclient.ui.Gfx;

/**
 * Vollbild-Weltkarte ({@link WorldMapUi} aus {@code core.map}) für Forge 1.8.9–1.12.2; hier nur die Anbindung.
 * Zeichnet den ganzen Bildschirm selbst.
 */
public final class WorldMapScreen extends TrsUiScreen {
	private WorldMapScreen(WorldMapUi ui) {
		super(I18n.tr("map.title"), ui);
	}

	/** Öffnet die Weltkarte (null, solange die Karte nicht läuft). */
	public static WorldMapScreen create() {
		MapEngine engine = MapEngine.get();
		if (engine == null) return null;
		WorldMapUi.Host host = new WorldMapUi.Host() {
			@Override
			public void closeScreen() {
				Mc.setScreen(null);
			}

			@Override
			public void playClick() {
				new TrsMenuHost(null).playClick();
			}

			@Override
			public boolean isMapKey(int rawKey) {
				return TrsKeys.worldMap != null && rawKey != 0 && rawKey == TrsKeys.worldMap.getKeyCode();
			}
		};
		return new WorldMapScreen(new WorldMapUi(engine, host));
	}

	@Override
	protected boolean customBackground() {
		return true;
	}

	@Override
	protected void drawBackground(Gfx g, float partialTick) {
		// Die Karte deckt alles ab.
	}
}
