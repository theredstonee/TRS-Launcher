package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.hud.EffectsPanel;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextWidth;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;

/** Effekte mit Symbol (Layout und Zeichnen in {@link EffectsPanel}); ersetzt die Vanilla-Symbole (EffectsMixin). */
public final class EffectsHud extends HudElement {
	private static final TextWidth MEASURE = new TextWidth() {
		@Override
		public int width(String text) {
			return Minecraft.getInstance().font.width(text);
		}
	};
	private final EffectsPanel panel;
	private MobEffectInstance[] samples;
	private Gfx frame;

	private final EffectsPanel.Icons icons = new EffectsPanel.Icons() {
		@Override
		public void draw(Canvas c, Object icon, int x, int y, int argb) {
			if (frame != null && icon != null) EffectsHud.icon(frame, icon, x, y, argb);
		}
	};

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
	public int width(Font font, boolean preview) {
		if (preview) collect(true);
		return panel.width(MEASURE);
	}

	@Override
	public int height(Font font, boolean preview) {
		return panel.height();
	}

	@Override
	public void draw(Gfx g, Font font, boolean preview) {
		frame = g;
		try {
			panel.draw(GfxCanvas.of(g, font), MEASURE, icons, System.currentTimeMillis());
		} finally {
			frame = null;
		}
	}

	private void collect(boolean preview) {
		panel.begin();
		boolean names = panel.needsNames();
		Player p = mc.player;
		if (p != null) {
			for (MobEffectInstance e : p.getActiveEffects()) {
				if (e.showIcon()) add(e, names);
			}
		}
		if (!preview || panel.count() > 0) {
			panel.end(true);
			return;
		}
		if (samples == null) samples = samples();
		int[] max = {2400, 1200, 300, 0};
		for (int i = 0; i < samples.length; i++) add(samples[i], names).max = max[i];
		panel.end(false);
	}

	private EffectsPanel.Entry add(MobEffectInstance e, boolean names) {
		MobEffect effect = Mc.effect(e);
		EffectsPanel.Entry en = panel.add(e.getDescriptionId());
		en.icon = e.getEffect();
		en.color = effect.getColor();
		en.amplifier = e.getAmplifier();
		en.duration = e.getDuration();
		en.infinite = e.isInfiniteDuration();
		MobEffectCategory cat = effect.getCategory();
		en.category = cat == MobEffectCategory.BENEFICIAL ? EffectsPanel.BENEFICIAL
				: cat == MobEffectCategory.HARMFUL ? EffectsPanel.HARMFUL : EffectsPanel.NEUTRAL;
		if (names) en.name = effect.getDisplayName().getString();
		return en;
	}

	// Beispiele für den HUD-Editor
	private static MobEffectInstance[] samples() {
		//? if >=1.21.5 {
		/*return new MobEffectInstance[]{new MobEffectInstance(MobEffects.SPEED, 1800, 1),
				new MobEffectInstance(MobEffects.STRENGTH, 900, 0), new MobEffectInstance(MobEffects.POISON, 160, 0),
				new MobEffectInstance(MobEffects.NIGHT_VISION, 40000, 0)};
		*///?} else {
		return new MobEffectInstance[]{new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 1800, 1),
				new MobEffectInstance(MobEffects.DAMAGE_BOOST, 900, 0), new MobEffectInstance(MobEffects.POISON, 160, 0),
				new MobEffectInstance(MobEffects.NIGHT_VISION, 40000, 0)};
		//?}
	}

	/** Effekt-Symbol 18×18 bei (x, y); {@code handle} = MobEffectInstance#getEffect (Holder ab 1.20.5). */
	@SuppressWarnings("unchecked")
	static void icon(Gfx g, Object handle, int x, int y, int argb) {
		//? if >=26.2 {
		/*((net.minecraft.client.gui.GuiGraphicsExtractor) g.raw()).blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
				net.minecraft.client.gui.Hud.getMobEffectSprite((net.minecraft.core.Holder<MobEffect>) handle), x, y, 18, 18, argb);
		*///?} elif >=26.1 {
		/*((net.minecraft.client.gui.GuiGraphicsExtractor) g.raw()).blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
				net.minecraft.client.gui.Gui.getMobEffectSprite((net.minecraft.core.Holder<MobEffect>) handle), x, y, 18, 18, argb);
		*///?} elif >=1.21.6 {
		/*((net.minecraft.client.gui.GuiGraphics) g.raw()).blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
				net.minecraft.client.gui.Gui.getMobEffectSprite((net.minecraft.core.Holder<MobEffect>) handle), x, y, 18, 18, argb);
		*///?} elif >=1.21.2 {
		/*((net.minecraft.client.gui.GuiGraphics) g.raw()).blitSprite(net.minecraft.client.renderer.RenderType::guiTextured,
				Mc.mc().getMobEffectTextures().get((net.minecraft.core.Holder<MobEffect>) handle), x, y, 18, 18, argb);
		*///?} elif >=1.20.5 {
		net.minecraft.client.gui.GuiGraphics gg = (net.minecraft.client.gui.GuiGraphics) g.raw();
		gg.flush();
		com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		gg.setColor(1f, 1f, 1f, (argb >>> 24) / 255f);
		gg.blit(x, y, 0, 18, 18, Mc.mc().getMobEffectTextures().get((net.minecraft.core.Holder<MobEffect>) handle));
		gg.setColor(1f, 1f, 1f, 1f);
		//?} else {
		/*net.minecraft.client.gui.GuiGraphics gg = (net.minecraft.client.gui.GuiGraphics) g.raw();
		gg.flush();
		com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		gg.setColor(1f, 1f, 1f, (argb >>> 24) / 255f);
		gg.blit(x, y, 0, 18, 18, Mc.mc().getMobEffectTextures().get((MobEffect) handle));
		gg.setColor(1f, 1f, 1f, 1f);
		*///?}
	}
}
