package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.circuit.CircuitWorld;
import dev.theredstonee.trsclient.core.circuit.Circuits;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
//? if >=1.9 {
/*import net.minecraft.util.math.BlockPos;
*///?} else
import net.minecraft.util.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Schaltungs-Bibliothek für Forge 1.8.9–1.12.2: Weltzugriff (Registry-Name + Blockzustand mit den alten Namen –
 * {@code unpowered_repeater}, {@code reeds} … übersetzt {@code core.circuit.BlockCatalog}), Blick/Position fürs
 * Platzieren und Inventar für die Materialliste. Nur Lesen.
 */
public final class CircuitProbe implements CircuitWorld, Circuits.Platform {
	private static final CircuitProbe INSTANCE = new CircuitProbe();
	private static final Circuits.Context CONTEXT = new Circuits.Context();

	private World world;
	private final Map<String, ItemStack> stacks = new HashMap<String, ItemStack>();

	private CircuitProbe() {
	}

	/** Einmal je Client-Tick (aus {@link RedstoneProbe#tick}). */
	public static void tick() {
		Circuits circuits = Circuits.get();
		if (!circuits.installed()) {
			TrsClient client = TrsClient.get();
			if (client == null) return;
			circuits.install(client.modules(), INSTANCE, new dev.theredstonee.trsclient.core.input.KeyPresses.Down() {
				@Override
				public boolean isDown(String keyName) {
					return Keys.isDown(keyName);
				}
			}, I18n.configDir());
		}
		World world = Mc.world();
		EntityPlayer player = Mc.player();
		Circuits.Context ctx = CONTEXT;
		INSTANCE.world = world;
		ctx.inWorld = world != null && player != null;
		if (ctx.inWorld) {
			String address = Mc.serverAddress();
			ctx.singleplayer = address == null;
			ctx.serverAddress = address;
			ctx.levelName = Mc.levelName();
			ctx.dimension = Mc.dimensionId();
			ctx.screenOpen = Mc.screen() != null;
			//? if >=1.9 {
			/*net.minecraft.util.math.RayTraceResult hr = Mc.mc().objectMouseOver;
			*///?} else
			net.minecraft.util.MovingObjectPosition hr = Mc.mc().objectMouseOver;
			BlockPos p = hr != null && hr.typeOfHit != null && "BLOCK".equals(hr.typeOfHit.name()) ? hr.getBlockPos() : null;
			ctx.hit = p != null;
			if (p != null) {
				ctx.hitX = p.getX();
				ctx.hitY = p.getY();
				ctx.hitZ = p.getZ();
				ctx.face = hr.sideHit == null ? -1 : hr.sideHit.getIndex();
			}
			ctx.x = player.posX;
			ctx.y = player.posY;
			ctx.z = player.posZ;
			ctx.yaw = player.rotationYaw;
		}
		circuits.tick(ctx.inWorld ? INSTANCE : null, ctx);
	}

	// --- CircuitWorld ---

	@Override
	public String block(int x, int y, int z, Map<String, String> props) {
		World w = world;
		if (w == null) return null;
		BlockPos pos = new BlockPos(x, y, z);
		if (!w.isBlockLoaded(pos)) return null;
		IBlockState state = w.getBlockState(pos);
		for (Object o : state.getProperties().entrySet()) {
			Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
			props.put(((net.minecraft.block.properties.IProperty<?>) e.getKey()).getName(), String.valueOf(e.getValue()));
		}
		return blockId(state.getBlock());
	}

	private static String blockId(Block b) {
		//? if >=1.9 {
		/*ResourceLocation rl = Block.REGISTRY.getNameForObject(b);
		*///?} else
		ResourceLocation rl = (ResourceLocation) Block.blockRegistry.getNameForObject(b);
		return rl == null ? "minecraft:air" : rl.toString();
	}

	@Override
	public boolean conductor(int x, int y, int z) {
		World w = world;
		if (w == null) return false;
		IBlockState state = w.getBlockState(new BlockPos(x, y, z));
		//? if >=1.9 {
		/*return state.isNormalCube();
		*///?} else
		return state.getBlock().isNormalCube();
	}

	@Override
	public boolean legacy() {
		return true;
	}

	// --- Platform ---

	@Override
	public String minecraftVersion() {
		return Mc.version();
	}

	@Override
	public Map<String, Integer> inventory() {
		Map<String, Integer> out = new HashMap<String, Integer>();
		EntityPlayer player = Mc.player();
		if (player == null) return out;
		for (int i = 0, n = player.inventory.getSizeInventory(); i < n; i++) {
			ItemStack s = player.inventory.getStackInSlot(i);
			if (Mc.isEmpty(s)) continue;
			String id = itemId(s.getItem());
			//? if >=1.11 {
			/*int count = s.getCount();
			*///?} else
			int count = s.stackSize;
			Integer old = out.get(id);
			out.put(id, (old == null ? 0 : old) + count);
		}
		return out;
	}

	private static String itemId(Item item) {
		//? if >=1.9 {
		/*ResourceLocation rl = Item.REGISTRY.getNameForObject(item);
		*///?} else
		ResourceLocation rl = (ResourceLocation) Item.itemRegistry.getNameForObject(item);
		if (rl == null) return "";
		String id = rl.toString();
		return id.substring(id.indexOf(':') + 1);
	}

	@Override
	public Object stack(String itemId) {
		ItemStack cached = stacks.get(itemId);
		if (cached != null) return cached;
		Item item = Mc.item(itemId);
		if (item == null) return null;
		ItemStack s = new ItemStack(item);
		stacks.put(itemId, s);
		return s;
	}

	@Override
	public void openMenu() {
		if (Mc.screen() == null) Mc.setScreen(new TrsMenuScreen(null));
	}
}
