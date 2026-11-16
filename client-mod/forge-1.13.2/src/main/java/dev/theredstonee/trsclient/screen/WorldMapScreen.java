package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.WorldMapUi;
import dev.theredstonee.trsclient.core.waypoint.WaypointsUi;
import net.minecraft.client.Minecraft;

/** Vollbild-Weltkarte ({@link WorldMapUi} aus {@code core.map}) für Forge 1.13.2; hier nur die Anbindung. */
public final class WorldMapScreen extends TrsUiScreen {
	private WorldMapScreen(WorldMapUi ui) {
		super(ui);
	}

	/** Öffnet die Weltkarte (null, solange die Karte nicht läuft). */
	public static WorldMapScreen create() {
		MapEngine engine = MapEngine.get();
		if (engine == null) return null;
		WorldMapUi.Host host = new WorldMapUi.Host() {
			@Override
			public void closeScreen() {
				Minecraft.getInstance().displayGuiScreen(null);
			}

			@Override
			public void playClick() {
				new TrsMenuHost(null).playClick();
			}

			@Override
			public boolean isMapKey(int rawKey) {
				return TrsKeys.worldMap != null && rawKey == TrsKeys.worldMap.getKey().getKeyCode();
			}
		};
		return new WorldMapScreen(new WorldMapUi(engine, host));
	}

	public static TrsUiScreen createWaypoints() {
		MapEngine engine = MapEngine.get();
		if (engine == null) return null;
		return new TrsUiScreen(new WaypointsUi(engine, new WaypointsUi.Host() {
			@Override public void closeScreen() { Minecraft.getInstance().displayGuiScreen(null); }
			@Override public void playClick() { new TrsMenuHost(null).playClick(); }
			@Override public boolean isOpenKey(int rawKey) {
				return TrsKeys.waypointList != null && rawKey == TrsKeys.waypointList.getKey().getKeyCode();
			}
		}));
	}

	@Override
	protected boolean vanillaBackground() {
		// Die Karte deckt alles ab.
		return false;
	}
}
