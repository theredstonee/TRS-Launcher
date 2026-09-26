package dev.theredstonee.trsclient.mixin;

import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
//? if <1.21.9 {
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
//?}

/**
 * Welt-Hosting: Spielergrenze des integrierten Servers (Vanilla-LAN: 8) auf die Grenze der Welt (≤ 10) heben – bis
 * 1.21.8 steht sie im Feld {@code PlayerList#maxPlayers}. Ab 1.21.9 leer und nicht in der Mixin-Liste (dort
 * {@link HostingPlayerListMixin}).
 */
@Mixin(PlayerList.class)
public interface HostingPlayerListAccessor {
	//? if <1.21.9 {
	@Accessor("maxPlayers")
	int trsclient$maxPlayers();

	@Mutable
	@Accessor("maxPlayers")
	void trsclient$setMaxPlayers(int value);
	//?}
}
