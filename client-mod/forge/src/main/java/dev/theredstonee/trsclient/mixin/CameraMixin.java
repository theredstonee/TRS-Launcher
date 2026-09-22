package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.camera.FreelookState;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Freelook: solange aktiv, nimmt die Kamera die Freelook-Winkel statt des Blicks der Spielfigur.
 * Die Kamera-Position (dritte Person) folgt automatisch, weil sie aus der Rotation berechnet wird.
 * Methode: Camera#setup bis 1.21.11, Camera#alignWithEntity ab 26.1.
 * Forge liefert MixinExtras erst ab 1.21.10 mit → überall schlichte @Redirects.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	//? if >=26.1 {
	/*@Redirect(method = "alignWithEntity",
	*///?} else
	@Redirect(method = "setup",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"), require = 1)
	private float trsclient$freelookYaw(Entity entity, float partialTick) {
		FreelookState f = TrsClient.get().pvp().freelook();
		return f.active() ? f.yaw() : entity.getViewYRot(partialTick);
	}

	//? if >=26.1 {
	/*@Redirect(method = "alignWithEntity",
	*///?} else
	@Redirect(method = "setup",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"), require = 1)
	private float trsclient$freelookPitch(Entity entity, float partialTick) {
		FreelookState f = TrsClient.get().pvp().freelook();
		return f.active() ? f.pitch() : entity.getViewXRot(partialTick);
	}
}
