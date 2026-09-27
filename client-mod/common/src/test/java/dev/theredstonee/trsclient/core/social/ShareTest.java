package dev.theredstonee.trsclient.core.social;

import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.social.SocialFakes.Backend;
import dev.theredstonee.trsclient.core.social.SocialFakes.FakeHttp;
import dev.theredstonee.trsclient.core.social.SocialFakes.Opener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static dev.theredstonee.trsclient.core.social.SocialFakes.BOB;
import static dev.theredstonee.trsclient.core.social.SocialFakes.DIRECT;
import static dev.theredstonee.trsclient.core.social.SocialFakes.DM;
import static dev.theredstonee.trsclient.core.social.SocialFakes.SELF;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wegpunkt-Karten (API.md §18.10) und geteilte Bilder (§23) im Mod. */
class ShareTest {
	static final String BASE = "http://127.0.0.1:1";
	static final String ID = "Qm9vLWJhei1xdXV4LTEyMw";

	// --- Wegpunkt-Karten ---

	private static String waypointMsg(String waypointJson) {
		return "{\"id\":\"m00000000000000000009\",\"conversationId\":\"" + DM + "\",\"seq\":9,\"kind\":\"text\","
				+ "\"sender\":{\"uuid\":\"" + BOB + "\",\"name\":\"Bob\"},\"text\":null,\"invite\":null,\"world\":null,"
				+ "\"waypoint\":" + waypointJson + ",\"attachments\":[],\"replyTo\":null,\"system\":null,\"reactions\":[],"
				+ "\"createdAt\":\"2026-09-27T10:00:00.000Z\",\"editedAt\":null,\"deleted\":false,\"deletedBy\":null,"
				+ "\"hidden\":false,\"nonce\":null}";
	}

	private static Chat.Message parse(String waypointJson) {
		return ChatJson.message(ChatJson.GSON.fromJson(waypointMsg(waypointJson), ChatJson.MessageDto.class), DM);
	}

	@Test
	void validWaypointCardIsParsed() {
		Chat.Message m = parse("{\"name\":\"Base\",\"x\":100,\"y\":64,\"z\":-20,\"dimension\":\"minecraft:overworld\","
				+ "\"world\":{\"type\":\"server\",\"address\":\"Play.Example.net:25566\"},\"color\":14690334,\"extra\":1}");
		assertNotNull(m);
		Chat.Waypoint w = m.waypoint();
		assertNotNull(w);
		assertEquals("Base", w.name);
		assertEquals(100, w.x);
		assertEquals(64, w.y);
		assertEquals(-20, w.z);
		assertEquals("minecraft:overworld", w.dimension);
		assertEquals("play.example.net:25566", w.address);
		assertNull(w.worldId);
		assertEquals(14690334, w.color);
		assertTrue(w.server());
		assertEquals("100 64 -20", w.coords());
		assertFalse(m.invite.server());

		Chat.Message sp = parse("{\"name\":\"Haus\",\"x\":0,\"y\":-60,\"z\":0,\"dimension\":\"minecraft:the_nether\","
				+ "\"world\":{\"type\":\"world\",\"id\":\"0123456789abcdef\"},\"color\":null}");
		assertEquals("0123456789abcdef", sp.waypoint().worldId);
		assertEquals(-1, sp.waypoint().color);
	}

