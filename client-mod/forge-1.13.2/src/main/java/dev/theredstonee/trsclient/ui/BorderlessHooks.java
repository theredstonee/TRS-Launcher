package dev.theredstonee.trsclient.ui;

import dev.theredstonee.trsclient.TrsClient;

/** Ob randloses Vollbild gerade gewollt ist. Vor dem Start des Clients gilt der Standard (an). */
public final class BorderlessHooks {
	private BorderlessHooks() {
	}

	public static boolean want() {
		try {
			TrsClient client = TrsClient.get();
			if (client == null || client.modules() == null) return true;
			return client.modules().borderlessFullscreen.isEnabled();
		} catch (Throwable ignored) {
			return true;
		}
	}
}
