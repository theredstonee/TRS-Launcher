package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.wheel.EmoteWheel;
import dev.theredstonee.trsclient.online.EmoteHooks;
import dev.theredstonee.trsclient.ui.Gfx;

/**
 * Emote-Rad (Taste halten, Maus zeigt, Loslassen spielt ab). Aussehen und Bedienung stehen in {@link EmoteWheel};
 * hier nur die Rad-Taste, Hinweise und Ton. Kein Vanilla-Hintergrund – die Welt bleibt sichtbar.
 */
public final class EmoteWheelScreen extends TrsUiScreen {
	private final EmoteWheel wheel;

	public EmoteWheelScreen() {
		this(new EmoteWheel(EmoteHooks.emotes(), new Host()));
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
			return EmoteHooks.keyHeld(TrsKeys.emoteWheel);
		}

		@Override
		public String keyName() {
			return EmoteHooks.keyName(TrsKeys.emoteWheel);
		}

		@Override
		public void close() {
			EmoteHooks.closeScreen();
		}

		@Override
		public void actionBar(String text) {
			EmoteHooks.actionBar(text);
		}

		@Override
		public void click() {
			new TrsMenuHost(null).playClick();
		}
	}
}
