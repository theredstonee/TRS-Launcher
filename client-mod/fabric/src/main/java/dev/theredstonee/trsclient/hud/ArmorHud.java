package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.format.HudFormat;
import dev.theredstonee.trsclient.core.hud.ArmorLayout;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.Font;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Rüstung und optional der Gegenstand in der Hand, jeweils mit Haltbarkeit – senkrecht (Helm oben) oder
 * waagerecht (Symbole in einer Zeile, Haltbarkeit darunter), siehe {@link ArmorLayout}.
 *
 * <p>Sichtbarkeit, Breite, Höhe und Zeichnen fragen je Bild mehrmals nach demselben Stand: die Texte werden
 * nur neu gebaut, wenn sich ein Gegenstand, seine Haltbarkeit oder eine Einstellung geändert hat.
 */
public final class ArmorHud extends HudElement {
	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND};

	private final TrsModules modules;
	private final ItemStack[] stacks = new ItemStack[SLOTS.length];
	private final String[] texts = new String[SLOTS.length];
	private final int[] colors = new int[SLOTS.length];
	private final int[] widths = new int[SLOTS.length];
	private final int[] places = new int[SLOTS.length * 4];
	/** Stand der letzten Berechnung: Gegenstand + Schaden je Platz, Einstellungen. */
	private final ItemStack[] lastStacks = new ItemStack[SLOTS.length];
	private final int[] lastDamage = new int[SLOTS.length];
	private int lastSettings = -1;
	private int rows;
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
		Player p = mc.player;
		boolean hand = modules.armorHand.get();
		int slots = hand ? SLOTS.length : SLOTS.length - 1;
		int settings = (preview ? 1 : 0) | (hand ? 2 : 0) | (modules.armorDurability.get() ? 4 : 0) | (modules.armorPercent.get() ? 8 : 0);
		boolean changed = settings != lastSettings;
		for (int i = 0; i < slots; i++) {
			ItemStack s = p == null ? ItemStack.EMPTY : p.getItemBySlot(SLOTS[i]);
			if (s.isEmpty() && preview) s = preview(i);
			int damage = s.isEmpty() ? -1 : s.getDamageValue();
			if (s != lastStacks[i] || damage != lastDamage[i]) {
				lastStacks[i] = s;
				lastDamage[i] = damage;
				changed = true;
			}
		}
		if (!changed) return;
		lastSettings = settings;
		rows = 0;
		for (int i = 0; i < slots; i++) {
			ItemStack s = lastStacks[i];
			if (s == null || s.isEmpty()) continue;
			stacks[rows] = s;
			texts[rows] = null;
			widths[rows] = 0;
			if (modules.armorDurability.get() && s.isDamageableItem()) {
				int pct = HudFormat.durabilityPercent(s.getDamageValue(), s.getMaxDamage());
				texts[rows] = modules.armorPercent.get() ? pct + " %" : Integer.toString(s.getMaxDamage() - s.getDamageValue());
				colors[rows] = 0xFF000000 | HudFormat.durabilityColor(pct);
				widths[rows] = mc.font.width(texts[rows]);
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
		return ArmorLayout.width(horizontal(), rows, widths);
	}

	@Override
	public int height(Font font, boolean preview) {
		collect(preview);
		return ArmorLayout.height(horizontal(), rows, widths);
	}

	@Override
	public void draw(Gfx g, Font font, boolean preview) {
		collect(preview);
		boolean horizontal = horizontal();
		int w = ArmorLayout.width(horizontal, rows, widths);
		int h = ArmorLayout.height(horizontal, rows, widths);
		int bg = module.backgroundArgb();
		if (bg != 0) g.fill(0, 0, w, h, bg);
		ArmorLayout.place(horizontal, rows, widths, places);
		boolean shadow = module.shadow();
		for (int i = 0; i < rows; i++) {
			g.item(font, stacks[i], places[4 * i], places[4 * i + 1]);
			if (texts[i] != null) g.text(font, texts[i], places[4 * i + 2], places[4 * i + 3], colors[i], shadow);
		}
	}
}
