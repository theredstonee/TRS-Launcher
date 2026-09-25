package dev.theredstonee.trsclient;

import dev.theredstonee.trsclient.core.perf.FpsConfigMode;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Vor dem Laden aller Mods (noch keine Minecraft-Klasse): Einstellungsdateien der Optimierungs-Mods für den
 * gewählten Grafik-Modus anpassen – so lesen Sodium & Co. sie schon in diesem Start. Fehler bleiben folgenlos.
 */
public final class TrsPreLaunch implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		try {
			FpsConfigMode.load(FabricLoader.getInstance().getConfigDir()).applyModFiles();
		} catch (RuntimeException | LinkageError e) {
			// nie den Start gefährden
		}
	}
}
