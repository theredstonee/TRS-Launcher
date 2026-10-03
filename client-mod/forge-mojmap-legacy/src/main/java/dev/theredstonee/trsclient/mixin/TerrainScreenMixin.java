package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.core.connect.FastSwitch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=1.18.2 && <1.19.3 {
/*import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
*///?}

/**
 * Schneller Serverwechsel, 1.18.2–1.19.2: „Lade Gelände …“ bleibt dort wegen eines Vanilla-Fehlers (MC-249059)
 * immer mindestens 2 s offen – auch wenn der eigene Chunk längst da ist. Hier gilt die Regel von 1.19.3 (ein Tick nach
 * den Lade-Paketen prüfen, dann schließen, sobald der Chunk fertig ist); die Bedingung selbst bleibt Vanilla.
 * Nur in der Mixin-Liste für 1.18.2–1.19.2.
 */
//? if <1.21.9 {
@Mixin(net.minecraft.client.gui.screens.ReceivingLevelScreen.class)
//?} else
/*@Mixin(net.minecraft.client.gui.screens.LevelLoadingScreen.class)*/
public abstract class TerrainScreenMixin {
	//? if >=1.18.2 && <1.19.3 {
	/*@Shadow
	private boolean loadingPacketsReceived;
	@Shadow
	private boolean oneTickSkipped;
	@Unique
	private boolean trsclient$seenPackets;

	@Inject(method = "tick", at = @At("HEAD"), require = 0)
	private void trsclient$noTwoSecondWait(CallbackInfo ci) {
		if (!loadingPacketsReceived || !FastSwitch.fixTerrainWait()) return;
		if (trsclient$seenPackets) oneTickSkipped = true;
		trsclient$seenPackets = true;
	}
	*///?}
}
