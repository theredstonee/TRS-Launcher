package dev.theredstonee.trsclient.core.module;

/**
 * Schaltungs-Bibliothek (TRS Client {@link NewSince#CIRCUITS}): fertige Redstone-Schaltungen mit Erklärung,
 * isometrischer Vorschau und Materialliste – und als Vorlage in der Welt einblendbar (Geisterblöcke: richtig grün,
 * falsch rot, fehlt grau). Nur Anzeige wie eine Litematica-Vorschau: nichts wird automatisch gebaut oder an den
 * Server geschickt.
 *
 * <p>Logik in {@code core.circuit}; die Bäume liefern nur Weltzugriff ({@code compat/CircuitProbe}) und die
 * Zeichen-Anbindung (Redstone-Overlay im HUD).
 */
public final class CircuitModules {
	public final Module circuitLibrary;
	public final BoolSetting labels;
	public final NumberSetting opacity;
	public final BoolSetting hideCorrect;
	public final KeySetting rotateKey;
	public final KeySetting confirmKey;
	public final KeySetting layerUpKey;
	public final KeySetting layerDownKey;
	public final KeySetting hideKey;
	public final KeySetting openKey;

	CircuitModules(ModuleRegistry registry) {
		circuitLibrary = registry.register(new Module("circuitLibrary", "Circuit Library",
				"Ready-made redstone circuits with an explanation, a rotatable preview and a material list. Show one "
						+ "as a template in the world: ghost blocks turn green when right, red when wrong and stay grey "
						+ "while missing. Display only – nothing is built for you or sent to the server.", true));
		labels = circuitLibrary.add(new BoolSetting("labels", "Block names next to missing and wrong blocks", true));
		opacity = circuitLibrary.add(new NumberSetting("opacity", "Ghost block opacity", 60, 20, 100, 5, "%"));
		hideCorrect = circuitLibrary.add(new BoolSetting("hideCorrect", "Only outline correct blocks", false));
		rotateKey = circuitLibrary.add(new KeySetting("rotateKey", "Rotate while placing", "key.keyboard.r"));
		confirmKey = circuitLibrary.add(new KeySetting("confirmKey", "Confirm position", "key.keyboard.enter"));
		layerUpKey = circuitLibrary.add(new KeySetting("layerUpKey", "Next layer", "key.keyboard.up"));
		layerDownKey = circuitLibrary.add(new KeySetting("layerDownKey", "Previous layer", "key.keyboard.down"));
		hideKey = circuitLibrary.add(new KeySetting("hideKey", "Hide / show template", "key.keyboard.h"));
		openKey = circuitLibrary.add(new KeySetting("openKey", "Open the library"));
		circuitLibrary.icon("chip").category(Category.REDSTONE);
	}
}
