package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.core.redstone.BlockProbe;
import dev.theredstonee.trsclient.core.redstone.RedstoneKind;
import dev.theredstonee.trsclient.core.redstone.RedstoneTools;
import dev.theredstonee.trsclient.core.redstone.RedstoneWorld;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBasePressurePlate;
import net.minecraft.block.BlockButton;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.state.properties.BlockStateProperties;
import net.minecraft.state.properties.ComparatorMode;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;

/**
 * Redstone-Adapter für Forge 1.13.2 (MCP-Namen): liest Blockzustände der Client-Welt für
 * {@link RedstoneTools} – gleiche Logik wie in den Mojmap-Versionen ({@code IBlockState} statt
 * {@code BlockState}, {@code POWER_0_15} statt {@code POWER} …).
 */
public final class RedstoneProbe implements RedstoneWorld {
	private static final RedstoneProbe INSTANCE = new RedstoneProbe();
	private static final EnumFacing[] DIRS = EnumFacing.values();

	private World world;
	private int[] counts = new int[54];
	private int[] maxStack = new int[54];

	private RedstoneProbe() {
	}

	/** Einmal je Client-Tick: angeschauten Block, Auge und (bei offenem Behälter) dessen Inhalt weitergeben. */
	public static void tick(RedstoneTools tools) {
		Minecraft mc = Minecraft.getInstance();
		World world = mc.world;
		EntityPlayer player = mc.player;
		if (world == null || player == null) {
			INSTANCE.world = null;
			tools.tick(null, null, false, 0, 0, 0, 0, 0, 0);
			return;
		}
		INSTANCE.world = world;
		RayTraceResult hr = mc.objectMouseOver;
		BlockPos p = hr != null && hr.type == RayTraceResult.Type.BLOCK ? hr.getBlockPos() : null;
		boolean hit = p != null;
		int hx = hit ? p.getX() : 0, hy = hit ? p.getY() : 0, hz = hit ? p.getZ() : 0;
		if (hit && mc.currentScreen instanceof GuiContainer) INSTANCE.snapshot(tools, player, hx, hy, hz);
		Entity view = mc.getRenderViewEntity() != null ? mc.getRenderViewEntity() : player;
		tools.tick(INSTANCE, world, hit, hx, hy, hz, view.posX, view.posY + view.getEyeHeight(), view.posZ);
	}

	/** Inhalt des gerade offenen Behälters merken (nur so kennt der Client ihn). */
	private void snapshot(RedstoneTools tools, EntityPlayer player, int x, int y, int z) {
		Container menu = player.openContainer;
		if (menu == null || menu == player.inventoryContainer) return;
		TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
		if (!(te instanceof IInventory)) return;
		int size = ((IInventory) te).getSizeInventory();
		IInventory container = null;
		int n = 0;
		for (int i = 0, total = menu.inventorySlots.size(); i < total; i++) {
			Slot slot = menu.inventorySlots.get(i);
			if (slot.inventory instanceof InventoryPlayer) continue;
			if (container == null) container = slot.inventory;
			else if (slot.inventory != container) continue;
			if (n >= counts.length) {
				counts = java.util.Arrays.copyOf(counts, n * 2);
				maxStack = java.util.Arrays.copyOf(maxStack, n * 2);
			}
			ItemStack stack = slot.getStack();
			counts[n] = stack.isEmpty() ? 0 : stack.getCount();
			maxStack[n] = stack.isEmpty() ? 64 : Math.min(container.getInventoryStackLimit(), stack.getMaxStackSize());
			n++;
		}
		if (container == null || (n != size && n != size * 2)) return;
		tools.rememberContainer(x, y, z, counts, maxStack, n);
	}

	private IBlockState state(BlockPos pos) {
		return world.getBlockState(pos);
	}

