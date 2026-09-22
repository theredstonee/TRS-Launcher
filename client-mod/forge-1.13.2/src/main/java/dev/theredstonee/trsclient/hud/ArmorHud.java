package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.format.HudFormat;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.ui.Brand;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

/** Rüstung (Helm oben) und optional der Gegenstand in der Hand, jeweils mit Haltbarkeit. */
public final class ArmorHud extends HudElement {
	/** Zeilen: Helm, Brust, Beine, Schuhe (armorInventory 3..0), Hand. */
	private static final int SLOTS = 5;
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

	private static ItemStack slot(EntityPlayer p, int i) {
		if (p == null) return ItemStack.EMPTY;
		return i < 4 ? p.inventory.armorInventory.get(3 - i) : p.getHeldItemMainhand();
	}

	private void collect(boolean preview) {
		EntityPlayer p = mc.player;
		rows = 0;
		textWidth = 0;
		int slots = modules.armorHand.get() ? SLOTS : SLOTS - 1;
		for (int i = 0; i < slots; i++) {
			ItemStack s = slot(p, i);
			if (s.isEmpty() && preview) s = preview(i);
			if (s.isEmpty()) continue;
			stacks[rows] = s;
			texts[rows] = null;
			if (modules.armorDurability.get() && s.isDamageable()) {
				int pct = HudFormat.durabilityPercent(s.getDamage(), s.getMaxDamage());
				texts[rows] = modules.armorPercent.get() ? pct + " %" : Integer.toString(s.getMaxDamage() - s.getDamage());
				colors[rows] = 0xFF000000 | HudFormat.durabilityColor(pct);
				textWidth = Math.max(textWidth, mc.fontRenderer.getStringWidth(texts[rows]));
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
	public void draw(FontRenderer font, boolean preview) {
		int w = width(font, preview);
		int h = height(font, preview);
		boolean bg = module.background.get();
		if (bg) Brand.rect(0, 0, w, h, Brand.HUD_BG);
		for (int i = 0; i < rows; i++) {
			int y = PAD + i * ROW;
			item(stacks[i], PAD, y);
			if (texts[i] != null) Brand.text(font, texts[i], PAD + 19, y + 4, colors[i], !bg);
		}
	}

	/** Gegenstand wie in der Hotbar zeichnen (GUI-Beleuchtung, danach Zustand für Text/Rechtecke zurück). */
	private void item(ItemStack stack, int x, int y) {
		GlStateManager.pushMatrix();
		GlStateManager.enableRescaleNormal();
		RenderHelper.enableGUIStandardItemLighting();
		mc.getItemRenderer().renderItemAndEffectIntoGUI(stack, x, y);
		RenderHelper.disableStandardItemLighting();
		GlStateManager.disableRescaleNormal();
		GlStateManager.enableBlend();
		GlStateManager.enableAlphaTest();
		GlStateManager.popMatrix();
		GlStateManager.color4f(1f, 1f, 1f, 1f);
	}
}
