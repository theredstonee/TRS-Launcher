package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.core.map.ChunkReader;
import net.minecraft.block.state.IBlockState;
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
		//? if >=1.9 {
		/*if (state.getMaterial() == net.minecraft.block.material.Material.AIR) return AIR;
		*///?} else
		if (state.getBlock().getMaterial() == net.minecraft.block.material.Material.air) return AIR;
		net.minecraft.block.material.MapColor color;
		//? if >=1.12 {
		/*color = state.getMapColor(world, pos);
		*///?} elif >=1.11 {
		/*color = state.getMapColor();
		*///?} else
		color = state.getBlock().getMapColor(state);
		return color == null ? 0 : color.colorValue & 0xFFFFFF;
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
}
