package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.inventory.AutoRefill;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * Automatisch nachlegen (Forge 1.8.9–1.12.2, MCP-Namen): liest je Tick den ausgewählten Hotbar-Slot und das
 * Inventar und legt über den normalen Inventar-Klick (serverseitig) nach – ab 1.9 mit {@code ClickType.SWAP},
 * in 1.8.9 mit dem Hotbar-Tausch-Modus 2. Logik in {@link AutoRefill}.
 */
public final class AutoRefillHooks {
	private static final AutoRefill REFILL = new AutoRefill();
	private static TrsModules modules;

	private AutoRefillHooks() {
	}

	public static void init(TrsModules m) {
		modules = m;
	}

	public static void tick() {
		if (modules == null) return;
		REFILL.tick(GAME);
	}

	private static final AutoRefill.Game GAME = new AutoRefill.Game() {
		@Override
		public boolean active() {
			if (modules == null || !modules.autoRefill.isEnabled()) return false;
			EntityPlayerSP p = Mc.player();
			if (p == null || Mc.world() == null) return false;
			if (Mc.screen() != null) return false;
			if (p.capabilities.isCreativeMode) return false;
			MapEngine maps = MapEngine.get();
			if (maps != null && (maps.fairPlay().active() || maps.fairPlay().serverFair())) return false;
			return true;
		}

		@Override
		public int selectedSlot() {
			EntityPlayerSP p = Mc.player();
			return p == null ? -1 : p.inventory.currentItem;
		}

		@Override
		public String itemKey(int slot) {
			EntityPlayerSP p = Mc.player();
			if (p == null) return null;
			if (slot < 0 || slot >= p.inventory.getSizeInventory()) return null;
			ItemStack s = p.inventory.getStackInSlot(slot);
			if (Mc.isEmpty(s)) return null;
			return itemId(s.getItem());
		}

		@Override
		public void swap(int from, int toHotbar) {
			EntityPlayerSP p = Mc.player();
			net.minecraft.client.multiplayer.PlayerControllerMP pc = Minecraft.getMinecraft().playerController;
			if (p == null || pc == null) return;
			// Spielerinventar: Hauptfeld 9..35 = Slot 9..35, Hotbar 0..8 = Slot 36..44.
			int containerSlot = from < 9 ? 36 + from : from;
			//? if >=1.9 {
			/*pc.windowClick(p.inventoryContainer.windowId, containerSlot, toHotbar,
					net.minecraft.inventory.ClickType.SWAP, p);
			*///?} else
			pc.windowClick(p.inventoryContainer.windowId, containerSlot, toHotbar, 2, p);
		}
	};

	private static String itemId(Item item) {
		//? if >=1.9 {
		/*ResourceLocation rl = Item.REGISTRY.getNameForObject(item);
		*///?} else
		ResourceLocation rl = (ResourceLocation) Item.itemRegistry.getNameForObject(item);
		return rl == null ? "" : rl.toString();
	}
}
