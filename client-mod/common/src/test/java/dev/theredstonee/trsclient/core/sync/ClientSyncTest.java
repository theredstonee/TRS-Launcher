package dev.theredstonee.trsclient.core.sync;

import com.google.gson.JsonObject;
import dev.theredstonee.trsclient.core.config.ConfigStore;
import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.hud.HudAnchor;
import dev.theredstonee.trsclient.core.intro.ClientState;
import dev.theredstonee.trsclient.core.module.TrsModules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Client-Sync gegen eine Attrappe der TRS API: Hochladen, Übernehmen, neuere Änderung gewinnt, 409, Einführung. */
class ClientSyncTest {
	static final String ACCOUNT = "0123456789abcdef0123456789abcdef";
	static final String OTHER = "fedcba9876543210fedcba9876543210";
	private static final Executor DIRECT = new Executor() {
		@Override
		public void execute(Runnable command) {
			command.run();
		}
	};

	@TempDir
	Path tmp;

	/** Alle Geräte des Tests – am Ende ausstehendes Schreiben abschließen, sonst kann Windows das Temp-Verzeichnis nicht löschen. */
	final List<Device> devices = new ArrayList<>();

	@AfterEach
	void flushDevices() {
		for (Device d : devices) d.store.flush();
	}

	/** Ein PC: eigene Config, eigener Sync-Zustand, gemeinsamer Server. */
	final class Device {
		final TrsModules modules = new TrsModules();
		final ConfigStore store;
		final ClientSync sync;
		String uuid = ACCOUNT;
		boolean consent = true;
		long clock = System.currentTimeMillis();

		Device(String name, FakeSyncServer server, boolean existingConfig) throws IOException {
			Path dir = tmp.resolve(name);
			Files.createDirectories(dir.resolve("trsclient"));
			if (existingConfig) Files.write(dir.resolve("trsclient.json"), "{\"configVersion\":2,\"modules\":{\"fps\":{\"enabled\":true}}}".getBytes("UTF-8"));
			store = new ConfigStore(dir.resolve("trsclient.json"));
			store.load(modules.registry);
			ClientSync.Online online = new ClientSync.Online() {
				@Override
				public boolean consent() {
					return consent;
				}

				@Override
				public String token() {
					return uuid;
				}

				@Override
				public String uuid() {
					return uuid;
				}

				@Override
				public void rejected(String token) {
				}
			};
			SyncState state = new SyncState(dir.resolve("trsclient").resolve("sync-state.json")).load();
			sync = new ClientSync(modules, online, new SyncApi(server, "http://127.0.0.1:1"), state, dir, "0.6.0", DIRECT, null);
			sync.attach(store);
			devices.add(this);
		}

		/** Zwei Ticks: Abgleich anstoßen (läuft sofort) und Ergebnis übernehmen. */
		void sync() {
			sync.syncNow();
			clock += 10 * 60_000L;
			sync.tick(clock);
			sync.tick(clock);
		}

		void save() throws IOException {
			store.saveLater(modules.registry);
			store.flush();
		}
	}

	@Test
	void firstDeviceUploadsWithoutLocalOnlyValues() throws Exception {
		FakeSyncServer server = new FakeSyncServer();
		Device a = new Device("a", server, false);
		a.modules.freelookServers.set("secret.example.net");
		a.modules.minimap.setEnabled(true);
		a.modules.waypointAddKey.set("key.keyboard.k");
		a.save();
		a.sync();
		JsonObject doc = server.clientDoc(ACCOUNT);
		assertNotNull(doc);
		assertEquals(1, doc.get("format").getAsInt());
		String text = doc.toString();
		assertFalse(text.contains("secret.example.net"), "Server-Adressen bleiben lokal");
		assertFalse(ClientDoc.data(doc, ClientDoc.MODULES).getAsJsonObject("trsOnline").toString().contains("\"sync\""),
				"der Sync-Schalter bleibt lokal");
		assertEquals("key.keyboard.k", ClientDoc.data(doc, ClientDoc.KEYS).getAsJsonObject("waypoints").get("addKey").getAsString());
		assertTrue(ClientDoc.data(doc, ClientDoc.HUD).getAsJsonArray("profiles").size() >= 1);
		assertNotNull(doc.get("seen"));
		assertTrue(ClientDoc.size(doc) < ClientDoc.MAX_BYTES);
		assertEquals(ClientSync.Status.SYNCED, a.sync.status());
	}

