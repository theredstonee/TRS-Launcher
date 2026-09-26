package dev.theredstonee.trsclient.core.ui.social;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.social.Chat;
import dev.theredstonee.trsclient.core.social.SafeText;
import dev.theredstonee.trsclient.core.social.Times;

import java.util.ArrayList;
import java.util.List;

/**
 * Anordnung einer Unterhaltung: Tagestrenner, Systemzeilen, Nachrichtenblasen mit Antwort, Text (umbrochen, Links),
 * Bildern, Einladungskarte, Reaktionen und Fußzeile. Reine Rechnung über ein {@link Measure} – ohne Minecraft
 * testbar; die Oberfläche zeichnet nur nach.
 */
public final class ChatLayout {
	public static final int LINE_H = 10;
	public static final int PAD = 4;
	public static final int HEADER_H = 11;
	public static final int REPLY_H = 12;
	public static final int INVITE_W = 176;
	public static final int INVITE_H = 36;
	public static final int CHIP_W = 22;
	public static final int CHIP_H = 11;
	public static final int FOOTER_H = 10;
	public static final int FACE_COL = 22;
	public static final int GAP = 3;
	/** Neue Kopfzeile (Name + Zeit), wenn mehr als so viel Zeit zwischen zwei Nachrichten liegt. */
	public static final long GROUP_MS = 5 * 60_000L;

	/** Textbreite (Minecraft-Schrift). */
	public interface Measure {
		int width(String s);
	}

	public enum Kind {
		DAY, SYSTEM, MESSAGE, OLDER
	}

	/** Ein Link in einer Textzeile: Zeile, Zeichenbereich in der Zeile, Adresse. */
	public static final class Link {
		public final int line;
		public final int start;
		public final int end;
		public final String url;

		Link(int line, int start, int end, String url) {
			this.line = line;
			this.start = start;
			this.end = end;
			this.url = url;
		}
	}

	/** Ein angeordnetes Element. Koordinaten relativ zum Inhalt (x ab linkem Rand, y ab oben). */
	public static final class Item {
		public final Kind kind;
		public int y;
		public int h;
		public String label;
		public Chat.Message message;
		public boolean own;
		public boolean header;
		public boolean face;
		/** Blase. */
		public int bubbleX;
		public int bubbleY;
		public int bubbleW;
		public int bubbleH;
		public int replyY = -1;
		public int textY = -1;
		public List<String> lines = new ArrayList<String>();
		public List<Link> links = new ArrayList<Link>();
		/** Platzhaltertext (gelöscht, verborgen) statt echtem Inhalt. */
		public boolean placeholder;
		public int imagesY = -1;
		public int imageCols;
		public int cellW;
		public int cellH;
		public int inviteY = -1;
		public int reactionsY = -1;
		public int footerY = -1;
		public String footer;
		public boolean footerError;

		Item(Kind kind) {
			this.kind = kind;
		}
	}

	/** Ergebnis: Elemente und Gesamthöhe. */
	public static final class Result {
		public final List<Item> items;
		public final int height;

		Result(List<Item> items, int height) {
			this.items = items;
			this.height = height;
		}
	}

	private ChatLayout() {
	}

