package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.format.HudFormat;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.Font;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Rüstung (Helm oben) und optional der Gegenstand in der Hand, jeweils mit Haltbarkeit. */
public final class ArmorHud extends HudElement {
	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND};
	private static final int ROW = 17;
	private static final int PAD = 3;

	private final TrsModules modules;
	private final ItemStack[] stacks = new ItemStack[SLOTS.length];
	private final String[] texts = new String[SLOTS.length];
	private final int[] colors = new int[SLOTS.length];
	private int rows;
	private int textWidth;
	private ItemStack[] previewStacks;

	public ArmorHud(HudModule module, TrsModules modules) {
		super(module);
		this.modules = modules;
	}

	@Override
	public boolean visible() {
		collect(false);
		return rows > 0;
	}

	private void collect(boolean preview) {
		Player p = mc.player;
		rows = 0;
		textWidth = 0;
		int slots = modules.armorHand.get() ? SLOTS.length : SLOTS.length - 1;
		for (int i = 0; i < slots; i++) {
			ItemStack s = p == null ? ItemStack.EMPTY : p.getItemBySlot(SLOTS[i]);
			if (s.isEmpty() && preview) s = preview(i);
			if (s.isEmpty()) continue;
			stacks[rows] = s;
			texts[rows] = null;
			if (modules.armorDurability.get() && s.isDamageableItem()) {
				int pct = HudFormat.durabilityPercent(s.getDamageValue(), s.getMaxDamage());
				texts[rows] = modules.armorPercent.get() ? pct + " %" : (s.getMaxDamage() - s.getDamageValue()) + "";
				colors[rows] = 0xFF000000 | HudFormat.durabilityColor(pct);
				textWidth = Math.max(textWidth, mc.font.width(texts[rows]));
			}
			rows++;
		}
	}

	private ItemStack preview(int i) {
		if (previewStacks == null) {
			previewStacks = new ItemStack[]{new ItemStack(Items.IRON_HELMET), new ItemStack(Items.DIAMOND_CHESTPLATE),
					new ItemStack(Items.IRON_LEGGINGS), new ItemStack(Items.GOLDEN_BOOTS), new ItemStack(Items.DIAMOND_SWORD)};
		}
		return previewStacks[i];
	}

	@Override
	public int width(Font font, boolean preview) {
		collect(preview);
		return PAD * 2 + 16 + (textWidth > 0 ? 3 + textWidth : 0);
	}

	@Override
	public int height(Font font, boolean preview) {
		collect(preview);
		return PAD * 2 + Math.max(1, rows) * ROW - 1;
	}

	@Override
	public void draw(Gfx g, Font font, boolean preview) {
		int w = width(font, preview);
		int h = height(font, preview);
		int bg = module.backgroundArgb();
		if (bg != 0) g.fill(0, 0, w, h, bg);
		for (int i = 0; i < rows; i++) {
			int y = PAD + i * ROW;
			g.item(font, stacks[i], PAD, y);
			if (texts[i] != null) g.text(font, texts[i], PAD + 19, y + 4, colors[i], module.shadow());
		}
	}
}
