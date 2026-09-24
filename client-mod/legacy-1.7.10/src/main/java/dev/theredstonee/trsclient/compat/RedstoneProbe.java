package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.core.redstone.BlockProbe;
import dev.theredstonee.trsclient.core.redstone.Dir;
import dev.theredstonee.trsclient.core.redstone.RedstoneKind;
import dev.theredstonee.trsclient.core.redstone.RedstoneTools;
import dev.theredstonee.trsclient.core.redstone.RedstoneWorld;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBasePressurePlate;
import net.minecraft.block.BlockButton;
import net.minecraft.block.BlockPressurePlateWeighted;
import net.minecraft.block.BlockRedstoneDiode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

/**
 * Redstone-Adapter für Minecraft 1.7.10: dort gibt es noch keine Blockzustände, sondern Block + 4 Bit
 * Metadaten. Hier wird beides auf {@link BlockProbe} abgebildet (Staub: Metadaten = Stärke; Verstärker:
 * Richtung in Bit 0–1, Verzögerung − 1 in Bit 2–3; Komparator: Bit 2 = Subtrahieren, Bit 3 = an; Hebel,
 * Knöpfe, Kolben, Haken, Werfer, Trichter: Bit 3 …). An/aus ist bei Fackel, Verstärker, Komparator und
 * Lampe ein eigener Block. Richtungen der Dioden: 0 Süden, 1 Westen, 2 Norden, 3 Osten.
 */
public final class RedstoneProbe implements RedstoneWorld {
	private static final RedstoneProbe INSTANCE = new RedstoneProbe();

	private static final int POWERED_RAIL = 27;
	private static final int DETECTOR_RAIL = 28;
	private static final int STICKY_PISTON = 29;
	private static final int PISTON = 33;
	private static final int PISTON_HEAD = 34;
	private static final int WIRE = 55;
	private static final int LEVER = 69;
	private static final int TORCH_OFF = 75;
	private static final int TORCH_ON = 76;
	private static final int REPEATER_OFF = 93;
	private static final int REPEATER_ON = 94;
	private static final int LAMP_OFF = 123;
	private static final int LAMP_ON = 124;
	private static final int TRIPWIRE_HOOK = 131;
	private static final int COMPARATOR_OFF = 149;
	private static final int COMPARATOR_ON = 150;
	private static final int DAYLIGHT = 151;
	private static final int REDSTONE_BLOCK = 152;
	private static final int HOPPER = 154;
	private static final int ACTIVATOR_RAIL = 157;
	private static final int DISPENSER = 23;
	private static final int DROPPER = 158;
	/** Diodenrichtung (Metadaten Bit 0–1) → Richtung des Eingangs ({@link Dir}). */
	private static final int[] DIODE_FACING = {Dir.SOUTH, Dir.WEST, Dir.NORTH, Dir.EAST};

	private World world;
	private int[] counts = new int[54];
	private int[] maxStack = new int[54];

	private RedstoneProbe() {
	}

	/** Einmal je Client-Tick: angeschauten Block, Auge und (bei offenem Behälter) dessen Inhalt weitergeben. */
	public static void tick(RedstoneTools tools) {
		Minecraft mc = Minecraft.getMinecraft();
		World world = mc.theWorld;
		EntityPlayer player = mc.thePlayer;
		if (world == null || player == null) {
			INSTANCE.world = null;
			tools.tick(null, null, false, 0, 0, 0, 0, 0, 0);
			return;
		}
		INSTANCE.world = world;
		MovingObjectPosition hr = mc.objectMouseOver;
		boolean hit = hr != null && hr.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
		int hx = hit ? hr.blockX : 0, hy = hit ? hr.blockY : 0, hz = hit ? hr.blockZ : 0;
		if (hit && mc.currentScreen instanceof GuiContainer) INSTANCE.snapshot(tools, player, hx, hy, hz);
		EntityLivingBase view = mc.renderViewEntity != null ? mc.renderViewEntity : player;
		// 1.7.10: posY des eigenen Spielers ist schon Augenhöhe (yOffset), andere Entities stehen auf den Füßen.
		double eyeY = view == player ? view.posY : view.posY + view.getEyeHeight();
		tools.tick(INSTANCE, world, hit, hx, hy, hz, view.posX, eyeY, view.posZ);
	}

