package dev.theredstonee.trsclient.mixin;

// Nur ab 1.15 eingetragen (wie HatLayerMixin).
//? if >=1.15 {
import com.mojang.blaze3d.vertex.PoseStack;
import dev.theredstonee.trsclient.online.WingsHooks;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if <1.21.2 {
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
//?}

/**
 * TRS-Flügel (v2, {@code attach: "body"}): hängt sich wie die Kopf-Kosmetik an den Umhang-Layer (läuft für jeden
 * Spieler) und zeichnet zusätzlich in den Körper-Teil des bereits posierten Spielermodells – bricht nie ab.
 * require = 0: ändert eine Version die Methode, gibt es eben keine Flügel – nie einen Absturz.
 * priority 500: vor der Umhang-Physik (CapeLayerMixin bricht die Methode nach dem Stoff-Umhang ab).
 */
@Mixin(value = CapeLayer.class, priority = 500)
public abstract class WingsLayerMixin {
	private HumanoidModel<?> trsclient$wingsParent() {
		return (HumanoidModel<?>) ((RenderLayer<?, ?>) (Object) this).getParentModel();
	}

	//? if >=1.21.9 {
	/*@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
			at = @At("HEAD"), require = 0)
	private void trsclient$wings(PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, int light,
			net.minecraft.client.renderer.entity.state.AvatarRenderState state, float yRot, float xRot, CallbackInfo ci) {
		if (state.isInvisible) return;
		WingsHooks.render(pose, collector, light, state.id, trsclient$wingsParent().body);
	}
	*///?} elif >=1.21.2 {
	/*@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/PlayerRenderState;FF)V",
			at = @At("HEAD"), require = 0)
	private void trsclient$wings(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, int light,
			net.minecraft.client.renderer.entity.state.PlayerRenderState state, float yRot, float xRot, CallbackInfo ci) {
		if (state.isInvisible) return;
		WingsHooks.render(pose, buffers, light, dev.theredstonee.trsclient.online.HatHooks.player(state.id), trsclient$wingsParent().body);
	}
	*///?} else {
	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
			at = @At("HEAD"), require = 0)
	private void trsclient$wings(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
			float limbSwing, float limbSwingAmount, float partial, float age, float headYaw, float headPitch, CallbackInfo ci) {
		WingsHooks.render(pose, buffers, light, player, trsclient$wingsParent().body);
	}
	//?}
}
//?}
