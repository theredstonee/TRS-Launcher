package dev.theredstonee.trsclient.mixin;

// Schild-Position ab 1.21.9: Deckkraft beim Blocken. Solange ShieldHooks#drawAlpha kleiner als 1 ist (nur während
// das Schild der 1. Person übergeben wird), bekommen die übergebenen Modellteile die durchsichtige Schild-Textur
// und eine Farbe mit Deckkraft. Alle Übergaben landen in SubmitNodeCollection (auch die des SubmitNodeStorage).
// Ab 26.2 gibt es dort nur noch submitModel. Nur in der Mixin-Liste ab 1.21.9.
//? if >=26.3 {
/*import dev.theredstonee.trsclient.render.ShieldHooks;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(SubmitNodeCollection.class)
public abstract class ShieldSubmitMixin {
	@ModifyVariable(method = "submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V",
			at = @At("HEAD"), argsOnly = true, require = 1)
	private RenderType trsclient$shieldType(RenderType type) {
		return ShieldHooks.renderType(type);
	}

	@ModifyVariable(method = "submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V",
			at = @At("HEAD"), argsOnly = true, ordinal = 2, require = 1)
	private int trsclient$shieldTint(int tint) {
		return ShieldHooks.tint(tint);
	}
}
*///?} elif >=26.2 {
/*import dev.theredstonee.trsclient.render.ShieldHooks;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(SubmitNodeCollection.class)
public abstract class ShieldSubmitMixin {
	@ModifyVariable(method = "submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
			at = @At("HEAD"), argsOnly = true, require = 1)
	private RenderType trsclient$shieldType(RenderType type) {
		return ShieldHooks.renderType(type);
	}

	@ModifyVariable(method = "submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
			at = @At("HEAD"), argsOnly = true, ordinal = 2, require = 1)
	private int trsclient$shieldTint(int tint) {
		return ShieldHooks.tint(tint);
	}
}
*///?} elif >=1.21.11 {
/*import dev.theredstonee.trsclient.render.ShieldHooks;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(SubmitNodeCollection.class)
public abstract class ShieldSubmitMixin {
	@ModifyVariable(method = "submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
			at = @At("HEAD"), argsOnly = true, require = 1)
	private RenderType trsclient$shieldType(RenderType type) {
		return ShieldHooks.renderType(type);
	}

	@ModifyVariable(method = "submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
			at = @At("HEAD"), argsOnly = true, ordinal = 2, require = 1)
	private int trsclient$shieldTint(int tint) {
		return ShieldHooks.tint(tint);
	}

	@ModifyVariable(method = "submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ZZILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;I)V",
			at = @At("HEAD"), argsOnly = true, require = 1)
	private RenderType trsclient$shieldPartType(RenderType type) {
		return ShieldHooks.renderType(type);
	}

	@ModifyVariable(method = "submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ZZILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;I)V",
			at = @At("HEAD"), argsOnly = true, ordinal = 2, require = 1)
	private int trsclient$shieldPartTint(int tint) {
		return ShieldHooks.tint(tint);
	}
}
*///?} elif >=1.21.9 {
/*import dev.theredstonee.trsclient.render.ShieldHooks;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SubmitNodeCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(SubmitNodeCollection.class)
public abstract class ShieldSubmitMixin {
	@ModifyVariable(method = "submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
			at = @At("HEAD"), argsOnly = true, require = 1)
	private RenderType trsclient$shieldType(RenderType type) {
		return ShieldHooks.renderType(type);
	}

	@ModifyVariable(method = "submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
			at = @At("HEAD"), argsOnly = true, ordinal = 2, require = 1)
	private int trsclient$shieldTint(int tint) {
		return ShieldHooks.tint(tint);
	}

	@ModifyVariable(method = "submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/RenderType;IILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ZZILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;I)V",
			at = @At("HEAD"), argsOnly = true, require = 1)
	private RenderType trsclient$shieldPartType(RenderType type) {
		return ShieldHooks.renderType(type);
	}

	@ModifyVariable(method = "submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/RenderType;IILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ZZILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;I)V",
			at = @At("HEAD"), argsOnly = true, ordinal = 2, require = 1)
	private int trsclient$shieldPartTint(int tint) {
		return ShieldHooks.tint(tint);
	}
}
*///?}