	/** Staubstärke an (x, y, z) in der aktuellen Welt (für den Selbsttest). */
	public static int dustPowerAt(int x, int y, int z) {
		return INSTANCE.dustPower(x, y, z);
	}

	/** Inhalt des gerade offenen Behälters merken (nur so kennt der Client ihn). */
	private void snapshot(RedstoneTools tools, EntityPlayer player, int x, int y, int z) {
		Container menu = player.openContainer;
		if (menu == null || menu == player.inventoryContainer) return;
		TileEntity te = world.getTileEntity(x, y, z);
		if (!(te instanceof IInventory)) return;
		int size = ((IInventory) te).getSizeInventory();
		IInventory container = null;
		int n = 0;
		for (int i = 0, total = menu.inventorySlots.size(); i < total; i++) {
			Slot slot = (Slot) menu.inventorySlots.get(i);
			if (slot.inventory instanceof InventoryPlayer) continue;
			if (container == null) container = slot.inventory;
			else if (slot.inventory != container) continue;
			if (n >= counts.length) {
				counts = java.util.Arrays.copyOf(counts, n * 2);
				maxStack = java.util.Arrays.copyOf(maxStack, n * 2);
			}
			ItemStack stack = slot.getStack();
			boolean empty = stack == null || stack.stackSize <= 0;
			counts[n] = empty ? 0 : stack.stackSize;
			maxStack[n] = empty ? 64 : Math.min(container.getInventoryStackLimit(), stack.getMaxStackSize());
			n++;
		}
		if (container == null || (n != size && n != size * 2)) return;
		tools.rememberContainer(x, y, z, counts, maxStack, n);
	}

	@Override
	public boolean probe(int x, int y, int z, BlockProbe out) {
		out.clear();
		if (world == null) return false;
		Block b = world.getBlock(x, y, z);
		int id = Block.getIdFromBlock(b);
		int meta = world.getBlockMetadata(x, y, z);
		switch (id) {
			case WIRE:
				out.kind = RedstoneKind.DUST;
				out.power = meta;
				break;
			case REPEATER_OFF:
			case REPEATER_ON:
				out.kind = RedstoneKind.REPEATER;
				out.delay = ((meta >> 2) & 3) + 1;
				out.locked = b instanceof BlockRedstoneDiode && ((BlockRedstoneDiode) b).func_149910_g(world, x, y, z, meta);
				out.powered(id == REPEATER_ON);
				out.facing = DIODE_FACING[meta & 3];
				break;
			case COMPARATOR_OFF:
			case COMPARATOR_ON:
				out.kind = RedstoneKind.COMPARATOR;
				out.subtract = (meta & 4) != 0;
				out.powered((meta & 8) != 0 || id == COMPARATOR_ON);
				out.facing = DIODE_FACING[meta & 3];
				break;
			case TORCH_OFF:
			case TORCH_ON:
				out.kind = RedstoneKind.TORCH;
				out.powered(id == TORCH_ON);
				break;
			case LEVER:
				out.kind = RedstoneKind.LEVER;
				out.powered((meta & 8) != 0);
				break;
			case PISTON:
			case STICKY_PISTON:
				out.kind = RedstoneKind.PISTON;
				out.extended = (meta & 8) != 0;
				out.powered(out.extended);
				out.received = world.getStrongestIndirectPower(x, y, z);
				break;
			case PISTON_HEAD: {
				out.kind = RedstoneKind.PISTON;
				out.extended = true;
				out.powered(true);
				int f = meta & 7;
				if (f < 6) out.received = world.getStrongestIndirectPower(x - Dir.dx(f), y - Dir.dy(f), z - Dir.dz(f));
				break;
			}
			case LAMP_OFF:
			case LAMP_ON:
				out.kind = RedstoneKind.LAMP;
				out.powered(id == LAMP_ON);
				out.received = world.getStrongestIndirectPower(x, y, z);
				break;
			case DAYLIGHT:
				out.kind = RedstoneKind.DAYLIGHT;
				out.power = meta;
				break;
			case REDSTONE_BLOCK:
				out.kind = RedstoneKind.REDSTONE_BLOCK;
				out.powered(true);
				break;
			case TRIPWIRE_HOOK:
			case DETECTOR_RAIL:
				out.kind = RedstoneKind.SOURCE;
				out.powered((meta & 8) != 0);
				out.power = out.powered ? 15 : 0;
				break;
			case POWERED_RAIL:
			case ACTIVATOR_RAIL:
			case DISPENSER:
			case DROPPER:
			case HOPPER:
				// Bit 3: angesteuert (Trichter: gesperrt)
				out.kind = RedstoneKind.CONSUMER;
				out.powered((meta & 8) != 0);
				out.received = world.getStrongestIndirectPower(x, y, z);
				break;
			default:
				probeOther(x, y, z, b, meta, out);
		}
		if (out.kind != RedstoneKind.NONE && !out.container) out.container = world.getTileEntity(x, y, z) instanceof IInventory;
		return out.kind != RedstoneKind.NONE;
	}

