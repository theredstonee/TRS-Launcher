package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.core.redstone.BlockProbe;
import dev.theredstonee.trsclient.core.redstone.RedstoneKind;
import dev.theredstonee.trsclient.core.redstone.RedstoneTools;
import dev.theredstonee.trsclient.core.redstone.RedstoneWorld;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBasePressurePlate;
import net.minecraft.block.BlockButton;
import net.minecraft.block.BlockRedstoneWire;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;
//? if >=1.9 {
/*import net.minecraft.util.math.BlockPos;
*///?} else
import net.minecraft.util.BlockPos;

/**
 * Redstone-Adapter für Forge 1.8.9–1.12.2: liest Blockzustände der Client-Welt für {@link RedstoneTools}.
 * Bauteile werden über ihre (seit 1.8 unveränderten) Block-IDs erkannt, Werte über die Namen der
 * Blockzustands-Eigenschaften ("power", "powered", "delay" …) – das ist in allen fünf Versionen gleich,
 * obwohl die Klassen- und Feldnamen der Blöcke wandern. Die Signal-Methoden liegen bis 1.8.9 am Block,
 * ab 1.9 am Blockzustand.
 */
public final class RedstoneProbe implements RedstoneWorld {
	private static final RedstoneProbe INSTANCE = new RedstoneProbe();

	// Block-IDs (Minecraft 1.8–1.12)
	private static final int STICKY_PISTON = 29;
	private static final int PISTON = 33;
	private static final int PISTON_HEAD = 34;
	private static final int LEVER = 69;
	private static final int TORCH_OFF = 75;
	private static final int TORCH_ON = 76;
	private static final int REPEATER_OFF = 93;
	private static final int REPEATER_ON = 94;
	private static final int LAMP_OFF = 123;
	private static final int LAMP_ON = 124;
	private static final int COMPARATOR_OFF = 149;
	private static final int COMPARATOR_ON = 150;
	private static final int DAYLIGHT = 151;
	private static final int REDSTONE_BLOCK = 152;
	private static final int HOPPER = 154;
	private static final int DAYLIGHT_INVERTED = 178;
	private static final int OBSERVER = 218;

	private World world;
	private int[] counts = new int[54];
	private int[] maxStack = new int[54];

	private RedstoneProbe() {
	}

	/** Einmal je Client-Tick: angeschauten Block, Auge und (bei offenem Behälter) dessen Inhalt weitergeben. */
	public static void tick(RedstoneTools tools) {
		World world = Mc.world();
		EntityPlayer player = Mc.player();
		if (world == null || player == null) {
			INSTANCE.world = null;
			tools.tick(null, null, false, 0, 0, 0, 0, 0, 0);
			return;
		}
		INSTANCE.world = world;
		//? if >=1.9 {
		/*net.minecraft.util.math.RayTraceResult hr = Mc.mc().objectMouseOver;
		*///?} else
		net.minecraft.util.MovingObjectPosition hr = Mc.mc().objectMouseOver;
		BlockPos p = hr != null && hr.typeOfHit != null && "BLOCK".equals(hr.typeOfHit.name()) ? hr.getBlockPos() : null;
		boolean hit = p != null;
		int hx = hit ? p.getX() : 0, hy = hit ? p.getY() : 0, hz = hit ? p.getZ() : 0;
		if (hit && Mc.screen() instanceof GuiContainer) INSTANCE.snapshot(tools, player, hx, hy, hz);
		Entity view = Mc.viewEntity();
		if (view == null) view = player;
		tools.tick(INSTANCE, world, hit, hx, hy, hz, view.posX, view.posY + view.getEyeHeight(), view.posZ);
	}