	@Test
	void secondDeviceTakesOverAccountSettings() throws Exception {
		FakeSyncServer server = new FakeSyncServer();
		Device a = new Device("a", server, false);
		a.modules.zoomFactor.set(7.5);
		a.modules.minimap.setEnabled(true);
		a.modules.minimap.position().anchor = HudAnchor.BOTTOM_LEFT;
		a.modules.waypointListKey.set("key.keyboard.j");
		a.save();
		a.sync();

		Device b = new Device("b", server, true);
		b.modules.freelookServers.set("mine.example.org");
		b.save();
		b.sync();
		assertEquals(7.5, b.modules.zoomFactor.get(), 1e-9);
		assertTrue(b.modules.minimap.isEnabled());
		assertEquals(HudAnchor.BOTTOM_LEFT, b.modules.minimap.position().anchor);
		assertEquals("key.keyboard.j", b.modules.waypointListKey.get());
		assertEquals("mine.example.org", b.modules.freelookServers.get(), "lokale Werte bleiben");
		// Übernommen wird ohne erneutes Hochladen der gleichen Daten in einer Schleife.
		int puts = server.putClientCount;
		b.sync();
		assertEquals(puts, server.putClientCount);
	}

	@Test
	void newerChangeWinsPerSection() throws Exception {
		FakeSyncServer server = new FakeSyncServer();
		Device a = new Device("a", server, false);
		a.sync();
		Device b = new Device("b", server, false);
		b.sync();

		// A ändert eine Taste, B danach das HUD – beide Änderungen bleiben erhalten (je Abschnitt).
		a.modules.waypointAddKey.set("key.keyboard.y");
		a.save();
		Thread.sleep(5);
		b.modules.fps.position().anchor = HudAnchor.TOP_RIGHT;
		b.save();
		a.sync();
		b.sync();
		a.sync();
		assertEquals("key.keyboard.y", b.modules.waypointAddKey.get());
		assertEquals(HudAnchor.TOP_RIGHT, a.modules.fps.position().anchor);

		// Beide ändern denselben Abschnitt: die spätere Änderung gewinnt.
		a.modules.zoomFactor.set(3.0);
		a.save();
		Thread.sleep(5);
		b.modules.zoomFactor.set(9.0);
		b.save();
		b.sync();
		a.sync();
		assertEquals(9.0, a.modules.zoomFactor.get(), 1e-9);
		assertEquals(9.0, b.modules.zoomFactor.get(), 1e-9);
	}

	@Test
	void staleWriteMergesAndRetries() throws Exception {
		final FakeSyncServer server = new FakeSyncServer();
		Device a = new Device("a", server, false);
		a.sync();
		final Device b = new Device("b", server, false);
		b.sync();
		b.modules.waypointAddKey.set("key.keyboard.p");
		b.save();
		// Zwischen GET und PUT von A schreibt B – mit einer Zeit in der Zukunft (A's PUT ist dann „älter“).
		a.modules.zoomFactor.set(6.0);
		a.save();
		server.beforePut = new Runnable() {
			@Override
			public void run() {
				JsonObject doc = server.clientDoc(ACCOUNT);
				JsonObject keys = ClientDoc.object(doc, ClientDoc.KEYS);
				keys.addProperty("at", SyncTime.iso(System.currentTimeMillis() + 60_000L));
				keys.getAsJsonObject("data").getAsJsonObject("waypoints").addProperty("addKey", "key.keyboard.p");
				server.put(ACCOUNT, doc, System.currentTimeMillis() + 60_000L);
			}
		};
		a.sync();
		JsonObject doc = server.clientDoc(ACCOUNT);
		assertEquals("key.keyboard.p", ClientDoc.data(doc, ClientDoc.KEYS).getAsJsonObject("waypoints").get("addKey").getAsString());
		assertEquals(6.0, ClientDoc.data(doc, ClientDoc.MODULES).getAsJsonObject("zoom").getAsJsonObject("numbers").get("factor").getAsDouble(), 1e-9);
		assertEquals("key.keyboard.p", a.modules.waypointAddKey.get());
	}

	@Test
	void introOncePerAccount() throws Exception {
		FakeSyncServer server = new FakeSyncServer();
		Device a = new Device("a", server, false);
		assertFalse(a.modules.clientState.introDone(), "Neuinstallation: Einführung offen");
		a.modules.clientState.markIntro(ClientState.FINISHED, "pvp", System.currentTimeMillis());
		a.save();
		a.sync();
		assertTrue(server.clientDoc(ACCOUNT).getAsJsonObject("intro").get("done").getAsBoolean());

		Device b = new Device("b", server, false);
		assertFalse(b.modules.clientState.introDone());
		assertFalse(b.sync.firstRoundDone());
		b.sync();
		assertTrue(b.sync.firstRoundDone());
		assertTrue(b.modules.clientState.introDone(), "anderer PC mit demselben Konto: keine Einführung");
		assertEquals(ClientState.ACCOUNT, b.modules.clientState.introHow());
		assertFalse(b.modules.clientState.welcomeShown());

		// Ohne TRS-Dienste: nur je Instanz.
		Device c = new Device("c", server, false);
		c.consent = false;
		c.sync();
		assertFalse(c.modules.clientState.introDone());
		assertEquals(ClientSync.Status.NO_CONSENT, c.sync.status());
	}

