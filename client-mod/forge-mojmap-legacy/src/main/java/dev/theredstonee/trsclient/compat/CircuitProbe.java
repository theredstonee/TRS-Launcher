package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.circuit.CircuitWorld;
import dev.theredstonee.trsclient.core.circuit.Circuits;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.Map;

/**
 * Schaltungs-Bibliothek für die Mojmap-Versionen 1.14.4–26.3 (Fabric, NeoForge, Forge – dieselbe Datei):
 * Weltzugriff für den Abgleich Vorlage ↔ Welt (Registry-Name + Blockzustand), Blick/Position fürs Platzieren,
 * Inventar für die Materialliste. Nur Lesen, nichts wird gesendet.
 */
public final class CircuitProbe implements CircuitWorld, Circuits.Platform {
	private static final CircuitProbe INSTANCE = new CircuitProbe();
	private static final Circuits.Context CONTEXT = new Circuits.Context();

	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private Level level;
	private Map<String, Item> items;
	private final Map<String, ItemStack> stacks = new HashMap<String, ItemStack>();

	private CircuitProbe() {
	}

	/** Einmal je Client-Tick (aus {@link RedstoneProbe#tick}). */
	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		Circuits circuits = Circuits.get();
		if (!circuits.installed()) {
			TrsClient client = TrsClient.get();
			if (client == null) return;
			circuits.install(client.modules(), INSTANCE, Keys::isDown, I18n.configDir());
		}
		Circuits.Context ctx = CONTEXT;
		INSTANCE.level = mc.level;
		ctx.inWorld = mc.level != null && mc.player != null;
		if (ctx.inWorld) {
			String address = Mc.serverAddress();
			ctx.singleplayer = address == null;
			ctx.serverAddress = address;
			ctx.levelName = Mc.levelName();
			ctx.dimension = Mc.dimensionId();
			ctx.screenOpen = Mc.screen() != null;
			HitResult hr = mc.hitResult;
			ctx.hit = hr != null && hr.getType() == HitResult.Type.BLOCK && hr instanceof BlockHitResult;
			if (ctx.hit) {
				BlockHitResult bh = (BlockHitResult) hr;
				BlockPos p = bh.getBlockPos();
				ctx.hitX = p.getX();
				ctx.hitY = p.getY();
				ctx.hitZ = p.getZ();
				ctx.face = bh.getDirection().ordinal();
			}
			ctx.x = Mc.cameraX();
			ctx.y = Mc.cameraY() - 1.62;
			ctx.z = Mc.cameraZ();
			ctx.yaw = Mc.cameraYaw();
		}
		circuits.tick(ctx.inWorld ? INSTANCE : null, ctx);
	}

	// --- CircuitWorld ---

	@Override
	public String block(int x, int y, int z, Map<String, String> props) {
		Level l = level;
		if (l == null || !l.hasChunk(x >> 4, z >> 4)) return null;
		pos.set(x, y, z);
		BlockState state = l.getBlockState(pos);
		for (Property<?> p : state.getProperties()) {
			props.put(p.getName(), String.valueOf(state.getValue(p)));
		}
		//? if >=1.19.3 {
		/*return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()));
		*///?} else
		return String.valueOf(net.minecraft.core.Registry.BLOCK.getKey(state.getBlock()));
	}

	@Override
	public boolean conductor(int x, int y, int z) {
		Level l = level;
		if (l == null) return false;
		pos.set(x, y, z);
		return l.getBlockState(pos).isRedstoneConductor(l, pos);
	}

	@Override
	public boolean legacy() {
		return false;
	}

	// --- Platform ---

	@Override
	public String minecraftVersion() {
		dev.theredstonee.trsclient.core.hosting.HostingPlatform hp = dev.theredstonee.trsclient.core.hosting.Hosting.platform();
		return hp == null ? null : hp.minecraftVersion();
	}

	@Override
	public Map<String, Integer> inventory() {
		Minecraft mc = Minecraft.getInstance();
		Map<String, Integer> out = new HashMap<String, Integer>();
		if (mc.player == null) return out;
		//? if >=1.17 {
		/*Inventory inv = mc.player.getInventory();
		*///?} else
		Inventory inv = mc.player.inventory;
		for (int i = 0, n = inv.getContainerSize(); i < n; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			String id = itemId(s.getItem());
			Integer old = out.get(id);
			out.put(id, (old == null ? 0 : old) + s.getCount());
		}
		return out;
	}

	private static String itemId(Item item) {
		//? if >=1.19.3 {
		/*String id = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item));
		*///?} else
		String id = String.valueOf(net.minecraft.core.Registry.ITEM.getKey(item));
		return id.startsWith("minecraft:") ? id.substring(10) : id;
	}

	@Override
	public Object stack(String itemId) {
		ItemStack cached = stacks.get(itemId);
		if (cached != null) return cached;
		if (items == null) {
			items = new HashMap<String, Item>();
			//? if >=1.19.3 {
			/*for (Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) items.put(itemId(item), item);
			*///?} else
			for (Item item : net.minecraft.core.Registry.ITEM) items.put(itemId(item), item);
		}
		Item item = items.get(itemId);
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
