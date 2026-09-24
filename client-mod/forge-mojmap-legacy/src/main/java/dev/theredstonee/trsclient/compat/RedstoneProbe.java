package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.core.redstone.BlockProbe;
import dev.theredstonee.trsclient.core.redstone.RedstoneKind;
import dev.theredstonee.trsclient.core.redstone.RedstoneTools;
import dev.theredstonee.trsclient.core.redstone.RedstoneWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Redstone-Adapter für die Mojmap-Versionen 1.14.4–26.3 (Fabric, NeoForge, Forge – dieselbe Datei):
 * liest Blockzustände der Client-Welt für {@link RedstoneTools}. Nur Lesen, nur was der Client kennt.
 * Einzige Versionsweiche: {@code getAnalogOutputSignal} bekommt ab 1.21.9 eine Richtung.
 */
public final class RedstoneProbe implements RedstoneWorld {
	private static final RedstoneProbe INSTANCE = new RedstoneProbe();
	private static final Direction[] DIRS = Direction.values();

	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private Level level;
	private int[] counts = new int[54];
	private int[] maxStack = new int[54];

	private RedstoneProbe() {
	}

	/** Einmal je Client-Tick: angeschauten Block, Kamera und (bei offenem Behälter) dessen Inhalt weitergeben. */
	public static void tick(RedstoneTools tools) {
		Minecraft mc = Minecraft.getInstance();
		Level level = mc.level;
		if (level == null || mc.player == null) {
			INSTANCE.level = null;
			tools.tick(null, null, false, 0, 0, 0, 0, 0, 0);
			return;
		}
		INSTANCE.level = level;
		HitResult hr = mc.hitResult;
		boolean hit = hr != null && hr.getType() == HitResult.Type.BLOCK && hr instanceof BlockHitResult;
		int hx = 0, hy = 0, hz = 0;
		if (hit) {
			BlockPos p = ((BlockHitResult) hr).getBlockPos();
			hx = p.getX();
			hy = p.getY();
			hz = p.getZ();
			if (Mc.screen() instanceof AbstractContainerScreen) INSTANCE.snapshot(tools, mc, hx, hy, hz);
		}
		tools.tick(INSTANCE, level, hit, hx, hy, hz, Mc.cameraX(), Mc.cameraY(), Mc.cameraZ());
	}

	/**
	 * Inhalt des gerade offenen Behälters merken (nur so kennt der Client ihn). Nur wenn die Plätze
	 * des Menüs zum angeschauten Behälter passen – das Kreativ-Inventar o. Ä. zählt nicht.
	 */
	private void snapshot(RedstoneTools tools, Minecraft mc, int x, int y, int z) {
		AbstractContainerMenu menu = mc.player.containerMenu;
		if (menu == null || menu == mc.player.inventoryMenu) return;
		pos.set(x, y, z);
		BlockEntity be = level.getBlockEntity(pos);
		if (!(be instanceof Container)) return;
		int size = ((Container) be).getContainerSize();
		Container container = null;
		int n = 0;
		for (int i = 0, total = menu.slots.size(); i < total; i++) {
			Slot slot = menu.slots.get(i);
			if (slot.container instanceof Inventory) continue;
			if (container == null) container = slot.container;
			else if (slot.container != container) continue;
			if (n >= counts.length) {
				counts = java.util.Arrays.copyOf(counts, n * 2);
				maxStack = java.util.Arrays.copyOf(maxStack, n * 2);
			}
			ItemStack stack = slot.getItem();
			counts[n] = stack.isEmpty() ? 0 : stack.getCount();
			maxStack[n] = stack.isEmpty() ? 64 : Math.min(container.getMaxStackSize(), stack.getMaxStackSize());
			n++;
		}
		// Doppeltruhe: das Menü hat doppelt so viele Plätze wie eine Hälfte.
		if (container == null || (n != size && n != size * 2)) return;
		tools.rememberContainer(x, y, z, counts, maxStack, n);
	}

