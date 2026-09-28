package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.core.map.ChunkReader;
import dev.theredstonee.trsclient.core.map.MapColors;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.TexturePalette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Chunk-Zugriff der Karte ({@link ChunkReader}): Oberkante, Kartenfarbe und Tönung (Biom-Gras/Laub/Wasser) der
 * Blöcke eines geladenen Chunks. Alles Weitere rechnet {@code core.map}. Nur geladene Chunks – die Karte zeigt nie
 * mehr, als das Spiel ohnehin kennt. Versionen: MaterialColor → MapColor ab 1.20, Weltuntergrenze ab 1.17,
 * Tönung über BlockColors (ab 26.1 BlockTintSource). Unsichtbare technische Blöcke (Barriere, Strukturleere, ab
 * 1.17 Licht-Block) gelten als Luft, wassergeflutet als Wasser; leere Abschnitte ab 1.18 über hasOnlyAir.
 *
 * <p>Texturfarben ({@link #textureColor}): je Block-Zustand einmal die Textur der Oberseite aus dem Blockmodell
 * suchen (sonst eine beliebige Fläche, sonst das Partikelbild) – gemittelt wird in {@link TexturePalette}.
 * Modell-API: BakedModel#getQuads bis 1.21.4, BlockStateModel#collectParts ab 1.21.5, BlockStateModelSet ab 26.1;
 * BakedQuad#getSprite fehlt 1.15–1.16.5 (Feld per Typ).
 */
public final class MapSampler implements ChunkReader {
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	/** Erst die Oberseite, dann Flächen ohne Richtung (Kreuz-Modelle wie Blumen). */
	private static final Direction[] SIDES = {Direction.UP, null};
	//? if >=1.19 {
	private final net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(42L);
	//?} else
	/*private final java.util.Random random = new java.util.Random(42L);*/
	private static java.lang.reflect.Field quadSpriteField;
	private Level level;
	private LevelChunk chunk;
	private int baseX, baseZ;

	@Override
	public boolean isLoaded(int chunkX, int chunkZ) {
		Level l = Minecraft.getInstance().level;
		return l != null && l.hasChunk(chunkX, chunkZ);
	}

	@Override
	public boolean open(int chunkX, int chunkZ) {
		level = Minecraft.getInstance().level;
		chunk = null;
		if (level == null || !level.hasChunk(chunkX, chunkZ)) return false;
		chunk = level.getChunk(chunkX, chunkZ);
		baseX = chunkX << 4;
		baseZ = chunkZ << 4;
		return chunk != null;
	}

	@Override
	public int minY() {
		if (level == null) return 0;
		//? if >=1.21.2 {
		/*return level.getMinY();
		*///?} elif >=1.17 {
		return level.getMinBuildHeight();
		//?} else
		/*return 0;*/
	}

	@Override
	public int top(int localX, int localZ) {
		return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ) + 1;
	}

	@Override
	public int block(int localX, int y, int localZ) {
		pos.set(baseX + localX, y, baseZ + localZ);
		BlockState state = chunk.getBlockState(pos);
		if (state.isAir()) return AIR;
		if (invisible(state.getBlock())) return state.getFluidState().isEmpty() ? AIR : MapColors.MAP_WATER;
		//? if >=1.20 {
		net.minecraft.world.level.material.MapColor color = state.getMapColor(level, pos);
		//?} else
		/*net.minecraft.world.level.material.MaterialColor color = state.getMapColor(level, pos);*/
		int rgb = color == null ? 0 : color.col & 0xFFFFFF;
		return state.canOcclude() ? rgb | OPAQUE : rgb;
	}

	/** Barriere, Strukturleere und (ab 1.17) Licht-Block: im Spiel unsichtbar, auf der Karte Luft. */
	private static boolean invisible(Block b) {
		//? if >=1.17 {
		if (b == Blocks.LIGHT) return true;
		//?}
		return b == Blocks.BARRIER || b == Blocks.STRUCTURE_VOID;
	}

	@Override
	public boolean sectionEmpty(int y) {
		LevelChunkSection[] sections = chunk.getSections();
		//? if >=1.17 {
		int i = chunk.getSectionIndex(y);
		//?} else
		/*int i = y >> 4;*/
		if (i < 0 || i >= sections.length) return true;
		//? if >=1.18 {
		return sections[i] == null || sections[i].hasOnlyAir();
		//?} else
		/*return LevelChunkSection.isEmpty(sections[i]);*/
	}

	@Override
	public int tint(int localX, int y, int localZ) {
		pos.set(baseX + localX, y, baseZ + localZ);
		BlockState state = chunk.getBlockState(pos);
		//? if >=26.1 {
		/*net.minecraft.client.color.block.BlockTintSource source = Minecraft.getInstance().getBlockColors().getTintSource(state, 0);
		if (source == null) return -1;
		int c = source.colorInWorld(state, (net.minecraft.client.multiplayer.ClientLevel) level, pos);
		*///?} else
		int c = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
		return c == -1 ? -1 : c & 0xFFFFFF;
	}

	@Override
	public int textureColor(int localX, int y, int localZ) {
		MapEngine engine = MapEngine.get();
		if (engine == null || chunk == null) return -1;
		TexturePalette palette = engine.palette();
		pos.set(baseX + localX, y, baseZ + localZ);
		BlockState state = chunk.getBlockState(pos);
		int c = palette.lookup(state);
		if (c != TexturePalette.UNRESOLVED) return c;
		String sprite = null;
		boolean tinted = false;
		try {
			TextureAtlasSprite found = null;
			Minecraft mc = Minecraft.getInstance();
			//? if >=26.1 {
			/*net.minecraft.client.renderer.block.dispatch.BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(state);
			java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts = new java.util.ArrayList<>();
			model.collectParts(random, parts);
			search:
			for (Direction side : SIDES) {
				for (net.minecraft.client.renderer.block.dispatch.BlockStateModelPart part : parts) {
					for (net.minecraft.client.resources.model.geometry.BakedQuad q : part.getQuads(side)) {
						found = q.materialInfo().sprite();
						tinted = q.materialInfo().isTinted();
						break search;
					}
				}
			}
			if (found == null) found = model.particleMaterial().sprite();
			*///?} elif >=1.21.5 {
			/*net.minecraft.client.renderer.block.model.BlockStateModel model = mc.getBlockRenderer().getBlockModel(state);
			java.util.List<net.minecraft.client.renderer.block.model.BlockModelPart> parts = model.collectParts(random);
			search:
			for (Direction side : SIDES) {
				for (net.minecraft.client.renderer.block.model.BlockModelPart part : parts) {
					for (net.minecraft.client.renderer.block.model.BakedQuad q : part.getQuads(side)) {
						found = q.sprite();
						tinted = q.isTinted();
						break search;
					}
				}
			}
			if (found == null) found = model.particleIcon();
			*///?} else {
			net.minecraft.client.resources.model.BakedModel model = mc.getBlockRenderer().getBlockModel(state);
			search:
			for (Direction side : SIDES) {
				for (net.minecraft.client.renderer.block.model.BakedQuad q : model.getQuads(state, side, random)) {
					found = quadSprite(q);
					tinted = q.isTinted();
					break search;
				}
			}
			if (found == null) found = model.getParticleIcon();
			//?}
			if (found != null) sprite = spriteName(found);
		} catch (RuntimeException | LinkageError e) {
			sprite = null;
		}
		return palette.resolve(state, sprite, tinted);
	}

	/** Textur eines Quads (bis 1.21.4); 1.15–1.16.5 ohne Getter → Feld per Typ. */
	private static TextureAtlasSprite quadSprite(Object quad) {
		//? if >=1.21.5 {
		/*return null;
		*///?} elif >=1.17 {
		return ((net.minecraft.client.renderer.block.model.BakedQuad) quad).getSprite();
		//?} elif >=1.15 {
		/*try {
			if (quadSpriteField == null) {
				for (java.lang.reflect.Field f : net.minecraft.client.renderer.block.model.BakedQuad.class.getDeclaredFields()) {
					if (f.getType() == TextureAtlasSprite.class) {
						f.setAccessible(true);
						quadSpriteField = f;
					}
				}
			}
			return quadSpriteField == null ? null : (TextureAtlasSprite) quadSpriteField.get(quad);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
		*///?} else
		/*return ((net.minecraft.client.renderer.block.model.BakedQuad) quad).getSprite();*/
	}

	/** Name der Textur, z. B. {@code minecraft:block/grass_block_top}. */
	private static String spriteName(TextureAtlasSprite sprite) {
		//? if >=1.19.3 {
		return sprite.contents().name().toString();
		//?} else
		/*return sprite.getName().toString();*/
	}
}
