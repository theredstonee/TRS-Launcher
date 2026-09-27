package dev.theredstonee.trsclient.core.circuit;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.menu.ModulePanel;

/**
 * Bereich auf der Modulseite „Schaltungs-Bibliothek“: „Bibliothek öffnen“ (Unterseite im Menü), Stand der
 * eingeblendeten Vorlage und „Vorlage entfernen“.
 */
public final class CircuitPanel implements ModulePanel {
	@Override
	public int draw(Canvas c, Hits hits, int x, int y, int w, int mouseX, int mouseY, final Runnable click) {
		Theme t = Theme.get();
		Circuits ctl = Circuits.get();
		int by = y + 2;
		String open = I18n.tr("circuits.open");
		int ow = Math.min(w, c.textWidth(open) + 24);
		boolean hover = mouseX >= x && mouseX < x + ow && mouseY >= by && mouseY < by + 17;
		Paint.button(c, x, by, ow, 17, open, true, hover);
		hits.add(x, by, ow, 17, new Runnable() {
			@Override
			public void run() {
				click.run();
				CircuitLibraryPage.requestOpen();
			}
		});
		int bx = x + ow + 6;
		if (ctl.active() != null && !ctl.placing()) {
			String remove = I18n.tr("circuits.remove");
			int rw = Math.min(Math.max(0, x + w - bx), c.textWidth(remove) + 24);
			if (rw >= 40) {
				boolean rh = mouseX >= bx && mouseX < bx + rw && mouseY >= by && mouseY < by + 17;
				Paint.button(c, bx, by, rw, 17, c.clip(remove, rw - 10), false, rh);
				hits.add(bx, by, rw, 17, new Runnable() {
					@Override
					public void run() {
						click.run();
						Circuits.get().remove();
					}
				});
			}
		}
		int ty = by + 22;
		String status;
		Circuit a = ctl.active();
		CircuitCheck check = ctl.check();
		if (a == null) status = I18n.tr("circuits.panel.none");
		else if (ctl.placing()) status = I18n.tr("circuits.panel.placing", CircuitTexts.get().name(a));
		else status = I18n.tr("circuits.panel.active", CircuitTexts.get().name(a), check == null ? 0 : check.correct(),
				check == null ? a.blockCount() : check.total());
		ty = Paint.paragraph(c, status, x, ty, w, 10, t.textDim);
		ty = Paint.paragraph(c, I18n.tr("circuits.fairPlay"), x, ty + 2, w, 10, t.textDim);
		return ty + 4;
	}
}
