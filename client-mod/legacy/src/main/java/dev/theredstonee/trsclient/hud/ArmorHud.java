package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.format.HudFormat;
import dev.theredstonee.trsclient.core.hud.ArmorLayout;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * Rüstung und optional der Gegenstand in der Hand, jeweils mit Haltbarkeit – senkrecht (Helm oben) oder
 * waagerecht (Symbole in einer Zeile, Haltbarkeit darunter), siehe {@link ArmorLayout}.
 */
public final class ArmorHud extends HudElement {
	/** Slots in Mc#equipment: Helm, Brust, Hose, Stiefel, Haupthand. */
	private static final int SLOTS = 5;
	private static final String[] PREVIEW = {"iron_helmet", "diamond_chestplate", "iron_leggings", "golden_boots", "diamond_sword"};

	private final TrsModules modules;
	private final ItemStack[] stacks = new ItemStack[SLOTS];
	private final String[] texts = new String[SLOTS];
	private final int[] colors = new int[SLOTS];
	private final int[] widths = new int[SLOTS];
	private final int[] places = new int[SLOTS * 4];
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

	private boolean horizontal() {
		return modules.armorLayout.get() == ArmorLayout.Orientation.HORIZONTAL;
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
			widths[rows] = 0;
			if (modules.armorDurability.get() && s.isItemStackDamageable()) {
				int pct = HudFormat.durabilityPercent(s.getItemDamage(), s.getMaxDamage());
				texts[rows] = modules.armorPercent.get() ? pct + " %" : (s.getMaxDamage() - s.getItemDamage()) + "";
				colors[rows] = 0xFF000000 | HudFormat.durabilityColor(pct);
				widths[rows] = Mc.font().getStringWidth(texts[rows]);
				textWidth = Math.max(textWidth, widths[rows]);
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
		return ArmorLayout.width(horizontal(), rows, widths);
	}

	@Override
	public int height(FontRenderer font, boolean preview) {
		collect(preview);
		return ArmorLayout.height(horizontal(), rows, widths);
	}

	@Override
	public void draw(Gfx g, FontRenderer font, boolean preview) {
		int w = width(font, preview);
		int h = height(font, preview);
		int bg = module.backgroundArgb();
		if (bg != 0) g.fill(0, 0, w, h, bg);
		ArmorLayout.place(horizontal(), rows, widths, places);
		for (int i = 0; i < rows; i++) {
			g.item(font, stacks[i], places[4 * i], places[4 * i + 1]);
			if (texts[i] != null) g.text(font, texts[i], places[4 * i + 2], places[4 * i + 3], colors[i], module.shadow());
		}
	}
}