	private BlockState state(int x, int y, int z) {
		pos.set(x, y, z);
		return level.getBlockState(pos);
	}

	@Override
	public boolean probe(int x, int y, int z, BlockProbe out) {
		out.clear();
		if (level == null) return false;
		BlockState state = state(x, y, z);
		Block b = state.getBlock();
		if (b == Blocks.REDSTONE_WIRE) {
			out.kind = RedstoneKind.DUST;
			out.power = state.getValue(BlockStateProperties.POWER);
		} else if (b == Blocks.REPEATER) {
			out.kind = RedstoneKind.REPEATER;
			out.delay = state.getValue(BlockStateProperties.DELAY);
			out.locked = state.getValue(BlockStateProperties.LOCKED);
			out.powered(state.getValue(BlockStateProperties.POWERED));
			out.facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING).ordinal();
		} else if (b == Blocks.COMPARATOR) {
			out.kind = RedstoneKind.COMPARATOR;
			out.subtract = state.getValue(BlockStateProperties.MODE_COMPARATOR) == ComparatorMode.SUBTRACT;
			out.powered(state.getValue(BlockStateProperties.POWERED));
			out.facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING).ordinal();
		} else if (b == Blocks.REDSTONE_TORCH || b == Blocks.REDSTONE_WALL_TORCH) {
			out.kind = RedstoneKind.TORCH;
			out.powered(state.getValue(BlockStateProperties.LIT));
		} else if (b == Blocks.LEVER) {
			out.kind = RedstoneKind.LEVER;
			out.powered(state.getValue(BlockStateProperties.POWERED));
		} else if (b instanceof ButtonBlock) {
			out.kind = RedstoneKind.BUTTON;
			out.powered(state.getValue(BlockStateProperties.POWERED));
		} else if (b instanceof BasePressurePlateBlock) {
			out.kind = RedstoneKind.PLATE;
			if (state.hasProperty(BlockStateProperties.POWER)) out.power = state.getValue(BlockStateProperties.POWER);
			else if (state.hasProperty(BlockStateProperties.POWERED)) out.powered(state.getValue(BlockStateProperties.POWERED));
		} else if (b == Blocks.PISTON || b == Blocks.STICKY_PISTON) {
			out.kind = RedstoneKind.PISTON;
			out.extended = state.getValue(BlockStateProperties.EXTENDED);
			out.powered(out.extended);
			out.received = level.getBestNeighborSignal(pos);
		} else if (b == Blocks.PISTON_HEAD) {
			// Kopf: Werte des Kolbens dahinter
			Direction facing = state.getValue(BlockStateProperties.FACING);
			out.kind = RedstoneKind.PISTON;
			out.extended = true;
			out.powered(true);
			pos.set(x - facing.getStepX(), y - facing.getStepY(), z - facing.getStepZ());
			out.received = level.getBestNeighborSignal(pos);
		} else if (b == Blocks.REDSTONE_LAMP) {
			out.kind = RedstoneKind.LAMP;
			out.powered(state.getValue(BlockStateProperties.LIT));
			out.received = level.getBestNeighborSignal(pos);
		} else if (b == Blocks.OBSERVER) {
			out.kind = RedstoneKind.OBSERVER;
			out.powered(state.getValue(BlockStateProperties.POWERED));
		} else if (b == Blocks.DAYLIGHT_DETECTOR) {
			out.kind = RedstoneKind.DAYLIGHT;
			out.power = state.getValue(BlockStateProperties.POWER);
		} else if (b == Blocks.REDSTONE_BLOCK) {
			out.kind = RedstoneKind.REDSTONE_BLOCK;
			out.powered(true);
		} else {
			probeOther(state, b, out);
		}
		return out.kind != RedstoneKind.NONE;
	}

	/** Alles andere allgemein über die Blockzustands-Eigenschaften (Zielblock, Sculk, Türen, Werfer …). */
	private void probeOther(BlockState state, Block b, BlockProbe out) {
		BlockEntity be = level.getBlockEntity(pos);
		out.container = be instanceof Container;
		boolean power = state.hasProperty(BlockStateProperties.POWER);
		boolean powered = state.hasProperty(BlockStateProperties.POWERED);
		if (state.isSignalSource()) {
			out.kind = RedstoneKind.SOURCE;
			if (power) {
				out.power = state.getValue(BlockStateProperties.POWER);
			} else {
				// z. B. Redstone-Truhe, Haken, Blitzableiter: stärkstes abgegebenes Signal
				int best = 0;
				for (int d = 0; d < DIRS.length; d++) best = Math.max(best, state.getSignal(level, pos, DIRS[d]));
				out.power = best;
				if (powered) out.powered(state.getValue(BlockStateProperties.POWERED));
			}
		} else if (powered) {
			out.kind = RedstoneKind.CONSUMER;
			out.powered(state.getValue(BlockStateProperties.POWERED));
		} else if (state.hasProperty(BlockStateProperties.TRIGGERED)) {
			out.kind = RedstoneKind.CONSUMER;
			out.powered(state.getValue(BlockStateProperties.TRIGGERED));
		} else if (b == Blocks.HOPPER) {
			out.kind = RedstoneKind.CONSUMER;
			out.powered(!state.getValue(BlockStateProperties.ENABLED));
		} else if (out.container) {
			out.kind = RedstoneKind.CONTAINER;
		} else if (be == null && state.hasAnalogOutputSignal()) {
			out.kind = RedstoneKind.ANALOG;
			out.analog = analogOf(state);
		}
		if (out.kind.receives()) out.received = level.getBestNeighborSignal(pos);
	}

	@Override
	public String name(int x, int y, int z) {
		if (level == null) return "";
		return state(x, y, z).getBlock().getName().getString();
	}

	@Override
	public int dustPower(int x, int y, int z) {
		if (level == null) return -1;
		BlockState state = state(x, y, z);
		return state.getBlock() == Blocks.REDSTONE_WIRE ? state.getValue(BlockStateProperties.POWER) : -1;
	}

	@Override
	public int signal(int x, int y, int z, int dir) {
		if (level == null) return 0;
		pos.set(x, y, z);
		return level.getSignal(pos, DIRS[dir]);
	}

	@Override
	public int sideSignal(int x, int y, int z, int dir) {
		if (level == null) return 0;
		BlockState state = state(x, y, z);
		if (!state.isSignalSource()) return 0;
		Block b = state.getBlock();
		if (b == Blocks.REDSTONE_BLOCK) return 15;
		if (b == Blocks.REDSTONE_WIRE) return state.getValue(BlockStateProperties.POWER);
		return state.getDirectSignal(level, pos, DIRS[dir]);
	}

	@Override
	public int analog(int x, int y, int z) {
		if (level == null) return NO_ANALOG;
		BlockState state = state(x, y, z);
		if (!state.hasAnalogOutputSignal()) return NO_ANALOG;
		// Mit Block-Entity (Truhe, Plattenspieler, Pult …) steckt der Wert im Inhalt → unbekannt/gemerkt.
		if (level.getBlockEntity(pos) != null) return CONTAINER_ANALOG;
		return analogOf(state);
	}

	private int analogOf(BlockState state) {
		//? if >=1.21.9 {
		/*return state.getAnalogOutputSignal(level, pos, Direction.NORTH);
		*///?} else
		return state.getAnalogOutputSignal(level, pos);
	}

	@Override
	public boolean conductor(int x, int y, int z) {
		return level != null && state(x, y, z).isRedstoneConductor(level, pos);
	}

	@Override
	public boolean opaque(int x, int y, int z) {
		if (level == null) return false;
		BlockState state = state(x, y, z);
		return state.canOcclude() && state.isRedstoneConductor(level, pos);
	}
}