	@Test
	void upgradedInstallCountsAsIntroducedAndMarksNewSettings() throws Exception {
		FakeSyncServer server = new FakeSyncServer();
		Device a = new Device("a", server, true);
		assertTrue(a.modules.clientState.introDone());
		assertEquals(ClientState.EXISTING, a.modules.clientState.introHow());
		assertTrue(a.modules.clientState.news().isNew(a.modules.trsOnline, a.modules.syncClient));
		assertTrue(a.modules.clientState.news().hasNew(a.modules.trsOnline));
		a.sync();
		// Auf B (Neuinstallation) gilt der Stand des Kontos: die Einstellung ist dort genauso „NEU“.
		Device b = new Device("b", server, false);
		assertFalse(b.modules.clientState.news().hasNew(b.modules.trsOnline));
		b.sync();
		assertTrue(b.modules.clientState.news().hasNew(b.modules.trsOnline));
		// Auf B geöffnet → nach dem Abgleich auch auf A nicht mehr neu.
		b.modules.clientState.news().opened(b.modules.trsOnline);
		b.save();
		b.sync();
		a.sync();
		assertFalse(a.modules.clientState.news().hasNew(a.modules.trsOnline));
	}

	@Test
	void unknownModulesOfNewerClientsSurvive() throws Exception {
		FakeSyncServer server = new FakeSyncServer();
		Device a = new Device("a", server, false);
		a.sync();
		JsonObject doc = server.clientDoc(ACCOUNT);
		JsonObject future = new JsonObject();
		future.addProperty("enabled", true);
		ClientDoc.data(doc, ClientDoc.MODULES).add("futureModule", future);
		ClientDoc.data(doc, ClientDoc.MODULES).getAsJsonObject("zoom").getAsJsonObject("flags").addProperty("futureFlag", true);
		doc.addProperty("futureTop", 5);
		server.put(ACCOUNT, doc, System.currentTimeMillis() - 1000);
		a.sync();
		a.modules.zoomFactor.set(5.0);
		a.save();
		a.sync();
		JsonObject after = server.clientDoc(ACCOUNT);
		assertNotNull(ClientDoc.data(after, ClientDoc.MODULES).get("futureModule"));
		assertTrue(ClientDoc.data(after, ClientDoc.MODULES).getAsJsonObject("zoom").getAsJsonObject("flags").get("futureFlag").getAsBoolean());
		assertEquals(5, after.get("futureTop").getAsInt());
	}

	@Test
	void newerFormatIsReadOnly() throws Exception {
		FakeSyncServer server = new FakeSyncServer();
		JsonObject doc = new JsonObject();
		doc.addProperty("format", 99);
		server.put(ACCOUNT, doc, System.currentTimeMillis());
		Device a = new Device("a", server, false);
		a.modules.zoomFactor.set(8.0);
		a.save();
		a.sync();
		assertEquals(99, server.clientDoc(ACCOUNT).get("format").getAsInt());
		assertEquals(0, server.putClientCount);
		assertEquals(ClientSync.Status.NEWER_CLIENT, a.sync.status());
	}

	@Test
	void offlineIsSilentWithBackoff() throws Exception {
		FakeSyncServer server = new FakeSyncServer();
		server.offline = true;
		Device a = new Device("a", server, false);
		a.sync();
		assertEquals(ClientSync.Status.OFFLINE, a.sync.status());
		assertTrue(a.sync.firstRoundDone(), "die Einführung wartet nicht ewig");
		int calls = server.calls.size();
		a.sync.tick(a.clock + 1000);
		a.sync.tick(a.clock + 2000);
		assertEquals(calls, server.calls.size(), "während der Wartezeit keine Anfragen");
		server.offline = false;
		a.sync();
		assertEquals(ClientSync.Status.SYNCED, a.sync.status());
	}

