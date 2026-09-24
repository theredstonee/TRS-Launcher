package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.core.minimap.MinimapCache;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MaterialColor;

/**
 * Liest für die Minimap den obersten Block jedes Feldes eines Chunks (Kartenfarbe + Höhe).
 * Nur geladene Chunks werden gelesen – die Minimap zeigt also nie mehr, als das Spiel ohnehin kennt.
 * Einziger Versionsunterschied bis 1.19.4: die Weltuntergrenze gibt es erst ab 1.17
 * (davor beginnt jede Welt bei y = 0). "MaterialColor" heißt erst ab 1.20 "MapColor".
 */
public final class MapSampler implements MinimapCache.Source {
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

	@Override
	public boolean isLoaded(int chunkX, int chunkZ) {
		Level level = Minecraft.getInstance().level;
		return level != null && level.hasChunk(chunkX, chunkZ);
	}

	@Override
	public boolean fill(int chunkX, int chunkZ, int[] colors, int[] heights) {
		Level level = Minecraft.getInstance().level;
		if (level == null) return false;
		LevelChunk chunk = level.getChunk(chunkX, chunkZ);
		if (chunk == null) return false;
		int minY = minY(level);
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int worldX = (chunkX << 4) + x;
				int worldZ = (chunkZ << 4) + z;
				int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				int index = z * 16 + x;
				int color = 0;
				int y = top;
				// Vom obersten Block nach unten, bis etwas eine Kartenfarbe hat (Luft/Glas nicht).
				for (int steps = 0; steps < 16 && y > minY; steps++) {
					pos.set(worldX, y - 1, worldZ);
					// Direkt aus dem Chunk (spart je Block die Chunk-Suche der Welt).
					BlockState state = chunk.getBlockState(pos);
					color = mapColor(state, level, pos);
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
	private static int mapColor(BlockState state, Level level, BlockPos pos) {
		MaterialColor color = state.getMapColor(level, pos);
		if (color == null) return 0;
		return color.col & 0xFFFFFF;
	}

	/** Unterste Bauhöhe der Welt. */
	private static int minY(Level level) {
		//? if >=1.17 {
		/*return level.getMinBuildHeight();
		*///?} else
		return 0;
	}
}
