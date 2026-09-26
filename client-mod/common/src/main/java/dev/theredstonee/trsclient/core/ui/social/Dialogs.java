package dev.theredstonee.trsclient.core.ui.social;

import dev.theredstonee.trsclient.core.account.FaceCache;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.FriendsView;
import dev.theredstonee.trsclient.core.social.Chat;
import dev.theredstonee.trsclient.core.social.SafeText;
import dev.theredstonee.trsclient.core.social.Social;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Faces;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Die Dialoge des Sozial-Bildschirms. */
public final class Dialogs {
	private Dialogs() {
	}

	/** Umbrochener Absatz; Rückgabe: Höhe. */
	static int paragraph(Canvas c, String text, int x, int y, int w, int color) {
		List<String> lines = Paint.wrap(c, text, w);
		int ty = y;
		for (String l : lines) {
			Paint.textClipped(c, l, x, ty, w, color, false);
			ty += 10;
		}
		return ty - y;
	}

	// --- Rückfrage ---

	/** Ja/Nein-Rückfrage (Beitreten, Löschen, Verlassen, Link öffnen …). */
	public static final class Confirm extends Dialog {
		private final String title;
		private final String text;
		private final String detail;
		private final String yes;
		private final boolean danger;
		private final Runnable action;

		public Confirm(String title, String text, String detail, String yes, boolean danger, Runnable action) {
			this.title = title;
			this.text = text;
			this.detail = detail;
			this.yes = yes;
			this.danger = danger;
			this.action = action;
		}

		@Override
		protected int[] size(int screenW, int screenH) {
			return new int[]{Math.min(260, screenW - 20), 110};
		}

		@Override
		protected String title() {
			return title;
		}

		@Override
		protected void body(Canvas c, Kit kit, int x, int y, int w, int h, int mx, int my) {
			Theme t = Theme.get();
			int used = paragraph(c, text, x, y, w, t.text);
			if (detail != null) paragraph(c, detail, x, y + used + 3, w, ColorMath.lerp(t.textDim, t.accent, 0.35f));
			int bw = Math.min(90, (w - 6) / 2);
			int by = y + h - 18;
			kit.button(c, x + w - bw, by, bw, 18, yes, !danger, true, mx, my, new Runnable() {
				@Override
				public void run() {
					close();
					action.run();
				}
			});
			kit.button(c, x + w - bw * 2 - 6, by, bw, 18, I18n.tr("social.cancel"), false, true, mx, my, new Runnable() {
				@Override
				public void run() {
					close();
				}
			});
		}

		@Override
		public boolean keyPressed(UiKey key, String paste) {
			if (key == UiKey.ENTER) {
				close();
				action.run();
				return true;
			}
			return super.keyPressed(key, paste);
		}
	}

	// --- Melden ---

	/** Melden (API.md §20.1): Grund + optionaler Text. */
	public static final class Report extends Dialog {
		static final String[] REASONS = {"insult_hate", "spam", "inappropriate", "scam_phishing", "harassment", "other"};
		private final Social social;
		private final String kind;
		private final String target;
		private final String conversationId;
		private final String subject;
		private final TextInput note = new TextInput(500);
		private String reason;
		private boolean sending;
		private String error;

		/**
		 * @param kind message|image|player|group
		 * @param subject was gemeldet wird („Nachricht von Bob“)
		 */
		public Report(Social social, String kind, String target, String conversationId, String subject) {
			this.social = social;
			this.kind = kind;
			this.target = target;
			this.conversationId = conversationId;
			this.subject = subject;
		}

		@Override
		protected int[] size(int screenW, int screenH) {
			return new int[]{Math.min(300, screenW - 20), Math.min(214, screenH - 16)};
		}

		@Override
		protected String title() {
			return I18n.tr("social.report.title");
		}

