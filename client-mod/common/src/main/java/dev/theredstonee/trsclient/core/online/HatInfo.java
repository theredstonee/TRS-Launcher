package dev.theredstonee.trsclient.core.online;

import dev.theredstonee.trsclient.core.cosmetic.CosmeticModels;

/**
 * Kopf-Kosmetik aus der Lookup-Antwort ({@code cosmetics.hat}, API.md §11): Vorlage + Textur. Die Textur hat
 * dasselbe Format wie ein Umhang (Bilder senkrecht übereinander, je 2:1) und läuft deshalb über dieselben
 * Umhang-Texturen – mit eigener Kennung {@code cos-<id>}, damit sich Cache-Dateien nie mit Umhängen mischen.
 */
public final class HatInfo {
	public final String id;
	public final String template;
	public final CapeInfo texture;

	HatInfo(String id, String template, CapeInfo texture) {
		this.id = id;
		this.template = template;
		this.texture = texture;
	}

	/** Prüft streng; unbekannte Vorlage (neuere API), fremde URL oder kaputte Werte → null (nichts zeichnen). */
	public static HatInfo of(String id, String template, String url, Integer scale, Integer frames, Integer frameTimeMs,
			OnlineConfig config) {
		if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,35}")) return null;
		if (template == null || !CosmeticModels.supported(template)) return null;
		CapeInfo tex = CapeInfo.of("cos-" + id, url, scale, frames, frameTimeMs, config);
		if (tex == null) return null;
		return new HatInfo(id, template, tex);
	}
}