	/**
	 * Ordnet {@code messages} (aufsteigend) für die Breite {@code width} an.
	 *
	 * @param self eigene UUID
	 * @param conversation die Unterhaltung (Lesebestätigungen, Gruppe) oder null
	 * @param hasOlder oben „ältere laden“ anzeigen
	 */
	public static Result layout(Measure m, List<Chat.Message> messages, String self, Chat.Conversation conversation,
			int width, long now, boolean hasOlder) {
		List<Item> out = new ArrayList<Item>();
		int y = 0;
		if (hasOlder) {
			Item it = new Item(Kind.OLDER);
			it.y = y;
			it.h = 16;
			out.add(it);
			y += it.h;
		}
		int maxBubble = Math.max(60, Math.min(260, (int) ((width - FACE_COL) * 0.78f)));
		Chat.Message prev = null;
		long lastOwnSeen = -1;
		Chat.Message lastOwn = null;
		for (Chat.Message msg : messages) {
			if (msg.from(self) && !msg.system && !msg.pending && msg.failed == null) lastOwn = msg;
		}
		if (lastOwn != null && conversation != null && conversation.seenBy(lastOwn.seq)) lastOwnSeen = lastOwn.seq;
		for (Chat.Message msg : messages) {
			long at = msg.createdAt > 0 ? msg.createdAt : now;
			if (prev == null || !Times.sameDay(prev.createdAt > 0 ? prev.createdAt : now, at)) {
				Item d = new Item(Kind.DAY);
				d.label = Times.day(at, now);
				d.y = y;
				d.h = 16;
				out.add(d);
				y += d.h;
				prev = null;
			}
			if (msg.system) {
				Item s = new Item(Kind.SYSTEM);
				s.message = msg;
				s.label = systemText(msg);
				s.y = y;
				List<String> lines = wrapLines(m, s.label, Math.max(40, width - 20));
				s.lines = lines;
				s.h = lines.size() * LINE_H + 4;
				out.add(s);
				y += s.h;
				prev = null;
				continue;
			}
			Item it = message(m, msg, prev, self, width, maxBubble, conversation, lastOwnSeen);
			it.y = y;
			shift(it, y);
			out.add(it);
			y += it.h;
			prev = msg;
		}
		return new Result(out, y + 2);
	}

	/** Verschiebt die relativ zu 0 berechneten Teil-Positionen auf {@code y}. */
	private static void shift(Item it, int y) {
		it.bubbleY += y;
		if (it.replyY >= 0) it.replyY += y;
		if (it.textY >= 0) it.textY += y;
		if (it.imagesY >= 0) it.imagesY += y;
		if (it.inviteY >= 0) it.inviteY += y;
		if (it.reactionsY >= 0) it.reactionsY += y;
		if (it.footerY >= 0) it.footerY += y;
	}

