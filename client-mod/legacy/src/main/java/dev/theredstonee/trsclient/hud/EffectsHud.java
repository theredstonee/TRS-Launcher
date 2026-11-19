package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.hud.EffectsPanel;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextWidth;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ResourceLocation;

/**
 * Effekte mit Symbol (Layout und Zeichnen in {@link EffectsPanel}). Ab 1.9 blendet TrsClient die Vanilla-Symbole über
 * RenderGameOverlayEvent.Pre (POTION_ICONS) aus; 1.8.9 hat oben rechts keine.
 */
public final class EffectsHud extends HudElement {
	private static final TextWidth MEASURE = new TextWidth() {
		@Override
		public int width(String text) {
			return Mc.font().getStringWidth(text);
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
	public void draw(Gfx g, FontRenderer font, boolean preview) {
		panel.draw(GfxCanvas.of(g, font), MEASURE, ICONS, System.currentTimeMillis());
	}

	private void collect(boolean preview) {
		panel.begin();
		boolean names = panel.needsNames();
		EntityPlayer p = Mc.player();
		if (p != null) {
			for (PotionEffect e : Mc.effects(p)) {
				//? if >=1.9
				/*if (!e.doesShowParticles()) continue;*/
				add(e, names);
			}
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
		Potion potion = Mc.potion(e);
		if (potion == null) return null;
		EffectsPanel.Entry en = panel.add(potion.getName());
		en.icon = potion.hasStatusIcon() ? potion.getStatusIconIndex() : -1;
		en.color = potion.getLiquidColor();
		en.amplifier = e.getAmplifier();
		en.duration = e.getDuration();
		en.infinite = e.getIsPotionDurationMax();
		//? if >=1.9 {
		/*en.category = potion.isBadEffect() ? EffectsPanel.HARMFUL
				: potion.isBeneficial() ? EffectsPanel.BENEFICIAL : EffectsPanel.NEUTRAL;
		*///?} else
		en.category = potion.isBadEffect() ? EffectsPanel.HARMFUL : EffectsPanel.BENEFICIAL;
		if (names) en.name = net.minecraft.client.resources.I18n.format(potion.getName());
		return en;
	}

	// Beispiele für den HUD-Editor
	private static PotionEffect[] samples() {
		//? if >=1.9 {
		/*return new PotionEffect[]{new PotionEffect(net.minecraft.init.MobEffects.SPEED, 1800, 1),
				new PotionEffect(net.minecraft.init.MobEffects.STRENGTH, 900, 0), new PotionEffect(net.minecraft.init.MobEffects.POISON, 160, 0),
				new PotionEffect(net.minecraft.init.MobEffects.NIGHT_VISION, 40000, 0)};
		*///?} else {
		return new PotionEffect[]{new PotionEffect(Potion.moveSpeed.id, 1800, 1), new PotionEffect(Potion.damageBoost.id, 900, 0),
				new PotionEffect(Potion.poison.id, 160, 0), new PotionEffect(Potion.nightVision.id, 40000, 0)};
		//?}
	}
}
