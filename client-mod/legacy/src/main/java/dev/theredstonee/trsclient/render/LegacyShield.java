package dev.theredstonee.trsclient.render;

import dev.theredstonee.trsclient.core.module.TrsModules;

/**
 * Schild-Position auf Legacy-Forge (Logik in core.shield.ShieldPosition). Ohne Mixins: Forge meldet jede Hand der
 * 1. Person einzeln (RenderSpecificHandEvent, ab Forge für 1.10.2). Hält die Hand ein Schild, wird das Ereignis
 * abgebrochen und die Hand hier nachgezeichnet – mit denselben Arm-Bewegungen wie Vanilla (Seite, Ausholen,
 * Wechsel-Absenken), dann die Haltung aus common und das Schild als Kopie des Stapels (Vanillas Modell-Bedingung
 * „blocking“ vergleicht per Identität, so bleibt es beim normalen Modell und die Block-Haltung wird weich
 * übergeblendet). Durchsichtig beim Blocken: GL-Farbe im Gegenstands-Renderer des Schilds
 * (TileEntityItemStackRenderer, erst beim ersten Bedarf eingehängt). 1.8.9 hat keine Schilde, 1.9/1.9.4 kein
 * Hand-Ereignis – dort ist das Modul ausgeblendet.
 */
public final class LegacyShield {
	private LegacyShield() {
	}

	/** Hängt sich in die Forge-Ereignisse ein (ab 1.10.2). */
	public static void install(TrsModules modules) {
		//? if >=1.10.2 {
		/*net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new Handler(modules));
		*///?}
	}

