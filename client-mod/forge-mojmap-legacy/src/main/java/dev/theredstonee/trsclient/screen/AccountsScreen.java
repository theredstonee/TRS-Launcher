package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.account.AccountManager;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.account.AccountsHost;
import dev.theredstonee.trsclient.core.ui.account.AccountsUi;
import net.minecraft.client.gui.screens.Screen;

/**
 * Der Kontobildschirm (Kontowechsel ohne Neustart). Inhalt und Bedienung stehen versionsunabhängig in
 * {@link AccountsUi}; hier hängt er nur an einem Minecraft-Bildschirm. Einstieg für Titelbildschirm und
 * TRS-Menü: {@link #create(Screen)}.
 */
public final class AccountsScreen {
	private AccountsScreen() {
	}

	/** Gibt es die Kontoverwaltung (beim Start angelegt)? */
	public static boolean available() {
		return AccountManager.get() != null;
	}

	/** Kontobildschirm; {@code parent} = wohin „Schließen“ führt. */
	public static Screen create(final Screen parent) {
		final AccountManager manager = AccountManager.get();
		AccountsHost host = new AccountsHost() {
			@Override
			public void playClick() {
				new TrsMenuHost(parent).playClick();
			}

			@Override
			public void closeScreen() {
				Mc.setScreen(parent);
			}

			@Override
			public String userAgent() {
				return manager == null ? "TRS-Client" : manager.userAgent();
			}
		};
		return new TrsUiScreen(I18n.tr("accounts.title"), new AccountsUi(host, manager));
	}

	/** Öffnet den Kontobildschirm über dem aktuellen Bildschirm. */
	public static void open() {
		Mc.setScreen(create(Mc.screen()));
	}
}
