package dev.theredstonee.trsclient;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge-Einstieg (mods.toml: modId "trsclient"). Der TRS Client ist ein reiner Client-Mod:
 * auf einem dedizierten Server passiert nichts (und keine Client-Klasse wird geladen).
 */
@Mod(TrsClient.MOD_ID)
public final class TrsClientMod {
	public TrsClientMod() {
		DistExecutor.runWhenOn(Dist.CLIENT, () -> TrsClient::bootstrap);
	}
}
