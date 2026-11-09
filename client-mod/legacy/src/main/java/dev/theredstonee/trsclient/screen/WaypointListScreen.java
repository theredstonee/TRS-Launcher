package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.waypoint.WaypointsUi;
import net.minecraft.client.gui.GuiScreen;

/**
 * Wegpunkte im Spiel ({@link WaypointsUi} aus {@code core.waypoint}); hier nur die Anbindung an diese Version.
 * Liste und Dialog sehen in allen Versionen gleich aus.
 */
public final class WaypointListScreen extends TrsUiScreen {
	public WaypointListScreen(GuiScreen parent) {
		this(parent, false);
	}

	private WaypointListScreen(GuiScreen parent, boolean onlyDialog) {
		super(I18n.tr("waypoints.title"), ui(parent, onlyDialog));
	}

	/** Nur der Dialog „Neuer Wegpunkt“ am eigenen Standort (Taste im Spiel). */
	public static WaypointListScreen newHere(GuiScreen parent) {
		return new WaypointListScreen(parent, true);
	}

	private static WaypointsUi ui(final GuiScreen parent, boolean onlyDialog) {
		WaypointsUi.Host host = new WaypointsUi.Host() {
			@Override
			public void closeScreen() {
				Mc.setScreen(parent);
			}

			@Override
			public void playClick() {
				new TrsMenuHost(null).playClick();
			}

			@Override
			public boolean isOpenKey(int rawKey) {
				TrsClient client = TrsClient.get();
				return client != null && client.modules().waypointListKey.get().equals(new TrsMenuHost(null).keyNameOf(rawKey));
			}
		};
		return onlyDialog ? WaypointsUi.dialogOnly(MapEngine.get(), host) : new WaypointsUi(MapEngine.get(), host);
	}
}