	/** Staubstärke an (x, y, z) in der aktuellen Welt (für den Selbsttest). */
	public static int dustPowerAt(int x, int y, int z) {
		return INSTANCE.dustPower(x, y, z);
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
			boolean empty = Mc.isEmpty(stack);
			counts[n] = empty ? 0 : count(stack);
			maxStack[n] = empty ? 64 : Math.min(container.getInventoryStackLimit(), stack.getMaxStackSize());
			n++;
		}
		if (container == null || (n != size && n != size * 2)) return;
		tools.rememberContainer(x, y, z, counts, maxStack, n);
	}

	private static int count(ItemStack stack) {
		//? if >=1.11 {
		/*return stack.getCount();
		*///?} else
		return stack.stackSize;
	}

	// --- Blockzustand ---

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static IProperty property(IBlockState state, String name) {
		for (Object key : state.getProperties().keySet()) {
			IProperty p = (IProperty) key;
			if (name.equals(p.getName())) return p;
		}
		return null;
	}

	/** Zahl-Eigenschaft (z. B. "power") oder -1. */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static int intValue(IBlockState state, String name) {
		IProperty p = property(state, name);
		if (p == null) return -1;
		Object v = state.getValue(p);
		return v instanceof Integer ? ((Integer) v).intValue() : -1;
	}

	/** Wahr/falsch-Eigenschaft (z. B. "powered"); null, wenn der Block sie nicht hat. */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Boolean boolValue(IBlockState state, String name) {
		IProperty p = property(state, name);
		if (p == null) return null;
		Object v = state.getValue(p);
		return v instanceof Boolean ? (Boolean) v : null;
	}

	/** Wert einer Eigenschaft als Text ("subtract", "north" …) oder null. */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static String textValue(IBlockState state, String name) {
		IProperty p = property(state, name);
		return p == null ? null : p.getName(state.getValue(p));
	}

	/** Richtung aus der Eigenschaft "facing" als Index (unten 0 … Osten 5) oder -1. */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static int facing(IBlockState state) {
		IProperty p = property(state, "facing");
		if (p == null) return -1;
		Object v = state.getValue(p);
		return v instanceof EnumFacing ? ((EnumFacing) v).getIndex() : -1;
	}

	private IBlockState state(int x, int y, int z) {
		return world.getBlockState(new BlockPos(x, y, z));
	}

	@Override
	public boolean probe(int x, int y, int z, BlockProbe out) {
		out.clear();
		if (world == null) return false;
		BlockPos pos = new BlockPos(x, y, z);
		IBlockState state = world.getBlockState(pos);
		Block b = state.getBlock();
		int id = Block.getIdFromBlock(b);
		if (b instanceof BlockRedstoneWire) {
			out.kind = RedstoneKind.DUST;
			out.power = intValue(state, "power");
		} else if (id == REPEATER_OFF || id == REPEATER_ON) {
			out.kind = RedstoneKind.REPEATER;
			out.delay = intValue(state, "delay");
			out.locked = Boolean.TRUE.equals(boolValue(state, "locked"));
			out.powered(id == REPEATER_ON);
			out.facing = facing(state);
		} else if (id == COMPARATOR_OFF || id == COMPARATOR_ON) {
			out.kind = RedstoneKind.COMPARATOR;
			out.subtract = "subtract".equals(textValue(state, "mode"));
			Boolean on = boolValue(state, "powered");
			out.powered(on != null ? on.booleanValue() : id == COMPARATOR_ON);
			out.facing = facing(state);
		} else if (id == TORCH_OFF || id == TORCH_ON) {
			out.kind = RedstoneKind.TORCH;
			out.powered(id == TORCH_ON);
		} else if (id == LEVER) {
			out.kind = RedstoneKind.LEVER;
			out.powered(Boolean.TRUE.equals(boolValue(state, "powered")));
		} else if (b instanceof BlockButton) {
			out.kind = RedstoneKind.BUTTON;
			out.powered(Boolean.TRUE.equals(boolValue(state, "powered")));
		} else if (b instanceof BlockBasePressurePlate) {
			out.kind = RedstoneKind.PLATE;
			int power = intValue(state, "power");
			if (power >= 0) out.power = power;
			else out.powered(Boolean.TRUE.equals(boolValue(state, "powered")));
		} else if (id == PISTON || id == STICKY_PISTON) {
			out.kind = RedstoneKind.PISTON;
			out.extended = Boolean.TRUE.equals(boolValue(state, "extended"));
			out.powered(out.extended);
			out.received = neighborSignal(pos);
		} else if (id == PISTON_HEAD) {
			out.kind = RedstoneKind.PISTON;
			out.extended = true;
			out.powered(true);
			int f = facing(state);
			if (f >= 0) {
				EnumFacing d = EnumFacing.VALUES[f];
				out.received = neighborSignal(pos.offset(d.getOpposite()));
			}
		} else if (id == LAMP_OFF || id == LAMP_ON) {
			out.kind = RedstoneKind.LAMP;
			out.powered(id == LAMP_ON);
			out.received = neighborSignal(pos);
		} else if (id == OBSERVER) {
			out.kind = RedstoneKind.OBSERVER;
			out.powered(Boolean.TRUE.equals(boolValue(state, "powered")));
		} else if (id == DAYLIGHT || id == DAYLIGHT_INVERTED) {
			out.kind = RedstoneKind.DAYLIGHT;
			out.power = intValue(state, "power");
		} else if (id == REDSTONE_BLOCK) {
			out.kind = RedstoneKind.REDSTONE_BLOCK;
			out.powered(true);
		} else {
			probeOther(pos, state, id, out);
		}
		return out.kind != RedstoneKind.NONE;
	}

	/** Alles andere über die Eigenschaften: Haken, Türen, Werfer, Schienen, Notenblock, Behälter … */
	private void probeOther(BlockPos pos, IBlockState state, int id, BlockProbe out) {
		TileEntity te = world.getTileEntity(pos);
		out.container = te instanceof IInventory;
		int power = intValue(state, "power");
		Boolean powered = boolValue(state, "powered");
		Boolean triggered = boolValue(state, "triggered");
		if (canProvidePower(state)) {
			out.kind = RedstoneKind.SOURCE;
			if (power >= 0) {
				out.power = power;
			} else {
				int best = 0;
				for (EnumFacing f : EnumFacing.VALUES) best = Math.max(best, weakPower(state, pos, f));
				out.power = best;
				if (powered != null) out.powered(powered.booleanValue());
			}
		} else if (powered != null) {
			out.kind = RedstoneKind.CONSUMER;
			out.powered(powered.booleanValue());
		} else if (triggered != null) {
			out.kind = RedstoneKind.CONSUMER;
			out.powered(triggered.booleanValue());
		} else if (id == HOPPER) {
			out.kind = RedstoneKind.CONSUMER;
			out.powered(Boolean.FALSE.equals(boolValue(state, "enabled")));
		} else if (out.container) {
			out.kind = RedstoneKind.CONTAINER;
		} else if (te == null && hasComparatorOutput(state)) {
			out.kind = RedstoneKind.ANALOG;
			out.analog = comparatorOutput(state, pos);
		}
		if (out.kind.receives()) out.received = neighborSignal(pos);
	}

	// --- Versionsweichen: Signal-Methoden am Block (1.8.9) bzw. am Blockzustand (ab 1.9) ---

	private static boolean canProvidePower(IBlockState state) {
		//? if >=1.9 {
		/*return state.canProvidePower();
		*///?} else
		return state.getBlock().canProvidePower();
	}

	private int weakPower(IBlockState state, BlockPos pos, EnumFacing side) {
		//? if >=1.9 {
		/*return state.getWeakPower(world, pos, side);
		*///?} else
		return state.getBlock().getWeakPower(world, pos, state, side);
	}

	private static boolean hasComparatorOutput(IBlockState state) {
		//? if >=1.9 {
		/*return state.hasComparatorInputOverride();
		*///?} else
		return state.getBlock().hasComparatorInputOverride();
	}

	private int comparatorOutput(IBlockState state, BlockPos pos) {
		//? if >=1.9 {
		/*return state.getComparatorInputOverride(world, pos);
		*///?} else
		return state.getBlock().getComparatorInputOverride(world, pos);
	}

	private static boolean normalCube(IBlockState state) {
		//? if >=1.9 {
		/*return state.isNormalCube();
		*///?} else
		return state.getBlock().isNormalCube();
	}

	private static boolean opaqueCube(IBlockState state) {
		//? if >=1.9 {
		/*return state.isOpaqueCube();
		*///?} else
		return state.getBlock().isOpaqueCube();
	}

	private int neighborSignal(BlockPos pos) {
		//? if >=1.12 {
		/*return world.getRedstonePowerFromNeighbors(pos);
		*///?} else
		return world.isBlockIndirectlyGettingPowered(pos);
	}

	// --- RedstoneWorld ---

	@Override
	public String name(int x, int y, int z) {
		if (world == null) return "";
		return state(x, y, z).getBlock().getLocalizedName();
	}

	@Override
	public int dustPower(int x, int y, int z) {
		if (world == null) return -1;
		IBlockState state = state(x, y, z);
		if (!(state.getBlock() instanceof BlockRedstoneWire)) return -1;
		return ((Integer) state.getValue(BlockRedstoneWire.POWER)).intValue();
	}

	@Override
	public int signal(int x, int y, int z, int dir) {
		if (world == null) return 0;
		return world.getRedstonePower(new BlockPos(x, y, z), EnumFacing.VALUES[dir]);
	}

	@Override
	public int sideSignal(int x, int y, int z, int dir) {
		if (world == null) return 0;
		IBlockState state = state(x, y, z);
		if (!canProvidePower(state)) return 0;
		Block b = state.getBlock();
		if (Block.getIdFromBlock(b) == REDSTONE_BLOCK) return 15;
		if (b instanceof BlockRedstoneWire) return ((Integer) state.getValue(BlockRedstoneWire.POWER)).intValue();
		return world.getStrongPower(new BlockPos(x, y, z), EnumFacing.VALUES[dir]);
	}

	@Override
	public int analog(int x, int y, int z) {
		if (world == null) return NO_ANALOG;
		BlockPos pos = new BlockPos(x, y, z);
		IBlockState state = world.getBlockState(pos);
		if (!hasComparatorOutput(state)) return NO_ANALOG;
		if (world.getTileEntity(pos) != null) return CONTAINER_ANALOG;
		return comparatorOutput(state, pos);
	}

	@Override
	public boolean conductor(int x, int y, int z) {
		return world != null && normalCube(state(x, y, z));
	}

	@Override
	public boolean opaque(int x, int y, int z) {
		if (world == null) return false;
		IBlockState state = state(x, y, z);
		return opaqueCube(state) && normalCube(state);
	}
}
