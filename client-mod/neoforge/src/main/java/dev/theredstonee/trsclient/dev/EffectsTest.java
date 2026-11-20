package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.hud.EffectsPanel;
import dev.theredstonee.trsclient.core.hud.HudAnchor;
import dev.theredstonee.trsclient.core.hud.HudPosition;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.ui.menu.HudEditor;
import dev.theredstonee.trsclient.hud.HudElement;
import dev.theredstonee.trsclient.screen.HudEditorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * Selbsttest Effekt-Anzeige ({@code -PtrsAutotestOnly=effects}): Minimap an, mehrere Effekte (einer unendlich, zwei
 * schlecht, einer blinkt), dann jeder Restzeit-Stil waagerecht und senkrecht, zum Vergleich Vanilla (Modul aus), zuletzt
 * der HUD-Editor mit Beispielen und einmal Ziehen.
 * Screenshots trsclient-&lt;mc&gt;-effects-*.png.
 */
public final class EffectsTest {
	private static final EffectsPanel.TimeStyle[] STYLES = EffectsPanel.TimeStyle.values();
	private int phase;
	private int wait;
	private int shot;

	/**
	 * Effekte über den eingebauten Server, ohne Partikel (die schweben sonst als große Flächen vor der Kamera), das Symbol
	 * bleibt sichtbar.
	 */
	private static void give(Minecraft mc) {
		net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null) return;
		java.util.UUID id = mc.player.getUUID();
		//? if >=1.19.4 {
		int forever = -1;
		//?} else
		/*int forever = 20000000;*/
		server.execute(() -> {
			net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null) return;
			p.removeAllEffects();
			//? if >=1.21.5 {
			/*p.addEffect(new MobEffectInstance(MobEffects.SPEED, 95 * 20, 1, false, false, true));
			p.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 50 * 20, 0, false, false, true));
			p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30 * 20, 2, false, false, true));
			*///?} else {
			p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 95 * 20, 1, false, false, true));
			p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 50 * 20, 0, false, false, true));
			p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30 * 20, 2, false, false, true));
			//?}
			p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, forever, 0, false, false, true));
			p.addEffect(new MobEffectInstance(MobEffects.POISON, 9 * 20, 0, false, false, true));
		});
	}

	/** Box der Effekt-Anzeige im Editor (Vorschau), GUI-Pixel. */
	private static int[] editorBox(Minecraft mc) {
		for (HudElement el : TrsClient.get().hud().elements()) {
			if (el.module() != TrsClient.get().modules().effects) continue;
			return TrsClient.get().hud().bounds(mc.font, el, Mc.window().getGuiScaledWidth(), Mc.window().getGuiScaledHeight(), true)
					.clone();
		}
		return null;
	}

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		if (wait > 0) {
			wait--;
			return true;
		}
		switch (phase) {
			case 0: {
				if (mc.player == null) return false;
				actions.command("time set day");
				actions.command("weather clear");
				give(mc);
				for (Module m : modules.registry.all()) {
					if (m instanceof HudModule) m.setEnabled(false);
				}
				modules.minimap.setEnabled(false);
				modules.effects.setEnabled(false);
				modules.effects.resetPosition();
				phase++;
				wait = 40;
				return true;
			}
			case 1:
				// Vergleich: Vanilla-Symbole oben rechts (Modul aus, ohne Minimap)
				actions.shot("trsclient-effects-vanilla");
				modules.minimap.setEnabled(true);
				modules.effects.setEnabled(true);
				modules.effectsBlink.set(true);
				modules.effectsBorder.set(true);
				modules.effectsLevel.set(true);
				modules.effectsSort.set(EffectsPanel.Sort.TIME);
				phase++;
				shot = 0;
				wait = 4;
				return true;
			case 2: {
				if (shot >= STYLES.length * 2) {
					phase++;
					return true;
				}
				EffectsPanel.TimeStyle style = STYLES[shot / 2];
				EffectsPanel.Layout layout = shot % 2 == 0 ? EffectsPanel.Layout.HORIZONTAL : EffectsPanel.Layout.VERTICAL;
				if (modules.effectsTimeStyle.get() != style || modules.effectsLayout.get() != layout) {
					modules.effectsTimeStyle.set(style);
					modules.effectsLayout.set(layout);
					wait = 4;
					return true;
				}
				actions.shot("trsclient-effects-" + style.name().toLowerCase(java.util.Locale.ROOT) + "-"
						+ layout.name().toLowerCase(java.util.Locale.ROOT));
				shot++;
				wait = 2;
				return true;
			}
			case 3:
				// Sortierung nach Art, ohne Minimap (Standardplatz oben rechts)
				modules.effectsTimeStyle.set(EffectsPanel.TimeStyle.TEXT);
				modules.effectsLayout.set(EffectsPanel.Layout.HORIZONTAL);
				modules.effectsSort.set(EffectsPanel.Sort.TYPE);
				modules.minimap.setEnabled(false);
				TrsClient.LOGGER.info("[Autotest] Effekte: {} aktiv", mc.player.getActiveEffects().size());
				phase++;
				wait = 4;
				return true;
			case 4:
				actions.shot("trsclient-effects-type-nominimap");
				modules.effectsSort.set(EffectsPanel.Sort.TIME);
				modules.minimap.setEnabled(true);
				// verschoben, sodass die Anzeige auf der Minimap liegt: muss darüber gezeichnet sein
				modules.effects.position().set(new HudPosition(HudAnchor.TOP_RIGHT, -0.06, 0.12));
				phase++;
				wait = 4;
				return true;
			case 5:
				actions.shot("trsclient-effects-over-minimap");
				// weit weg und ohne Minimap: oben rechts darf kein Vanilla-Symbol stehen
				modules.effects.position().set(new HudPosition(HudAnchor.CENTER_LEFT, 0.01, 0));
				modules.minimap.setEnabled(false);
				phase++;
				wait = 4;
				return true;
			case 6:
				actions.shot("trsclient-effects-moved");
				modules.effects.resetPosition();
				modules.minimap.setEnabled(true);
				// HUD-Editor ohne echte Effekte: Beispiele, Platz neben der Minimap, Ziehen
				actions.command("effect clear @p");
				phase++;
				wait = 10;
				return true;
			case 7:
				Mc.setScreen(new HudEditorScreen(null));
				phase++;
				wait = 20;
				return true;
			case 8: {
				actions.shot("trsclient-effects-editor");
				int[] b = editorBox(mc);
				if (b != null && Mc.screen() instanceof HudEditorScreen) {
					HudEditor editor = (HudEditor) ((HudEditorScreen) Mc.screen()).ui();
					double cx = b[0] + b[2] / 2.0;
					double cy = b[1] + b[3] / 2.0;
					boolean grabbed = editor.mouseClicked(cx, cy, 0);
					editor.mouseDragged(cx - 90, cy + 70, 0);
					editor.mouseReleased(cx - 90, cy + 70, 0);
					TrsClient.LOGGER.info("[Autotest] Effekte im Editor: Box {},{} {}x{}, gegriffen={}, verschoben={}, Position {}", b[0], b[1],
							b[2], b[3], grabbed, !modules.effects.isDefaultPosition(), modules.effects.position());
				} else {
					TrsClient.LOGGER.warn("[Autotest] Effekte im Editor nicht gefunden");
				}
				phase++;
				wait = 10;
				return true;
			}
			case 9:
				actions.shot("trsclient-effects-editor-dragged");
				Mc.setScreen(null);
				modules.effects.resetPosition();
				modules.effects.setEnabled(false);
				phase++;
				return false;
			default:
				return false;
		}
	}
}
