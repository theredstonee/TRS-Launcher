package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.format.HudFormat;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** Rüstung (Helm oben) und optional der Gegenstand in der Hand, jeweils mit Haltbarkeit. */
public final class ArmorHud extends HudElement {
	/** Slots in Mc#equipment: Helm, Brust, Hose, Stiefel, Haupthand. */
	private static final int SLOTS = 5;
	private static final String[] PREVIEW = {"iron_helmet", "diamond_chestplate", "iron_leggings", "golden_boots", "diamond_sword"};
	private static final int ROW = 17;
	private static final int PAD = 3;

	private final TrsModules modules;
	private final ItemStack[] stacks = new ItemStack[SLOTS];
	private final String[] texts = new String[SLOTS];
	private final int[] colors = new int[SLOTS];
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
		EntityPlayer p = Mc.player();
		rows = 0;
		textWidth = 0;
		int slots = modules.armorHand.get() ? SLOTS : SLOTS - 1;
		for (int i = 0; i < slots; i++) {
			ItemStack s = p == null ? null : Mc.equipment(p, i);
			if (s == null && preview) s = preview(i);
			if (s == null) continue;
			stacks[rows] = s;
			texts[rows] = null;
			if (modules.armorDurability.get() && s.isItemStackDamageable()) {
				int pct = HudFormat.durabilityPercent(s.getItemDamage(), s.getMaxDamage());
				texts[rows] = modules.armorPercent.get() ? pct + " %" : (s.getMaxDamage() - s.getItemDamage()) + "";
				colors[rows] = 0xFF000000 | HudFormat.durabilityColor(pct);
				textWidth = Math.max(textWidth, Mc.font().getStringWidth(texts[rows]));
			}
			rows++;
		}
	}

	private ItemStack preview(int i) {
		if (previewStacks == null) {
			previewStacks = new ItemStack[SLOTS];
			for (int j = 0; j < SLOTS; j++) {
				Item item = Mc.item(PREVIEW[j]);
				previewStacks[j] = item == null ? null : new ItemStack(item);
			}
		}
		return previewStacks[i];
	}

	@Override
	public int width(FontRenderer font, boolean preview) {
		collect(preview);
		return PAD * 2 + 16 + (textWidth > 0 ? 3 + textWidth : 0);
	}

	@Override
	public int height(FontRenderer font, boolean preview) {
		collect(preview);
		return PAD * 2 + Math.max(1, rows) * ROW - 1;
	}

	@Override
	public void draw(Gfx g, FontRenderer font, boolean preview) {
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
