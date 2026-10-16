package dev.theredstonee.trsclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.cinematic.CinematicHooks;
import dev.theredstonee.trsclient.core.camera.FreelookState;
import dev.theredstonee.trsclient.core.cinematic.CameraPose;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freelook: solange aktiv, nimmt die Kamera die Freelook-Winkel statt des Blicks der Spielfigur.
 * Die Kamera-Position (dritte Person) folgt automatisch, weil sie aus der Rotation berechnet wird.
 * Kamera-Fahrten: am Ende der Ausrichtung Position und Blick der Fahrt setzen und die Kamera als „losgelöst“
 * markieren (die eigene Figur wird gezeichnet, keine Hand in der ersten Person).
 * Methode: Camera#setup bis 1.21.11, Camera#alignWithEntity ab 26.1.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

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

	//? if >=26.1 {
	/*@Inject(method = "alignWithEntity", at = @At("TAIL"), require = 0)
	*///?} else
	@Inject(method = "setup", at = @At("TAIL"), require = 0)
	private void trsclient$cinematic(CallbackInfo ci) {
		CameraPose pose = CinematicHooks.frame();
		if (pose == null) return;
		setPosition(pose.x, pose.y, pose.z);
		setRotation(pose.yaw, pose.pitch);
		detached = true;
	}
}
