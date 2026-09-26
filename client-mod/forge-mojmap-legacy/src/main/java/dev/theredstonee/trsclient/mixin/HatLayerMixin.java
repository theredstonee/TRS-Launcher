package dev.theredstonee.trsclient.mixin;

// Nur ab 1.15 eingetragen (wie CapeLayerMixin).
//? if >=1.15 {
import com.mojang.blaze3d.vertex.PoseStack;
import dev.theredstonee.trsclient.online.HatHooks;
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
 * TRS-Kopf-Kosmetik (Quietscheente): hängt sich an den Umhang-Layer, weil der für jeden Spieler läuft – egal ob
 * mit Umhang. Zeichnet zusätzlich (bricht nie ab) in den Kopf-Teil des bereits posierten Spielermodells.
 * require = 0: ändert eine Version die Methode, gibt es eben keine Ente – nie einen Absturz.
 * priority 500: vor der Umhang-Physik (CapeLayerMixin bricht die Methode nach dem Stoff-Umhang ab).
 */
@Mixin(value = CapeLayer.class, priority = 500)
public abstract class HatLayerMixin {
	private HumanoidModel<?> trsclient$parent() {
		return (HumanoidModel<?>) ((RenderLayer<?, ?>) (Object) this).getParentModel();
	}

	//? if >=1.21.9 {
	/*@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
			at = @At("HEAD"), require = 0)
	private void trsclient$hat(PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, int light,
			net.minecraft.client.renderer.entity.state.AvatarRenderState state, float yRot, float xRot, CallbackInfo ci) {
		if (state.isInvisible) return;
		HatHooks.render(pose, collector, light, state.id, trsclient$parent().head);
	}
	*///?} elif >=1.21.2 {
	/*@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/PlayerRenderState;FF)V",
			at = @At("HEAD"), require = 0)
	private void trsclient$hat(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, int light,
			net.minecraft.client.renderer.entity.state.PlayerRenderState state, float yRot, float xRot, CallbackInfo ci) {
		if (state.isInvisible) return;
		HatHooks.render(pose, buffers, light, HatHooks.player(state.id), trsclient$parent().head);
	}
	*///?} else {
	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
			at = @At("HEAD"), require = 0)
	private void trsclient$hat(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
			float limbSwing, float limbSwingAmount, float partial, float age, float headYaw, float headPitch, CallbackInfo ci) {
		HatHooks.render(pose, buffers, light, player, trsclient$parent().head);
	}
	//?}
}
//?}
