package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.hud.EffectsPanel;
import dev.theredstonee.trsclient.core.hud.HudAnchor;
import dev.theredstonee.trsclient.core.hud.HudPosition;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.compat.Mc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

/**
 * Selbsttest Effekt-Anzeige ({@code -PtrsAutotestOnly=effects}): Minimap an, mehrere Effekte (einer unendlich, zwei
 * schlecht, einer blinkt), dann jeder Restzeit-Stil waagerecht und senkrecht, zum Vergleich Vanilla (Modul aus).
 * Screenshots trsclient-&lt;mc&gt;-effects-*.png; in 1.8.9 gibt es oben rechts keine Vanilla-Symbole.
 */
public final class EffectsTest {
	private static final EffectsPanel.TimeStyle[] STYLES = EffectsPanel.TimeStyle.values();
	private int phase;
	private int wait;
	private int shot;

	//? if <1.9 {
	// 1.8.9 zeigt die Symbole auch ohne Partikel – also ohne (sonst schweben große Flächen vor der Kamera)
	private static void give(Minecraft mc, final String name) {
		final net.minecraft.server.integrated.IntegratedServer server = mc.getIntegratedServer();
		if (server == null) return;
		server.addScheduledTask(new Runnable() {
			@Override
			public void run() {
				net.minecraft.entity.player.EntityPlayerMP p = server.getConfigurationManager().getPlayerByUsername(name);
				if (p == null) return;
				p.clearActivePotions();
				p.addPotionEffect(new net.minecraft.potion.PotionEffect(net.minecraft.potion.Potion.moveSpeed.id, 95 * 20, 1, false, false));
				p.addPotionEffect(new net.minecraft.potion.PotionEffect(net.minecraft.potion.Potion.damageBoost.id, 50 * 20, 0, false, false));
				p.addPotionEffect(new net.minecraft.potion.PotionEffect(net.minecraft.potion.Potion.nightVision.id, 20000000, 0, false, false));
				p.addPotionEffect(new net.minecraft.potion.PotionEffect(net.minecraft.potion.Potion.moveSlowdown.id, 30 * 20, 2, false, false));
				p.addPotionEffect(new net.minecraft.potion.PotionEffect(net.minecraft.potion.Potion.poison.id, 9 * 20, 0, false, false));
			}
		});
	}
	//?}

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		if (wait > 0) {
			wait--;
			return true;
		}
		switch (phase) {
			case 0: {
				EntityPlayerSP player = Mc.player();
				if (player == null) return false;
				String name = player.getName();
				actions.command("time set 1000");
				actions.command("weather clear");
				//? if <1.9 {
				give(mc, name);
				//?} else {
				/*actions.command("effect " + name + " clear");
				actions.command("effect " + name + " minecraft:speed 95 1");
				actions.command("effect " + name + " minecraft:strength 50 0");
				actions.command("effect " + name + " minecraft:night_vision 1000000 0");
				actions.command("effect " + name + " minecraft:slowness 30 2");
				actions.command("effect " + name + " minecraft:poison 9 0");
				*///?}
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
				// Vergleich: Vanilla-Symbole oben rechts (ab 1.9; Modul aus, ohne Minimap)
				actions.shot("effects-vanilla");
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
				actions.shot("effects-" + style.name().toLowerCase(java.util.Locale.ROOT) + "-"
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
				TrsClient.LOGGER.info("[Autotest] Effekte: {} aktiv", Mc.effects(Mc.player()).size());
				phase++;
				wait = 4;
				return true;
			case 4:
				actions.shot("effects-type-nominimap");
				modules.effectsSort.set(EffectsPanel.Sort.TIME);
				modules.minimap.setEnabled(true);
				// verschoben auf die Minimap: muss darüber liegen
				modules.effects.position().set(new HudPosition(HudAnchor.TOP_RIGHT, -0.06, 0.12));
				phase++;
				wait = 4;
				return true;
			case 5:
				actions.shot("effects-over-minimap");
				// weit weg, ohne Minimap: oben rechts kein Vanilla-Symbol
				modules.effects.position().set(new HudPosition(HudAnchor.CENTER_LEFT, 0.01, 0));
				modules.minimap.setEnabled(false);
				phase++;
				wait = 4;
				return true;
			case 6:
				actions.shot("effects-moved");
				modules.effects.resetPosition();
				modules.effects.setEnabled(false);
				phase++;
				return false;
			default:
				return false;
		}
	}
}
