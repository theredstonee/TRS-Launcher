package dev.theredstonee.trsclient.core.cosmetic.v2;

/**
 * Eine v2-Kopf-Kosmetik für genau ein Bild: Modell, Texturen des aktuellen Bildes und die feste Uhrzeit, mit der alle
 * Durchgänge (Grund, Leuchten, Höfe) gerechnet werden – so liegen Grund- und Leucht-Schicht exakt aufeinander, auch
 * wenn die Version erst später zeichnet (ab 1.21.9 wird eingesammelt).
 *
 * @param <T> Textur-Kennung der Version
 */
public final class V2Hat<T> {
	/** Texturen je Entfernungs-Stufe ({@link CosmeticV2Renderer#lodLevel}); Stufe 0 = volle Auflösung. */
	public interface Lod<T> {
		T base(long now, int level);

		T glow(long now, int level);
	}

	public final CosmeticV2 model;
	public final T base;
	/** null = keine Leucht-Schicht. */
	public final T glow;
	/** null = keine Höfe. */
	public final T halo;
	public final long now;
	/** Träger hat einen Helm auf: Teil wird auf den Helm gesetzt (siehe {@code CosmeticV2Renderer#HELMET_SCALE}). */
	public final boolean helmet;

	private final Lod<T> lod;

	public V2Hat(CosmeticV2 model, T base, T glow, T halo, long now, boolean helmet) {
		this(model, base, glow, halo, now, helmet, null);
	}

	public V2Hat(CosmeticV2 model, T base, T glow, T halo, long now, boolean helmet, Lod<T> lod) {
		this.lod = lod;
		this.model = model;
		this.base = base;
		this.glow = glow;
		this.halo = halo;
		this.now = now;
		this.helmet = helmet;
	}

	/**
	 * Entfernungs-Stufe für dieses Bild aus der Kamera ({@code eye} im Anhängepunkt-Raum, null = volle Auflösung),
	 * dem senkrechten Sichtfeld und der Bildhöhe in Pixeln.
	 */
	public int level(double[] eye, double fovDegrees, int screenHeight) {
		return lod == null ? 0 : CosmeticV2Renderer.lodLevel(model.scale, eye, fovDegrees, screenHeight);
	}

	/** Grundtextur in der Stufe {@code level} (0 = {@link #base}). */
	public T base(int level) {
		if (level <= 0 || lod == null) return base;
		T t = lod.base(now, level);
		return t == null ? base : t;
	}

	/** Leucht-Schicht in der Stufe {@code level} (0 = {@link #glow}); null = keine. */
	public T glow(int level) {
		if (level <= 0 || lod == null || glow == null) return glow;
		T t = lod.glow(now, level);
		return t == null ? glow : t;
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