	//? if >=1.10.2 {
	/*// Zeichnet die Schild-Hand der 1. Person selbst (siehe Klassenbeschreibung).
	public static final class Handler {
		private final TrsModules modules;
		private final dev.theredstonee.trsclient.core.shield.ShieldTransform pose = new dev.theredstonee.trsclient.core.shield.ShieldTransform();
		private boolean failed;

		Handler(TrsModules modules) {
			this.modules = modules;
		}

		@net.minecraftforge.fml.common.eventhandler.SubscribeEvent(priority = net.minecraftforge.fml.common.eventhandler.EventPriority.LOW)
		public void onHand(net.minecraftforge.client.event.RenderSpecificHandEvent event) {
			net.minecraft.item.ItemStack stack = event.getItemStack();
			if (failed || !modules.shieldPosition.isEnabled() || stack == null || !(stack.getItem() instanceof net.minecraft.item.ItemShield)) return;
			net.minecraft.client.entity.EntityPlayerSP player = dev.theredstonee.trsclient.compat.Mc.player();
			if (player == null) return;
			try {
				render(event, player, stack);
				event.setCanceled(true);
			} catch (RuntimeException e) {
				failed = true;
				Alpha.value = 1f;
				dev.theredstonee.trsclient.TrsClient.LOGGER.warn("Schild-Position abgeschaltet (Fehler beim Zeichnen): " + e);
			}
		}

		private void render(net.minecraftforge.client.event.RenderSpecificHandEvent event, net.minecraft.client.entity.EntityPlayerSP player,
				net.minecraft.item.ItemStack stack) {
			net.minecraft.util.EnumHand hand = event.getHand();
			boolean main = hand == net.minecraft.util.EnumHand.MAIN_HAND;
			net.minecraft.util.EnumHandSide side = main ? player.getPrimaryHand() : player.getPrimaryHand().opposite();
			boolean right = side == net.minecraft.util.EnumHandSide.RIGHT;
			int i = right ? 1 : -1;
			float swing = event.getSwingProgress();
			float equip = event.getEquipProgress();
			boolean blocking = player.isHandActive() && player.getItemInUseCount() > 0 && player.getActiveHand() == hand;
			net.minecraft.client.renderer.GlStateManager.pushMatrix();
			if (blocking) {
				// Vanilla (Benutzen, Aktion BLOCK): nur die Seite
				net.minecraft.client.renderer.GlStateManager.translate(i * 0.56F, -0.52F + equip * -0.6F, -0.72F);
			} else {
				// Vanilla (Halten): Ausholen, Seite, Schlag-Drehung
				float sq = (float) Math.sqrt(swing);
				float fx = -0.4F * (float) Math.sin(sq * Math.PI);
				float fy = 0.2F * (float) Math.sin(sq * Math.PI * 2);
				float fz = -0.2F * (float) Math.sin(swing * Math.PI);
				net.minecraft.client.renderer.GlStateManager.translate(i * fx, fy, fz);
				net.minecraft.client.renderer.GlStateManager.translate(i * 0.56F, -0.52F + equip * -0.6F, -0.72F);
				float f = (float) Math.sin(swing * swing * Math.PI);
				net.minecraft.client.renderer.GlStateManager.rotate(i * (45.0F + f * -20.0F), 0.0F, 1.0F, 0.0F);
				float f1 = (float) Math.sin(sq * Math.PI);
				net.minecraft.client.renderer.GlStateManager.rotate(i * f1 * -20.0F, 0.0F, 0.0F, 1.0F);
				net.minecraft.client.renderer.GlStateManager.rotate(f1 * -80.0F, 1.0F, 0.0F, 0.0F);
				net.minecraft.client.renderer.GlStateManager.rotate(i * -45.0F, 0.0F, 1.0F, 0.0F);
			}
			boolean shieldBlocking = blocking && player.getActiveItemStack() != null
					&& player.getActiveItemStack().getItem() instanceof net.minecraft.item.ItemShield;
			int handIndex = main ? dev.theredstonee.trsclient.core.shield.ShieldPosition.MAIN_HAND : dev.theredstonee.trsclient.core.shield.ShieldPosition.OFF_HAND;
			modules.shield.transform(handIndex, !right, shieldBlocking, System.nanoTime(), pose);
			if (!pose.isIdentity()) {
				net.minecraft.client.renderer.GlStateManager.translate(pose.x, pose.y, pose.z);
				net.minecraft.client.renderer.GlStateManager.rotate(pose.angleDegrees(), pose.axisX(), pose.axisY(), pose.axisZ());
				net.minecraft.client.renderer.GlStateManager.scale(pose.scale, pose.scale, pose.scale);
			}
			float alpha = modules.shield.alpha(handIndex);
			if (alpha < 1f) Alpha.install();
			Alpha.value = alpha;
			try {
				net.minecraft.client.Minecraft.getMinecraft().getItemRenderer().renderItemSide(player, stack.copy(),
						right ? net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType.FIRST_PERSON_RIGHT_HAND
								: net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType.FIRST_PERSON_LEFT_HAND,
						!right);
			} finally {
				Alpha.value = 1f;
				net.minecraft.client.renderer.GlStateManager.popMatrix();
			}
		}
	}

	// Gegenstands-Renderer des Schilds mit Deckkraft: setzt die GL-Farbe, nachdem Minecraft sie auf Weiß gestellt hat.
	// Delegiert alles an den vorherigen Renderer (auch den anderer Mods).
	static final class Alpha extends net.minecraft.client.renderer.tileentity.TileEntityItemStackRenderer {
		static float value = 1f;
		private static boolean installed;
		private final net.minecraft.client.renderer.tileentity.TileEntityItemStackRenderer inner;

		private Alpha(net.minecraft.client.renderer.tileentity.TileEntityItemStackRenderer inner) {
			this.inner = inner;
		}

		static void install() {
			if (installed) return;
			installed = true;
			net.minecraft.client.renderer.tileentity.TileEntityItemStackRenderer.instance = new Alpha(net.minecraft.client.renderer.tileentity.TileEntityItemStackRenderer.instance);
		}

		@Override
		public void renderByItem(net.minecraft.item.ItemStack stack) {
			boolean on = value < 1f;
			if (on) begin();
			try {
				inner.renderByItem(stack);
			} finally {
				if (on) end();
			}
		}

		// Forge 1.12.2 hat zusätzlich eine Fassung mit Teil-Tick (ruft die einfache Fassung des inneren Renderers auf).
		public void renderByItem(net.minecraft.item.ItemStack stack, float partialTicks) {
			renderByItem(stack);
		}

		private static void begin() {
			net.minecraft.client.renderer.GlStateManager.enableBlend();
			net.minecraft.client.renderer.GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
			net.minecraft.client.renderer.GlStateManager.color(1f, 1f, 1f, value);
		}

		private static void end() {
			net.minecraft.client.renderer.GlStateManager.color(1f, 1f, 1f, 1f);
			net.minecraft.client.renderer.GlStateManager.disableBlend();
		}
	}
	*///?}
}
