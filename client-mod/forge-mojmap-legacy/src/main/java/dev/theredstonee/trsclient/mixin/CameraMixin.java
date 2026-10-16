package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.camera.FreelookState;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import dev.theredstonee.trsclient.cinematic.CinematicHooks;
import dev.theredstonee.trsclient.core.cinematic.CameraPose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Freelook: solange aktiv, nimmt die Kamera die Freelook-Winkel statt des Blicks der Spielfigur.
 * Die Kamera-Position (dritte Person) folgt automatisch, weil Camera#setup sie aus der Rotation berechnet.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Redirect(method = "setup",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"), require = 1)
	private float trsclient$freelookYaw(Entity entity, float partialTick) {
		FreelookState f = TrsClient.get().pvp().freelook();
		return f.active() ? f.yaw() : entity.getViewYRot(partialTick);
	}

	@Redirect(method = "setup",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"), require = 1)
	private float trsclient$freelookPitch(Entity entity, float partialTick) {
		FreelookState f = TrsClient.get().pvp().freelook();
		return f.active() ? f.pitch() : entity.getViewXRot(partialTick);
	}

	/**
	 * Kamera-Fahrten: am Ende von Camera#setup Position und Blick der Fahrt setzen und die Kamera als „losgelöst“ markieren
	 * (die eigene Figur wird gezeichnet, keine Hand in der ersten Person).
	 */
	@Inject(method = "setup", at = @At("TAIL"), require = 0)
	private void trsclient$cinematic(CallbackInfo ci) {
		CameraPose pose = CinematicHooks.frame();
		if (pose == null) return;
		setPosition(pose.x, pose.y, pose.z);
		setRotation(pose.yaw, pose.pitch);
		detached = true;
	}
}
