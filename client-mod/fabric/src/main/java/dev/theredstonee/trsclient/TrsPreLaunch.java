package dev.theredstonee.trsclient;

import dev.theredstonee.trsclient.core.perf.FpsConfigMode;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Vor dem Laden aller Mods (noch keine Minecraft-Klasse): Einstellungsdateien der Optimierungs-Mods für den
 * gewählten Grafik-Modus anpassen – so lesen Sodium & Co. sie schon in diesem Start – und optionale Erweiterungen
 * (core.ext) früh starten. Fehler bleiben folgenlos.
 */
public final class TrsPreLaunch implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		try {
			FpsConfigMode.load(FabricLoader.getInstance().getConfigDir()).applyModFiles();
		} catch (RuntimeException | LinkageError e) {
			// nie den Start gefährden
		}
		try {
			final FabricLoader loader = FabricLoader.getInstance();
			String minecraft = loader.getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
			dev.theredstonee.trsclient.core.ext.TrsExtensions.preLaunch(new dev.theredstonee.trsclient.core.ext.ExtContext.Early(
					loader.getConfigDir().toFile(), id -> loader.isModLoaded(id), minecraft, "fabric",
					line -> org.apache.logging.log4j.LogManager.getLogger("TRS Client").info(line)));
		} catch (RuntimeException | LinkageError e) {
			// ohne Erweiterungen weiter
		}
	}
}
