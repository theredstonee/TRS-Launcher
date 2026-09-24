package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.format.HudFormat;
import dev.theredstonee.trsclient.core.hud.ArmorLayout;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.ui.Brand;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Rüstung und optional der Gegenstand in der Hand, jeweils mit Haltbarkeit – senkrecht (Helm oben) oder
 * waagerecht (Symbole in einer Zeile, Haltbarkeit darunter), siehe {@link ArmorLayout}.
 */
public final class ArmorHud extends HudElement {
	/** Zeilen: Helm, Brust, Beine, Schuhe (armorInventory 3..0), Hand. */
	private static final int SLOTS = 5;
	private static final RenderItem ITEMS = new RenderItem();

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

	private ItemStack slot(EntityPlayer p, int i) {
		if (p == null) return null;
		return i < 4 ? p.inventory.armorInventory[3 - i] : p.getCurrentEquippedItem();
	}

	private boolean horizontal() {
		return modules.armorLayout.get() == ArmorLayout.Orientation.HORIZONTAL;
	}

	private void collect(boolean preview) {
		EntityPlayer p = mc.thePlayer;
		rows = 0;
		textWidth = 0;
		int slots = modules.armorHand.get() ? SLOTS : SLOTS - 1;
		for (int i = 0; i < slots; i++) {
			ItemStack s = slot(p, i);
			if (s == null && preview) s = preview(i);
			if (s == null) continue;
			stacks[rows] = s;
			texts[rows] = null;
			widths[rows] = 0;
			if (modules.armorDurability.get() && s.isItemStackDamageable()) {
				int pct = HudFormat.durabilityPercent(s.getItemDamage(), s.getMaxDamage());
				texts[rows] = modules.armorPercent.get() ? pct + " %" : Integer.toString(s.getMaxDamage() - s.getItemDamage());
				colors[rows] = 0xFF000000 | HudFormat.durabilityColor(pct);
				widths[rows] = mc.fontRenderer.getStringWidth(texts[rows]);
				textWidth = Math.max(textWidth, widths[rows]);
			}
			rows++;
		}
	}

	private ItemStack preview(int i) {
		if (previewStacks == null) {
			previewStacks = new ItemStack[]{new ItemStack(Items.iron_helmet), new ItemStack(Items.diamond_chestplate),
					new ItemStack(Items.iron_leggings), new ItemStack(Items.golden_boots), new ItemStack(Items.diamond_sword)};
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
	public void draw(FontRenderer font, boolean preview) {
		int w = width(font, preview);
		int h = height(font, preview);
		int bg = module.backgroundArgb();
		if (bg != 0) Brand.rect(0, 0, w, h, bg);
		ArmorLayout.place(horizontal(), rows, widths, places);
		for (int i = 0; i < rows; i++) {
			item(font, stacks[i], places[4 * i], places[4 * i + 1]);
			if (texts[i] != null) Brand.text(font, texts[i], places[4 * i + 2], places[4 * i + 3], colors[i], module.shadow());
		}
	}

	/** Gegenstand wie in der Hotbar zeichnen (GUI-Beleuchtung, danach Zustand für Text/Rechtecke zurück). */
	private void item(FontRenderer font, ItemStack stack, int x, int y) {
		GL11.glPushMatrix();
		GL11.glEnable(GL12.GL_RESCALE_NORMAL);
		GL11.glEnable(GL11.GL_DEPTH_TEST);
		RenderHelper.enableGUIStandardItemLighting();
		ITEMS.renderItemAndEffectIntoGUI(font, mc.getTextureManager(), stack, x, y);
		RenderHelper.disableStandardItemLighting();
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glDisable(GL12.GL_RESCALE_NORMAL);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glEnable(GL11.GL_ALPHA_TEST);
		GL11.glPopMatrix();
		GL11.glColor4f(1f, 1f, 1f, 1f);
	}
}
