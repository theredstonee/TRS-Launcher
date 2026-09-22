package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.core.minimap.MinimapCache;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
//? if >=1.9 {
/*import net.minecraft.util.math.BlockPos;
*///?} else
import net.minecraft.util.BlockPos;

/**
 * Liest für die Minimap den obersten Block jedes Feldes eines Chunks (Kartenfarbe + Höhe).
 * Nur geladene Chunks werden gelesen – die Minimap zeigt also nie mehr, als das Spiel ohnehin kennt.
 *
 * <p>Versionsunterschiede: die Kartenfarbe holt man bis 1.10.2 über {@code Block#getMapColor(IBlockState)},
 * in 1.11 über {@code IBlockState#getMapColor()} und ab 1.12 über
 * {@code IBlockState#getMapColor(IBlockAccess, BlockPos)}; {@code BlockPos} liegt ab 1.9 in {@code util.math}.
 */
public final class MapSampler implements MinimapCache.Source {
	/** So viele Blöcke wird pro Feld nach unten gesucht, bis etwas eine Kartenfarbe hat. */
	private static final int MAX_DEPTH = 16;

	/** Chunk der Client-Welt ({@code getChunkFromChunkCoords} heißt ab 1.12 {@code getChunk}). */
	private static Chunk chunk(World world, int chunkX, int chunkZ) {
		//? if >=1.12 {
		/*return world.getChunk(chunkX, chunkZ);
		*///?} else
		return world.getChunkFromChunkCoords(chunkX, chunkZ);
	}

	@Override
	public boolean isLoaded(int chunkX, int chunkZ) {
		World world = Mc.world();
		if (world == null) return false;
		Chunk c = chunk(world, chunkX, chunkZ);
		return c != null && !c.isEmpty();
	}

	@Override
	public boolean fill(int chunkX, int chunkZ, int[] colors, int[] heights) {
		World world = Mc.world();
		if (world == null) return false;
		Chunk chunk = chunk(world, chunkX, chunkZ);
		if (chunk == null || chunk.isEmpty()) return false;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int worldX = (chunkX << 4) + x;
				int worldZ = (chunkZ << 4) + z;
				int top = chunk.getHeightValue(x, z);
				int index = z * 16 + x;
				int color = 0;
				int y = top;
				// Vom obersten Block nach unten, bis etwas eine Kartenfarbe hat (Luft/Glas nicht).
				for (int steps = 0; steps < MAX_DEPTH && y > 0; steps++) {
					color = mapColor(world, worldX, y - 1, worldZ);
					if (color != 0) break;
					y--;
				}
				colors[index] = color;
				heights[index] = y;
			}
		}
		return true;
	}

	/** Kartenfarbe eines Blocks als 0xRRGGBB (0 = keine, z. B. Luft). */
	private static int mapColor(World world, int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		IBlockState state = world.getBlockState(pos);
		if (state == null) return 0;
		net.minecraft.block.material.MapColor color;
		//? if >=1.12 {
		/*color = state.getMapColor(world, pos);
		*///?} elif >=1.11 {
		/*color = state.getMapColor();
		*///?} else
		color = state.getBlock().getMapColor(state);
		if (color == null) return 0;
		return color.colorValue & 0xFFFFFF;
	}
}
