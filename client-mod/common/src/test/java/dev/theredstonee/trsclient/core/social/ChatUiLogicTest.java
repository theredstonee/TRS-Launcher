package dev.theredstonee.trsclient.core.social;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.online.RawHttp;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.social.ChatInput;
import dev.theredstonee.trsclient.core.ui.social.ChatLayout;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static dev.theredstonee.trsclient.core.social.SocialFakes.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatUiLogicTest {
	/** Jedes Zeichen 6 px breit (wie die meisten Minecraft-Glyphen). */
	static final ChatLayout.Measure MONO = s -> s.codePointCount(0, s.length()) * 6;

	@BeforeAll
	static void english() {
		I18n.use("en");
	}

	@Test
	void wrapBreaksAtSpacesNewlinesAndHardInsideLongWords() {
		List<String> lines = ChatLayout.wrapLines(MONO, "hello world foo", 66);
		assertEquals(2, lines.size());
		assertEquals("hello world", lines.get(0));
		assertEquals("foo", lines.get(1));
		List<String> hard = ChatLayout.wrapLines(MONO, "aaaaaaaaaaaaaaaaaaaa", 60);
		assertEquals(2, hard.size());
		assertEquals(10, hard.get(0).length());
		List<String> nl = ChatLayout.wrapLines(MONO, "a\n\nb", 60);
		assertEquals(3, nl.size());
		assertEquals("", nl.get(1));
	}

	@Test
	void layoutGroupsBySenderAndSeparatesDays() {
		List<Chat.Message> msgs = new ArrayList<>();
		msgs.add(parse(msg("m00000000000000000001", DM, 1, BOB, "hi", null, "2026-09-25T10:00:00.000Z")));
		msgs.add(parse(msg("m00000000000000000002", DM, 2, BOB, "noch da?", null, "2026-09-25T10:01:00.000Z")));
		msgs.add(parse(msg("m00000000000000000003", DM, 3, SELF, "ja https://example.net", null, "2026-09-26T09:00:00.000Z")));
		long now = java.time.Instant.parse("2026-09-26T12:00:00.000Z").toEpochMilli();
		ChatLayout.Result r = ChatLayout.layout(MONO, msgs, SELF, null, 300, now, false);
		List<ChatLayout.Kind> kinds = new ArrayList<>();
		for (ChatLayout.Item it : r.items) kinds.add(it.kind);
		assertEquals(ChatLayout.Kind.DAY, kinds.get(0));
		assertEquals(ChatLayout.Kind.MESSAGE, kinds.get(1));
		assertEquals(ChatLayout.Kind.MESSAGE, kinds.get(2));
		assertEquals(ChatLayout.Kind.DAY, kinds.get(3));
		assertTrue(r.items.get(1).header);
		assertFalse(r.items.get(2).header, "gleicher Absender kurz danach: keine neue Kopfzeile");
		ChatLayout.Item own = r.items.get(4);
		assertTrue(own.own);
		assertEquals(1, own.links.size());
		assertEquals("https://example.net", own.links.get(0).url);
		assertTrue(own.bubbleX + own.bubbleW <= 300);
		assertTrue(r.height > 0);
	}

	private static Chat.Message parse(String json) {
		return ChatJson.message(ChatJson.GSON.fromJson(json, ChatJson.MessageDto.class), DM);
	}

	@Test
	void chatInputStripsFormattingAndHandlesPasteAndSurrogates() {
		ChatInput in = new ChatInput(10);
		assertFalse(in.type('§'));
		in.insert("ab§cd\r\nef\u0007");
		assertEquals("abd\nef", in.text());
		in.key(UiKey.BACKSPACE, null);
		assertEquals("abd\ne", in.text());
		in.key(UiKey.HOME, null);
		in.insert("X");
		assertEquals("abd\nXe", in.text());
		in.key(UiKey.PASTE, "1234567890");
		assertEquals(10, in.text().codePointCount(0, in.text().length()));
		ChatInput emoji = new ChatInput(5);
		emoji.insert("😀");
		emoji.key(UiKey.BACKSPACE, null);
		assertEquals("", emoji.text(), "Surrogatpaar als Ganzes löschen");
	}

	@Test
	void rawHttpSendsPatchWithBodyAndReadsChunkedAnswer() throws Exception {
		try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			AtomicReference<String> seen = new AtomicReference<>();
			Thread t = new Thread(() -> {
				try (Socket s = server.accept()) {
					BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
					StringBuilder head = new StringBuilder();
					String line;
					int length = 0;
					while ((line = r.readLine()) != null && !line.isEmpty()) {
						head.append(line).append('\n');
						if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
					}
					char[] body = new char[length];
					int read = 0;
					while (read < length) read += r.read(body, read, length - read);
					seen.set(head + "|" + new String(body));
					OutputStream out = s.getOutputStream();
					out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n"
							+ "5\r\n{\"a\":\r\n2\r\n1}\r\n0\r\n\r\n").getBytes(StandardCharsets.UTF_8));
					out.flush();
				} catch (Exception ignored) {
					// Test wertet unten aus
				}
			});
			t.start();
			Http.Request req = new Http.Request("PATCH", "http://127.0.0.1:" + server.getLocalPort() + "/v1/chat/messages/m1")
					.header("Content-Type", "application/json").header("Authorization", "Bearer x");
			req.body = "{\"text\":\"neu\"}".getBytes(StandardCharsets.UTF_8);
			Http.Response res = new RawHttp("TRS-Test").send(req);
			t.join(2000);
			assertEquals(200, res.status);
			assertEquals("{\"a\":1}", res.text());
			assertTrue(seen.get().startsWith("PATCH /v1/chat/messages/m1 HTTP/1.1\n"), seen.get());
			assertTrue(seen.get().endsWith("|{\"text\":\"neu\"}"), seen.get());
		}
		// Klartext nur zu localhost.
		assertThrows(java.io.IOException.class, () -> new RawHttp("x").send(new Http.Request("PATCH", "http://example.net/x")));
	}
}
