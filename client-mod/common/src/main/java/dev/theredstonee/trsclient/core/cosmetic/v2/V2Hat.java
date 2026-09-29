package dev.theredstonee.trsclient.core.cosmetic.v2;

/**
 * Eine v2-Kopf-Kosmetik für genau ein Bild: Modell, Texturen des aktuellen Bildes und die feste Uhrzeit, mit der alle
 * Durchgänge (Grund, Leuchten, Höfe) gerechnet werden – so liegen Grund- und Leucht-Schicht exakt aufeinander, auch
 * wenn die Version erst später zeichnet (ab 1.21.9 wird eingesammelt).
 *
 * @param <T> Textur-Kennung der Version
 */
public final class V2Hat<T> {
	public final CosmeticV2 model;
	public final T base;
	/** null = keine Leucht-Schicht. */
	public final T glow;
	/** null = keine Höfe. */
	public final T halo;
	public final long now;

	public V2Hat(CosmeticV2 model, T base, T glow, T halo, long now) {
		this.model = model;
		this.base = base;
		this.glow = glow;
		this.halo = halo;
		this.now = now;
	}

	/** Gibt es in diesem Durchgang etwas zu zeichnen? */
	public boolean has(int pass) {
		switch (pass) {
			case CosmeticV2Renderer.PASS_CUTOUT:
				return model.has(CosmeticV2.CUTOUT);
			case CosmeticV2Renderer.PASS_EMISSIVE:
				return model.has(CosmeticV2.EMISSIVE);
			case CosmeticV2Renderer.PASS_TRANSLUCENT:
				return model.has(CosmeticV2.TRANSLUCENT);
			case CosmeticV2Renderer.PASS_GLOW:
				return glow != null;
			default:
				return halo != null && !model.halos.isEmpty();
		}
	}
}
