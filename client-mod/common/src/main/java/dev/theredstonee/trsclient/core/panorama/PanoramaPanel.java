package dev.theredstonee.trsclient.core.panorama;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.ComfortModules;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.menu.ModulePanel;

/**
 * Bereich auf der Modulseite „Panorama-Screenshots“: „Jetzt aufnehmen“ (schließt das Menü, nimmt nach einem
 * Augenblick auf), „Ordner öffnen“ und – wenn ein anderes Paket das Teilen einhängt – „Teilen“.
 */
public final class PanoramaPanel implements ModulePanel {
	private final ComfortModules modules;
	private final Supported supported;
	private String message;
	private long messageUntil;

	/** Gibt es die Aufnahme in dieser Version? */
	public interface Supported {
		boolean supported();
	}

	public PanoramaPanel(ComfortModules modules, Supported supported) {
		this.modules = modules;
		this.supported = supported;
	}

	@Override
	public int draw(Canvas c, Hits hits, int x, int y, int w, int mouseX, int mouseY, final Runnable click) {
		Theme t = Theme.get();
		final Panorama p = Panorama.get();
		int by = y + 2;
		int bx = x;
		boolean can = supported.supported() && modules.panorama.isEnabled() && p.state() == Panorama.State.IDLE;
		String take = I18n.tr("panorama.take");
		int tw = Math.min(w, c.textWidth(take) + 24);
		boolean hover = can && inside(mouseX, mouseY, bx, by, tw, 17);
		Paint.button(c, bx, by, tw, 17, take, can, hover);
		if (can) {
			hits.add(bx, by, tw, 17, new Runnable() {
				@Override
				public void run() {
					click.run();
					p.request(modules.panoramaFormat.get().cube(), modules.panoramaFormat.get().equirect());
				}
			});
		}
		bx += tw + 6;
		String open = I18n.tr("panorama.openFolder");
		int ow = Math.min(Math.max(0, x + w - bx), c.textWidth(open) + 24);
		if (ow >= 40) {
			boolean oh = inside(mouseX, mouseY, bx, by, ow, 17);
			Paint.button(c, bx, by, ow, 17, open, false, oh);
			hits.add(bx, by, ow, 17, new Runnable() {
				@Override
				public void run() {
					click.run();
					if (!p.openLastFolder()) say(I18n.tr("clips.openFailed"));
				}
			});
			bx += ow + 6;
		}
		if (p.canShare()) {
			String share = I18n.tr("panorama.share");
			int sw = Math.min(Math.max(0, x + w - bx), c.textWidth(share) + 24);
			if (sw >= 40) {
				boolean sh = inside(mouseX, mouseY, bx, by, sw, 17);
				Paint.button(c, bx, by, sw, 17, share, false, sh);
				hits.add(bx, by, sw, 17, new Runnable() {
					@Override
					public void run() {
						click.run();
						if (!p.share()) say(I18n.tr("panorama.shareFailed"));
					}
				});
			}
		}
		int ty = by + 22;
		String line;
		if (!supported.supported()) line = I18n.tr("panorama.unsupported");
		else if (message != null && System.currentTimeMillis() < messageUntil) line = message;
		else if (p.shareMessage(System.currentTimeMillis()) != null) line = p.shareMessage(System.currentTimeMillis());
		else if (p.state() != Panorama.State.IDLE) line = I18n.tr("panorama.saving");
		else if (p.lastFolder() != null) line = I18n.tr("panorama.last", p.lastFolder().getFileName().toString());
		else line = I18n.tr("panorama.hint");
		return Paint.paragraph(c, line, x + 2, ty, w - 4, 10, t.textDim) + 4;
	}

	private void say(String text) {
		message = text;
		messageUntil = System.currentTimeMillis() + 4000;
	}

	private static boolean inside(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