	@Test
	void invalidWaypointCardsAreDropped() {
		String world = ",\"world\":{\"type\":\"server\",\"address\":\"play.example.net\"}";
		String[] bad = {
				"{\"name\":\"A\",\"x\":1.5,\"y\":64,\"z\":0,\"dimension\":\"minecraft:overworld\"" + world + "}",
				"{\"name\":\"A\",\"x\":30000001,\"y\":64,\"z\":0,\"dimension\":\"minecraft:overworld\"" + world + "}",
				"{\"name\":\"A\",\"x\":1,\"y\":5000,\"z\":0,\"dimension\":\"minecraft:overworld\"" + world + "}",
				"{\"name\":\"A\",\"x\":1,\"y\":-2049,\"z\":0,\"dimension\":\"minecraft:overworld\"" + world + "}",
				"{\"name\":\"A\",\"x\":1,\"y\":64,\"z\":0,\"dimension\":\"Overworld\"" + world + "}",
				"{\"name\":\"A\",\"x\":1,\"y\":64,\"z\":0,\"dimension\":\"minecraft:overworld\"}",
				"{\"name\":\"\",\"x\":1,\"y\":64,\"z\":0,\"dimension\":\"minecraft:overworld\"" + world + "}",
				"{\"name\":\"A\",\"x\":1,\"y\":64,\"z\":0,\"dimension\":\"minecraft:overworld\",\"world\":{\"type\":\"server\",\"address\":\"http://evil/\"}}",
				"{\"name\":\"A\",\"x\":1,\"y\":64,\"z\":0,\"dimension\":\"minecraft:overworld\",\"world\":{\"type\":\"world\",\"id\":\"ABC\"}}",
				"{\"name\":\"A\",\"x\":1,\"y\":64,\"z\":0,\"dimension\":\"minecraft:overworld\",\"world\":{\"type\":\"lan\",\"id\":\"0123456789abcdef\"}}",
		};
		for (String b : bad) {
			Chat.Message m = parse(b);
			assertNotNull(m, b);
			assertNull(m.invite, b);
		}
	}

	@Test
	void waypointNameIsCleanedAndLimited() {
		Chat.Waypoint w = Chat.Waypoint.of("§cBa‮se\nmit sehr langem Namen der zu lang ist!!", 1, 2, 3, "minecraft:overworld",
				"server", "a.b", null, null);
		assertNotNull(w);
		assertFalse(w.name.contains("§"));
		assertFalse(w.name.contains("‮"));
		assertTrue(SafeText.length(w.name) <= 32);
		assertNull(Chat.Waypoint.of("   ", 1, 2, 3, "minecraft:overworld", "server", "a.b", null, null));
		assertEquals(-1, Chat.Waypoint.of("A", 1, 2, 3, "minecraft:overworld", "server", "a.b", null, 0x1000000L).color);
	}

	@Test
	void replyToWaypointCountsAsCard() {
		String json = waypointMsg("null").replace("\"replyTo\":null", "\"replyTo\":{\"id\":\"m00000000000000000001\",\"seq\":1,"
				+ "\"sender\":{\"uuid\":\"" + SELF + "\",\"name\":\"Theredstonee\"},\"preview\":null,\"attachments\":0,"
				+ "\"invite\":false,\"world\":false,\"waypoint\":true,\"deleted\":false}").replace("\"text\":null", "\"text\":\"da\"");
		Chat.Message m = ChatJson.message(ChatJson.GSON.fromJson(json, ChatJson.MessageDto.class), DM);
		assertTrue(m.reply.invite);
	}

	@Test
	void sendingAWaypointPostsOnlyTheCard() throws Exception {
		FakeHttp http = new FakeHttp();
		AtomicReference<String> sent = new AtomicReference<>();
		http.on("POST /v1/chat/conversations/" + DM + "/messages", r -> {
			sent.set(FakeHttp.body(r));
			return FakeHttp.response(201, "{\"message\":" + waypointMsg("{\"name\":\"Base\",\"x\":1,\"y\":2,\"z\":3,"
					+ "\"dimension\":\"minecraft:overworld\",\"world\":{\"type\":\"world\",\"id\":\"0123456789abcdef\"}}") + "}");
		});
		Chat.Waypoint w = Chat.Waypoint.of("Base", 1, 2, 3, "minecraft:overworld", "world", null, "0123456789abcdef", 255L);
		Chat.Message m = new ChatApi(http, BASE).send("t", DM, "", null, null, new Chat.Invite(w), "nonce12345");
		assertEquals("Base", m.waypoint().name);
		String body = sent.get();
		assertTrue(body.contains("\"waypoint\":{\"name\":\"Base\",\"x\":1,\"y\":2,\"z\":3,\"dimension\":\"minecraft:overworld\","
				+ "\"world\":{\"type\":\"world\",\"id\":\"0123456789abcdef\"},\"color\":255}"), body);
		assertFalse(body.contains("\"invite\""), body);
		assertFalse(body.contains("\"text\""), body);
	}

