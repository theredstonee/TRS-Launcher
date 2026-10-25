package dev.theredstonee.trsclient.online;

// Nur ab 1.15 (PoseStack/MultiBufferSource) – wie HatHooks; 1.14 zeichnet mit festen GL-Aufrufen.
//? if >=1.15 {
import com.mojang.blaze3d.vertex.PoseStack;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.cosmetic.pet.PetDraw;
import dev.theredstonee.trsclient.core.cosmetic.pet.PetWorld;
import dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2Renderer;
import dev.theredstonee.trsclient.core.cosmetic.v2.V2Hat;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Begleiter am Boden (Winter 2026: Pinguine) im Spiel: einmal je Client-Tick die Spieler der Welt an die Simulation
 * ({@code core.cosmetic.pet.PetSim}) melden, einmal je Bild alle sichtbaren Begleiter in Weltkoordinaten zeichnen –
 * aufgerufen aus {@code PetWorldMixin} direkt nach den Entities (bis 1.21.1 am ersten {@code checkPoseStack} in
 * {@code renderLevel}, 1.21.2–1.21.8 am Ende von {@code renderEntities}, ab 1.21.9 am Ende von {@code submitEntities}).
 * Auch der eigene Begleiter ist sichtbar (auch in der Ich-Perspektive). Licht am Standort, Entfernungs-Grenze und
 * Obergrenze gesamt kommen aus {@code OnlineFeatures#petFrame}; gezeichnet wird mit den Render-Typen der Kopf-Kosmetik
 * ({@link HatHooks}). Die Kamera-Pose bleibt unverändert – {@link PetDraw#wrap} rechnet die Ecken selbst in die Welt um.
 */
public final class PetHooks {
	private static final Blocks2 BLOCKS = new Blocks2();

	private PetHooks() {
	}

	/** Einmal je Client-Tick (aus {@code OnlineHooks.tick}). */
	public static void tick(Minecraft mc) {
		OnlineFeatures<Object> f = OnlineHooks.features();
		if (f == null) return;
		Level level = mc.level;
		if (!f.petsBegin(mc.player == null ? null : level)) return;
		BLOCKS.level = level;
		for (AbstractClientPlayer p : mc.level.players()) {
			boolean hidden = p.isInvisible() || p.isSpectator();
			// Reitet er (Boot, Pferd, Lore …), zählt das unterste Fahrzeug: dessen Lage und Tempo, Boden darunter.
			net.minecraft.world.entity.Entity v = p.getRootVehicle();
			f.petOwner(p.getUUID(), p == mc.player, v.getX(), v.getY(), v.getZ(), p.yBodyRot, v == p && p.isShiftKeyDown(), hidden,
					BLOCKS);
		}
		f.petsEnd();
	}

	//? if >=1.21.9 {
	/*public static void render(PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector) {
		OnlineFeatures<Object> f = OnlineHooks.features();
		Minecraft mc = Minecraft.getInstance();
		if (f == null || mc.level == null) return;
		try {
			BLOCKS.level = mc.level;
			int n = f.petFrame(BLOCKS, Mc.cameraX(), Mc.cameraY(), Mc.cameraZ(), Mc.cameraYaw(), Mc.cameraPitch());
			for (int i = 0; i < n; i++) draw(f, f.petDraw(i), pose, collector);
		} catch (RuntimeException e) {
			f.online().reportError(e);
		}
	}

	private static void draw(final OnlineFeatures<Object> f, final PetDraw<Object> d, PoseStack pose,
			net.minecraft.client.renderer.SubmitNodeCollector collector) {
		final V2Hat<Object> hat = d.hat;
		final int light = d.light;
		final int lod = hat.level(d.eye, HatHooks.fov(), HatHooks.screenHeight());
		final double[] eye = hat.has(CosmeticV2Renderer.PASS_HALO) ? d.eye : null;
		if (hat.has(CosmeticV2Renderer.PASS_CUTOUT) || hat.has(CosmeticV2Renderer.PASS_EMISSIVE)) {
			collector.submitCustomGeometry(pose, HatHooks.cutout(hat.base(lod)), (last, vc) -> {
				f.emitV2(hat, CosmeticV2Renderer.PASS_CUTOUT, null, d.wrap(HatHooks.v2sink(vc, last, light)));
				f.emitV2(hat, CosmeticV2Renderer.PASS_EMISSIVE, null, d.wrap(HatHooks.v2sink(vc, last, HatHooks.FULL_BRIGHT)));
			});
		}
		if (hat.has(CosmeticV2Renderer.PASS_TRANSLUCENT)) {
			collector.submitCustomGeometry(pose, HatHooks.translucent(hat.base(lod)),
					(last, vc) -> f.emitV2(hat, CosmeticV2Renderer.PASS_TRANSLUCENT, null, d.wrap(HatHooks.v2sink(vc, last, light))));
		}
		if (hat.has(CosmeticV2Renderer.PASS_GLOW)) {
			collector.submitCustomGeometry(pose, HatHooks.additive(hat.glow(lod)),
					(last, vc) -> f.emitV2(hat, CosmeticV2Renderer.PASS_GLOW, null, d.wrap(HatHooks.additiveSink(vc, last))));
		}
		if (eye != null) {
			collector.submitCustomGeometry(pose, HatHooks.halo(hat.halo),
					(last, vc) -> f.emitV2(hat, CosmeticV2Renderer.PASS_HALO, eye, d.wrap(HatHooks.haloSink(vc, last))));
		}
	}
	*///?} else {
	public static void render(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers) {
		OnlineFeatures<Object> f = OnlineHooks.features();
		Minecraft mc = Minecraft.getInstance();
		if (f == null || mc.level == null || buffers == null) return;
		try {
			BLOCKS.level = mc.level;
			int n = f.petFrame(BLOCKS, Mc.cameraX(), Mc.cameraY(), Mc.cameraZ(), Mc.cameraYaw(), Mc.cameraPitch());
			for (int i = 0; i < n; i++) draw(f, f.petDraw(i), pose, buffers);
		} catch (RuntimeException e) {
			f.online().reportError(e);
		}
	}

	private static void draw(OnlineFeatures<Object> f, PetDraw<Object> d, PoseStack pose,
			net.minecraft.client.renderer.MultiBufferSource buffers) {
		V2Hat<Object> hat = d.hat;
		PoseStack.Pose last = pose.last();
		int lod = hat.level(d.eye, HatHooks.fov(), HatHooks.screenHeight());
		if (hat.has(CosmeticV2Renderer.PASS_CUTOUT) || hat.has(CosmeticV2Renderer.PASS_EMISSIVE)) {
			com.mojang.blaze3d.vertex.VertexConsumer vc = buffers.getBuffer(HatHooks.cutout(hat.base(lod)));
			f.emitV2(hat, CosmeticV2Renderer.PASS_CUTOUT, null, d.wrap(HatHooks.v2sink(vc, last, d.light)));
			f.emitV2(hat, CosmeticV2Renderer.PASS_EMISSIVE, null, d.wrap(HatHooks.v2sink(vc, last, HatHooks.FULL_BRIGHT)));
		}
		if (hat.has(CosmeticV2Renderer.PASS_TRANSLUCENT)) {
			f.emitV2(hat, CosmeticV2Renderer.PASS_TRANSLUCENT, null,
					d.wrap(HatHooks.v2sink(buffers.getBuffer(HatHooks.translucent(hat.base(lod))), last, d.light)));
		}
		if (hat.has(CosmeticV2Renderer.PASS_GLOW)) {
			f.emitV2(hat, CosmeticV2Renderer.PASS_GLOW, null,
					d.wrap(HatHooks.additiveSink(buffers.getBuffer(HatHooks.additive(hat.glow(lod))), last)));
		}
		if (hat.has(CosmeticV2Renderer.PASS_HALO)) {
			f.emitV2(hat, CosmeticV2Renderer.PASS_HALO, d.eye,
					d.wrap(HatHooks.haloSink(buffers.getBuffer(HatHooks.halo(hat.halo)), last)));
		}
	}
	//?}

	/** Block-Abfragen der Simulation ({@link PetWorld}) in der aktuellen Welt. Nur Spiel-/Render-Thread. */
	static final class Blocks2 implements PetWorld {
		private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		Level level;

		@Override
		public int block(int x, int y, int z) {
			Level l = level;
			if (l == null) return UNLOADED;
			pos.set(x, y, z);
			if (!l.isLoaded(pos)) return UNLOADED;
			BlockState s = l.getBlockState(pos);
			if (s.isAir()) return 0;
			int flags = 0;
			FluidState fluid = s.getFluidState();
			if (!fluid.isEmpty()) {
				Fluid t = fluid.getType();
				if (t == Fluids.WATER || t == Fluids.FLOWING_WATER) flags |= WATER;
				else if (t == Fluids.LAVA || t == Fluids.FLOWING_LAVA) flags |= DANGER;
			}
			Block b = s.getBlock();
			if (danger(b)) flags |= DANGER;
			if (slippery(b)) flags |= SLIPPERY;
			//? if >=1.17 {
			/*// Pulverschnee hat für Wesen ohne Lederstiefel keine Form – Pinguine laufen trotzdem darauf.
			if (b == Blocks.POWDER_SNOW) return PetWorld.code(flags | SOLID, 1.0);
			*///?}
			VoxelShape shape = s.getCollisionShape(l, pos);
			if (shape.isEmpty()) return flags;
			return PetWorld.code(flags | SOLID, shape.max(Direction.Axis.Y));
		}

		@Override
		public int light(int x, int y, int z) {
			Level l = level;
			if (l == null) return PetWorld.packLight(15, 15);
			pos.set(x, y, z);
			int sky = l.getChunkSource().getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(pos);
			int block = l.getChunkSource().getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(pos);
			return PetWorld.packLight(sky, block);
		}

		private static boolean slippery(Block b) {
			//? if >=1.17 {
			/*if (b == Blocks.POWDER_SNOW) return true;
			*///?}
			return b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.BLUE_ICE || b == Blocks.FROSTED_ICE || b == Blocks.SNOW
					|| b == Blocks.SNOW_BLOCK;
		}

		private static boolean danger(Block b) {
			//? if >=1.16 {
			if (b == Blocks.SOUL_FIRE || b == Blocks.SOUL_CAMPFIRE) return true;
			//?}
			return b == Blocks.FIRE || b == Blocks.CACTUS || b == Blocks.MAGMA_BLOCK || b == Blocks.CAMPFIRE
					|| b == Blocks.SWEET_BERRY_BUSH;
		}
	}
}
//?}
