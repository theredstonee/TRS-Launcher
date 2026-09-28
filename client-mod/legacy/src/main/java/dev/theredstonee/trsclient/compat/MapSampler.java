package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.core.map.ChunkReader;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.TexturePalette;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
//? if >=1.9 {
/*import net.minecraft.util.math.BlockPos;
*///?} else
import net.minecraft.util.BlockPos;

/**
 * Chunk-Zugriff der Karte ({@link ChunkReader}) für Forge 1.8.9–1.12.2: Oberkante, Kartenfarbe und Tönung
 * (Biom-Gras/Laub) der Blöcke eines geladenen Chunks. Alles Weitere rechnet {@code core.map}.
 *
 * <p>Versionsunterschiede: Kartenfarbe bis 1.10.2 über {@code Block#getMapColor(IBlockState)}, in 1.11 über
 * {@code IBlockState#getMapColor()}, ab 1.12 über {@code IBlockState#getMapColor(IBlockAccess, BlockPos)};
 * Tönung bis 1.8.9 am Block ({@code colorMultiplier}), ab 1.9 über {@code BlockColors}; Luft über das Material.
 * Barriere (und ab 1.10 Strukturleere) gelten als Luft – erkannt am Material.
 *
 * <p>Texturfarben ({@link #textureColor}): Textur der Oberseite aus dem Blockmodell (sonst beliebige Fläche, sonst
 * Partikelbild); ihre Pixel liegen unter Forge noch im Speicher ({@code getFrameTextureData}) und werden direkt
 * gemittelt – fehlen sie (Speicher-Mods), liest {@link TexturePalette} die Datei. 1.8.9 kennt die Textur eines
 * Quads nicht: sie wird über die UV-Koordinaten im Block-Atlas gesucht.
 */
public final class MapSampler implements ChunkReader {
	private World world;
	private Chunk chunk;
	private int baseX, baseZ;

	/** Chunk der Client-Welt ({@code getChunkFromChunkCoords} heißt ab 1.12 {@code getChunk}). */
	private static Chunk chunk(World world, int chunkX, int chunkZ) {
		//? if >=1.12 {
		/*return world.getChunk(chunkX, chunkZ);
		*///?} else
		return world.getChunkFromChunkCoords(chunkX, chunkZ);
	}

	@Override
	public boolean isLoaded(int chunkX, int chunkZ) {
		World w = Mc.world();
		if (w == null) return false;
		Chunk c = chunk(w, chunkX, chunkZ);
		return c != null && !c.isEmpty();
	}

	@Override
	public boolean open(int chunkX, int chunkZ) {
		world = Mc.world();
		chunk = null;
		if (world == null) return false;
		Chunk c = chunk(world, chunkX, chunkZ);
		if (c == null || c.isEmpty()) return false;
		chunk = c;
		baseX = chunkX << 4;
		baseZ = chunkZ << 4;
		return true;
	}

	@Override
	public int minY() {
		return 0;
	}

	@Override
	public int top(int localX, int localZ) {
		// Die Höhenkarte zählt nur lichtundurchlässige Blöcke – Schnee, Blumen, Glas liegen darüber.
		return Math.min(255, chunk.getHeightValue(localX, localZ) + 2);
	}

	@Override
	public int block(int localX, int y, int localZ) {
		BlockPos pos = new BlockPos(baseX + localX, y, baseZ + localZ);
		IBlockState state = chunk.getBlockState(pos);
		if (state == null) return AIR;
		//? if >=1.10 {
		/*net.minecraft.block.material.Material mat = state.getMaterial();
		if (mat == net.minecraft.block.material.Material.AIR || mat == net.minecraft.block.material.Material.BARRIER
				|| mat == net.minecraft.block.material.Material.STRUCTURE_VOID) return AIR;
		*///?} elif >=1.9 {
		/*net.minecraft.block.material.Material mat = state.getMaterial();
		if (mat == net.minecraft.block.material.Material.AIR || mat == net.minecraft.block.material.Material.BARRIER) return AIR;
		*///?} else {
		net.minecraft.block.material.Material mat = state.getBlock().getMaterial();
		if (mat == net.minecraft.block.material.Material.air || mat == net.minecraft.block.material.Material.barrier) return AIR;
		//?}
		net.minecraft.block.material.MapColor color;
		//? if >=1.12 {
		/*color = state.getMapColor(world, pos);
		*///?} elif >=1.11 {
		/*color = state.getMapColor();
		*///?} else
		color = state.getBlock().getMapColor(state);
		int rgb = color == null ? 0 : color.colorValue & 0xFFFFFF;
		//? if >=1.9 {
		/*boolean opaque = state.isOpaqueCube();
		*///?} else
		boolean opaque = state.getBlock().isOpaqueCube();
		return opaque ? rgb | OPAQUE : rgb;
	}

	@Override
	public boolean sectionEmpty(int y) {
		net.minecraft.world.chunk.storage.ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
		int i = y >> 4;
		if (i < 0 || i >= sections.length) return true;
		return sections[i] == null || sections[i].isEmpty();
	}

