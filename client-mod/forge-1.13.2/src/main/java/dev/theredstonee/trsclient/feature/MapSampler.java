package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.core.minimap.MinimapCache;
import net.minecraft.block.material.MaterialColor;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.Heightmap;

/**
 * Liest für die Minimap den obersten Block jedes Feldes eines Chunks (Kartenfarbe + Höhe).
 * Nur geladene Chunks werden gelesen – die Minimap zeigt also nie mehr, als das Spiel ohnehin kennt.
 *
 * <p>1.13.2: die Höhenkarte {@code Heightmap.Type.WORLD_SURFACE} wird beim Empfangen eines Chunks
 * vom Client selbst gebaut ({@code Chunk.read} → {@code generateHeightMap}), die Kartenfarbe kommt
 * über {@code Block#getMaterialColor(IBlockState, IBlockReader, BlockPos)} (früher {@code getMapColor}).
 * Nicht geladene Chunks liefert der {@code ChunkProviderClient} als leeren Platzhalter-Chunk.
 */
public final class MapSampler implements MinimapCache.Source {
	/** So viele Blöcke wird pro Feld nach unten gesucht, bis etwas eine Kartenfarbe hat. */
	private static final int MAX_DEPTH = 16;

	private final Minecraft mc = Minecraft.getInstance();

	private World world() {
		return mc.world;
	}

	@Override
	public boolean isLoaded(int chunkX, int chunkZ) {
		World world = world();
		if (world == null) return false;
		Chunk c = world.getChunk(chunkX, chunkZ);
		return c != null && !c.isEmpty();
	}

	@Override
	public boolean fill(int chunkX, int chunkZ, int[] colors, int[] heights) {
		World world = world();
		if (world == null) return false;
		Chunk chunk = world.getChunk(chunkX, chunkZ);
		if (chunk == null || chunk.isEmpty()) return false;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int worldX = (chunkX << 4) + x;
				int worldZ = (chunkZ << 4) + z;
				int index = z * 16 + x;
				int y = chunk.getTopBlockY(Heightmap.Type.WORLD_SURFACE, worldX, worldZ);
				int color = 0;
				// Vom obersten Block nach unten, bis etwas eine Kartenfarbe hat (Luft/Glas nicht).
				for (int steps = 0; steps < MAX_DEPTH && y > 0; steps++) {
					color = mapColor(world, worldX, y, worldZ);
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
		MaterialColor color = state.getBlock().getMaterialColor(state, world, pos);
		if (color == null) return 0;
		return color.colorValue & 0xFFFFFF;
	}
}
