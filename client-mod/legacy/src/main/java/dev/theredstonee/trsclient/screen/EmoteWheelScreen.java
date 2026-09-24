package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.wheel.EmoteWheel;
import dev.theredstonee.trsclient.online.LegacyEmotes;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.settings.GameSettings;
import org.lwjgl.input.Keyboard;

/**
 * Emote-Rad (Taste halten, Maus zeigt, Loslassen spielt ab) für Forge 1.8.9–1.12.2. Aussehen und Bedienung stehen
 * in {@link EmoteWheel}; hier nur Taste (LWJGL 2), Hinweise und Ton.
 */
public final class EmoteWheelScreen extends TrsUiScreen {
	private final EmoteWheel wheel;

	public EmoteWheelScreen() {
		this(new EmoteWheel(LegacyEmotes.emotes(), new Host()));
	}

	private EmoteWheelScreen(EmoteWheel wheel) {
		super(I18n.tr("wheel.title"), wheel);
		this.wheel = wheel;
	}

	public EmoteWheel wheel() {
		return wheel;
	}

	@Override
	protected boolean customBackground() {
		return true;
	}

	@Override
	protected void drawBackground(Gfx g, float partialTick) {
		// Das Rad dunkelt selbst ab.
	}

	private static final class Host implements EmoteWheel.Host {
		@Override
		public Boolean keyHeld() {
			int code = TrsKeys.emoteWheel.getKeyCode();
			// Negative Codes sind Maustasten (−100 + Taste) → Klick-Modus.
			if (code <= 0) return null;
			return Keyboard.isKeyDown(code);
		}

		@Override
		public String keyName() {
			return GameSettings.getKeyDisplayString(TrsKeys.emoteWheel.getKeyCode());
		}

		@Override
		public void close() {
			Mc.setScreen(null);
		}

		@Override
		public void actionBar(String text) {
			Mc.actionBar(text);
		}

		@Override
		public void click() {
			Mc.clickSound();
		}
	}
}