		@Override
		protected void body(Canvas c, Kit kit, int x, int y, int w, int h, int mx, int my) {
			Theme t = Theme.get();
			int cy = y;
			Paint.textClipped(c, subject, x, cy, w, t.textDim, false);
			cy += 12;
			for (final String r : REASONS) {
				kit.radio(c, x, cy, w, I18n.tr("social.report.reason." + r), r.equals(reason), mx, my, new Runnable() {
					@Override
					public void run() {
						reason = r;
						error = null;
					}
				});
				cy += 15;
			}
			cy += 2;
			kit.input(c, note, x, cy, w, 16, I18n.tr("social.report.noteHint"), null);
			cy += 20;
			if (error != null) {
				Paint.textClipped(c, I18n.tr(error), x, cy, w, t.dustOn, false);
			} else {
				Paint.textClipped(c, I18n.tr("social.report.privacy"), x, cy, w, t.textDim, false);
			}
			int bw = Math.min(90, (w - 6) / 2);
			int by = y + h - 18;
			boolean can = reason != null && !sending;
			kit.button(c, x + w - bw, by, bw, 18, I18n.tr(sending ? "social.report.sending" : "social.report.send"), true, can, mx,
					my, new Runnable() {
						@Override
						public void run() {
							send();
						}
					});
			kit.button(c, x + w - bw * 2 - 6, by, bw, 18, I18n.tr("social.cancel"), false, true, mx, my, new Runnable() {
				@Override
				public void run() {
					close();
				}
			});
		}

		private void send() {
			if (reason == null || sending) return;
			sending = true;
			social.report(kind, reason, note.text(), target, conversationId, new Social.Done<Boolean>() {
				@Override
				public void done(Boolean value, String err) {
					sending = false;
					if (value != null) close();
					else error = err == null ? "social.error.generic" : err;
				}
			});
		}

		@Override
		public void mouseClicked(double mx, double my) {
			note.setFocused(false);
		}

		@Override
		public boolean keyPressed(UiKey key, String paste) {
			if (key == UiKey.ESCAPE) {
				if (note.focused()) {
					note.setFocused(false);
					return true;
				}
				close();
				return true;
			}
			if (note.focused()) {
				if (key == UiKey.PASTE && paste != null) {
					for (int i = 0; i < paste.length(); i++) note.type(paste.charAt(i));
					return true;
				}
				if (key == UiKey.ENTER) {
					send();
					return true;
				}
				return note.key(key);
			}
			if (key == UiKey.ENTER) send();
			return true;
		}

		@Override
		public boolean charTyped(char ch) {
			if (!note.focused()) note.setFocused(true);
			note.type(ch);
			return true;
		}
	}

	// --- Gruppe ---

	/** Gruppe erstellen oder verwalten (umbenennen, Mitglieder, Besitz, verlassen, löschen). */
	public static final class Group extends Dialog {
		private final Social social;
		private final FaceCache faces;
		private final List<FriendsView.Friend> friends;
		/** null = neue Gruppe. */
		private final String conversationId;
		private final TextInput name = new TextInput(SafeText.MAX_NAME);
		private final Set<String> selected = new LinkedHashSet<String>();
		private final Opener opener;
		private final Confirmer confirmer;
		private int scroll;
		private int maxScroll;
		private final int[] listRect = new int[4];
		private boolean busy;
		private String confirmRemove;

		/** Gruppe geöffnet (nach dem Erstellen). */
		public interface Opener {
			void open(Chat.Conversation c);
		}

		/** Rückfrage anzeigen. */
		public interface Confirmer {
			void confirm(Dialog d);
		}

		public Group(Social social, FaceCache faces, List<FriendsView.Friend> friends, String conversationId, Opener opener,
				Confirmer confirmer) {
			this.social = social;
			this.faces = faces;
			this.friends = friends == null ? new ArrayList<FriendsView.Friend>() : friends;
			this.conversationId = conversationId;
			this.opener = opener;
			this.confirmer = confirmer;
			Chat.Conversation c = conversation();
			if (c != null) name.setText(c.name);
		}

		private Chat.Conversation conversation() {
			return conversationId == null ? null : social.store().get(conversationId);
		}

		@Override
		protected int[] size(int screenW, int screenH) {
			return new int[]{Math.min(320, screenW - 20), Math.min(260, screenH - 16)};
		}