	// --- Geteilte Bilder ---

	private static String share(String id, String url) {
		return "{\"id\":\"" + id + "\",\"url\":\"" + url + "\",\"imageUrl\":\"https://evil.example/i\",\"mime\":\"image/jpeg\","
				+ "\"width\":1920,\"height\":1080,\"bytes\":1234,\"createdAt\":\"2026-09-27T10:00:00.000Z\","
				+ "\"expiresAt\":\"2026-10-27T10:00:00.000Z\"}";
	}

	@Test
	void uploadListAndDelete() throws Exception {
		FakeHttp http = new FakeHttp();
		AtomicReference<Http_Request> seen = new AtomicReference<>();
		http.on("POST /v1/shares", r -> {
			seen.set(new Http_Request(r.headers.get("Content-Type"), r.headers.get("Authorization"), r.body));
			return FakeHttp.response(201, "{\"share\":" + share(ID, "https://evil.example/s/x") + "}");
		});
		http.json("GET /v1/shares", 200, "{\"shares\":[" + share(ID, "x") + "," + share("bad id", "x") + "],"
				+ "\"limits\":{\"active\":1,\"maxActive\":50,\"uploadsToday\":1,\"maxPerDay\":20}}");
		http.json("DELETE /v1/shares/" + ID, 204, "");
		ChatApi api = new ChatApi(http, BASE);
		byte[] png = png(8, 8);
		SharedImage s = api.share("tok", png, "image/png");
		assertEquals(ID, s.id);
		// Adressen nie aus der Antwort – immer vom eigenen API-Host.
		assertEquals(BASE + "/s/" + ID, s.url);
		assertEquals(BASE + "/v1/shares/" + ID + "/image", s.imageUrl);
		assertEquals(BASE + "/v1/shares/" + ID + "/image?thumb=1", s.thumbUrl);
		assertEquals("image/png", seen.get().type);
		assertEquals("Bearer tok", seen.get().auth);
		assertArrayEquals(png, seen.get().body);
		assertEquals(ChatJson.time("2026-10-27T10:00:00.000Z"), s.expiresAt);

		SharedImage.Listing l = api.shares("tok");
		assertEquals(1, l.shares.size());
		assertEquals(50, l.maxActive);
		assertEquals(20, l.maxPerDay);
		api.deleteShare("tok", ID);
		assertEquals(1, http.count("DELETE /v1/shares/" + ID));
	}

	@Test
	void deleteOfMissingLinkIsFineOtherErrorsThrow() throws Exception {
		FakeHttp http = new FakeHttp();
		http.json("DELETE /v1/shares/" + ID, 404, "{\"error\":{\"code\":\"share_not_found\",\"message\":\"x\"}}");
		new ChatApi(http, BASE).deleteShare("tok", ID);
		assertThrows(ApiException.class, () -> new ChatApi(http, BASE).deleteShare("tok", "../../v1/me"));
		http.json("POST /v1/shares", 409, "{\"error\":{\"code\":\"shared_image_limit\",\"message\":\"x\"}}");
		ApiException e = assertThrows(ApiException.class, () -> new ChatApi(http, BASE).share("tok", png(2, 2), "image/png"));
		assertEquals("shared_image_limit", e.code());
		http.json("POST /v1/shares", 201, "{\"share\":" + share("../../hack", "x") + "}");
		ApiException bad = assertThrows(ApiException.class, () -> new ChatApi(http, BASE).share("tok", png(2, 2), "image/png"));
		assertEquals("invalid_json", bad.code());
		assertThrows(ApiException.class, () -> new ChatApi(http, BASE).share("tok", new byte[ChatApi.MAX_SHARE_BYTES + 1], "image/png"));
	}

