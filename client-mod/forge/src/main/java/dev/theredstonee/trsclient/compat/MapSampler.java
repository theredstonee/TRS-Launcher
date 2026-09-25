package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.core.map.ChunkReader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Chunk-Zugriff der Karte ({@link ChunkReader}): Oberkante, Kartenfarbe und Tönung (Biom-Gras/Laub/Wasser) der
 * Blöcke eines geladenen Chunks. Alles Weitere rechnet {@code core.map}. Nur geladene Chunks – die Karte zeigt nie
 * mehr, als das Spiel ohnehin kennt. Versionen: MaterialColor → MapColor ab 1.20, Weltuntergrenze ab 1.17,
 * Tönung über BlockColors (ab 26.1 BlockTintSource).
 */
public final class MapSampler implements ChunkReader {
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
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
		//? if >=1.20 {
		net.minecraft.world.level.material.MapColor color = state.getMapColor(level, pos);
		//?} else
		/*net.minecraft.world.level.material.MaterialColor color = state.getMapColor(level, pos);*/
		return color == null ? 0 : color.col & 0xFFFFFF;
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
}