	private void probeOther(int x, int y, int z, Block b, int meta, BlockProbe out) {
		TileEntity te = world.getTileEntity(x, y, z);
		out.container = te instanceof IInventory;
		if (b instanceof BlockButton) {
			out.kind = RedstoneKind.BUTTON;
			out.powered((meta & 8) != 0);
		} else if (b instanceof BlockPressurePlateWeighted) {
			out.kind = RedstoneKind.PLATE;
			out.power = meta;
		} else if (b instanceof BlockBasePressurePlate) {
			out.kind = RedstoneKind.PLATE;
			out.powered(meta == 1);
		} else if (b.canProvidePower()) {
			// z. B. Redstone-Truhe: stärkstes abgegebenes Signal
			out.kind = RedstoneKind.SOURCE;
			int best = 0;
			for (int side = 0; side < 6; side++) best = Math.max(best, b.isProvidingWeakPower(world, x, y, z, side));
			out.power = best;
		} else if (out.container) {
			out.kind = RedstoneKind.CONTAINER;
		} else if (te == null && b.hasComparatorInputOverride()) {
			out.kind = RedstoneKind.ANALOG;
			out.analog = b.getComparatorInputOverride(world, x, y, z, 0);
		}
	}

	@Override
	public String name(int x, int y, int z) {
		if (world == null) return "";
		return world.getBlock(x, y, z).getLocalizedName();
	}

	@Override
	public int dustPower(int x, int y, int z) {
		if (world == null) return -1;
		return Block.getIdFromBlock(world.getBlock(x, y, z)) == WIRE ? world.getBlockMetadata(x, y, z) : -1;
	}

	@Override
	public int signal(int x, int y, int z, int dir) {
		return world == null ? 0 : world.getIndirectPowerLevelTo(x, y, z, dir);
	}

	@Override
	public int sideSignal(int x, int y, int z, int dir) {
		if (world == null) return 0;
		Block b = world.getBlock(x, y, z);
		if (!b.canProvidePower()) return 0;
		int id = Block.getIdFromBlock(b);
		if (id == REDSTONE_BLOCK) return 15;
		if (id == WIRE) return world.getBlockMetadata(x, y, z);
		return world.isBlockProvidingPowerTo(x, y, z, dir);
	}

	@Override
	public int analog(int x, int y, int z) {
		if (world == null) return NO_ANALOG;
		Block b = world.getBlock(x, y, z);
		if (!b.hasComparatorInputOverride()) return NO_ANALOG;
		if (world.getTileEntity(x, y, z) != null) return CONTAINER_ANALOG;
		return b.getComparatorInputOverride(world, x, y, z, 0);
	}

	@Override
	public boolean conductor(int x, int y, int z) {
		return world != null && world.getBlock(x, y, z).isNormalCube();
	}

	@Override
	public boolean opaque(int x, int y, int z) {
		if (world == null) return false;
		Block b = world.getBlock(x, y, z);
		return b.isOpaqueCube() && b.isNormalCube();
	}
}
