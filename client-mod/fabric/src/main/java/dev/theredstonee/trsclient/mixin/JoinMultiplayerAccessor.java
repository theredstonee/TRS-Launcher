package dev.theredstonee.trsclient.mixin;

import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Serverliste der Mehrspieler-Übersicht (Anheften, Auswahl). */
@Mixin(JoinMultiplayerScreen.class)
public interface JoinMultiplayerAccessor {
	@Accessor("serverSelectionList")
	ServerSelectionList trsclient$serverList();
}
