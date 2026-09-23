package dev.theredstonee.trsclient.mixin;

// Nur ab 1.15 eingetragen (1.14 zeichnet den Umhang noch mit festen GL-Aufrufen – dort bleibt er starr).
//? if >=1.15 {
import com.mojang.blaze3d.vertex.PoseStack;
import dev.theredstonee.trsclient.core.cape.ClothSim;
import dev.theredstonee.trsclient.online.OnlineHooks;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if <1.21.2 {
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
//?}

/**
 * Umhang-Physik: statt des starren Vanilla-Umhangs das simulierte Stoff-Gitter zeichnen – für alle Umhänge
 * (Mojang, OptiFine, TRS), mit derselben Textur, die Vanilla nehmen würde. Nur wenn es für diesen Spieler eine
 * Simulation gibt (Modul an, nah genug, kein Elytra/Schwimmen); sonst läuft Vanilla unverändert.
 * Die Vanilla-Bedingungen (unsichtbar, Umhang-Teil aus, Elytra) werden hier genauso geprüft.
 * require = 0: ändert eine Version die Methode, bleibt der Vanilla-Umhang – nie ein Absturz.
 */
@Mixin(CapeLayer.class)
public abstract class CapeLayerMixin {
	//? if >=1.21.9 {
	/*@Shadow @Final private HumanoidModel<net.minecraft.client.renderer.entity.state.AvatarRenderState> model;

	@Shadow
	private boolean hasLayer(ItemStack stack, net.minecraft.client.resources.model.EquipmentClientInfo.LayerType layer) {
		throw new AssertionError();
	}

	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$cloth(PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, int light,
			net.minecraft.client.renderer.entity.state.AvatarRenderState state, float yRot, float xRot, CallbackInfo ci) {
		if (state.isInvisible || !state.showCape || state.skin.cape() == null) return;
		if (hasLayer(state.chestEquipment, net.minecraft.client.resources.model.EquipmentClientInfo.LayerType.WINGS)) return;
		ClothSim sim = OnlineHooks.sim(state.id);
		if (sim == null) return;
		model.setupAnim(state);
		boolean armor = hasLayer(state.chestEquipment, net.minecraft.client.resources.model.EquipmentClientInfo.LayerType.HUMANOID);
		OnlineHooks.render(pose, collector, light, state.skin.cape().texturePath(), sim, model.body, armor);
		ci.cancel();
	}
	*///?} elif >=1.21.4 {
	/*@Shadow @Final private HumanoidModel<net.minecraft.client.renderer.entity.state.PlayerRenderState> model;

	@Shadow
	private boolean hasLayer(ItemStack stack, net.minecraft.client.resources.model.EquipmentClientInfo.LayerType layer) {
		throw new AssertionError();
	}

	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/PlayerRenderState;FF)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$cloth(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, int light,
			net.minecraft.client.renderer.entity.state.PlayerRenderState state, float yRot, float xRot, CallbackInfo ci) {
		if (state.isInvisible || !state.showCape || state.skin.capeTexture() == null) return;
		if (hasLayer(state.chestEquipment, net.minecraft.client.resources.model.EquipmentClientInfo.LayerType.WINGS)) return;
		ClothSim sim = OnlineHooks.sim(state.id);
		if (sim == null) return;
		model.setupAnim(state);
		boolean armor = hasLayer(state.chestEquipment, net.minecraft.client.resources.model.EquipmentClientInfo.LayerType.HUMANOID);
		OnlineHooks.render(pose, buffers, light, state.skin.capeTexture(), sim, model.body, armor);
		ci.cancel();
	}
	*///?} elif >=1.21.2 {
	/*@Shadow @Final private HumanoidModel<net.minecraft.client.renderer.entity.state.PlayerRenderState> model;

	@Shadow
	private boolean hasLayer(ItemStack stack, net.minecraft.world.item.equipment.EquipmentModel.LayerType layer) {
		throw new AssertionError();
	}

	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/PlayerRenderState;FF)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$cloth(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, int light,
			net.minecraft.client.renderer.entity.state.PlayerRenderState state, float yRot, float xRot, CallbackInfo ci) {
		if (state.isInvisible || !state.showCape || state.skin.capeTexture() == null) return;
		if (hasLayer(state.chestItem, net.minecraft.world.item.equipment.EquipmentModel.LayerType.WINGS)) return;
		ClothSim sim = OnlineHooks.sim(state.id);
		if (sim == null) return;
		model.setupAnim(state);
		boolean armor = hasLayer(state.chestItem, net.minecraft.world.item.equipment.EquipmentModel.LayerType.HUMANOID);
		OnlineHooks.render(pose, buffers, light, state.skin.capeTexture(), sim, model.body, armor);
		ci.cancel();
	}
	*///?} else {
	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$cloth(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
			float limbSwing, float limbSwingAmount, float partial, float age, float headYaw, float headPitch, CallbackInfo ci) {
		if (player.isInvisible() || !OnlineHooks.hasCapeVisible(player)) return;
		ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
		if (chest.getItem() == Items.ELYTRA) return;
		ClothSim sim = OnlineHooks.sim(player.getId());
		if (sim == null) return;
		HumanoidModel<?> parent = (HumanoidModel<?>) ((RenderLayer<?, ?>) (Object) this).getParentModel();
		//? if >=1.20.2 {
		Object texture = player.getSkin().capeTexture();
		//?} else
		/*Object texture = player.getCloakTextureLocation();*/
		OnlineHooks.render(pose, buffers, light, texture, sim, parent.body, !chest.isEmpty());
		ci.cancel();
	}
	//?}
}
//?}
