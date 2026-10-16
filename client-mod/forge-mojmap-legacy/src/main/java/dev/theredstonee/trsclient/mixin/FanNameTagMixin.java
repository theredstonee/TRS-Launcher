package dev.theredstonee.trsclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import dev.theredstonee.trsclient.online.FanHooks;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Fan-Symbol auf Namensschildern ab 26.2: Dort wird der Name nur noch als Zeichenfolge eingereicht (die Komponente geht
 * verloren) – deshalb das Symbol schon beim Einreichen ({@code SubmitNodeCollection#submitNameTag}) als eigene Geometrie
 * an die Lage des Schilds setzen. Davor nicht in der Mixin-Liste (leere Klasse).
 */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.renderer.SubmitNodeCollection.class)
public abstract class FanNameTagMixin {
	@Inject(method = "submitNameTag", at = @At("HEAD"), require = 0)
	private void trsclient$fanSymbol(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.world.phys.Vec3 attachment,
			int offset, net.minecraft.network.chat.Component name, boolean seeThrough, int light,
			net.minecraft.client.renderer.state.level.CameraRenderState camera, CallbackInfo ci) {
		FanHooks.submitNameTag((net.minecraft.client.renderer.OrderedSubmitNodeCollector) (Object) this, poseStack, attachment,
				offset, name, seeThrough, light, camera);
	}
}
*///?} else {
@Mixin(net.minecraft.client.Minecraft.class)
public abstract class FanNameTagMixin {
}
//?}