	private static Item message(Measure m, Chat.Message msg, Chat.Message prev, String self, int width, int maxBubble,
			Chat.Conversation conversation, long lastOwnSeen) {
		Item it = new Item(Kind.MESSAGE);
		it.message = msg;
		it.own = msg.from(self);
		boolean sameSender = prev != null && prev.sender != null && msg.sender != null
				&& prev.sender.uuid.equals(msg.sender.uuid) && msg.createdAt - prev.createdAt < GROUP_MS;
		it.header = !sameSender;
		it.face = it.header && !it.own;
		int cy = it.header ? HEADER_H : 0;
		it.bubbleY = cy;
		cy += PAD;
		int inner = maxBubble - PAD * 2;
		int contentW = 0;
		if (msg.deleted || msg.hidden) {
			it.placeholder = true;
			String text = I18n.tr(msg.hidden ? "social.msg.hiddenText" : msg.deletedBy != null && !msg.deletedBy.equals("sender")
					? "social.msg.removedText" : "social.msg.deletedText");
			it.lines = wrapLines(m, text, inner);
			it.textY = cy;
			for (String l : it.lines) contentW = Math.max(contentW, m.width(l));
			cy += it.lines.size() * LINE_H;
		} else {
			if (msg.reply != null) {
				it.replyY = cy;
				contentW = Math.max(contentW, Math.min(inner, m.width(replyText(msg.reply)) + 6));
				cy += REPLY_H;
			}
			if (msg.text != null && !msg.text.isEmpty()) {
				List<int[]> ranges = wrap(m, msg.text, inner);
				it.textY = cy;
				List<int[]> spans = SafeText.links(msg.text);
				for (int i = 0; i < ranges.size(); i++) {
					int[] r = ranges.get(i);
					String line = msg.text.substring(r[0], r[1]);
					it.lines.add(line);
					contentW = Math.max(contentW, m.width(line));
					for (int[] s : spans) {
						int a = Math.max(s[0], r[0]);
						int b = Math.min(s[1], r[1]);
						if (a < b) {
							String url = SafeText.safeLink(msg.text.substring(s[0], s[1]));
							if (url != null) it.links.add(new Link(i, a - r[0], b - r[0], url));
						}
					}
				}
				cy += ranges.size() * LINE_H;
			}
			if (!msg.attachments.isEmpty()) {
				if (cy > it.bubbleY + PAD) cy += 2;
				it.imagesY = cy;
				int n = msg.attachments.size();
				if (n == 1) {
					Chat.Attachment a = msg.attachments.get(0);
					float aspect = a.thumbHeight <= 0 ? 1.6f : a.thumbWidth / (float) a.thumbHeight;
					int w = Math.min(inner, 200);
					int h = Math.round(w / Math.max(0.3f, Math.min(4f, aspect)));
					if (h > 140) {
						h = 140;
						w = Math.max(40, Math.round(h * aspect));
						w = Math.min(w, inner);
					}
					it.imageCols = 1;
					it.cellW = w;
					it.cellH = h;
				} else {
					it.imageCols = n == 2 || n == 4 ? 2 : 3;
					it.cellW = Math.max(24, Math.min(64, (inner - (it.imageCols - 1) * 2) / it.imageCols));
					it.cellH = it.cellW;
				}
				int rows = (n + it.imageCols - 1) / it.imageCols;
				int gridW = it.imageCols * it.cellW + (it.imageCols - 1) * 2;
				contentW = Math.max(contentW, gridW);
				cy += rows * it.cellH + (rows - 1) * 2;
			}
			if (msg.invite != null) {
				if (cy > it.bubbleY + PAD) cy += 2;
				it.inviteY = cy;
				contentW = Math.max(contentW, Math.min(inner, INVITE_W));
				cy += INVITE_H;
			}
		}
		cy += PAD;
		it.bubbleW = Math.max(24, Math.min(maxBubble, contentW + PAD * 2));
		it.bubbleH = cy - it.bubbleY;
		it.bubbleX = it.own ? width - it.bubbleW - 2 : FACE_COL;
		if (!msg.reactions.isEmpty() && !it.placeholder) {
			cy += 2;
			it.reactionsY = cy;
			int perRow = Math.max(1, (maxBubble + 2) / (CHIP_W + 2));
			int rows = (msg.reactions.size() + perRow - 1) / perRow;
			cy += rows * (CHIP_H + 2);
		}
		String footer = null;
		boolean error = false;
		if (msg.failed != null) {
			footer = I18n.tr(msg.failed) + " · " + I18n.tr("social.msg.retryHint");
			error = true;
		} else if (msg.pending) {
			footer = I18n.tr("social.msg.sending");
		} else {
			StringBuilder f = new StringBuilder();
			if (msg.editedAt > 0 && !msg.deleted) f.append(I18n.tr("social.msg.edited"));
			if (it.own && lastOwnSeen >= 0 && msg.seq == lastOwnSeen) {
				if (f.length() > 0) f.append(" · ");
				int readers = conversation == null ? 0 : readers(conversation, msg.seq);
				f.append(conversation != null && conversation.group ? I18n.tr("social.msg.seenBy", readers) : I18n.tr("social.msg.seen"));
			}
			if (f.length() > 0) footer = f.toString();
		}
		if (footer != null) {
			cy += 1;
			it.footerY = cy;
			it.footer = footer;
			it.footerError = error;
			cy += FOOTER_H;
		}
		it.h = cy + GAP;
		return it;
	}

	static int readers(Chat.Conversation c, long seq) {
		int n = 0;
		for (Chat.Read r : c.reads) if (r.seq >= seq) n++;
		return n;
	}

