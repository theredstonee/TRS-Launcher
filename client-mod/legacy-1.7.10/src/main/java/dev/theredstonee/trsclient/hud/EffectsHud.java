package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.hud.EffectsPanel;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextWidth;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.ui.BrandCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ResourceLocation;

/** Effekte mit Symbol (Layout und Zeichnen in {@link EffectsPanel}); 1.7.10 hat oben rechts keine Vanilla-Symbole. */
public final class EffectsHud extends HudElement {
	private static final TextWidth MEASURE = new TextWidth() {
		@Override
		public int width(String text) {
			return Minecraft.getMinecraft().fontRenderer.getStringWidth(text);
		}
	};
	private static final TextureRef INVENTORY = new TextureRef(new ResourceLocation("textures/gui/container/inventory.png"), 256, 256);

	private static final EffectsPanel.Icons ICONS = new EffectsPanel.Icons() {
		@Override
		public void draw(Canvas c, Object icon, int x, int y, int argb) {
			int index = icon instanceof Integer ? (Integer) icon : -1;
			if (index < 0) return;
			c.push();
			c.translate(x, y);
			c.image(INVENTORY, index % 8 * 18, 198 + index / 8 * 18, 18, 18, argb);
			c.pop();
		}
	};

	private final EffectsPanel panel;
	private PotionEffect[] samples;

	public EffectsHud(TrsModules modules) {
		super(modules.effects);
		this.panel = new EffectsPanel(modules);
	}

	@Override
	public boolean visible() {
		collect(false);
		return panel.count() > 0;
	}

	@Override
	public int width(FontRenderer font, boolean preview) {
		if (preview) collect(true);
		return panel.width(MEASURE);
	}

	@Override
	public int height(FontRenderer font, boolean preview) {
		return panel.height();
	}

	@Override
	public void draw(FontRenderer font, boolean preview) {
		panel.draw(BrandCanvas.of(font), MEASURE, ICONS, System.currentTimeMillis());
	}

	private void collect(boolean preview) {
		panel.begin();
		boolean names = panel.needsNames();
		EntityPlayer p = Minecraft.getMinecraft().thePlayer;
		if (p != null) {
			@SuppressWarnings("unchecked")
			java.util.Collection<PotionEffect> effects = p.getActivePotionEffects();
			for (PotionEffect e : effects) add(e, names);
		}
		if (!preview || panel.count() > 0) {
			panel.end(true);
			return;
		}
		if (samples == null) samples = samples();
		int[] max = {2400, 1200, 300, 0};
		for (int i = 0; i < samples.length; i++) {
			EffectsPanel.Entry en = add(samples[i], names);
			if (en != null) en.max = max[i];
		}
		panel.end(false);
	}

	private EffectsPanel.Entry add(PotionEffect e, boolean names) {
		int id = e.getPotionID();
		Potion potion = id >= 0 && id < Potion.potionTypes.length ? Potion.potionTypes[id] : null;
		if (potion == null) return null;
		EffectsPanel.Entry en = panel.add(potion.getName());
		en.icon = potion.hasStatusIcon() ? potion.getStatusIconIndex() : -1;
		en.color = potion.getLiquidColor();
		en.amplifier = e.getAmplifier();
		en.duration = e.getDuration();
		en.infinite = e.getIsPotionDurationMax();
		en.category = potion.isBadEffect() ? EffectsPanel.HARMFUL : EffectsPanel.BENEFICIAL;
		if (names) en.name = net.minecraft.client.resources.I18n.format(potion.getName());
		return en;
	}

	// Beispiele für den HUD-Editor
	private static PotionEffect[] samples() {
		return new PotionEffect[]{new PotionEffect(Potion.moveSpeed.id, 1800, 1), new PotionEffect(Potion.damageBoost.id, 900, 0),
				new PotionEffect(Potion.poison.id, 160, 0), new PotionEffect(Potion.nightVision.id, 40000, 0)};
	}
}
