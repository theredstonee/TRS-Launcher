package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.core.map.ChunkReader;
import net.minecraft.block.material.MaterialColor;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.Heightmap;

/**
 * Chunk-Zugriff der Karte ({@link ChunkReader}) für Forge 1.13.2: Oberkante (Höhenkarte WORLD_SURFACE),
 * Kartenfarbe und Tönung (BlockColors) der Blöcke eines geladenen Chunks. Nur geladene Chunks. Barriere und
 * Strukturleere gelten als Luft (wassergeflutet als Wasser).
 */
public final class MapSampler implements ChunkReader {
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private WorldClient world;
	private Chunk chunk;
	private int baseX, baseZ;

	private static Chunk loaded(WorldClient w, int chunkX, int chunkZ) {
		Chunk c = w.getChunkProvider().getChunk(chunkX, chunkZ, false, false);
		return c == null || c.isEmpty() ? null : c;
	}

	@Override
	public boolean isLoaded(int chunkX, int chunkZ) {
		WorldClient w = Minecraft.getInstance().world;
		return w != null && loaded(w, chunkX, chunkZ) != null;
	}

	@Override
	public boolean open(int chunkX, int chunkZ) {
		world = Minecraft.getInstance().world;
		chunk = world == null ? null : loaded(world, chunkX, chunkZ);
		baseX = chunkX << 4;
		baseZ = chunkZ << 4;
		return chunk != null;
	}

	@Override
	public int minY() {
		return 0;
	}

	@Override
	public int top(int localX, int localZ) {
		return chunk.getTopBlockY(Heightmap.Type.WORLD_SURFACE, localX, localZ) + 1;
	}

	@Override
	public int block(int localX, int y, int localZ) {
		pos.setPos(baseX + localX, y, baseZ + localZ);
		IBlockState state = chunk.getBlockState(pos);
		if (state == null || state.isAir()) return AIR;
		net.minecraft.block.Block b = state.getBlock();
		if (b == net.minecraft.init.Blocks.BARRIER || b == net.minecraft.init.Blocks.STRUCTURE_VOID) {
			return state.getFluidState().isEmpty() ? AIR : dev.theredstonee.trsclient.core.map.MapColors.MAP_WATER;
		}
		MaterialColor color = state.getMaterialColor(world, pos);
		int rgb = color == null ? 0 : color.colorValue & 0xFFFFFF;
		return state.isOpaqueCube(world, pos) ? rgb | OPAQUE : rgb;
	}

	@Override
	public boolean sectionEmpty(int y) {
		net.minecraft.world.chunk.ChunkSection[] sections = chunk.getSections();
		int i = y >> 4;
		if (i < 0 || i >= sections.length) return true;
		return sections[i] == null || sections[i].isEmpty();
	}

	@Override
	public int tint(int localX, int y, int localZ) {
		pos.setPos(baseX + localX, y, baseZ + localZ);
		IBlockState state = chunk.getBlockState(pos);
		if (state == null) return -1;
		int c = Minecraft.getInstance().getBlockColors().getColor(state, world, pos, 0);
		return c == -1 ? -1 : c & 0xFFFFFF;
	}
}
