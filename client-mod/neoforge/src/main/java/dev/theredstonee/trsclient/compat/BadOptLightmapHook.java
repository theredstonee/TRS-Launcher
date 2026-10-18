package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.TrsClient;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.BooleanSupplier;

/**
 * BadOptimizations-Cache-Hook für die Lightmap.
 *
 * <p>BadOptimizations (eine der eingebauten Leistungs-Mods) friert die Lightmap ein, solange sich keiner der
 * von ihm beobachteten Werte ändert – und es beobachtet nur die Vanilla-Option {@code gamma()}. Das Modul
 * „Gamma" ändert diese Option bewusst nicht, sondern ersetzt den gelesenen Wert erst beim Berechnen der
 * Lightmap. Dadurch würde BadOptimizations die Aufhellung nie neu berechnen – „Gamma aus" und „Gamma 1000 %"
 * sähen identisch aus.
 *
 * <p>Dieser Hook meldet genau dann „dirty" (neu berechnen), wenn sich der effektive Gamma-Wert des Moduls
 * ändert (ein/aus, Regler, Boost). BadOptimizations rechnet die Lightmap dann einmal neu, danach wird wieder
 * zwischengespeichert. Registriert über {@code neoforge.mods.toml} unter {@code badoptimizations:cache_hooks}.
 * Ältere BadOptimizations-Fassungen ohne diese Hook-Schnittstelle ignorieren den Eintrag einfach.
 */
public final class BadOptLightmapHook implements BooleanSupplier {
	/** Zuletzt gemeldeter Override-Wert (null = Modul aus). */
	private Double last = null;

	@Override
	public boolean getAsBoolean() {
		Double now;
		try {
			OptionalDouble override = TrsClient.get().gammaOverride();
			now = override.isPresent() ? override.getAsDouble() : null;
		} catch (Throwable ignored) {
			return false;
		}
		if (!Objects.equals(now, last)) {
			last = now;
			return true;
		}
		return false;
	}
}