	@Override
	public boolean probe(int x, int y, int z, BlockProbe out) {
		out.clear();
		if (world == null) return false;
		BlockPos pos = new BlockPos(x, y, z);
		IBlockState state = state(pos);
		Block b = state.getBlock();
		if (b == Blocks.REDSTONE_WIRE) {
			out.kind = RedstoneKind.DUST;
			out.power = state.get(BlockStateProperties.POWER_0_15);
		} else if (b == Blocks.REPEATER) {
			out.kind = RedstoneKind.REPEATER;
			out.delay = state.get(BlockStateProperties.DELAY_1_4);
			out.locked = state.get(BlockStateProperties.LOCKED);
			out.powered(state.get(BlockStateProperties.POWERED));
			out.facing = state.get(BlockStateProperties.HORIZONTAL_FACING).getIndex();
		} else if (b == Blocks.COMPARATOR) {
			out.kind = RedstoneKind.COMPARATOR;
			out.subtract = state.get(BlockStateProperties.COMPARATOR_MODE) == ComparatorMode.SUBTRACT;
			out.powered(state.get(BlockStateProperties.POWERED));
			out.facing = state.get(BlockStateProperties.HORIZONTAL_FACING).getIndex();
		} else if (b == Blocks.REDSTONE_TORCH || b == Blocks.REDSTONE_WALL_TORCH) {
			out.kind = RedstoneKind.TORCH;
			out.powered(state.get(BlockStateProperties.LIT));
		} else if (b == Blocks.LEVER) {
			out.kind = RedstoneKind.LEVER;
			out.powered(state.get(BlockStateProperties.POWERED));
		} else if (b instanceof BlockButton) {
			out.kind = RedstoneKind.BUTTON;
			out.powered(state.get(BlockStateProperties.POWERED));
		} else if (b instanceof BlockBasePressurePlate) {
			out.kind = RedstoneKind.PLATE;
			if (state.has(BlockStateProperties.POWER_0_15)) out.power = state.get(BlockStateProperties.POWER_0_15);
			else if (state.has(BlockStateProperties.POWERED)) out.powered(state.get(BlockStateProperties.POWERED));
		} else if (b == Blocks.PISTON || b == Blocks.STICKY_PISTON) {
			out.kind = RedstoneKind.PISTON;
			out.extended = state.get(BlockStateProperties.EXTENDED);
			out.powered(out.extended);
			out.received = world.getRedstonePowerFromNeighbors(pos);
		} else if (b == Blocks.PISTON_HEAD) {
			EnumFacing facing = state.get(BlockStateProperties.FACING);
			out.kind = RedstoneKind.PISTON;
			out.extended = true;
			out.powered(true);
			out.received = world.getRedstonePowerFromNeighbors(pos.offset(facing.getOpposite()));
		} else if (b == Blocks.REDSTONE_LAMP) {
			out.kind = RedstoneKind.LAMP;
			out.powered(state.get(BlockStateProperties.LIT));
			out.received = world.getRedstonePowerFromNeighbors(pos);
		} else if (b == Blocks.OBSERVER) {
			out.kind = RedstoneKind.OBSERVER;
			out.powered(state.get(BlockStateProperties.POWERED));
		} else if (b == Blocks.DAYLIGHT_DETECTOR) {
			out.kind = RedstoneKind.DAYLIGHT;
			out.power = state.get(BlockStateProperties.POWER_0_15);
		} else if (b == Blocks.REDSTONE_BLOCK) {
			out.kind = RedstoneKind.REDSTONE_BLOCK;
			out.powered(true);
		} else {
			probeOther(pos, state, b, out);
		}
		return out.kind != RedstoneKind.NONE;
	}

	private void probeOther(BlockPos pos, IBlockState state, Block b, BlockProbe out) {
		TileEntity te = world.getTileEntity(pos);
		out.container = te instanceof IInventory;
		boolean power = state.has(BlockStateProperties.POWER_0_15);
		boolean powered = state.has(BlockStateProperties.POWERED);
		if (state.canProvidePower()) {
			out.kind = RedstoneKind.SOURCE;
			if (power) {
				out.power = state.get(BlockStateProperties.POWER_0_15);
			} else {
				int best = 0;
				for (EnumFacing d : DIRS) best = Math.max(best, state.getWeakPower(world, pos, d));
				out.power = best;
				if (powered) out.powered(state.get(BlockStateProperties.POWERED));
			}
		} else if (powered) {
			out.kind = RedstoneKind.CONSUMER;
			out.powered(state.get(BlockStateProperties.POWERED));
		} else if (state.has(BlockStateProperties.TRIGGERED)) {
			out.kind = RedstoneKind.CONSUMER;
			out.powered(state.get(BlockStateProperties.TRIGGERED));
		} else if (b == Blocks.HOPPER) {
			out.kind = RedstoneKind.CONSUMER;
			out.powered(!state.get(BlockStateProperties.ENABLED));
		} else if (out.container) {
			out.kind = RedstoneKind.CONTAINER;
		} else if (te == null && state.hasComparatorInputOverride()) {
			out.kind = RedstoneKind.ANALOG;
			out.analog = state.getComparatorInputOverride(world, pos);
		}
		if (out.kind.receives()) out.received = world.getRedstonePowerFromNeighbors(pos);
	}

	@Override
	public String name(int x, int y, int z) {
		if (world == null) return "";
		return state(new BlockPos(x, y, z)).getBlock().getNameTextComponent().getString();
	}

	@Override
	public int dustPower(int x, int y, int z) {
		if (world == null) return -1;
		IBlockState state = state(new BlockPos(x, y, z));
		return state.getBlock() == Blocks.REDSTONE_WIRE ? state.get(BlockStateProperties.POWER_0_15) : -1;
	}

	@Override
	public int signal(int x, int y, int z, int dir) {
		return world == null ? 0 : world.getRedstonePower(new BlockPos(x, y, z), DIRS[dir]);
	}

	@Override
	public int sideSignal(int x, int y, int z, int dir) {
		if (world == null) return 0;
		BlockPos pos = new BlockPos(x, y, z);
		IBlockState state = state(pos);
		if (!state.canProvidePower()) return 0;
		Block b = state.getBlock();
		if (b == Blocks.REDSTONE_BLOCK) return 15;
		if (b == Blocks.REDSTONE_WIRE) return state.get(BlockStateProperties.POWER_0_15);
		return world.getStrongPower(pos, DIRS[dir]);
	}

	@Override
	public int analog(int x, int y, int z) {
		if (world == null) return NO_ANALOG;
		BlockPos pos = new BlockPos(x, y, z);
		IBlockState state = state(pos);
		if (!state.hasComparatorInputOverride()) return NO_ANALOG;
		if (world.getTileEntity(pos) != null) return CONTAINER_ANALOG;
		return state.getComparatorInputOverride(world, pos);
	}

	@Override
	public boolean conductor(int x, int y, int z) {
		return world != null && state(new BlockPos(x, y, z)).isNormalCube();
	}

	@Override
	public boolean opaque(int x, int y, int z) {
		if (world == null) return false;
		BlockPos pos = new BlockPos(x, y, z);
		IBlockState state = state(pos);
		return state.isNormalCube() && state.isOpaqueCube(world, pos);
	}
}
