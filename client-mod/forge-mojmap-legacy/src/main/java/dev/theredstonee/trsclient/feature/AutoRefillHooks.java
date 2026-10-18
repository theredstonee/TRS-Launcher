package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.inventory.AutoRefill;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Automatisch nachlegen (Forge, Mojang-Mappings 1.14–1.19): liest je Tick den ausgewählten Hotbar-Slot und
 * das Inventar und legt über den normalen Inventar-Klick ({@link ClickType#SWAP}, serverseitig) nach. Logik in
 * {@link AutoRefill}; hier nur der versionsabhängige Zugriff aufs Spiel.
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

	private static Inventory inventory(Player p) {
		//? if >=1.17 {
		/*return p.getInventory();
		*///?} else
		return p.inventory;
	}

	private static final AutoRefill.Game GAME = new AutoRefill.Game() {
		@Override
		public boolean active() {
			if (modules == null || !modules.autoRefill.isEnabled()) return false;
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || mc.level == null) return false;
			// Nur im Spiel (kein Behälter/Bildschirm offen).
			if (Mc.screen() != null) return false;
			// Kreativ hat unendlich Gegenstände – da lohnt das Nachlegen nicht.
			if (mc.player.isCreative()) return false;
			// Fair Play (eigener Schalter oder vom Server erzwungen): nichts tun.
			MapEngine maps = MapEngine.get();
			if (maps != null && (maps.fairPlay().active() || maps.fairPlay().serverFair())) return false;
			return true;
		}

		@Override
		public int selectedSlot() {
			Player p = Minecraft.getInstance().player;
			if (p == null) return -1;
			Inventory inv = inventory(p);
			// getSelectedSlot() gibt es ab 1.21.5 (davor das öffentliche Feld selected).
			//? if >=1.21.5 {
			/*return inv.getSelectedSlot();
			*///?} else
			return inv.selected;
		}

		@Override
		public String itemKey(int slot) {
			Player p = Minecraft.getInstance().player;
			if (p == null) return null;
			Inventory inv = inventory(p);
			if (slot < 0 || slot >= inv.getContainerSize()) return null;
			ItemStack s = inv.getItem(slot);
			if (s.isEmpty()) return null;
			return itemId(s.getItem());
		}

		@Override
		public void swap(int from, int toHotbar) {
			Minecraft mc = Minecraft.getInstance();
			Player p = mc.player;
			if (p == null || mc.gameMode == null) return;
			// InventoryMenu: Hauptfeld 9..35 = Slot 9..35, Hotbar 0..8 = Slot 36..44.
			int containerSlot = from < 9 ? 36 + from : from;
			// Ab 26.1 heißt die Methode handleContainerInput und der Klick-Typ ContainerInput.
			//? if >=26.1 {
			/*mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, containerSlot, toHotbar,
					net.minecraft.world.inventory.ContainerInput.SWAP, p);
			*///?} else {
			mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, containerSlot, toHotbar,
					net.minecraft.world.inventory.ClickType.SWAP, p);
			//?}
		}
	};

	private static String itemId(Item item) {
		//? if >=1.19.3 {
		/*return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item));
		*///?} else
		return String.valueOf(net.minecraft.core.Registry.ITEM.getKey(item));
	}
}
