package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.hud.EffectsPanel;
import dev.theredstonee.trsclient.core.hud.HudAnchor;
import dev.theredstonee.trsclient.core.hud.HudPosition;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;

/**
 * Selbsttest Effekt-Anzeige ({@code -PtrsAutotestOnly=effects}): Minimap an, mehrere Effekte (einer endlos, zwei
 * schlecht), Vanilla-Vergleich (Modul aus), dann jeder Restzeit-Stil waagerecht und senkrecht. Screenshots
 * trsclient-1.7.10-effects-*.png.
 */
final class EffectsTest {
	interface Actions {
		void shot(String name);

		/** Effekte geben (Server-Seite). */
		void give();
	}

	private static final EffectsPanel.TimeStyle[] STYLES = EffectsPanel.TimeStyle.values();
	private int phase;
	private int wait;
	private int shot;

	/** Ein Tick; true = noch nicht fertig. */
	boolean step(TrsModules modules, Actions actions) {
		if (wait > 0) {
			wait--;
			return true;
		}
		switch (phase) {
			case 0:
				actions.give();
				for (Module m : modules.registry.all()) {
					if (m instanceof HudModule) m.setEnabled(false);
				}
				modules.minimap.setEnabled(false);
				modules.effects.setEnabled(false);
				modules.effects.resetPosition();
				phase++;
				wait = 40;
				return true;
			case 1:
				actions.shot("effects-vanilla");
				modules.minimap.setEnabled(true);
				modules.effects.setEnabled(true);
				modules.effectsBlink.set(true);
				modules.effectsBorder.set(true);
				modules.effectsLevel.set(true);
				modules.effectsSort.set(EffectsPanel.Sort.TIME);
				phase++;
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
				// verschoben auf die Minimap: muss darüber liegen
				modules.effectsTimeStyle.set(EffectsPanel.TimeStyle.TEXT);
				modules.effectsLayout.set(EffectsPanel.Layout.HORIZONTAL);
				modules.effects.position().set(new HudPosition(HudAnchor.TOP_RIGHT, -0.06, 0.12));
				phase++;
				wait = 4;
				return true;
			case 4:
				actions.shot("effects-over-minimap");
				// weit weg, ohne Minimap: oben rechts kein Vanilla-Symbol
				modules.effects.position().set(new HudPosition(HudAnchor.CENTER_LEFT, 0.01, 0));
				modules.minimap.setEnabled(false);
				phase++;
				wait = 4;
				return true;
			case 5:
				actions.shot("effects-moved");
				modules.effects.resetPosition();
				phase++;
				return true;
			default:
				TrsClient.LOGGER.info("[Autotest] Effekte fertig");
				modules.effects.setEnabled(false);
				return false;
		}
	}
}