		@Override
		protected String title() {
			return I18n.tr(conversationId == null ? "social.group.createTitle" : "social.group.manageTitle");
		}

		@Override
		protected void body(Canvas c, Kit kit, int x, int y, int w, int h, int mx, int my) {
			Theme t = Theme.get();
			final Chat.Conversation conv = conversation();
			if (conversationId != null && conv == null) {
				close();
				return;
			}
			final boolean owner = conv == null || conv.ownedBy(social.self());
			int cy = y;
			// Name
			if (owner) {
				int bw = conv == null ? 0 : Math.min(90, c.textWidth(I18n.tr("social.group.rename")) + 16);
				kit.input(c, name, x, cy, w - (bw > 0 ? bw + 4 : 0), 16, I18n.tr("social.group.nameHint"), null);
				if (bw > 0) {
					final String n = name.text().trim();
					kit.button(c, x + w - bw, cy, bw, 16, I18n.tr("social.group.rename"), false,
							!n.isEmpty() && !n.equals(conv.name), mx, my, new Runnable() {
								@Override
								public void run() {
									social.renameGroup(conversationId, n);
								}
							});
				}
			} else {
				Paint.textClipped(c, conv.title(), x, cy + 4, w, t.text, false);
			}
			cy += 20;
			// Liste: Mitglieder (verwalten) bzw. Freunde zum Auswählen
			int footer = 22;
			int listH = y + h - footer - cy;
			listRect[0] = x;
			listRect[1] = cy;
			listRect[2] = w;
			listRect[3] = listH;
			Redstone.well(c, x, cy, w, listH, t.border);
			c.scissor(x + 2, cy + 2, x + w - 2, cy + listH - 2);
			kit.hits.clip(x + 2, cy + 2, w - 4, listH - 4);
			int ry = cy + 3 - scroll;
			int rowW = w - 10;
			if (conv != null) {
				Paint.textClipped(c, I18n.tr("social.group.members", conv.members.size()), x + 4, ry, rowW, t.textDim, false);
				ry += 12;
				for (final Chat.Member m : conv.members) {
					Faces.draw(c, faces.face(m.uuid, null), m.uuid, m.name, x + 5, ry + 1, 1, false);
					Paint.textClipped(c, m.name, x + 17, ry + 1, rowW - 70, t.text, false);
					if (m.owner) Icons.draw(c, "crown", x + 19 + Math.min(rowW - 70, c.textWidth(m.name)), ry + 1, 1, t.lampOn);
					if (owner && !m.owner) {
						int bx = x + w - 22;
						if (m.uuid.equals(confirmRemove)) {
							kit.icon(c, bx, ry - 1, 12, "check", true, mx, my, new Runnable() {
								@Override
								public void run() {
									confirmRemove = null;
									social.removeMember(conversationId, m.uuid);
								}
							});
						} else {
							kit.icon(c, bx, ry - 1, 12, "trash", false, mx, my, new Runnable() {
								@Override
								public void run() {
									confirmRemove = m.uuid;
								}
							});
						}
						kit.icon(c, bx - 14, ry - 1, 12, "crown", false, mx, my, new Runnable() {
							@Override
							public void run() {
								confirmer.confirm(new Confirm(I18n.tr("social.group.transferTitle"),
										I18n.tr("social.group.transferText", m.name), null, I18n.tr("social.group.transfer"), false,
										new Runnable() {
											@Override
											public void run() {
												social.transferOwner(conversationId, m.uuid);
											}
										}));
							}
						});
					}
					ry += 13;
				}
				ry += 4;
			}
			if (owner) {
				List<FriendsView.Friend> addable = new ArrayList<FriendsView.Friend>();
				for (FriendsView.Friend f : friends) if (conv == null || !conv.isMember(f.uuid)) addable.add(f);
				Paint.textClipped(c, I18n.tr(conv == null ? "social.group.pickFriends" : "social.group.addFriends", selected.size()),
						x + 4, ry, rowW, t.textDim, false);
				ry += 12;
				if (addable.isEmpty()) {
					Paint.textClipped(c, I18n.tr("social.group.noFriends"), x + 4, ry, rowW, t.textDim, false);
					ry += 12;
				}
				for (final FriendsView.Friend f : addable) {
					final boolean on = selected.contains(f.uuid);
					kit.check(c, x + 3, ry, rowW, f.name, on, on || selected.size() < 24, mx, my, new Runnable() {
						@Override
						public void run() {
							if (on) selected.remove(f.uuid);
							else selected.add(f.uuid);
						}
					});
					ry += 14;
				}
			}
			kit.hits.noClip();
			c.noScissor();
			int content = ry + scroll - (cy + 3);
			maxScroll = Math.max(0, content - (listH - 6));
			scroll = Math.max(0, Math.min(scroll, maxScroll));
			Kit.scrollbar(c, x + w - 4, cy + 2, listH - 4, scroll, maxScroll, content);
			// Fuß
			int by = y + h - 18;
			if (conv == null) {
				final String n = name.text().trim();
				int bw = Math.min(110, w / 2);
				kit.button(c, x + w - bw, by, bw, 18, I18n.tr("social.group.create"), true, !n.isEmpty() && !busy, mx, my,
						new Runnable() {
							@Override
							public void run() {
								busy = true;
								social.createGroup(n, new ArrayList<String>(selected), new Social.Done<Chat.Conversation>() {
									@Override
									public void done(Chat.Conversation value, String error) {
										busy = false;
										if (value != null) {
											close();
											opener.open(value);
										}
									}
								});
							}
						});
			} else {
				int bw = Math.min(96, (w - 8) / 3);
				if (owner) {
					kit.button(c, x + w - bw, by, bw, 18, I18n.tr("social.group.add"), true, !selected.isEmpty(), mx, my,
							new Runnable() {
								@Override
								public void run() {
									social.addMembers(conversationId, new ArrayList<String>(selected));
									selected.clear();
								}
							});
				}
				kit.button(c, x, by, bw, 18, I18n.tr("social.group.leave"), false, true, mx, my, new Runnable() {
					@Override
					public void run() {
						confirmer.confirm(new Confirm(I18n.tr("social.group.leaveTitle"), I18n.tr("social.group.leaveText",
								conv.title()), null, I18n.tr("social.group.leave"), true, new Runnable() {
							@Override
							public void run() {
								close();
								social.leaveGroup(conversationId);
							}
						}));
					}
				});
				if (owner) {
					kit.button(c, x + bw + 4, by, bw, 18, I18n.tr("social.group.delete"), false, true, mx, my, new Runnable() {
						@Override
						public void run() {
							confirmer.confirm(new Confirm(I18n.tr("social.group.deleteTitle"), I18n.tr("social.group.deleteText",
									conv.title()), null, I18n.tr("social.group.delete"), true, new Runnable() {
								@Override
								public void run() {
									close();
									social.deleteGroup(conversationId);
								}
							}));
						}
					});
				}
			}
		}