	@Test
	void accountSwitchTakesTheNewAccountsSettings() throws Exception {
		FakeSyncServer server = new FakeSyncServer();
		Device other = new Device("other", server, false);
		other.uuid = OTHER;
		other.modules.zoomFactor.set(2.5);
		other.save();
		other.sync();

		Device a = new Device("a", server, false);
		a.modules.zoomFactor.set(8.0);
		a.save();
		a.sync();
		a.modules.zoomFactor.set(9.5);
		a.save();
		a.uuid = OTHER;
		a.sync();
		assertEquals(2.5, a.modules.zoomFactor.get(), 1e-9, "nach dem Wechsel gilt das neue Konto");
		assertEquals(8.0, ClientDoc.data(server.clientDoc(ACCOUNT), ClientDoc.MODULES).getAsJsonObject("zoom")
				.getAsJsonObject("numbers").get("factor").getAsDouble(), 1e-9, "das alte Konto bleibt unberührt");
	}

	@Test
	void syncSwitchOffStopsRequests() throws Exception {
		FakeSyncServer server = new FakeSyncServer();
		Device a = new Device("a", server, false);
		a.modules.syncClient.set(false);
		a.sync();
		assertTrue(server.calls.isEmpty());
		assertEquals(ClientSync.Status.OFF, a.sync.status());
	}

	@Test
	void settingsDocAppliesLookAndClientChoiceIsUploaded() throws Exception {
		FakeSyncServer server = new FakeSyncServer();
		FakeSyncServer.Stored s = new FakeSyncServer.Stored();
		s.data = new JsonObject();
		s.data.addProperty("theme", "light");
		s.data.addProperty("accent", "emerald");
		s.data.addProperty("language", "de");
		s.updatedAt = SyncTime.iso(System.currentTimeMillis());
		server.settings.put(ACCOUNT, s);
		Device a = new Device("a", server, false);
		try {
			a.sync();
			assertEquals("light", dev.theredstonee.trsclient.core.ui.Theme.get().themeName);
			assertEquals("emerald", dev.theredstonee.trsclient.core.ui.Theme.get().accentName);
			assertEquals("de", dev.theredstonee.trsclient.core.i18n.I18n.code());

			a.sync.chooseLook("oled", "lapis", "es", System.currentTimeMillis() + 5);
			a.sync();
			JsonObject stored = server.settings.get(ACCOUNT).data;
			assertEquals("oled", stored.get("theme").getAsString());
			assertEquals("lapis", stored.get("accent").getAsString());
			assertEquals("es", stored.get("language").getAsString());
			assertEquals("es", dev.theredstonee.trsclient.core.i18n.I18n.code());
		} finally {
			dev.theredstonee.trsclient.core.i18n.I18n.override(null);
			dev.theredstonee.trsclient.core.i18n.I18n.use("en");
			dev.theredstonee.trsclient.core.ui.Theme.set(dev.theredstonee.trsclient.core.ui.Theme.of("dark", "redstone", null));
		}
	}

	@Test
	void docStaysBelowLimitWithManyProfiles() {
		TrsModules m = new TrsModules();
		for (int i = 0; i < 15; i++) m.profiles.create("Profil " + i);
		ClientDoc doc = new ClientDoc(m.registry);
		SyncState state = new SyncState(null);
		ClientMerge.Result r = ClientMerge.merge(doc, state, ACCOUNT, m.registry.capture(), null, -1,
				System.currentTimeMillis(), System.currentTimeMillis(), false, "0.6.0");
		assertNotNull(r.upload);
		assertTrue(ClientDoc.size(r.upload) <= ClientDoc.MAX_BYTES);
		assertNull(r.merged);
		assertTrue(new HashSet<Object>(r.decisions.values()).contains(ClientMerge.Decision.KEEP_LOCAL));
	}

	@Test
	void appliedConfigRoundTrips() {
		TrsModules m = new TrsModules();
		m.zoomFactor.set(6.5);
		m.fps.position().anchor = HudAnchor.CENTER;
		ClientDoc doc = new ClientDoc(m.registry);
		TrsConfig cfg = m.registry.capture();
		JsonObject remote = new JsonObject();
		for (java.util.Map.Entry<String, JsonObject> e : doc.sections(cfg).entrySet()) {
			remote.add(e.getKey(), ClientDoc.section(1000, e.getValue()));
		}
		TrsModules other = new TrsModules();
		java.util.Set<String> all = new HashSet<String>(java.util.Arrays.asList(ClientDoc.LWW));
		other.registry.apply(new ClientDoc(other.registry).apply(other.registry.capture(), remote, all));
		assertEquals(6.5, other.zoomFactor.get(), 1e-9);
		assertEquals(HudAnchor.CENTER, other.fps.position().anchor);
		assertEquals(ClientDoc.hash(doc.sections(cfg).get(ClientDoc.MODULES)),
				ClientDoc.hash(new ClientDoc(other.registry).sections(other.registry.capture()).get(ClientDoc.MODULES)));
	}
}
