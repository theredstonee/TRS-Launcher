package dev.theredstonee.trsclient.compat;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

/** Loader-Zugriffe (Forge/FML) an einer Stelle – das Gegenstück zu FabricLoader im Fabric-Baum. */
public final class Platform {
	private Platform() {
	}

	public static Path configDir() {
		return FMLPaths.CONFIGDIR.get();
	}

	// ModList ist ab Forge 26.1 rein statisch (vorher Singleton über ModList.get()).
	public static boolean isModLoaded(String modId) {
		//? if >=26.1 {
		/*return ModList.isLoaded(modId);
		*///?} else {
		ModList list = ModList.get();
		return list != null && list.isLoaded(modId);
		//?}
	}

	/** Version einer geladenen Mod ("minecraft" = Spielversion), sonst "?". */
	public static String modVersion(String modId) {
		//? if >=26.1 {
		/*return ModList.getModContainerById(modId).map(c -> c.getModInfo().getVersion().toString()).orElse("?");
		*///?} else {
		ModList list = ModList.get();
		if (list == null) return "?";
		return list.getModContainerById(modId).map(c -> c.getModInfo().getVersion().toString()).orElse("?");
		//?}
	}
}
