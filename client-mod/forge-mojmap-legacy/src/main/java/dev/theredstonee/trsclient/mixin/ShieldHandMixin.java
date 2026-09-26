package dev.theredstonee.trsclient.mixin;

// Schild-Position: dünner Haken am Hand-Renderer der 1. Person (Logik in core.shield, Brücke render/ShieldHooks).
// Direkt vor dem Zeichnen des Gegenstands (renderItem) wird bei einem Schild die Haltung auf den Arm-Raum angewendet
// und Minecraft eine Kopie des Stapels gegeben (so bleibt es beim normalen Modell – die Block-Haltung fährt
// ShieldPosition selbst weich an). Bis 1.21.8 bekommt das Schild dabei ggf. einen Puffer mit Deckkraft, ab 1.21.9
// gilt die Deckkraft für alles, was bis zum Ende des Aufrufs übergeben wird (ShieldSubmitMixin).
// Die 3. Person läuft nicht durch diese Methode und bleibt unverändert.
// Versionen: 1.14 GL-Matrix; 1.15 – 1.19.3 TransformType; 1.19.4 – 1.21.4 ItemDisplayContext + boolean;
// 1.21.5 – 1.21.8 ohne boolean; 1.21.9 – 26.1 SubmitNodeCollector; 26.2 heißt die Methode submitArmWithItem;
// 26.3 eigener Renderer FirstPersonHandsAndItemsRenderer (Modell kommt aus FirstPersonHandsAndItems, siehe
// ShieldModelMixin).
//? if >=26.3 {
/*import dev.theredstonee.trsclient.render.ShieldHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class ShieldHandMixin {
	@Inject(method = "submitArmWithItem(Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"),
			require = 1)
	private void trsclient$shieldPose(net.minecraft.client.renderer.state.level.PlayerRenderState playerState,
			net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState state, float partialTicks, float xRot,
			net.minecraft.world.InteractionHand hand, float attack, net.minecraft.world.item.ItemStack stack, float inverseArmHeight,
			com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, int light,
			CallbackInfo ci) {
		ShieldHooks.before(Minecraft.getInstance().player, stack, hand, pose);
	}

	@Inject(method = "submitArmWithItem(Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V",
					shift = At.Shift.AFTER),
			require = 1)
	private void trsclient$shieldDone(CallbackInfo ci) {
		ShieldHooks.after();
	}
}
*///?} elif >=26.2 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import dev.theredstonee.trsclient.render.ShieldHooks;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public abstract class ShieldHandMixin {
	@ModifyArg(method = "submitArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"),
			index = 1, require = 1)
	private ItemStack trsclient$shieldPose(LivingEntity entity, ItemStack stack, ItemDisplayContext context, PoseStack pose,
			SubmitNodeCollector collector, int light) {
		return ShieldHooks.before(entity, stack, context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND, pose);
	}

	@Inject(method = "submitArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
					shift = At.Shift.AFTER),
			require = 1)
	private void trsclient$shieldDone(CallbackInfo ci) {
		ShieldHooks.after();
	}
}
*///?} elif >=1.21.9 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import dev.theredstonee.trsclient.render.ShieldHooks;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public abstract class ShieldHandMixin {
	@ModifyArg(method = "renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"),
			index = 1, require = 1)
	private ItemStack trsclient$shieldPose(LivingEntity entity, ItemStack stack, ItemDisplayContext context, PoseStack pose,
			SubmitNodeCollector collector, int light) {
		return ShieldHooks.before(entity, stack, context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND, pose);
	}

	@Inject(method = "renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
					shift = At.Shift.AFTER),
			require = 1)
	private void trsclient$shieldDone(CallbackInfo ci) {
		ShieldHooks.after();
	}
}
*///?} elif >=1.21.5 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import dev.theredstonee.trsclient.render.ShieldHooks;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ItemInHandRenderer.class)
public abstract class ShieldHandMixin {
	@ModifyArg(method = "renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"),
			index = 1, require = 1)
	private ItemStack trsclient$shieldPose(LivingEntity entity, ItemStack stack, ItemDisplayContext context, PoseStack pose,
			MultiBufferSource buffers, int light) {
		return ShieldHooks.before(entity, stack, context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND, pose);
	}

	@ModifyArg(method = "renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"),
			index = 4, require = 1)
	private MultiBufferSource trsclient$shieldAlpha(LivingEntity entity, ItemStack stack, ItemDisplayContext context, PoseStack pose,
			MultiBufferSource buffers, int light) {
		return ShieldHooks.buffers(entity, stack, context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND, buffers);
	}
}
*///?} elif >=1.19.4 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import dev.theredstonee.trsclient.render.ShieldHooks;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ItemInHandRenderer.class)
public abstract class ShieldHandMixin {
	@ModifyArg(method = "renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"),
			index = 1, require = 1)
	private ItemStack trsclient$shieldPose(LivingEntity entity, ItemStack stack, ItemDisplayContext context, boolean leftHand,
			PoseStack pose, MultiBufferSource buffers, int light) {
		return ShieldHooks.before(entity, stack, leftHand, pose);
	}

	@ModifyArg(method = "renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"),
			index = 5, require = 1)
	private MultiBufferSource trsclient$shieldAlpha(LivingEntity entity, ItemStack stack, ItemDisplayContext context, boolean leftHand,
			PoseStack pose, MultiBufferSource buffers, int light) {
		return ShieldHooks.buffers(entity, stack, leftHand, buffers);
	}
}
*///?} elif >=1.15 {
import com.mojang.blaze3d.vertex.PoseStack;
import dev.theredstonee.trsclient.render.ShieldHooks;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ItemInHandRenderer.class)
public abstract class ShieldHandMixin {
	@ModifyArg(method = "renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/renderer/block/model/ItemTransforms$TransformType;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"),
			index = 1, require = 1)
	private ItemStack trsclient$shieldPose(LivingEntity entity, ItemStack stack, ItemTransforms.TransformType type, boolean leftHand,
			PoseStack pose, MultiBufferSource buffers, int light) {
		return ShieldHooks.before(entity, stack, leftHand, pose);
	}

	@ModifyArg(method = "renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/renderer/block/model/ItemTransforms$TransformType;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"),
			index = 5, require = 1)
	private MultiBufferSource trsclient$shieldAlpha(LivingEntity entity, ItemStack stack, ItemTransforms.TransformType type, boolean leftHand,
			PoseStack pose, MultiBufferSource buffers, int light) {
		return ShieldHooks.buffers(entity, stack, leftHand, buffers);
	}
}
//?} else {
/*import dev.theredstonee.trsclient.render.ShieldHooks;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public abstract class ShieldHandMixin {
	@ModifyArg(method = "renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;F)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/renderer/block/model/ItemTransforms$TransformType;Z)V"),
			index = 1, require = 1)
	private ItemStack trsclient$shieldPose(LivingEntity entity, ItemStack stack, ItemTransforms.TransformType type, boolean leftHand) {
		return ShieldHooks.before(entity, stack, leftHand, null);
	}

	@Inject(method = "renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;F)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/renderer/block/model/ItemTransforms$TransformType;Z)V",
					shift = At.Shift.AFTER),
			require = 1)
	private void trsclient$shieldDone(CallbackInfo ci) {
		ShieldHooks.after();
	}
}
*///?}