		@Override
		public void mouseClicked(double mx, double my) {
			name.setFocused(false);
		}

		@Override
		public boolean mouseScrolled(double mx, double my, double amount) {
			if (Kit.inside(mx, my, listRect[0], listRect[1], listRect[2], listRect[3])) {
				scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.round(amount * 14)));
			}
			return true;
		}

		@Override
		public boolean keyPressed(UiKey key, String paste) {
			if (key == UiKey.ESCAPE) {
				if (name.focused()) {
					name.setFocused(false);
					return true;
				}
				close();
				return true;
			}
			if (name.focused()) {
				if (key == UiKey.PASTE && paste != null) {
					for (int i = 0; i < paste.length(); i++) name.type(paste.charAt(i));
					return true;
				}
				return name.key(key);
			}
			return true;
		}

		@Override
		public boolean charTyped(char ch) {
			if (!name.focused()) name.setFocused(true);
			name.type(ch);
			return true;
		}
	}

	// --- Bild groß ---

	/** Bild in voller Größe (so groß, wie der Bildschirm erlaubt); „Melden“ für fremde Bilder. */
	public static final class Lightbox extends Dialog {
		private final Social social;
		private final Chat.Attachment attachment;
		private final Runnable report;

		public Lightbox(Social social, Chat.Attachment attachment, Runnable report) {
			this.social = social;
			this.attachment = attachment;
			this.report = report;
		}

		@Override
		protected int[] size(int screenW, int screenH) {
			return new int[]{screenW - 12, screenH - 12};
		}

		@Override
		protected String title() {
			return I18n.tr("social.image.title", attachment.width, attachment.height);
		}

		@Override
		protected void body(Canvas c, Kit kit, int x, int y, int w, int h, int mx, int my) {
			Theme t = Theme.get();
			int footer = report != null ? 22 : 0;
			int ah = h - footer;
			TextureRef full = social.attachment(attachment, true);
			TextureRef thumb = full == null ? social.attachment(attachment, false) : null;
			TextureRef tex = full != null ? full : thumb;
			float aspect = attachment.height <= 0 ? 1.6f : attachment.width / (float) attachment.height;
			int iw = w;
			int ih = Math.round(iw / aspect);
			if (ih > ah) {
				ih = ah;
				iw = Math.round(ih * aspect);
			}
			int ix = x + (w - iw) / 2;
			int iy = y + (ah - ih) / 2;
			c.fill(ix, iy, ix + iw, iy + ih, t.deep);
			if (tex != null && c.images()) {
				c.push();
				c.translate(ix, iy);
				c.scale(iw / (float) tex.width, ih / (float) tex.height);
				c.image(tex, 0, 0, tex.width, tex.height, 0xFFFFFFFF);
				c.pop();
			}
			if (full == null) {
				Paint.textCentered(c, I18n.tr(social.images().failed("f:" + attachment.id) ? "social.image.failed" : "social.image.loading"),
						x + w / 2, iy + ih - 12, t.textDim, true);
			}
			kit.quiet(ix, iy, iw, ih, new Runnable() {
				@Override
				public void run() {
					close();
				}
			});
			if (report != null) {
				int bw = Math.min(110, c.textWidth(I18n.tr("social.image.report")) + 24);
				kit.button(c, x + w - bw, y + h - 18, bw, 18, I18n.tr("social.image.report"), false, true, mx, my, new Runnable() {
					@Override
					public void run() {
						close();
						report.run();
					}
				});
			}
		}
	}

	// --- Einstellungen ---

	/** Chat-Einstellungen des Kontos (gegenseitig) + Hinweis auf die Benachrichtigungen im TRS-Menü. */
	public static final class Settings extends Dialog {
		private final Social social;

		public Settings(Social social) {
			this.social = social;
		}

		@Override
		protected int[] size(int screenW, int screenH) {
			return new int[]{Math.min(280, screenW - 20), 150};
		}

		@Override
		protected String title() {
			return I18n.tr("social.settings.title");
		}

		@Override
		protected void body(Canvas c, Kit kit, int x, int y, int w, int h, int mx, int my) {
			Theme t = Theme.get();
			final Social.ApiSettings s = social.settings();
			int cy = y;
			kit.toggle(c, x, cy, w, I18n.tr("social.settings.readReceipts"), s.readReceipts, mx, my, new Runnable() {
				@Override
				public void run() {
					social.updateChatSettings(!s.readReceipts, null);
				}
			});
			cy += 16;
			cy += paragraph(c, I18n.tr("social.settings.readReceiptsHint"), x, cy, w, t.textDim) + 4;
			kit.toggle(c, x, cy, w, I18n.tr("social.settings.typing"), s.typing, mx, my, new Runnable() {
				@Override
				public void run() {
					social.updateChatSettings(null, !s.typing);
				}
			});
			cy += 18;
			paragraph(c, I18n.tr("social.settings.toastsHint"), x, cy, w, ColorMath.lerp(t.textDim, t.accent, 0.3f));
		}
	}
}
