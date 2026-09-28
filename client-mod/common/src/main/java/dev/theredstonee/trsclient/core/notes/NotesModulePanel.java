package dev.theredstonee.trsclient.core.notes;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.menu.ModulePanel;

/** Bereich auf der Modulseite „Notizen“: „Notizen öffnen“ und Stand des Syncs. */
public final class NotesModulePanel implements ModulePanel {
	@Override
	public int draw(Canvas c, Hits hits, int x, int y, int w, int mouseX, int mouseY, final Runnable click) {
		Theme t = Theme.get();
		int by = y + 2;
		String open = I18n.tr("notes.open");
		int ow = Math.min(w, c.textWidth(open) + 24);
		boolean hover = mouseX >= x && mouseX < x + ow && mouseY >= by && mouseY < by + 17;
		Paint.button(c, x, by, ow, 17, open, true, hover);
		hits.add(x, by, ow, 17, new Runnable() {
			@Override
			public void run() {
				click.run();
				Notes.requestOpen();
			}
		});
		int ty = by + 24;
		NotesSync sync = NotesSync.get();
		if (sync == null) {
			return Paint.paragraph(c, I18n.tr("notes.sync.localOnly"), x, ty, Math.min(w, 360), 10, t.textDim) + 4;
		}
		float lit = sync.status() == NotesSync.Status.SYNCED ? 1f : sync.status() == NotesSync.Status.SYNCING ? 0.6f : 0f;
		Redstone.pip(c, x, ty + 1, 7, lit);
		Paint.textClipped(c, statusText(sync), x + 12, ty, w - 12, t.text, false);
		ty += 12;
		if (sync.rejectReason() != null) {
			ty = Paint.paragraph(c, I18n.tr(NotesSyncApi.Result.LIMIT.equals(sync.rejectReason()) ? "notes.sync.limit" : "notes.sync.invalid"),
					x, ty, Math.min(w, 360), 10, t.dustOn);
		}
		return Paint.paragraph(c, I18n.tr("notes.sync.what"), x, ty + 2, Math.min(w, 360), 10, t.textDim) + 4;
	}

	/** Kurzer Stand für die Notiz-Seite und die Modulseite. */
	public static String statusText(NotesSync sync) {
		if (sync == null) return I18n.tr("notes.sync.localOnly");
		switch (sync.status()) {
			case SYNCED:
				long ago = Math.max(0, (System.currentTimeMillis() - sync.lastSyncAt()) / 60_000L);
				return ago < 1 ? I18n.tr("notes.sync.justNow") : I18n.tr("notes.sync.minutesAgo", ago);
			case SYNCING:
				return I18n.tr("notes.sync.syncing");
			case WAITING:
				return I18n.tr("notes.sync.waiting");
			case OFFLINE:
				return I18n.tr("notes.sync.offline");
			case UNSUPPORTED:
				return I18n.tr("notes.sync.unsupported");
			case NO_CONSENT:
				return I18n.tr("notes.sync.noConsent");
			default:
				return I18n.tr("notes.sync.off");
		}
	}
}
