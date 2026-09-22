package dev.theredstonee.trsclient;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge-Einstieg. Reine Client-Mod: auf einem dedizierten Server passiert nichts
 * (und es werden keine Client-Klassen geladen).
 */
@Mod(TrsClient.MOD_ID)
public final class TrsClientMod {
	public TrsClientMod() {
		if (FMLEnvironment.dist == Dist.CLIENT) {
			TrsClient.init();
		}
	}
}