	@Test
	void socialShareReportsErrorKeys(@TempDir Path dir) throws Exception {
		FakeHttp http = new FakeHttp();
		http.json("POST /v1/shares", 429, "{\"error\":{\"code\":\"share_daily_limit\",\"message\":\"x\"}}");
		Social social = new Social(new ChatApi(http, BASE), new MeStream(new Opener(), BASE), new Backend(), DIRECT, DIRECT, DIRECT);
		social.tick(System.currentTimeMillis(), "trs_token", SELF, "Theredstonee", false);
		Path file = dir.resolve("shot.png");
		Files.write(file, png(16, 9));
		AtomicReference<String> error = new AtomicReference<>();
		AtomicReference<SharedImage> value = new AtomicReference<>();
		social.shareImage(file, (v, err) -> {
			value.set(v);
			error.set(err);
		});
		social.tick(System.currentTimeMillis(), "trs_token", SELF, "Theredstonee", false);
		assertNull(value.get());
		// Tageslimit (429) hat einen eigenen Text, andere 429 heißen „zu schnell“; bekannte 409 → eigener Text.
		assertEquals("social.error.share_daily_limit", error.get());
		http.json("POST /v1/shares", 429, "{\"error\":{\"code\":\"rate_limited\",\"message\":\"x\"}}");
		social.shareImage(file, (v, err) -> error.set(err));
		social.tick(System.currentTimeMillis(), "trs_token", SELF, "Theredstonee", false);
		assertEquals("social.error.rate_limited", error.get());

		http.json("POST /v1/shares", 409, "{\"error\":{\"code\":\"shared_image_limit\",\"message\":\"x\"}}");
		social.shareImage(file, (v, err) -> error.set(err));
		social.tick(System.currentTimeMillis(), "trs_token", SELF, "Theredstonee", false);
		assertEquals("social.error.shared_image_limit", error.get());

		http.json("POST /v1/shares", 201, "{\"share\":" + share(ID, "x") + "}");
		social.shareImage(file, (v, err) -> value.set(v));
		social.tick(System.currentTimeMillis(), "trs_token", SELF, "Theredstonee", false);
		assertEquals(ID, value.get().id);

		// Nicht lesbare Datei: kein Upload.
		long before = http.count("POST /v1/shares");
		social.shareImage(dir.resolve("fehlt.png"), (v, err) -> error.set(err));
		social.tick(System.currentTimeMillis(), "trs_token", SELF, "Theredstonee", false);
		assertEquals("social.error.image_unreadable", error.get());
		assertEquals(before, http.count("POST /v1/shares"));
	}

	// --- Vorbereiten ---

	@Test
	void prepareShareKeepsSmallImagesAndShrinksHugeOnes() throws Exception {
		byte[] small = png(64, 36);
		ChatImages.Upload a = ChatImages.prepareShare(small);
		assertArrayEquals(small, a.bytes);
		assertEquals("image/png", a.mime);

		byte[] wide = png(5000, 40);
		ChatImages.Upload b = ChatImages.prepareShare(wide);
		assertEquals("image/jpeg", b.mime);
		BufferedImage out = ImageIO.read(new ByteArrayInputStream(b.bytes));
		assertEquals(4096, out.getWidth());
		assertTrue(out.getHeight() <= 40 && out.getHeight() >= 30);

		assertThrows(IOException.class, () -> ChatImages.prepareShare("GIF89a....".getBytes()));
		assertThrows(IOException.class, () -> ChatImages.prepareShare(new byte[0]));
	}

	static byte[] png(int w, int h) throws IOException {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, (x * 7) << 16 | (y * 5) << 8 | 0x40);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	/** Was beim Upload ankam. */
	static final class Http_Request {
		final String type;
		final String auth;
		final byte[] body;

		Http_Request(String type, String auth, byte[] body) {
			this.type = type;
			this.auth = auth;
			this.body = body;
		}
	}
}
