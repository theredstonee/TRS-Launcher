package dev.theredstonee.trsclient.core.config;

/**
 * Zusätzlicher Teil der Config außerhalb der Module (z. B. HUD-Profile).
 * Wird nach den Modulen gelesen bzw. geschrieben (siehe {@code ModuleRegistry#addPart}).
 */
public interface ConfigPart {
	/** Übernimmt den eigenen Teil aus der Config (die Module sind bereits geladen). */
	void read(TrsConfig config);

	/** Schreibt den eigenen Teil in die Config (die Module sind bereits geschrieben). */
	void write(TrsConfig config);
}