	/** Einzeilige Antwort-Vorschau. */
	public static String replyText(Chat.Reply r) {
		String name = r.sender == null ? "?" : r.sender.name;
		String preview;
		if (r.deleted || r.preview == null) preview = I18n.tr("social.msg.deletedText");
		else if (!r.preview.isEmpty()) preview = r.preview;
		else if (r.attachments > 0) preview = I18n.tr("social.preview.image");
		else if (r.invite) preview = I18n.tr("social.preview.invite");
		else preview = "…";
		return name + ": " + preview;
	}

	/** Systemzeile als Satz. */
	public static String systemText(Chat.Message m) {
		Chat.SystemInfo s = m.info;
		if (s == null) return I18n.tr("social.system.unknown");
		String actor = s.actor == null ? I18n.tr("social.system.someone") : s.actor.name;
		String target = s.target == null ? "?" : s.target.name;
		switch (s.event) {
			case "group_created":
				return I18n.tr("social.system.group_created", actor);
			case "member_added":
				return I18n.tr("social.system.member_added", actor, target);
			case "member_removed":
				return I18n.tr("social.system.member_removed", actor, target);
			case "member_left":
				return I18n.tr("social.system.member_left", s.target != null ? target : actor);
			case "renamed":
				return I18n.tr("social.system.renamed", actor, s.name == null ? "?" : s.name);
			case "owner_changed":
				return I18n.tr("social.system.owner_changed", s.target != null ? target : actor);
			default:
				return I18n.tr("social.system.unknown");
		}
	}

	// --- Umbruch ---

	/** Zeilen als Text. */
	public static List<String> wrapLines(Measure m, String text, int maxWidth) {
		List<String> out = new ArrayList<String>();
		for (int[] r : wrap(m, text, maxWidth)) out.add(text.substring(r[0], r[1]));
		return out;
	}

	/**
	 * Umbruch in Zeilenbereiche [start, end) des Textes: an Zeilenumbrüchen, an Leerzeichen, zu lange Wörter hart
	 * nach Zeichen (Codepunkten). Leerzeichen am Zeilenende fallen weg.
	 */
	public static List<int[]> wrap(Measure m, String text, int maxWidth) {
		List<int[]> out = new ArrayList<int[]>();
		if (text == null) return out;
		int max = Math.max(8, maxWidth);
		int ps = 0;
		while (ps <= text.length()) {
			int pe = text.indexOf('\n', ps);
			if (pe < 0) pe = text.length();
			paragraph(m, text, ps, pe, max, out);
			ps = pe + 1;
			if (pe == text.length()) break;
		}
		return out;
	}

	private static void paragraph(Measure m, String text, int ps, int pe, int max, List<int[]> out) {
		if (ps >= pe) {
			out.add(new int[]{ps, ps});
			return;
		}
		int pos = ps;
		while (pos < pe) {
			// Führende Leerzeichen einer Folgezeile überspringen.
			if (pos > ps) while (pos < pe && text.charAt(pos) == ' ') pos++;
			if (pos >= pe) break;
			int width = 0;
			int i = pos;
			int lastSpace = -1;
			while (i < pe) {
				int cp = text.codePointAt(i);
				int n = Character.charCount(cp);
				int cw = m.width(new String(Character.toChars(cp)));
				if (width + cw > max && i > pos) break;
				if (cp == ' ') lastSpace = i;
				width += cw;
				i += n;
			}
			if (i >= pe) {
				out.add(new int[]{pos, trimEnd(text, pos, pe)});
				break;
			}
			int end;
			if (text.charAt(i) == ' ') {
				// Die Zeile ist genau voll und endet an einem Leerzeichen.
				out.add(new int[]{pos, trimEnd(text, pos, i)});
				pos = i + 1;
			} else if (lastSpace > pos) {
				end = lastSpace;
				out.add(new int[]{pos, trimEnd(text, pos, end)});
				pos = end + 1;
			} else {
				end = i;
				out.add(new int[]{pos, end});
				pos = end;
			}
		}
	}

	private static int trimEnd(String text, int start, int end) {
		while (end > start && text.charAt(end - 1) == ' ') end--;
		return end;
	}
}
