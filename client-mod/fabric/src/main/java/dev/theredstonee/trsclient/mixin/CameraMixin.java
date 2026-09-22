package dev.theredstonee.trsclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.camera.FreelookState;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Freelook: solange aktiv, nimmt die Kamera die Freelook-Winkel statt des Blicks der Spielfigur.
 * Die Kamera-Position (dritte Person) folgt automatisch, weil sie aus der Rotation berechnet wird.
 * Methode: Camera#setup bis 1.21.11, Camera#alignWithEntity ab 26.1.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	//? if >=26.1 {
	/*@ModifyExpressionValue(method = "alignWithEntity",
	*///?} else
	@ModifyExpressionValue(method = "setup",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"), require = 1)
	private float trsclient$freelookYaw(float yaw) {
		FreelookState f = TrsClient.get().pvp().freelook();
		return f.active() ? f.yaw() : yaw;
	}

	//? if >=26.1 {
	/*@ModifyExpressionValue(method = "alignWithEntity",
	*///?} else
	@ModifyExpressionValue(method = "setup",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"), require = 1)
	private float trsclient$freelookPitch(float pitch) {
		FreelookState f = TrsClient.get().pvp().freelook();
		return f.active() ? f.pitch() : pitch;
	}
}
