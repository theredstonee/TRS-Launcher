package dev.theredstonee.trsclient.core.ui.menu;

/** Zusätzlicher Knopf auf der Einstellungsseite eines Moduls (z. B. „Fadenkreuz bearbeiten“). */
public final class MenuAction {
	private final String label;
	private final String icon;
	private final Runnable action;

	public MenuAction(String label, String icon, Runnable action) {
		this.label = label;
		this.icon = icon;
		this.action = action;
	}

	public String label() {
		return label;
	}

	/** Symbol-ID (siehe {@code core.ui.Icons}) oder null. */
	public String icon() {
		return icon;
	}

	public void run() {
		if (action != null) action.run();
	}
}
