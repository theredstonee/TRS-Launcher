package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.core.inventory.AutoRefill;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * Automatisch nachlegen (Forge 1.13.2, MCP-Namen): liest je Tick den ausgewählten Hotbar-Slot und das
 * Inventar und legt über den normalen Inventar-Klick ({@link ClickType#SWAP}, serverseitig) nach.
 * Logik in {@link AutoRefill}; hier nur der versionsabhängige Zugriff aufs Spiel.
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
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || mc.world == null) return false;
			if (mc.currentScreen != null) return false;
			if (mc.player.isCreative()) return false;
			MapEngine maps = MapEngine.get();
			if (maps != null && (maps.fairPlay().active() || maps.fairPlay().serverFair())) return false;
			return true;
		}

		@Override
		public int selectedSlot() {
			EntityPlayerSP p = Minecraft.getInstance().player;
			return p == null ? -1 : p.inventory.currentItem;
		}

		@Override
		public String itemKey(int slot) {
			EntityPlayerSP p = Minecraft.getInstance().player;
			if (p == null) return null;
			InventoryPlayer inv = p.inventory;
			if (slot < 0 || slot >= inv.getSizeInventory()) return null;
			ItemStack s = inv.getStackInSlot(slot);
			if (s.isEmpty()) return null;
			return itemId(s.getItem());
		}

		@Override
		public void swap(int from, int toHotbar) {
			Minecraft mc = Minecraft.getInstance();
			EntityPlayerSP p = mc.player;
			if (p == null || mc.playerController == null) return;
			// Spielerinventar: Hauptfeld 9..35 = Slot 9..35, Hotbar 0..8 = Slot 36..44.
			int containerSlot = from < 9 ? 36 + from : from;
			mc.playerController.windowClick(p.inventoryContainer.windowId, containerSlot, toHotbar, ClickType.SWAP, p);
		}
	};

	private static String itemId(Item item) {
		ResourceLocation id = item.getRegistryName();
		return id == null ? "" : id.toString();
	}
}