	@Override
	public int tint(int localX, int y, int localZ) {
		BlockPos pos = new BlockPos(baseX + localX, y, baseZ + localZ);
		IBlockState state = chunk.getBlockState(pos);
		if (state == null) return -1;
		int c;
		//? if >=1.9 {
		/*c = net.minecraft.client.Minecraft.getMinecraft().getBlockColors().colorMultiplier(state, world, pos, 0);
		*///?} else
		c = state.getBlock().colorMultiplier(world, pos, 0);
		// Ungetönte Blöcke melden bis 1.8.9 Weiß.
		if (c == -1 || (c & 0xFFFFFF) == 0xFFFFFF) return -1;
		return c & 0xFFFFFF;
	}

	/** 1.8.9: alle Texturen des Block-Atlas (für die Suche per UV), je Atlas-Stand einmal. */
	private static java.util.Collection<TextureAtlasSprite> atlasSprites;
	private static Object atlasToken;

	@Override
	public int textureColor(int localX, int y, int localZ) {
		MapEngine engine = MapEngine.get();
		if (engine == null || chunk == null) return -1;
		TexturePalette palette = engine.palette();
		IBlockState state = chunk.getBlockState(new BlockPos(baseX + localX, y, baseZ + localZ));
		if (state == null) return -1;
		int c = palette.lookup(state);
		if (c != TexturePalette.UNRESOLVED) return c;
		TextureAtlasSprite sprite = null;
		boolean tinted = false;
		try {
			BlockModelShapes shapes = Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes();
			//? if >=1.9 {
			/*net.minecraft.client.renderer.block.model.IBakedModel model = shapes.getModelForState(state);
			java.util.List<BakedQuad> quads = model.getQuads(state, EnumFacing.UP, 0L);
			if (quads.isEmpty()) quads = model.getQuads(state, null, 0L);
			if (!quads.isEmpty()) {
				sprite = quads.get(0).getSprite();
				tinted = quads.get(0).hasTintIndex();
			}
			*///?} else {
			net.minecraft.client.resources.model.IBakedModel model = shapes.getModelForState(state);
			java.util.List<BakedQuad> quads = model.getFaceQuads(EnumFacing.UP);
			if (quads.isEmpty()) quads = model.getGeneralQuads();
			if (!quads.isEmpty()) {
				sprite = spriteOf(quads.get(0));
				tinted = quads.get(0).hasTintIndex();
			}
			//?}
			if (sprite == null) sprite = shapes.getTexture(state);
		} catch (RuntimeException | LinkageError e) {
			sprite = null;
		}
		if (sprite == null) return palette.resolveColor(state, TexturePalette.UNKNOWN, false);
		int rgb = TexturePalette.UNKNOWN;
		try {
			if (sprite.getFrameCount() > 0) {
				int[][] frame = sprite.getFrameTextureData(0);
				if (frame != null && frame.length > 0 && frame[0] != null) {
					rgb = TexturePalette.average(frame[0], sprite.getIconWidth(), sprite.getIconHeight());
				}
			}
		} catch (RuntimeException e) {
			rgb = TexturePalette.UNKNOWN;
		}
		if (rgb != TexturePalette.UNKNOWN) return palette.resolveColor(state, rgb, tinted);
		// Pixel schon verworfen → die Datei lesen lassen ("minecraft:blocks/stone").
		return palette.resolve(state, sprite.getIconName(), tinted);
	}

	/** 1.8.9: Textur eines Quads über die Mitte seiner UV-Koordinaten im Block-Atlas. */
	private static TextureAtlasSprite spriteOf(BakedQuad quad) {
		int[] d = quad.getVertexData();
		if (d == null || d.length < 28 || d.length % 4 != 0) return null;
		int stride = d.length / 4;
		if (stride < 6) return null;
		float u = 0f, v = 0f;
		for (int i = 0; i < 4; i++) {
			u += Float.intBitsToFloat(d[i * stride + 4]);
			v += Float.intBitsToFloat(d[i * stride + 5]);
		}
		u /= 4f;
		v /= 4f;
		for (TextureAtlasSprite s : sprites()) {
			if (u >= s.getMinU() && u <= s.getMaxU() && v >= s.getMinV() && v <= s.getMaxV()) return s;
		}
		return null;
	}

	/** Alle hochgeladenen Texturen des Block-Atlas (privates Feld, per Typ: das zweite {@code Map}-Feld). */
	@SuppressWarnings("unchecked")
	private static java.util.Collection<TextureAtlasSprite> sprites() {
		net.minecraft.client.renderer.texture.TextureMap atlas = Minecraft.getMinecraft().getTextureMapBlocks();
		Object token = atlas.getAtlasSprite("minecraft:blocks/stone");
		if (atlasSprites != null && token == atlasToken) return atlasSprites;
		atlasToken = token;
		atlasSprites = java.util.Collections.emptyList();
		try {
			int maps = 0;
			for (java.lang.reflect.Field f : net.minecraft.client.renderer.texture.TextureMap.class.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType() != java.util.Map.class) continue;
				if (maps++ != 1) continue;
				f.setAccessible(true);
				java.util.Map<String, TextureAtlasSprite> map = (java.util.Map<String, TextureAtlasSprite>) f.get(atlas);
				if (map != null) atlasSprites = new java.util.ArrayList<TextureAtlasSprite>(map.values());
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			atlasSprites = java.util.Collections.emptyList();
		}
		return atlasSprites;
	}
}
